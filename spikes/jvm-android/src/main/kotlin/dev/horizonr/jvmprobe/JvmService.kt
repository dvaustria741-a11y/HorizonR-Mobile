package dev.horizonr.jvmprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Runs in the `:jvm` process (see the manifest). It copies the runtime, loads the Rust library, boots a JVM once,
 * records the outcome in files and then ends its own process: a process can hold one JVM and HotSpot cannot be
 * unloaded, so every measurement needs a fresh process.
 */
class JvmService : Service() {

    override fun onCreate() {
        super.onCreate()
        ProbeFiles.installCrashRecorder(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getIntExtra(ProbeFiles.EXTRA_MODE, -1) ?: -1
        currentMode = mode
        val heap = intent?.getIntExtra(ProbeFiles.EXTRA_HEAP_MIB, ProbeFiles.DEFAULT_HEAP_MIB) ?: ProbeFiles.DEFAULT_HEAP_MIB
        val dir = ProbeFiles.dir(this)
        val foreground = intent?.getBooleanExtra(ProbeFiles.EXTRA_FOREGROUND, false) ?: false
        val eula = intent?.getBooleanExtra(ProbeFiles.EXTRA_EULA, false) ?: false
        // startForegroundService() obliges a startForeground() call within seconds, so it comes first.
        if (foreground) promoteToForeground()
        if (used) {
            // A second boot in this process cannot work. Say so and leave; the app starts a fresh process next time.
            stage(dir, "error", "process already booted a JVM")
            Process.killProcess(Process.myPid())
            return START_NOT_STICKY
        }
        used = true
        ProbeFiles.startHeartbeat(dir, foreground)
        // 8 MiB stack: the JVM's creating thread; an untuned guess, the default app thread would also be a candidate.
        Thread(null, { boot(dir, mode, heap, eula) }, "jvmprobe-boot", 8L * 1024 * 1024).start()
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notification_title))
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stage(dir: File, stage: String, detail: String? = null) {
        val values = LinkedHashMap<String, String>()
        values["pid"] = Process.myPid().toString()
        values["stage"] = stage
        values["mode"] = currentMode.toString()
        if (detail != null) values["detail"] = detail.replace('\n', ' ')
        ProbeFiles.writeKeyValues(File(dir, ProbeFiles.STAGE), values)
    }

    private fun finish(dir: File, stage: String, detail: String?) {
        stage(dir, stage, detail)
        Thread.sleep(300)
        Process.killProcess(Process.myPid())
    }

    private fun boot(dir: File, mode: Int, heapMib: Int, eula: Boolean) {
        stage(dir, "installing")
        val home = ProbeFiles.runtimeHome(this)
        try {
            val install = RuntimeInstaller.ensure(this, home) { done, total -> stage(dir, "installing", "copied $done of $total files") }
            ProbeFiles.writeKeyValues(
                File(dir, ProbeFiles.INSTALL),
                mapOf(
                    "cached" to install.cached.toString(),
                    "files" to install.files.toString(),
                    "bytes" to install.bytes.toString(),
                    "install_ms" to install.ms.toString(),
                ),
            )
            RuntimeInstaller.copyProbeJar(this, File(dir, ProbeFiles.PROBE_JAR))
        } catch (e: Exception) {
            finish(dir, "error", "runtime copy: $e")
            return
        }
        try {
            System.loadLibrary(NativeBoot.LIBRARY)
        } catch (e: UnsatisfiedLinkError) {
            finish(dir, "error", "loading lib${NativeBoot.LIBRARY}.so: ${e.message}")
            return
        }
        if (mode == NativeBoot.MODE_SERVER) {
            runServer(dir, home, heapMib, eula)
            return
        }
        stage(dir, "booting")
        val code = NativeBoot.run(mode, heapMib, home.path, File(dir, ProbeFiles.PROBE_JAR).path, dir.path)
        finish(dir, "done", code.toString())
    }

    /**
     * Downloads what the server needs, starts the monitor and runs the Fabric server jar in this process. The call
     * blocks until the JVM exits by itself; the monitor ends it with SIGTERM once the server is ready.
     */
    private fun runServer(dir: File, home: File, heapMib: Int, eula: Boolean) {
        val startMs = System.currentTimeMillis()
        val prepared = try {
            ServerSetup.prepare(this, home, eula) { detail -> stage(dir, "preparing", detail) }
        } catch (e: Exception) {
            finish(dir, "error", "server setup: $e")
            return
        }
        ProbeFiles.writeKeyValues(File(dir, ProbeFiles.PREP), prepared.facts)
        // Diagnostics only: a HotSpot crash report and the GC's init lines (which collector the JVM chose).
        val options = prepared.options + listOf(
            "-XX:ErrorFile=${File(dir, "hs_err_%p.log").path}",
            "-Xlog:gc+init=info,gc=info:file=${File(dir, ProbeFiles.GC_LOG).path}",
        )
        val console = redirectConsoleInput()
        startServerMonitor(dir, startMs, console)
        stage(dir, "booting", "server")
        val code = NativeBoot.runServer(heapMib, home.path, dir.path, prepared.dir.path, prepared.jar.path, options.joinToString("\n"))
        // Reached only when the JVM returned by itself (the console stop worked); a SIGTERM exit never gets here.
        val server = File(dir, ProbeFiles.SERVER)
        ProbeFiles.writeKeyValues(server, ProbeFiles.readKeyValues(server) + mapOf("exit_code" to code.toString()))
        finish(dir, "done", code.toString())
    }

