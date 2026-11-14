package dev.horizonr.jvmprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import java.io.File

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
        val heap = intent?.getIntExtra(ProbeFiles.EXTRA_HEAP_MIB, ProbeFiles.DEFAULT_HEAP_MIB) ?: ProbeFiles.DEFAULT_HEAP_MIB
        val dir = ProbeFiles.dir(this)
        val foreground = intent?.getBooleanExtra(ProbeFiles.EXTRA_FOREGROUND, false) ?: false
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
        Thread(null, { boot(dir, mode, heap) }, "jvmprobe-boot", 8L * 1024 * 1024).start()
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
        if (detail != null) values["detail"] = detail.replace('\n', ' ')
        ProbeFiles.writeKeyValues(File(dir, ProbeFiles.STAGE), values)
    }

    private fun finish(dir: File, stage: String, detail: String?) {
        stage(dir, stage, detail)
        Thread.sleep(300)
        Process.killProcess(Process.myPid())
    }

    private fun boot(dir: File, mode: Int, heapMib: Int) {
        stage(dir, "installing")
        val home = ProbeFiles.runtimeHome(this)
        try {
            val install = RuntimeInstaller.ensure(this, home)
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
        stage(dir, "booting")
        val code = NativeBoot.run(mode, heapMib, home.path, File(dir, ProbeFiles.PROBE_JAR).path, dir.path)
        finish(dir, "done", code.toString())
    }

    private companion object {
        const val CHANNEL_ID = "jvmprobe"
        const val NOTIFICATION_ID = 1

        @Volatile
        var used = false
    }
}
