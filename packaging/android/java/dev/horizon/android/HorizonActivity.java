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
 * - Full-Screen Separate-Window Dialog Splash Overlay (`HorizonSplashOverlayView`) so Android's
 *   SurfaceFlinger renders the splash screen ON TOP of `NativeActivity`'s `takeSurface()` window
 *   all the way until the Minecraft 26.3 Main Menu is rendered (zero black screen gap!).
 */
public final class HorizonActivity extends NativeActivity {
    private static final String TAG = "HorizonActivity";
    public static final int SAF_DOCUMENT_REQUEST_CODE = 4101;
    private volatile boolean watchdogRunning = true;
    private Dialog splashDialog;
    private HorizonSplashOverlayView splashOverlayView;
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

        // Home "Boost": threads created from here on (the engine's included) inherit display priority.
        try {
            if (new File(homeDir, "boost.flag").exists()) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
            }
        } catch (Throwable ignored) {
        }

        showHorizonSplashDialog(homeDir);

        writeBootMilestone(homeDir, "15|Initializing HorizonZ Native Runtime...");

        appendCrossProcessLog(homeDir, "[GameProcess] Starting HorizonActivity (:game process) for Minecraft 26.3.");
        ensureEngineDataExtracted();
        applyLauncherLaunchEnvironment(getIntent());
        startLogMirror();
        applyRequestedOrientationFromIntent(getIntent());
        enableImmersiveFullscreen();

        startExitWatchdog(homeDir);
        startGeminiAiAgentBridgeIfEnabled(homeDir, getIntent());
        super.onCreate(savedInstanceState);
    }

    private void showHorizonSplashDialog(final File homeDir) {
        try {
            splashDialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
            splashDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            splashDialog.setCancelable(false);

            splashOverlayView = new HorizonSplashOverlayView(this, homeDir, () -> {
                dismissSplashDialogSafely();
                showControlsOverlay();
            });
            splashOverlayView.setStartTimeMs(getIntent().getLongExtra("HORIZON_SPLASH_START_MS", 0L));
            splashDialog.setContentView(
                splashOverlayView,
                new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            );

            Window win = splashDialog.getWindow();
            if (win != null) {
                win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                win.setFormat(android.graphics.PixelFormat.TRANSLUCENT);
                win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                win.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                );
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                );
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    WindowManager.LayoutParams lp = win.getAttributes();
                    lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                    win.setAttributes(lp);
                }
                win.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                );
            }
            splashDialog.show();
        } catch (Exception e) {
            Log.w(TAG, "Could not show splash dialog: " + e.getMessage());
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

    private void dismissSplashDialogSafely() {
        try {
            if (splashOverlayView != null) {
                splashOverlayView.stopPolling();
                if (splashOverlayView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) splashOverlayView.getParent()).removeView(splashOverlayView);
                }
            }
            if (splashDialog != null && splashDialog.isShowing()) {
                splashDialog.dismiss();
            }
        } catch (Exception ignored) {
        }
        splashOverlayView = null;
        splashDialog = null;
        enableImmersiveFullscreen();
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
        dismissSplashDialogSafely();
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
        dismissSplashDialogSafely();
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
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
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

    /**
     * Pure Black Minecraft Java Edition Style Loading Splash Overlay:
     * - Compact, standard-proportioned 8x8 blocky Minecraft logo "HORIZONZ" where the first 'O'
     *   is an iconic 8x8 Minecraft Creeper Face block!
     * - Stays visible on its own Dialog Window surface above NativeActivity until the Rust engine
     *   signals `100|Main Menu Ready` AND the swapchain has presented the Main Menu frame!
     */
    public static final class HorizonSplashOverlayView extends View {
        private final File bootFile;
        private final Runnable onDismissCallback;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pixelPaint = new Paint();
        private final Paint barBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tmpRect = new RectF();
        private final Paint bgImagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF bgDst = new RectF();
        private volatile Bitmap[] sceneLayers = new Bitmap[0];
        private volatile Bitmap[] spriteSheets = new Bitmap[0];
        private final Rect spriteSrc = new Rect();
        private final RectF spriteDst = new RectF();

        // Small animated props, one 4-frame strip each, sized in the 1536x1024 layer space and drawn
        // inside their layer so they follow its dive zoom and sway. Order: creeper, bat.
        private static final String[] SPRITE_FILES = {
            "splash_sprite_creeper.png", "splash_sprite_bat.png"
        };
        private static final int[] SPRITE_LAYER = {2, 0};
        private static final float BAT_FIRST_MS = 4200f;
        private static final float BAT_PERIOD_MS = 12000f;
        private volatile boolean released = false;

        private float displayedProgress = 0.22f;
        private float targetProgress = 0.28f;
        private String stageLabel = "Initializing HorizonZ Rust Engine...";
        private boolean engineReady = false;
        private long createdTimeMs = System.currentTimeMillis();
        private long engineReadyFirstSeenMs = 0L;
        private float overlayAlpha = 1.0f;
        private boolean active = true;
        // Launcher mode: the same splash drawn behind PLAY in the launcher process. It animates from the
        // shared start time but never reads the boot file and never dismisses itself.
        private boolean launcherMode = false;

        // Compact 8x8 Chunky Minecraft Title Block Glyphs (1 = foreground block, 2 = Creeper face feature)
        private static final int[][] GLYPH_H = {
            {1,1,0,0,0,0,1,1},
            {1,1,0,0,0,0,1,1},
            {1,1,0,0,0,0,1,1},
            {1,1,1,1,1,1,1,1},
            {1,1,1,1,1,1,1,1},
            {1,1,0,0,0,0,1,1},
            {1,1,0,0,0,0,1,1},
            {1,1,0,0,0,0,1,1}
        };
        // Iconic 8x8 Minecraft Creeper Face integrated as the letter 'O'!
        // 1 = Creeper Green Block, 2 = Creeper Black Eyes/Mouth, 3 = Lighter Creeper Pixel Highlight
        private static final int[][] GLYPH_CREEPER_O = {
            {1,1,3,1,1,3,1,1},
            {1,3,1,1,3,1,1,1},
            {1,2,2,1,1,2,2,1},
            {1,2,2,1,1,2,2,1},
            {1,1,1,2,2,1,1,1},
            {1,1,2,2,2,2,1,1},
            {1,1,2,2,2,2,1,1},
            {1,1,2,1,1,2,1,1}
        };
        private static final int[][] GLYPH_R = {
            {1,1,1,1,1,1,1,0},
            {1,1,0,0,0,0,1,1},
            {1,1,0,0,0,0,1,1},
            {1,1,1,1,1,1,1,0},
            {1,1,1,1,1,0,0,0},
            {1,1,0,0,1,1,0,0},
            {1,1,0,0,0,1,1,0},
            {1,1,0,0,0,0,1,1}
        };
        private static final int[][] GLYPH_I = {
            {1,1,1,1,1,1,1,1},
            {0,0,0,1,1,0,0,0},
            {0,0,0,1,1,0,0,0},
            {0,0,0,1,1,0,0,0},
            {0,0,0,1,1,0,0,0},
            {0,0,0,1,1,0,0,0},
            {0,0,0,1,1,0,0,0},
            {1,1,1,1,1,1,1,1}
        };
        private static final int[][] GLYPH_Z = {
            {1,1,1,1,1,1,1,1},
            {0,0,0,0,0,1,1,1},
            {0,0,0,0,1,1,1,0},
            {0,0,0,1,1,1,0,0},
            {0,0,1,1,1,0,0,0},
            {0,1,1,1,0,0,0,0},
            {1,1,1,0,0,0,0,0},
            {1,1,1,1,1,1,1,1}
        };
        private static final int[][] GLYPH_O = {
            {0,1,1,1,1,1,1,0},
            {1,1,1,0,0,1,1,1},
            {1,1,0,0,0,0,1,1},
            {1,1,0,0,0,0,1,1},
            {1,1,0,0,0,0,1,1},
            {1,1,0,0,0,0,1,1},
            {1,1,1,0,0,1,1,1},
            {0,1,1,1,1,1,1,0}
        };
        private static final int[][] GLYPH_N = {
            {1,1,0,0,0,0,1,1},
            {1,1,1,0,0,0,1,1},
            {1,1,1,1,0,0,1,1},
            {1,1,0,1,1,0,1,1},
            {1,1,0,0,1,1,1,1},
            {1,1,0,0,0,1,1,1},
            {1,1,0,0,0,0,1,1},
            {1,1,0,0,0,0,1,1}
        };

        private static final int[][][] WORD_HORIZONZ = {
            GLYPH_H, GLYPH_CREEPER_O, GLYPH_R, GLYPH_I, GLYPH_Z, GLYPH_O, GLYPH_N, GLYPH_Z
        };

        private final Runnable frameLoop = new Runnable() {
            @Override
            public void run() {
                if (!active) return;
                pollBootMilestone();
                updateAnimationState();
                invalidate();
                if (active) {
                    handler.postDelayed(this, 16L);
                }
            }
        };

        public HorizonSplashOverlayView(Context context, File homeDir, Runnable onDismissCallback) {
            super(context);
            this.bootFile = new File(homeDir, "zalith-game-boot.txt");
            this.onDismissCallback = onDismissCallback;

            bgPaint.setColor(Color.BLACK);
            bgPaint.setStyle(Paint.Style.FILL);
            pixelPaint.setStyle(Paint.Style.FILL);

            barBorderPaint.setColor(Color.WHITE);
            barBorderPaint.setStyle(Paint.Style.STROKE);

            barFillPaint.setColor(Color.WHITE);
            barFillPaint.setStyle(Paint.Style.FILL);

            textPaint.setColor(Color.parseColor("#D5DEE5"));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setFakeBoldText(true);

            subTextPaint.setColor(Color.parseColor("#9EA8B0"));
            subTextPaint.setTextAlign(Paint.Align.CENTER);
            subTextPaint.setFakeBoldText(true);

            loadSceneLayersAsync(context);
            handler.post(frameLoop);
        }

        /** Back-to-front art stack: assets/splash_layer_0.png (opaque) .. splash_layer_3.png (transparent). */
        private void loadSceneLayersAsync(final Context context) {
            final android.content.res.AssetManager am = context.getAssets();
            Thread th = new Thread(() -> {
                Bitmap[] raw = new Bitmap[4];
                int count = 0;
                for (int i = 0; i < raw.length; i++) {
                    Bitmap b = decodeSplashAsset(am, "splash_layer_" + i + ".png", i == 0);
                    if (b == null && i == 0) b = decodeSplashAsset(am, "splash_bg.png", true);
                    raw[i] = b;
                    if (b != null) count++;
                }
                if (count == 0) return;
                Bitmap[] sprites = new Bitmap[SPRITE_FILES.length];
                for (int i = 0; i < sprites.length; i++) {
                    sprites[i] = decodeSplashAsset(am, SPRITE_FILES[i], false);
                }
                if (released) {
                    for (Bitmap b : raw) {
                        if (b != null) b.recycle();
                    }
                    for (Bitmap b : sprites) {
                        if (b != null) b.recycle();
                    }
                    return;
                }
                spriteSheets = sprites;
                sceneLayers = raw;
            }, "splash-layers");
            th.setDaemon(true);
            th.start();
        }

        private static Bitmap decodeSplashAsset(android.content.res.AssetManager am, String name, boolean opaque) {
            try (java.io.InputStream in = am.open(name)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                byte[] data = bos.toByteArray();
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                opts.inSampleSize = 1;
                while (opts.outWidth / opts.inSampleSize > 1700) opts.inSampleSize *= 2;
                opts.inJustDecodeBounds = false;
                opts.inPreferredConfig = opaque ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
                return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            } catch (Exception ignored) {
                return null;
            }
        }

        private void drawSprites(Canvas canvas, int layer, float left, float top, float unit,
                                 float t, int alphaInt, float dive) {
            Bitmap[] sheets = spriteSheets;
            for (int s = 0; s < sheets.length; s++) {
                Bitmap sh = sheets[s];
                if (sh == null || sh.isRecycled() || SPRITE_LAYER[s] != layer) continue;
                int cw = sh.getWidth() / 4;
                int ch = sh.getHeight();
                int frame;
                float cx;
                float cy;
                boolean bottomAnchor;
                if (s == 0) {
                    frame = ((int) (t / 280f)) % 4;
                    cx = 316f;
                    cy = 754f;
                    bottomAnchor = true;
                } else {
                    float since = t - BAT_FIRST_MS;
                    if (since < 0f) continue;
                    float local = (since % BAT_PERIOD_MS) / BAT_PERIOD_MS;
                    cx = -cw + local * (1536f + cw * 2f);
                    cy = 330f + (float) Math.sin(t / 650.0) * 22f;
                    int f = ((int) (t / 110f)) % 6;
                    frame = f < 4 ? f : 6 - f;
                    bottomAnchor = false;
                }
                float dw = cw * unit;
                float dh = ch * unit;
                float x = left + (cx - cw / 2f) * unit;
                float y = top + (bottomAnchor ? (cy - ch) : (cy - ch / 2f)) * unit;
                spriteSrc.set(frame * cw, 0, (frame + 1) * cw, ch);
                spriteDst.set(x, y, x + dw, y + dh);
                bgImagePaint.setAlpha((int) (alphaInt * clamp01(dive * 1.4f)));
                canvas.drawBitmap(sh, spriteSrc, spriteDst, bgImagePaint);
            }
        }

        /**
         * The world behind the logo. During the dive the layers start zoomed in (front ones most) and settle,
         * so the camera seems to fall through the scene; afterwards they sway very slightly at different speeds.
         */
        private void drawScene(Canvas canvas, int w, int h, float t, int alphaInt, float dive) {
            Bitmap[] layers = sceneLayers;
            int n = layers.length;
            if (n == 0 || dive <= 0f) return;
            for (int i = 0; i < n; i++) {
                Bitmap b = layers[i];
                if (b == null || b.isRecycled()) continue;
                float depth = n > 1 ? i / (float) (n - 1) : 0f;
                float zoom = 1.06f + (1f - dive) * (0.30f + 0.55f * depth);
                float swing = 0.4f + depth * 1.6f;
                float driftX = (float) Math.sin(t / 5200.0 + i * 1.7) * w * 0.006f * swing;
                float driftY = (float) Math.cos(t / 6400.0 + i * 1.1) * h * 0.004f * swing;
                float scale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight()) * zoom;
                float dw = b.getWidth() * scale;
                float dh = b.getHeight() * scale;
                float left = (w - dw) / 2f + driftX;
                float top = (h - dh) / 2f + driftY - (1f - dive) * h * 0.12f * depth;
                bgDst.set(left, top, left + dw, top + dh);
                bgImagePaint.setAlpha((int) (alphaInt * clamp01(dive * 1.4f)));
                canvas.drawBitmap(b, null, bgDst, bgImagePaint);
                drawSprites(canvas, i, left, top, scale * (b.getWidth() / 1536f), t, alphaInt, dive);
            }
            pixelPaint.setColor(0xFF000000);
            pixelPaint.setAlpha((int) (alphaInt * 0.18f * dive));
            canvas.drawRect(0, 0, w, h, pixelPaint);
        }

        public void setLauncherMode(boolean enabled) {
            launcherMode = enabled;
        }

        /** Continue the animation from a moment another process already started it (wall clock). */
        public void setStartTimeMs(long ms) {
            long now = System.currentTimeMillis();
            if (ms <= 0L || ms > now || now - ms > 30000L) return;
            createdTimeMs = ms;
            float p = timerProgress(now - ms);
            displayedProgress = p;
            targetProgress = Math.max(targetProgress, p);
        }

        private static float timerProgress(long elapsedMs) {
            if (elapsedMs > 3100L) return 0.88f + Math.min(0.09f, (elapsedMs - 3100L) / 40000.0f * 0.09f);
            if (elapsedMs > 2200L) return 0.88f;
            if (elapsedMs > 1200L) return 0.70f;
            if (elapsedMs > 400L) return 0.46f;
            return 0.28f;
        }

        private void resumeLoop() {
            if (!active) return;
            handler.removeCallbacks(frameLoop);
            handler.post(frameLoop);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            resumeLoop();
        }

        @Override
        protected void onDetachedFromWindow() {
            handler.removeCallbacks(frameLoop);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == View.VISIBLE) {
                resumeLoop();
            } else {
                // Hidden behind the running game: stop drawing frames nobody can see.
                handler.removeCallbacks(frameLoop);
            }
        }

        void stopPolling() {
            active = false;
            released = true;
            Bitmap[] old = sceneLayers;
            Bitmap[] oldSprites = spriteSheets;
            sceneLayers = new Bitmap[0];
            spriteSheets = new Bitmap[0];
            for (Bitmap b : old) {
                if (b != null) b.recycle();
            }
            for (Bitmap b : oldSprites) {
                if (b != null) b.recycle();
            }
            handler.removeCallbacksAndMessages(null);
        }

        private void pollBootMilestone() {
            if (launcherMode || !bootFile.exists()) return;
            try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(bootFile), StandardCharsets.UTF_8)
            )) {
                String line = br.readLine();
                if (line != null && !line.isEmpty()) {
                    int sep = line.indexOf('|');
                    if (sep > 0) {
                        int pct = Integer.parseInt(line.substring(0, sep).trim());
                        String msg = line.substring(sep + 1).trim();
                        if (!msg.isEmpty()) {
                            stageLabel = msg;
                        }
                        if (pct >= 100) {
                            if (!engineReady) {
                                engineReady = true;
                                engineReadyFirstSeenMs = System.currentTimeMillis();
                            }
                        } else {
                            targetProgress = Math.max(targetProgress, Math.min(0.92f, pct / 100.0f));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private void updateAnimationState() {
            long now = System.currentTimeMillis();
            long elapsed = now - createdTimeMs;

            if (launcherMode || !engineReady) {
                if (elapsed > 400L && targetProgress < 0.46f) {
                    targetProgress = 0.46f;
                    stageLabel = "Creating Vulkan / WGPU Surface...";
                } else if (elapsed > 1200L && targetProgress < 0.70f) {
                    targetProgress = 0.70f;
                    stageLabel = "Loading Minecraft 26.3 Registries & Atlases...";
                } else if (elapsed > 2200L && targetProgress < 0.88f) {
                    targetProgress = 0.88f;
                    stageLabel = "Applying Custom Player Skin & Audio Engine...";
                } else if (elapsed > 3100L) {
                    // Slow creep while the engine really loads; never claims to be done early.
                    float creep = 0.88f + Math.min(0.09f, (elapsed - 3100L) / 40000.0f * 0.09f);
                    targetProgress = Math.max(targetProgress, creep);
                    stageLabel = "Preparing Panorama & Main Menu...";
                }
                // Safety net only: the engine writes "100|Main Menu Ready" when the menu exists.
                if (!launcherMode && elapsed >= 60000L) {
                    engineReady = true;
                    engineReadyFirstSeenMs = now;
                }
                displayedProgress += (targetProgress - displayedProgress) * 0.09f;
            } else {
                // Once `100|Main Menu Ready` is written by Rust, wait ~1.25s while smoothly advancing
                // to 100% so the Vulkan swapchain has already presented the TitleScreen underneath!
                long sinceEngineReady = now - engineReadyFirstSeenMs;
                targetProgress = 1.0f;
                displayedProgress += (1.0f - displayedProgress) * 0.10f;
                stageLabel = "Minecraft 26.3 Ready";

                if (sinceEngineReady >= 800L && displayedProgress >= 0.97f && elapsed >= INTRO_HOLD_END_MS + DIVE_MS + 400L) {
                    displayedProgress = 1.0f;
                    float fadeProgress = (sinceEngineReady - 800L) / 400.0f;
                    overlayAlpha = Math.max(0.0f, 1.0f - fadeProgress);
                    if (overlayAlpha <= 0.01f) {
                        active = false;
                        if (onDismissCallback != null) {
                            onDismissCallback.run();
                        }
                    }
                }
            }
        }

        // ---- Intro animation (inspired by the Mojang-style "scramble, then settle" logo reveal) ----
        private static final int COL_WHITE_TOP = 0xFFFFFFFF;
        private static final int COL_WHITE_MID = 0xFFF2F6F8;
        private static final int COL_WHITE_LOW = 0xFFD0D8DE;
        private static final int COL_WHITE_SHADOW = 0xFF2A3238;
        private static final int COL_CREEPER_EYE = 0xFF111712;
        private static final int COL_CREEPER_HI = 0xFF72E052;
        private static final int COL_CREEPER_TOP = 0xFF6CE04E;
        private static final int COL_CREEPER_BODY = 0xFF49AC30;
        private static final int COL_CREEPER_SHADOW = 0xFF1B4D14;
        private static final int COL_Z_TOP = 0xFFC2F0FF;
        private static final int COL_Z_BODY = 0xFF8BE0FF;
        private static final int COL_Z_SHADOW = 0xFF1D4E63;

        // Intro timeline: logo scrambles in on a flat colour, holds, then the camera dives into the scene.
        private static final long INTRO_HOLD_END_MS = 2900L;
        private static final long DIVE_MS = 1100L;
        private static final int INTRO_BG_COLOR = 0xFFE5392B;

        private static final long LETTER_START_MS = 180L;   // first letter begins scrambling
        private static final long LETTER_STAGGER_MS = 150L; // delay between letters
        private static final long SCRAMBLE_MS = 450L;       // how long a letter shows shuffled fragments
        private static final long POP_MS = 400L;            // settle pop + flash


        private static float clamp01(float v) {
            return v < 0f ? 0f : (v > 1f ? 1f : v);
        }

        /** Deterministic 0..1 hash, so the "random" fragments are stable within one animation tick. */
        private static float hash01(int a, int b) {
            int h = a * 73856093 ^ b * 19349663;
            h ^= (h >>> 13);
            h *= 0x5bd1e995;
            h ^= (h >>> 15);
            return (h & 0xFFFF) / 65535.0f;
        }

        private static int lerpColor(int from, int to, float f) {
            f = clamp01(f);
            int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * f);
            int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * f);
            int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f);
            return Color.rgb(r, g, b);
        }

        /** Draws one glyph at (lx, ly). `shine` is the x of the sweeping highlight band (or <0 for none). */
        private void drawLetter(Canvas canvas, int l, int[][] glyph, float lx, float ly, float pxSize,
                                float depthOff, int alphaInt, float flash, float shineX, boolean scrambled,
                                int bucket) {
            int letterCols = 8;
            int letterRows = 8;
            boolean isCreeper = (l == 1);
            boolean isLast = (l == WORD_HORIZONZ.length - 1);
            int shadow = isCreeper ? COL_CREEPER_SHADOW : (isLast ? COL_Z_SHADOW : COL_WHITE_SHADOW);

            // Extrusion pass
            pixelPaint.setColor(shadow);
            pixelPaint.setAlpha(alphaInt);
            for (int r = 0; r < letterRows; r++) {
                for (int c = 0; c < letterCols; c++) {
                    if (glyph[r][c] == 0) continue;
                    if (scrambled && hash01(l * 64 + r * 8 + c, bucket) < 0.30f) continue;
                    float px = lx + c * pxSize;
                    float py = ly + r * pxSize;
                    canvas.drawRect(px + depthOff * 0.6f, py + depthOff,
                        px + pxSize + depthOff * 0.6f + 0.5f, py + pxSize + depthOff + 0.5f, pixelPaint);
                }
            }
            // Face pass
            float band = pxSize * 7f;
            for (int r = 0; r < letterRows; r++) {
                for (int c = 0; c < letterCols; c++) {
                    int cell = glyph[r][c];
                    if (cell == 0) continue;
                    if (scrambled && hash01(l * 64 + r * 8 + c, bucket) < 0.30f) continue;
                    int color;
                    if (isCreeper && !scrambled) {
                        color = (cell == 2) ? COL_CREEPER_EYE
                            : (cell == 3) ? COL_CREEPER_HI
                            : (r == 0) ? COL_CREEPER_TOP : COL_CREEPER_BODY;
                    } else if (isLast) {
                        color = (r == 0) ? COL_Z_TOP : COL_Z_BODY;
                    } else if (isCreeper) {
                        color = (r == 0) ? COL_CREEPER_TOP : COL_CREEPER_BODY;
                    } else {
                        color = (r == 0) ? COL_WHITE_TOP : (r >= 6) ? COL_WHITE_LOW : COL_WHITE_MID;
                    }
                    float px = lx + c * pxSize;
                    float py = ly + r * pxSize;
                    float boost = flash;
                    if (shineX >= 0f) {
                        // Diagonal sweep: leaning band travelling left to right.
                        float d = Math.abs((px + pxSize * 0.5f) + (py - ly) * 0.6f - shineX);
                        if (d < band) boost = Math.max(boost, 0.75f * (1f - d / band));
                    }
                    if (boost > 0f) color = lerpColor(color, 0xFFFFFFFF, boost);
                    pixelPaint.setColor(color);
                    pixelPaint.setAlpha(alphaInt);
                    canvas.drawRect(px, py, px + pxSize + 0.5f, py + pxSize + 0.5f, pixelPaint);
                }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float t = (float) (System.currentTimeMillis() - createdTimeMs);
            float diveRaw = clamp01((t - INTRO_HOLD_END_MS) / (float) DIVE_MS);
            float dive = diveRaw * diveRaw * (3f - 2f * diveRaw);
            int alphaInt = Math.max(0, Math.min(255, (int) (overlayAlpha * 255.0f)));
            bgPaint.setAlpha(alphaInt);
            canvas.drawRect(0, 0, w, h, bgPaint);

            float density = getResources().getDisplayMetrics().density;
            int letterCols = 8;
            int letterRows = 8;
            int gapCols = 1;
            int count = WORD_HORIZONZ.length;
            int totalCols = count * letterCols + (count - 1) * gapCols;

            float pxSize = Math.max(4f, Math.min(w * 0.50f / totalCols, 6.2f * density));
            float logoWidth = totalCols * pxSize;
            float logoHeight = letterRows * pxSize;
            float startX = (w - logoWidth) / 2f;
            float normalStartY = (h * 0.41f) - (logoHeight / 2f);
            float introStartY = (h * 0.50f) - (logoHeight / 2f);
            float startY = introStartY + (normalStartY - introStartY) * dive;
            float depthOff = Math.max(2f, pxSize * 0.48f);
            float cx = w / 2f;
            float cy = startY + logoHeight / 2f;

            drawScene(canvas, w, h, t, alphaInt, dive);
            float colorA = 1f - clamp01(dive * 1.6f);
            if (colorA > 0f) {
                pixelPaint.setColor(INTRO_BG_COLOR);
                pixelPaint.setAlpha((int) (alphaInt * colorA));
                canvas.drawRect(0, 0, w, h, pixelPaint);
            }

            // Exit: the whole scene pushes toward the viewer while it fades (like the intro's dive).
            float exit = 1f - overlayAlpha;
            canvas.save();
            if (exit > 0f) {
                float zoom = 1f + 0.12f * exit * exit;
                canvas.scale(zoom, zoom, cx, cy);
            }

            long lastSettled = LETTER_START_MS + (count - 1) * LETTER_STAGGER_MS + SCRAMBLE_MS + POP_MS;
            // Shine sweeps every ~2.6s once the whole word has settled.
            float shineX = -1f;
            if (t > lastSettled) {
                float cyc = ((t - lastSettled) % 2600f) / 2600f;
                if (cyc < 0.45f) {
                    float u = cyc / 0.45f;
                    shineX = startX - pxSize * 8f + u * (logoWidth + pxSize * 16f);
                }
            }

            for (int l = 0; l < count; l++) {
                float lStart = LETTER_START_MS + l * LETTER_STAGGER_MS;
                if (t < lStart) continue;
                float lx = startX + l * (letterCols + gapCols) * pxSize;
                float ly = startY;
                int letterAlpha = alphaInt;
                if (t < lStart + SCRAMBLE_MS) {
                    // Scramble: shuffled glyph fragments flicker in place, then fade up.
                    int bucket = (int) (t / 90f);
                    int pick = (int) (hash01(l + 3, bucket) * count) % count;
                    float fade = clamp01((t - lStart) / 140f);
                    letterAlpha = (int) (alphaInt * (0.35f + 0.5f * fade));
                    float jitter = (hash01(l, bucket + 99) - 0.5f) * pxSize * 0.8f;
                    drawLetter(canvas, l, WORD_HORIZONZ[pick], lx, ly + jitter, pxSize, depthOff,
                        letterAlpha, 0f, -1f, true, bucket);
                } else {
                    float p = clamp01((t - lStart - SCRAMBLE_MS) / POP_MS);
                    float scale = (p < 1f)
                        ? 1f + 0.20f * (1f - p) * (1f - p) + 0.04f * (float) Math.sin(p * Math.PI)
                        : 1f;
                    float flash = (1f - p) * (1f - p);
                    float lcx = lx + letterCols * pxSize / 2f;
                    float lcy = ly + letterRows * pxSize / 2f;
                    canvas.save();
                    canvas.scale(scale, scale, lcx, lcy);
                    drawLetter(canvas, l, WORD_HORIZONZ[l], lx, ly, pxSize, depthOff,
                        letterAlpha, flash, shineX, false, 0);
                    canvas.restore();
                }
            }

            // Subtitle fades up after the word settles.
            float subIn = clamp01((t - (INTRO_HOLD_END_MS + DIVE_MS * 0.55f)) / 450f);
            subTextPaint.setTextSize(10.5f * density);
            subTextPaint.setAlpha((int) (alphaInt * 0.85f * subIn));
            canvas.drawText(
                "MINECRAFT 26.3  •  RUST EDITION",
                w / 2f,
                startY + logoHeight + depthOff + (18f * density) + (1f - subIn) * 6f * density,
                subTextPaint
            );

            // Progress bar slides up and fades in after the logo.
            float barIn = clamp01((t - (INTRO_HOLD_END_MS + DIVE_MS * 0.65f)) / 500f);
            float barSlide = (1f - barIn) * 14f * density;
            int barAlpha = (int) (alphaInt * barIn);
            float barWidth = Math.min(w * 0.54f, 400f * density);
            float barHeight = 14f * density;
            float barLeft = (w - barWidth) / 2f;
            float barTop = h * 0.72f + barSlide;
            float borderStroke = Math.max(2f, 1.8f * density);
            float innerPad = Math.max(3f, 2.8f * density);

            barBorderPaint.setStrokeWidth(borderStroke);
            barBorderPaint.setAlpha(barAlpha);
            tmpRect.set(barLeft, barTop, barLeft + barWidth, barTop + barHeight);
            canvas.drawRect(tmpRect, barBorderPaint);

            float maxFillWidth = (barWidth - innerPad * 2f);
            float fillWidth = Math.max(0f, Math.min(maxFillWidth, maxFillWidth * displayedProgress));
            if (fillWidth > 0f) {
                barFillPaint.setAlpha(barAlpha);
                float fl = barLeft + innerPad;
                float ft = barTop + innerPad;
                float fb = barTop + barHeight - innerPad;
                tmpRect.set(fl, ft, fl + fillWidth, fb);
                canvas.drawRect(tmpRect, barFillPaint);
                // Moving shine stripe across the filled part.
                float stripeW = Math.max(10f * density, fillWidth * 0.18f);
                float sx = fl + ((t % 1400f) / 1400f) * (fillWidth + stripeW) - stripeW;
                float sl = Math.max(fl, sx);
                float sr = Math.min(fl + fillWidth, sx + stripeW);
                if (sr > sl) {
                    pixelPaint.setColor(0xFF8BE0FF);
                    pixelPaint.setAlpha((int) (barAlpha * 0.8f));
                    canvas.drawRect(sl, ft, sr, fb, pixelPaint);
                }
            }

            int pctInt = Math.max(1, Math.min(100, Math.round(displayedProgress * 100f)));
            textPaint.setTextSize(11f * density);
            textPaint.setAlpha((int) (barAlpha * 0.85f));
            canvas.drawText(
                stageLabel + " (" + pctInt + "%)",
                w / 2f,
                barTop + barHeight + (18f * density),
                textPaint
            );
            canvas.restore();
        }
    }
}
