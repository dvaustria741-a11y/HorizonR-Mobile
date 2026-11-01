package dev.horizon.android;

import android.app.Dialog;
import android.app.NativeActivity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
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

import dev.horizon.android.ui.ZalithMainActivity;

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
 * - Guaranteed single-shot launch & clean return to ZalithMainActivity on game exit.
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
            Log.i(TAG, "No active launch token (or exit flag present) — returning to ZalithMainActivity.");
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
        applyZalithLaunchEnvironment(getIntent());
        applyRequestedOrientationFromIntent(getIntent());
        enableImmersiveFullscreen();

        // Show the Separate-Window Splash Dialog BEFORE super.onCreate so there is zero black flash!
        showHorizonSplashDialog(homeDir);

        startExitWatchdog(homeDir);
        startGeminiAiAgentBridgeIfEnabled(homeDir, getIntent());
        super.onCreate(savedInstanceState);
    }

    private void showHorizonSplashDialog(final File homeDir) {
        try {
            splashDialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            splashDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            splashDialog.setCancelable(false);

            splashOverlayView = new HorizonSplashOverlayView(this, homeDir, () -> {
                dismissSplashDialogSafely();
            });
            splashDialog.setContentView(
                splashOverlayView,
                new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            );

            Window win = splashDialog.getWindow();
            if (win != null) {
                win.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
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
            : "Bantu player di dalam game Minecraft 26.3.";

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
                        chatGreet.put("message", "[HorizonZ Gemini AI] Terhubung ke Google AI Studio (" + modelId + "). Ketik @ai <pesan> di chat untuk meminta bantuan!");
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
            Intent backIntent = new Intent(this, ZalithMainActivity.class);
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
    protected void onDestroy() {
        watchdogRunning = false;
        dismissSplashDialogSafely();
        super.onDestroy();
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

    private void applyZalithLaunchEnvironment(Intent intent) {
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

                String shaderPack = intent.getStringExtra("HORIZON_SHADER_PACK");
                if (shaderPack != null && !shaderPack.trim().isEmpty()) {
                    Os.setenv("HORIZON_SHADER_PACK", shaderPack.trim(), true);
                } else {
                    try { Os.unsetenv("HORIZON_SHADER_PACK"); } catch (Exception ignored) {}
                }

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
    private static final class HorizonSplashOverlayView extends View {
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

        private float displayedProgress = 0.22f;
        private float targetProgress = 0.28f;
        private String stageLabel = "Initializing HorizonZ Rust Engine...";
        private boolean engineReady = false;
        private long createdTimeMs = System.currentTimeMillis();
        private long engineReadyFirstSeenMs = 0L;
        private float overlayAlpha = 1.0f;
        private boolean active = true;

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

        HorizonSplashOverlayView(Context context, File homeDir, Runnable onDismissCallback) {
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

            handler.post(frameLoop);
        }

        void stopPolling() {
            active = false;
            handler.removeCallbacksAndMessages(null);
        }

        private void pollBootMilestone() {
            if (!bootFile.exists()) return;
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

            if (!engineReady) {
                if (elapsed > 400L && targetProgress < 0.46f) {
                    targetProgress = 0.46f;
                    stageLabel = "Creating Vulkan / WGPU Surface...";
                } else if (elapsed > 1200L && targetProgress < 0.70f) {
                    targetProgress = 0.70f;
                    stageLabel = "Loading Minecraft 26.3 Registries & Atlases...";
                } else if (elapsed > 2200L && targetProgress < 0.88f) {
                    targetProgress = 0.88f;
                    stageLabel = "Applying Custom Player Skin & Audio Engine...";
                } else if (elapsed > 3100L && targetProgress < 0.96f) {
                    targetProgress = Math.min(0.96f, targetProgress + 0.004f);
                    stageLabel = "Preparing Panorama & Main Menu...";
                }
                if (elapsed >= 4000L) {
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

                if (sinceEngineReady >= 650L && displayedProgress >= 0.97f && elapsed >= 2200L) {
                    displayedProgress = 1.0f;
                    float fadeProgress = (sinceEngineReady - 650L) / 320.0f;
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

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            int alphaInt = Math.max(0, Math.min(255, (int) (overlayAlpha * 255.0f)));
            bgPaint.setAlpha(alphaInt);
            canvas.drawRect(0, 0, w, h, bgPaint);

            float density = getResources().getDisplayMetrics().density;
            int letterCols = 8;
            int letterRows = 8;
            int gapCols = 1;
            int totalCols = WORD_HORIZONZ.length * letterCols + (WORD_HORIZONZ.length - 1) * gapCols;

            // Compact, standard Minecraft title proportions (never stretched vertically!)
            float pxSize = Math.max(4f, Math.min(w * 0.50f / totalCols, 6.2f * density));
            float logoWidth = totalCols * pxSize;
            float logoHeight = letterRows * pxSize;
            float startX = (w - logoWidth) / 2f;
            float startY = (h * 0.41f) - (logoHeight / 2f);
            float depthOff = Math.max(2f, pxSize * 0.48f);

            // Pass 1: 3D Blocky Bottom/Side Extrusion Shadow for all letters
            for (int l = 0; l < WORD_HORIZONZ.length; l++) {
                int[][] glyph = WORD_HORIZONZ[l];
                float lx = startX + l * (letterCols + gapCols) * pxSize;
                int shadowColor = (l == 1) ? Color.parseColor("#1B4D14")
                    : (l == WORD_HORIZONZ.length - 1) ? Color.parseColor("#1D4E63")
                    : Color.parseColor("#2A3238");
                pixelPaint.setColor(shadowColor);
                pixelPaint.setAlpha(alphaInt);

                for (int r = 0; r < letterRows; r++) {
                    for (int c = 0; c < letterCols; c++) {
                        if (glyph[r][c] != 0) {
                            float px = lx + c * pxSize;
                            float py = startY + r * pxSize;
                            canvas.drawRect(
                                px + depthOff * 0.6f,
                                py + depthOff,
                                px + pxSize + depthOff * 0.6f + 0.5f,
                                py + pxSize + depthOff + 0.5f,
                                pixelPaint
                            );
                        }
                    }
                }
            }

            // Pass 2: Crisp Foreground Pixel Blocks (with Creeper Face on O and Top Bevel Highlight)
            for (int l = 0; l < WORD_HORIZONZ.length; l++) {
                int[][] glyph = WORD_HORIZONZ[l];
                float lx = startX + l * (letterCols + gapCols) * pxSize;

                for (int r = 0; r < letterRows; r++) {
                    for (int c = 0; c < letterCols; c++) {
                        int cell = glyph[r][c];
                        if (cell == 0) continue;
                        int blockColor;
                        if (l == 1) {
                            // Creeper Face 'O' letter!
                            if (cell == 2) {
                                blockColor = Color.parseColor("#111712"); // Creeper black eyes & mouth
                            } else if (cell == 3) {
                                blockColor = Color.parseColor("#72E052"); // Bright Creeper green pixel
                            } else {
                                blockColor = (r == 0) ? Color.parseColor("#6CE04E") : Color.parseColor("#49AC30");
                            }
                        } else if (l == WORD_HORIZONZ.length - 1) {
                            // Final 'Z' in Sky-Blue accent with bright top edge
                            blockColor = (r == 0) ? Color.parseColor("#C2F0FF") : Color.parseColor("#8BE0FF");
                        } else {
                            // Standard Minecraft Title White/Stone with bright top edge & subtle lower shade
                            blockColor = (r == 0) ? Color.WHITE
                                : (r >= 6) ? Color.parseColor("#D0D8DE")
                                : Color.parseColor("#F2F6F8");
                        }
                        pixelPaint.setColor(blockColor);
                        pixelPaint.setAlpha(alphaInt);
                        float px = lx + c * pxSize;
                        float py = startY + r * pxSize;
                        canvas.drawRect(px, py, px + pxSize + 0.5f, py + pxSize + 0.5f, pixelPaint);
                    }
                }
            }

            // Subtitle under HORIZONZ logo
            subTextPaint.setTextSize(10.5f * density);
            subTextPaint.setAlpha((int) (alphaInt * 0.85f));
            canvas.drawText(
                "MINECRAFT 26.3  •  RUST EDITION",
                w / 2f,
                startY + logoHeight + depthOff + (18f * density),
                subTextPaint
            );

            // Classic Minecraft Java Edition Progress Bar
            float barWidth = Math.min(w * 0.54f, 400f * density);
            float barHeight = 14f * density;
            float barLeft = (w - barWidth) / 2f;
            float barTop = h * 0.72f;
            float borderStroke = Math.max(2f, 1.8f * density);
            float innerPad = Math.max(3f, 2.8f * density);

            barBorderPaint.setStrokeWidth(borderStroke);
            barBorderPaint.setAlpha(alphaInt);
            tmpRect.set(barLeft, barTop, barLeft + barWidth, barTop + barHeight);
            canvas.drawRect(tmpRect, barBorderPaint);

            float maxFillWidth = (barWidth - innerPad * 2f);
            float fillWidth = Math.max(0f, Math.min(maxFillWidth, maxFillWidth * displayedProgress));
            if (fillWidth > 0f) {
                barFillPaint.setAlpha(alphaInt);
                tmpRect.set(
                    barLeft + innerPad,
                    barTop + innerPad,
                    barLeft + innerPad + fillWidth,
                    barTop + barHeight - innerPad
                );
                canvas.drawRect(tmpRect, barFillPaint);
            }

            int pctInt = Math.max(1, Math.min(100, Math.round(displayedProgress * 100f)));
            textPaint.setTextSize(11f * density);
            textPaint.setAlpha((int) (alphaInt * 0.85f));
            canvas.drawText(
                stageLabel + " (" + pctInt + "%)",
                w / 2f,
                barTop + barHeight + (18f * density),
                textPaint
            );
        }
    }
}