    /**
     * Watches the server's console log (`jvm_stdio.log`). When vanilla's "Done" line appears it waits
     * [ServerConfig.SETTLE_SECONDS], records RSS and peak RSS, and sends SIGTERM to this process, which HotSpot answers by
     * running shutdown hooks (Minecraft's saves the world). Whether HotSpot handles SIGTERM next to ART is unverified;
     * after [ServerConfig.STOP_TIMEOUT_SECONDS] the process is killed instead and `stop=killed` is recorded.
     */
    private fun startServerMonitor(dir: File, startMs: Long, console: OutputStream?) {
        Thread({
            val log = File(dir, ProbeFiles.STDIO)
            val results = LinkedHashMap<String, String>()
            results["start_ms"] = startMs.toString()
            val deadline = startMs + ServerConfig.START_TIMEOUT_SECONDS * 1000L
            var reached = false
            while (!reached) {
                Thread.sleep(500)
                reached = ServerLog.summarize(readLog(log)).doneSeconds != null
                if (!reached && System.currentTimeMillis() > deadline) {
                    results["done"] = "not reached in ${ServerConfig.START_TIMEOUT_SECONDS} s"
                    results["stop"] = "killed"
                    ProbeFiles.writeKeyValues(File(dir, ProbeFiles.SERVER), results)
                    stage(dir, "error", "server did not print Done within ${ServerConfig.START_TIMEOUT_SECONDS} s")
                    Thread.sleep(300)
                    Process.killProcess(Process.myPid())
                }
            }
            results["done_ms"] = (System.currentTimeMillis() - startMs).toString()
            results.putAll(pingFacts())
            Thread.sleep(ServerConfig.SETTLE_SECONDS * 1000L)
            val status = runCatching { File("/proc/self/status").readText() }.getOrDefault("")
            results["rss_kib_settled"] = ProbeFiles.parseStatusNumber(status, "VmRSS")?.toString() ?: "unknown"
            results["rss_peak_kib"] = ProbeFiles.parseStatusNumber(status, "VmHWM")?.toString() ?: "unknown"
            results["threads"] = ProbeFiles.parseStatusNumber(status, "Threads")?.toString() ?: "unknown"
            results["settle_s"] = ServerConfig.SETTLE_SECONDS.toString()
            val viaConsole = console != null && sendConsoleStop(console)
            results["stop"] = if (viaConsole) "console command '${ServerConfig.STOP_COMMAND}' sent" else "SIGTERM sent (no console)"
            ProbeFiles.writeKeyValues(File(dir, ProbeFiles.SERVER), results)
            stage(dir, "server_stopping", "${results["stop"]}, waiting")
            if (viaConsole) {
                Thread.sleep(ServerConfig.CONSOLE_STOP_SECONDS * 1000L)
                results["stop"] = "the console command did not end the JVM within ${ServerConfig.CONSOLE_STOP_SECONDS} s; SIGTERM sent"
                ProbeFiles.writeKeyValues(File(dir, ProbeFiles.SERVER), results)
            }
            runCatching { Os.kill(Process.myPid(), OsConstants.SIGTERM) }
            Thread.sleep(ServerConfig.STOP_TIMEOUT_SECONDS * 1000L)
            results["stop"] = "killed: the JVM did not exit within ${ServerConfig.STOP_TIMEOUT_SECONDS} s of SIGTERM"
            ProbeFiles.writeKeyValues(File(dir, ProbeFiles.SERVER), results)
            Process.killProcess(Process.myPid())
        }, "jvmprobe-server-monitor").apply { isDaemon = true }.start()
    }

    /**
     * Points this process's stdin (fd 0) at a pipe before the JVM starts, so the server's console can be driven with
     * `stop`. Returns the pipe's write end, or null when the pipe could not be set up (SIGTERM is used then).
     */
    private fun redirectConsoleInput(): OutputStream? = try {
        val pipe = Os.pipe()
        Os.dup2(pipe[0], 0)
        Os.close(pipe[0])
        FileOutputStream(pipe[1])
    } catch (e: Exception) {
        null
    }

    private fun sendConsoleStop(console: OutputStream): Boolean = try {
        console.write((ServerConfig.STOP_COMMAND + "\n").toByteArray())
        console.flush()
        true
    } catch (e: IOException) {
        false
    }

    /** What the running server answers to a status ping; single-line values, because server.txt is key=value lines. */
    private fun pingFacts(): Map<String, String> {
        val outcome = ServerPing.queryWithRetry(ServerConfig.SERVER_HOST, ServerConfig.SERVER_PORT, ServerConfig.PING_ATTEMPTS, ServerConfig.PING_DELAY_MS, ServerConfig.PING_TIMEOUT_MS)
        val status = outcome.status ?: return mapOf("ping_error" to (outcome.error ?: "unknown").replace('\n', ' '))
        return linkedMapOf(
            "ping_version_name" to (status.versionName ?: "missing in the reply"),
            "ping_protocol" to (status.protocol?.toString() ?: "missing in the reply"),
            "ping_ms" to status.roundTripMs.toString(),
            "ping_json" to status.rawJson.replace('\n', ' ').take(400),
        )
    }

    /** The whole log up to 2 MiB; empty when unreadable. */
    private fun readLog(file: File): List<String> =
        if (!file.isFile) emptyList() else runCatching { file.readText().take(2 * 1024 * 1024).lines() }.getOrDefault(emptyList())

    private companion object {
        const val CHANNEL_ID = "jvmprobe"
        const val NOTIFICATION_ID = 1

        @Volatile
        var used = false

        /** The mode of this process's run, written into every stage file so the screen can name the run. */
        @Volatile
        var currentMode = -1
    }
}
