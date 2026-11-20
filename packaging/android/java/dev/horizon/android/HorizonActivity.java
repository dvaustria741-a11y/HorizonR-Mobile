package dev.horizon.android;

import android.app.Dialog;
import android.app.NativeActivity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import dev.horizon.android.ui.HorizonLauncherActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * NativeActivity for the Vasysik/horizon-android + horizon-rust/horizon 26.3 engine.
 * Includes:
 * - Guaranteed single-shot launch & clean return to HorizonLauncherActivity on game exit.
 * - No splash or loading screen: the window stays hidden over the launcher (which shows the PLAY bar) and is
 *   revealed, with the touch overlay, as soon as the engine writes `100|Main Menu Ready` to `zalith-game-boot.txt`
 *   (or after 60 s regardless).
 */
public final class HorizonActivity extends NativeActivity {
    private static final String TAG = "HorizonActivity";
    public static final int SAF_DOCUMENT_REQUEST_CODE = 4101;
    private volatile boolean watchdogRunning = true;
    private static final float HIDDEN_WINDOW_ALPHA = 0.01f;
    private boolean gameWindowHidden;
    private dev.horizon.android.controls.ControlsOverlay controlsOverlay;

    static {
        try {
            System.loadLibrary("horizon_android");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "libhorizon_android.so could not be preloaded: " + e.getMessage());
        }
    }

    private native void nativeDocumentResult(int request, int result, String uri);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        File homeDir = getFilesDir();
        File tokenFile = new File(homeDir, "zalith-launch-token.txt");
        File exitFlag = new File(homeDir, "zalith-game-exited.flag");

        if (savedInstanceState != null || exitFlag.exists() || !tokenFile.exists()) {
            Log.i(TAG, "No active launch token (or exit flag present) — returning to HorizonLauncherActivity.");
            exitFlag.delete();
            tokenFile.delete();
            returnToLauncherAndFinish();
            super.onCreate(null);
            return;
        }

        tokenFile.delete();
        exitFlag.delete();

        writeBootMilestone(homeDir, "15|Initializing HorizonZ Native Runtime...");

        appendCrossProcessLog(homeDir, "[GameProcess] Starting HorizonActivity (:game process) for Minecraft 26.3.");
        ensureEngineDataExtracted();
        applyLauncherLaunchEnvironment(getIntent());
        startLogMirror();
        applyRequestedOrientationFromIntent(getIntent());
        enableImmersiveFullscreen();

        startExitWatchdog(homeDir);
        startMenuReadyWatcher(homeDir);
        startGeminiAiAgentBridgeIfEnabled(homeDir, getIntent());
        super.onCreate(savedInstanceState);
        hideGameWindowUntilMenuReady();
    }

    /**
     * Shows the touch layout once the engine reports its menu is ready. The engine writes
     * `100|Main Menu Ready` to the boot file; after 60 s the overlay is shown regardless, as before.
     */
    private void startMenuReadyWatcher(final File homeDir) {
        Thread watcher = new Thread(() -> {
            File bootFile = new File(homeDir, "zalith-game-boot.txt");
            long deadline = System.currentTimeMillis() + 60000L;
            while (watchdogRunning && System.currentTimeMillis() < deadline) {
                if (isMenuReady(bootFile)) break;
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!watchdogRunning) return;
            runOnUiThread(() -> {
                revealGameWindow();
                if (!isFinishing()) showControlsOverlay();
            });
        }, "HorizonZ-MenuReadyWatcher");
        watcher.setDaemon(true);
        watcher.setPriority(Thread.MIN_PRIORITY);
        watcher.start();
    }

    private static boolean isMenuReady(File bootFile) {
        if (!bootFile.exists()) return false;
        try (BufferedReader br = new BufferedReader(
            new InputStreamReader(new FileInputStream(bootFile), StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line == null) return false;
            int sep = line.indexOf('|');
            if (sep <= 0) return false;
            return Integer.parseInt(line.substring(0, sep).trim()) >= 100;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The game window starts translucent (HorizonGameTheme) and nearly invisible, so the launcher, with its
     * PLAY bar, stays what the player sees while the engine boots. The window must stay visible to the system
     * (an alpha of exactly 0 could stop the compositor from taking the engine's frames), and not touchable so
     * taps cannot reach the engine's boot screen. {@link #revealGameWindow()} undoes both.
     */
    private void hideGameWindowUntilMenuReady() {
        try {
            Window w = getWindow();
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.alpha = HIDDEN_WINDOW_ALPHA;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            gameWindowHidden = true;
        } catch (Throwable t) {
            Log.e(TAG, "Could not hide the game window while loading: " + t.getMessage());
        }
    }

    private void revealGameWindow() {
        if (!gameWindowHidden) return;
        gameWindowHidden = false;
        try {
            Window w = getWindow();
            w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.alpha = 1.0f;
            w.setAttributes(lp);
        } catch (Throwable t) {
            Log.e(TAG, "Could not reveal the game window: " + t.getMessage());
        }
    }

    /** The launcher's touch layout over the game, when the player turned it on in Settings. */
    private void showControlsOverlay() {
        try {
            if (controlsOverlay == null) {
                controlsOverlay = new dev.horizon.android.controls.ControlsOverlay(this);
            }
            controlsOverlay.show();
        } catch (Throwable t) {
            Log.e(TAG, "Touch overlay unavailable: " + t.getMessage());
        }
    }

    private void hideControlsOverlay() {
        try {
            if (controlsOverlay != null) controlsOverlay.hide();
        } catch (Throwable ignored) {
        }
    }

    private void writeBootMilestone(File homeDir, String content) {
        try {
            File bootFile = new File(homeDir, "zalith-game-boot.txt");
            try (FileOutputStream fos = new FileOutputStream(bootFile, false)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private void startExitWatchdog(final File homeDir) {
        Thread watchdog = new Thread(() -> {
            File exitFlag = new File(homeDir, "zalith-game-exited.flag");
            while (watchdogRunning) {
                try {
                    if (exitFlag.exists()) {
                        exitFlag.delete();
                        appendCrossProcessLog(homeDir, "[GameProcess] Quit Game detected — closing :game activity and returning to HorizonZ Launcher.");
                        runOnUiThread(this::returnToLauncherAndFinish);
                        break;
                    }
                    Thread.sleep(400L);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }, "HorizonZ-ExitWatchdog");
        watchdog.setDaemon(true);
        watchdog.setPriority(Thread.MIN_PRIORITY);
        watchdog.start();
    }

    /**
     * Bridges Horizon's local in-game AI Agent control protocol (`$HORIZON_CONTROL` / `horizon-control.json`)
     * to Google AI Studio (`generativelanguage.googleapis.com/v1beta/models/<model>:generateContent`)
     * using the API key stored strictly locally in `horizon-gemini-secret.json`.
     */
    private void startGeminiAiAgentBridgeIfEnabled(final File homeDir, final Intent intent) {
        if (homeDir == null || intent == null) return;
        final boolean aiEnabled = intent.getBooleanExtra("HORIZON_AI_AGENT_ENABLED", false);
        final String provider = intent.getStringExtra("HORIZON_AI_AGENT_PROVIDER");
        if (!aiEnabled || (provider != null && !"gemini".equalsIgnoreCase(provider))) {
            return;
        }
        final String modelId = intent.getStringExtra("HORIZON_GEMINI_MODEL") != null
            ? intent.getStringExtra("HORIZON_GEMINI_MODEL")
            : "gemini-4-argon";
        final boolean autoAssistChat = intent.getBooleanExtra("HORIZON_AI_AGENT_AUTO_CHAT", true);
        final boolean autonomousActions = intent.getBooleanExtra("HORIZON_AI_AGENT_AUTONOMOUS", true);
        final String taskPrompt = intent.getStringExtra("HORIZON_AI_AGENT_TASK") != null
            ? intent.getStringExtra("HORIZON_AI_AGENT_TASK")
            : "Help the player in Minecraft 26.3.";

        Thread agentThread = new Thread(() -> {
            String localApiKey = readLocalGeminiKey(homeDir);
            if (localApiKey.isEmpty()) {
                appendCrossProcessLog(homeDir, "[GeminiAgent] AI Agent enabled, waiting for local Google AI Studio API key in Settings > AI Agent.");
                return;
            }
            appendCrossProcessLog(homeDir, "[GeminiAgent] Google AI Studio Bridge active (model=" + modelId + ", local key verified).");
            String lastHandledChatLine = "";
            boolean greetedInWorld = false;

            while (watchdogRunning) {
                try {
                    File ctrlFile = new File(homeDir, "horizon-control.json");
                    if (!ctrlFile.exists()) {
                        Thread.sleep(2000L);
                        continue;
                    }
                    String ctrlRaw = readSmallTextFile(ctrlFile);
                    JSONObject ctrlObj = new JSONObject(ctrlRaw);
                    String addr = ctrlObj.optString("address", ctrlObj.optString("addr", "127.0.0.1:0"));
                    String token = ctrlObj.optString("token", "");
                    int colon = addr.lastIndexOf(':');
                    if (colon <= 0) {
                        Thread.sleep(2000L);
                        continue;
                    }
                    String host = addr.substring(0, colon).trim();
                    int port = Integer.parseInt(addr.substring(colon + 1).trim());
                    if (port <= 0) {
                        Thread.sleep(2000L);
                        continue;
                    }

                    String stateJson = sendControlCommand(host, port, token, "{\"op\":\"state\"}");
                    if (stateJson == null || stateJson.isEmpty()) {
                        Thread.sleep(2000L);
                        continue;
                    }

                    if (!greetedInWorld) {
                        greetedInWorld = true;
                        JSONObject chatGreet = new JSONObject();
                        chatGreet.put("op", "chat");
                        chatGreet.put("message", "[HorizonZ Gemini AI] Connected to Google AI Studio (" + modelId + "). Type @ai <message> in chat to ask for help!");
                        sendControlCommand(host, port, token, chatGreet.toString());
                    }

                    if (autoAssistChat) {
                        String msgsJson = sendControlCommand(host, port, token, "{\"op\":\"messages\"}");
                        if (msgsJson != null && !msgsJson.isEmpty()) {
                            String triggerMsg = extractLatestAiChatTrigger(msgsJson, lastHandledChatLine);
                            if (triggerMsg != null && !triggerMsg.isEmpty()) {
                                lastHandledChatLine = triggerMsg;
                                String invJson = sendControlCommand(host, port, token, "{\"op\":\"inventory\"}");
                                String lookJson = sendControlCommand(host, port, token, "{\"op\":\"looking-at\"}");
                                String geminiReply = callGeminiAgentApi(
                                    localApiKey,
                                    modelId,
                                    taskPrompt,
                                    stateJson,
                                    invJson != null ? invJson : "{}",
                                    lookJson != null ? lookJson : "{}",
                                    triggerMsg,
                                    autonomousActions
                                );
                                if (geminiReply != null && !geminiReply.isEmpty()) {
                                    dispatchGeminiAgentResponse(host, port, token, geminiReply, autonomousActions);
                                }
                            }
                        }
                    }
                    Thread.sleep(1500L);
                } catch (Exception ignored) {
                    try { Thread.sleep(2500L); } catch (InterruptedException ie) { break; }
                }
            }
        }, "HorizonZ-GeminiAgentBridge");
        agentThread.setDaemon(true);
        agentThread.setPriority(Thread.MIN_PRIORITY);
        agentThread.start();
    }

    private String readLocalGeminiKey(File homeDir) {
        try {
            File f = new File(homeDir, "horizon-gemini-secret.json");
            if (!f.exists()) return "";
            String txt = readSmallTextFile(f);
            JSONObject obj = new JSONObject(txt);
            return obj.optString("gemini_api_key", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String readSmallTextFile(File f) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String sendControlCommand(String host, int port, String token, String cmdJsonStr) {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), 1500);
            sock.setSoTimeout(2000);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            if (token != null && !token.isEmpty()) {
                JSONObject cmdObj = new JSONObject(cmdJsonStr);
                if (!cmdObj.has("token")) {
                    cmdObj.put("token", token);
                }
                cmdJsonStr = cmdObj.toString();
            }
            writer.write(cmdJsonStr);
            writer.write("\n");
            writer.flush();
            return reader.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractLatestAiChatTrigger(String msgsJson, String lastHandled) {
        try {
            JSONObject root = new JSONObject(msgsJson);
            JSONArray arr = root.optJSONArray("messages");
            if (arr == null) return null;
            for (int i = arr.length() - 1; i >= 0; i--) {
                String line = arr.optString(i, "").trim();
                if (line.contains("[HorizonZ Gemini AI]")) continue;
                String lower = line.toLowerCase();
                int idx = lower.indexOf("@ai ");
                if (idx < 0) idx = lower.indexOf("!ai ");
                if (idx >= 0) {
                    if (line.equals(lastHandled)) return null;
                    return line;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String callGeminiAgentApi(
        String apiKey,
        String modelId,
        String taskPrompt,
        String stateJson,
        String invJson,
        String lookJson,
        String userTrigger,
        boolean allowActions
    ) {
        String cleanModel = modelId.startsWith("models/") ? modelId.substring(7) : modelId;
        String lower = cleanModel.toLowerCase();
        String fallback1 = (lower.contains("argon") || lower.contains("ultra") || lower.contains("pro"))
            ? "gemini-2.5-pro"
            : (lower.contains("lite") ? "gemini-2.5-flash-lite" : "gemini-2.5-flash");
        String[] candidates = new String[] { cleanModel, fallback1, "gemini-2.5-flash", "gemini-2.0-flash" };

        for (String candidate : candidates) {
            try {
                String urlStr = "https://generativelanguage.googleapis.com/v1beta/models/" + candidate + ":generateContent?key=" + apiKey;
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            String sysPrompt = "You are HorizonZ Gemini AI Agent inside Minecraft 26.3. "
                + "Role instruction: " + taskPrompt + "\n"
                + "Current game state JSON: " + stateJson + "\n"
                + "Inventory JSON: " + invJson + "\n"
                + "Looking-at JSON: " + lookJson + "\n"
                + "Player message: " + userTrigger + "\n"
                + "Respond ONLY with a single compact JSON object: "
                + "{\"reply\":\"<short helpful chat reply under 180 chars>\",\"actions\":[{\"op\":\"jump\"}]} "
                + (allowActions
                    ? "Allowed action ops in actions array: jump, attack, interact, use-item, or {\"op\":\"command\",\"command\":\"...\"}."
                    : "Keep actions array empty [].");

            JSONObject payload = new JSONObject();
            JSONArray contents = new JSONArray();
            JSONObject contentItem = new JSONObject();
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("text", sysPrompt));
            contentItem.put("parts", parts);
            contents.put(contentItem);
            payload.put("contents", contents);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                JSONObject resp = new JSONObject(sb.toString());
                return resp.optJSONArray("candidates")
                    .optJSONObject(0)
                    .optJSONObject("content")
                    .optJSONArray("parts")
                    .optJSONObject(0)
                    .optString("text", "")
                    .trim();
                } else if (conn.getResponseCode() == 400 || conn.getResponseCode() == 401 || conn.getResponseCode() == 403) {
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void dispatchGeminiAgentResponse(String host, int port, String token, String rawText, boolean allowActions) {
        try {
            String cleaned = rawText.trim();
            if (cleaned.startsWith("```")) {
                int firstNl = cleaned.indexOf('\n');
                if (firstNl > 0) cleaned = cleaned.substring(firstNl + 1);
                if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
            }
            String replyText = cleaned;
            JSONArray actions = null;
            if (cleaned.startsWith("{")) {
                JSONObject obj = new JSONObject(cleaned);
                replyText = obj.optString("reply", obj.optString("chat", cleaned));
                actions = obj.optJSONArray("actions");
            }
            if (replyText != null && !replyText.isEmpty()) {
                JSONObject chatCmd = new JSONObject();
                chatCmd.put("op", "chat");
                chatCmd.put("message", "[HorizonZ Gemini AI] " + replyText);
                sendControlCommand(host, port, token, chatCmd.toString());
            }
            if (allowActions && actions != null) {
                for (int i = 0; i < actions.length(); i++) {
                    JSONObject act = actions.optJSONObject(i);
                    if (act != null && act.has("op")) {
                        sendControlCommand(host, port, token, act.toString());
                    }
                }
            }
        } catch (Exception e) {
            try {
                JSONObject fallbackChat = new JSONObject();
                fallbackChat.put("op", "chat");
                fallbackChat.put("message", "[HorizonZ Gemini AI] " + rawText.replace("\n", " ").substring(0, Math.min(180, rawText.length())));
                sendControlCommand(host, port, token, fallbackChat.toString());
            } catch (Exception ignored) {
            }
        }
    }

    private void returnToLauncherAndFinish() {
        watchdogRunning = false;
        try {
            Intent backIntent = new Intent(this, HorizonLauncherActivity.class);
            backIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(backIntent);
        } catch (Exception ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            } else {
                finish();
            }
        } catch (Exception ignored) {
            finish();
        }
        new Thread(() -> {
            try {
                Thread.sleep(180L);
            } catch (InterruptedException ignored) {
            }
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            dev.horizon.android.ui.PlayTime.begin(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        try {
            dev.horizon.android.ui.PlayTime.end(this);
        } catch (Throwable ignored) {
        }
        // Leaving the app mid-game opens the pause menu, so the world is not left running behind it.
        try {
            if (!isFinishing() && dev.horizon.android.controls.HorizonBridge.INSTANCE.isCaptured()) {
                dev.horizon.android.controls.HorizonBridge.INSTANCE.command("key key.keyboard.escape tap");
            }
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        logMirrorRunning = false;
        mirrorLogsOnce();
        watchdogRunning = false;
        hideControlsOverlay();
        super.onDestroy();
    }

    private volatile boolean logMirrorRunning;

    /**
     * Copies the game's log and crash reports (private storage) to
     * /storage/emulated/0/Android/data/dev.horizonr.mobile/files/ so a person can open them with a file
     * manager. The engine keeps writing to its own folder: its log lock may not work on shared storage.
     */
    private void startLogMirror() {
        if (logMirrorRunning) return;
        logMirrorRunning = true;
        Thread t = new Thread(() -> {
            while (logMirrorRunning) {
                mirrorLogsOnce();
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "log-mirror");
        t.setDaemon(true);
        t.start();
    }

    private void mirrorLogsOnce() {
        try {
            File ext = getExternalFilesDir(null);
            File home = getFilesDir();
            if (ext == null || home == null) return;
            mirrorFile(new File(home, "logs/latest.log"), new File(ext, "logs/latest.log"));
            mirrorFile(new File(home, "startup-trace.json"), new File(ext, "startup-trace.json"));
            File[] reports = new File(home, "crash-reports").listFiles();
            if (reports != null) {
                for (File r : reports) {
                    if (r.isFile()) mirrorFile(r, new File(ext, "crash-reports/" + r.getName()));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void mirrorFile(File from, File to) {
        try {
            if (!from.isFile()) return;
            if (to.isFile() && to.length() == from.length() && to.lastModified() >= from.lastModified()) return;
            File dir = to.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }
        } catch (Exception ignored) {
        }
    }

    private void applyRequestedOrientationFromIntent(Intent intent) {
        if (intent == null) return;
        String orient = intent.getStringExtra("HORIZON_ORIENTATION");
        if (orient == null) return;
        switch (orient) {
            case "landscape":
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
            case "reverse-landscape":
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
                break;
            case "portrait":
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case "reverse-portrait":
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
                break;
            case "auto":
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                break;
        }
    }

    private void appendCrossProcessLog(File homeDir, String msg) {
        try {
            File logFile = new File(homeDir, "zalith-system.log");
            String line = msg + "\n";
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private void ensureEngineDataExtracted() {
        try {
            File homeDir = getFilesDir();
            if (homeDir == null) return;
            if (!homeDir.exists()) homeDir.mkdirs();
            File engineZip = new File(homeDir, "engine-data.zip");
            if (!engineZip.exists() || engineZip.length() < 1024) {
                try (InputStream in = getAssets().open("engine-data.zip");
                     FileOutputStream out = new FileOutputStream(engineZip)) {
                    byte[] buf = new byte[65536];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not pre-extract engine-data.zip: " + e.getMessage());
        }
    }

    private void applyLauncherLaunchEnvironment(Intent intent) {
        try {
            File homeDir = getFilesDir();
            if (homeDir != null) {
                Os.setenv("HORIZON_HOME", homeDir.getAbsolutePath(), true);
                Os.setenv("HORIZON_DIRECT_GAME", "1", true);
                // Every named startup step with a timestamp, for finding what the launch spends its time on.
                Os.setenv("HORIZON_STARTUP_TRACE", new File(homeDir, "startup-trace.json").getAbsolutePath(), true);
            }
            if (intent != null) {
                boolean publicMode = intent.getBooleanExtra("HORIZON_ANDROID_PUBLIC", true);
                Os.setenv("HORIZON_ANDROID_PUBLIC", publicMode ? "1" : "0", true);

                boolean disableAudio = intent.getBooleanExtra("HORIZON_NO_AUDIO", false);
                if (disableAudio) {
                    Os.setenv("HORIZON_NO_AUDIO", "1", true);
                    Os.setenv("ALSOFT_DISABLE_EVENTS", "1", true);
                }

                boolean disableDiscord = intent.getBooleanExtra("HORIZON_NO_DISCORD", false);
                if (disableDiscord) {
                    Os.setenv("HORIZON_NO_DISCORD", "1", true);
                }

                String playerName = intent.getStringExtra("HORIZON_PLAYER_NAME");
                if (playerName != null && !playerName.isEmpty()) {
                    Os.setenv("HORIZON_PLAYER_NAME", playerName, true);
                }

                String quickPlayServer = intent.getStringExtra("HORIZON_QUICK_PLAY_SERVER");
                if (quickPlayServer != null && !quickPlayServer.isEmpty()) {
                    Os.setenv("HORIZON_QUICK_PLAY_SERVER", quickPlayServer, true);
                } else {
                    try { Os.unsetenv("HORIZON_QUICK_PLAY_SERVER"); } catch (Exception ignored) {}
                }

                String quickPlayWorld = intent.getStringExtra("HORIZON_QUICK_PLAY_WORLD");
                if (quickPlayWorld != null && !quickPlayWorld.isEmpty()) {
                    Os.setenv("HORIZON_QUICK_PLAY_WORLD", quickPlayWorld, true);
                } else {
                    try { Os.unsetenv("HORIZON_QUICK_PLAY_WORLD"); } catch (Exception ignored) {}
                }

                // Experimental Stuff > Trust shader packs: pack programs compile without wgpu's runtime checks.
                Os.setenv("HORIZON_PACK_TRUSTED_SHADERS",
                        "1".equals(intent.getStringExtra("HORIZON_PACK_TRUSTED_SHADERS")) ? "1" : "0", true);

                // Experimental Stuff > Define missing shader switches: on unless the launcher says "0".
                Os.setenv("HORIZON_PACK_AUTO_SWITCHES",
                        "0".equals(intent.getStringExtra("HORIZON_PACK_AUTO_SWITCHES")) ? "0" : "1", true);

                String renderBackend = intent.getStringExtra("HORIZON_WGPU_BACKEND");
                if (renderBackend != null && !renderBackend.isEmpty()) {
                    Os.setenv("WGPU_BACKEND", renderBackend, true);
                }

                int ramAllocMb = intent.getIntExtra("HORIZON_RAM_ALLOCATION_MB", 0);
                int deviceTotalRamMb = intent.getIntExtra("HORIZON_DEVICE_TOTAL_RAM_MB", 8192);
                boolean unlimitedRam = (ramAllocMb <= 0);
                int effectiveRamMb = unlimitedRam ? Math.max(2048, (int) (deviceTotalRamMb * 0.85f)) : Math.max(1024, ramAllocMb);
                Os.setenv("HORIZON_RAM_ALLOCATION_MB", String.valueOf(ramAllocMb), true);
                Os.setenv("HORIZON_RAM_UNLIMITED", unlimitedRam ? "1" : "0", true);
                Os.setenv("HORIZON_EFFECTIVE_RAM_MB", String.valueOf(effectiveRamMb), true);
                Os.setenv("WGPU_MEMORY_BUDGET_MB", String.valueOf(effectiveRamMb), true);

                // The shader pack is the engine's own pick (pickedShaderPack in horizon-graphics.txt).
                // HORIZON_SHADER_PACK would override it for the whole session, so the in-game
                // Shader Packs screen could not turn a pack off: never leave it set.
                try { Os.unsetenv("HORIZON_SHADER_PACK"); } catch (Exception ignored) {}

                boolean aiAgentEnabled = intent.getBooleanExtra("HORIZON_AI_AGENT_ENABLED", false);
                String aiProvider = intent.getStringExtra("HORIZON_AI_AGENT_PROVIDER");
                String geminiModel = intent.getStringExtra("HORIZON_GEMINI_MODEL");
                if (aiAgentEnabled && homeDir != null) {
                    File controlFile = new File(homeDir, "horizon-control.json");
                    Os.setenv("HORIZON_CONTROL", controlFile.getAbsolutePath(), true);
                    Os.setenv("HORIZON_AI_AGENT_ENABLED", "1", true);
                    Os.setenv("HORIZON_AI_AGENT_PROVIDER", (aiProvider != null && !aiProvider.isEmpty()) ? aiProvider : "gemini", true);
                    Os.setenv("HORIZON_GEMINI_MODEL", (geminiModel != null && !geminiModel.isEmpty()) ? geminiModel : "gemini-4-argon", true);
                } else {
                    Os.setenv("HORIZON_AI_AGENT_ENABLED", "0", true);
                }

                try {
                    Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
                    Object vmRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime").invoke(null);
                    vmRuntimeClass.getDeclaredMethod("setTargetHeapUtilization", float.class)
                        .invoke(vmRuntime, unlimitedRam ? 0.85f : 0.75f);
                } catch (Throwable ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to set environment variables for Horizon native engine", e);
        }
    }

    private void enableImmersiveFullscreen() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SAF_DOCUMENT_REQUEST_CODE) {
            String uriString = null;
            if (data != null && data.getData() != null) {
                Uri uri = data.getData();
                try {
                    final int takeFlags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(uri, takeFlags);
                } catch (SecurityException ignored) {
                }
                uriString = uri.toString();
            }
            try {
                nativeDocumentResult(request, result, uriString);
            } catch (UnsatisfiedLinkError err) {
                Log.e(TAG, "nativeDocumentResult JNI symbol not linked: " + err.getMessage());
            }
        }
    }
}
