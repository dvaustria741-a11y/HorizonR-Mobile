package dev.horizonr.jvmprobe

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** The one screen: two buttons and a text block meant to be screenshotted whole. */
class MainActivity : Activity() {

    private companion object {
        const val MAX_PROBLEMS = 6
        const val MAX_PROBLEM_CHARS = 600
        const val REQUEST_EXPORT = 7
    }

    private enum class State { IDLE, RUNNING, DONE, ERROR, DIED }

    private lateinit var output: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var startedAtMs = 0L
    private var deadPolls = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeFiles.installCrashRecorder(applicationContext)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(Button(this).apply {
            setText(R.string.button_jni)
            setOnClickListener { start(NativeBoot.MODE_JNI, false) }
        })
        column.addView(Button(this).apply {
            setText(R.string.button_jni_fg)
            setOnClickListener { start(NativeBoot.MODE_JNI, true) }
        })
        column.addView(Button(this).apply {
            setText(R.string.button_jli)
            setOnClickListener { start(NativeBoot.MODE_JLI, false) }
        })
        column.addView(Button(this).apply {
            setText(R.string.button_server)
            setOnClickListener { confirmServer() }
        })
        column.addView(Button(this).apply {
            setText(R.string.button_server_keep)
            setOnClickListener { confirmServer(true) }
        })
        column.addView(Button(this).apply {
            setText(R.string.button_stop_server)
            setOnClickListener { stopServer() }
        })
        column.addView(Button(this).apply {
            setText(R.string.button_export)
            setOnClickListener { confirmExport() }
        })
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
        }
        column.addView(output)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        render()
        val stage = ProbeFiles.readKeyValues(File(ProbeFiles.dir(this), ProbeFiles.STAGE))["stage"]
        if (stage != null && stage != "done" && stage != "error") poll()
    }

    override fun onPause() {
        super.onPause()
        polling = false
        handler.removeCallbacksAndMessages(null)
    }

    /** The server run writes `eula.txt`, so it asks once; the answer is kept in this app's private preferences. */
    private fun confirmServer(keepRunning: Boolean = false) {
        val prefs = getSharedPreferences("probe", Context.MODE_PRIVATE)
        if (prefs.getBoolean("eula_accepted", false)) {
            start(NativeBoot.MODE_SERVER, true, keepRunning)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.eula_title)
            .setMessage(getString(R.string.eula_message, ServerConfig.EULA_URL))
            .setPositiveButton(R.string.eula_accept) { _, _ ->
                prefs.edit().putBoolean("eula_accepted", true).apply()
                start(NativeBoot.MODE_SERVER, true, keepRunning)
            }
            .setNegativeButton(R.string.eula_cancel, null)
            .show()
    }

    /** Asks before copying anything out of the app's private storage; the system's storage prompt follows if access is missing. */
    private fun confirmExport() {
        AlertDialog.Builder(this)
            .setTitle(R.string.export_title)
            .setMessage(getString(R.string.export_message, LogExport.FOLDER))
            .setPositiveButton(R.string.export_ok) { _, _ -> requestExport() }
            .setNegativeButton(R.string.eula_cancel, null)
            .show()
    }

    private fun requestExport() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            runExport(true)
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_EXPORT)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_EXPORT) runExport(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
    }

    private fun runExport(sharedStorageAllowed: Boolean) {
        val report = output.text.toString()
        val context = applicationContext
        Thread {
            val message = try {
                val result = LogExport.export(context, report, sharedStorageAllowed)
                if (result.copied == 0 && result.failed.isNotEmpty()) {
                    getString(R.string.export_failed, result.failed.joinToString("; "))
                } else {
                    getString(R.string.export_done, result.copied.toString(), result.location) +
                        if (result.failed.isEmpty()) "" else "\n" + getString(R.string.export_not_saved, result.failed.joinToString("; "))
                }
            } catch (e: Exception) {
                getString(R.string.export_failed, e.toString())
            }
            runOnUiThread { AlertDialog.Builder(this).setTitle(R.string.export_title).setMessage(message).setPositiveButton(android.R.string.ok, null).show() }
        }.start()
    }

    /** Asks the running `:jvm` process to stop its server with the console command. Harmless when none is running. */
    private fun stopServer() {
        try {
            startService(Intent(this, JvmService::class.java).putExtra(ProbeFiles.EXTRA_STOP, true))
        } catch (e: RuntimeException) {
            AlertDialog.Builder(this).setMessage(getString(R.string.start_failed, e.message ?: e.javaClass.simpleName)).setPositiveButton(android.R.string.ok, null).show()
        }
        poll()
    }

    private fun start(mode: Int, foreground: Boolean, keepRunning: Boolean = false) {
        val dir = ProbeFiles.dir(this)
        File(filesDir, ProbeFiles.LAST_CRASH).delete()
        ProbeFiles.killStaleJvm(dir)
        ProbeFiles.reset(dir)
        ProbeFiles.writeKeyValues(File(dir, ProbeFiles.STAGE), mapOf("pid" to "0", "stage" to "starting", "mode" to mode.toString()))
        startedAtMs = System.currentTimeMillis()
        deadPolls = 0
        val intent = Intent(this, JvmService::class.java)
            .putExtra(ProbeFiles.EXTRA_MODE, mode)
            .putExtra(ProbeFiles.EXTRA_HEAP_MIB, if (mode == NativeBoot.MODE_SERVER) ServerConfig.HEAP_MIB else ProbeFiles.DEFAULT_HEAP_MIB)
            .putExtra(ProbeFiles.EXTRA_FOREGROUND, foreground)
            // Only reached after the dialog above (or an earlier acceptance); the service refuses a server run without it.
            .putExtra(ProbeFiles.EXTRA_EULA, mode == NativeBoot.MODE_SERVER)
            .putExtra(ProbeFiles.EXTRA_KEEP_RUNNING, keepRunning)
        try {
            if (foreground) startForegroundService(intent) else startService(intent)
        } catch (e: RuntimeException) {
            // Android refuses to start a service of a process it marked bad; show the reason instead of crashing the UI.
            val detail = getString(R.string.start_failed, e.message ?: e.javaClass.simpleName).replace('\n', ' ')
            ProbeFiles.writeKeyValues(File(dir, ProbeFiles.STAGE), mapOf("pid" to "0", "stage" to "error", "detail" to detail, "mode" to mode.toString()))
            render()
            return
        }
        render()
        poll()
    }

    private fun poll() {
        if (polling) return
        polling = true
        handler.post(object : Runnable {
            override fun run() {
                if (!polling) return
                val state = render()
                if (state == State.RUNNING) {
                    deadPolls = 0
                    handler.postDelayed(this, 500)
                } else if (state == State.DIED && deadPolls++ < 8) {
                    // Android records the exit reason a moment after the death; keep rendering so it shows up.
                    handler.postDelayed(this, 1000)
                } else {
                    polling = false
                }
            }
        })
    }

    private fun state(stage: Map<String, String>): State {
        val name = stage["stage"] ?: return State.IDLE
        val pid = stage["pid"]?.toIntOrNull() ?: 0
        return when {
            name == "done" -> State.DONE
            name == "error" -> State.ERROR
            // The monitor sent SIGTERM and HotSpot exited the process, so "done" was never written: that is the normal end.
            name == "server_stopping" && !ProbeFiles.isJvmProcess(pid) -> State.DONE
            // The service has not written its pid yet: give it a few seconds before calling the process dead.
            // startedAtMs is 0 in a freshly launched UI process, so a stage left at "starting" there means the old run is gone.
            pid == 0 -> if (startedAtMs != 0L && System.currentTimeMillis() - startedAtMs < 5000) State.RUNNING else State.DIED
            ProbeFiles.isJvmProcess(pid) -> State.RUNNING
            else -> State.DIED
        }
    }

    private fun render(): State {
        val dir = ProbeFiles.dir(this)
        val stage = ProbeFiles.readKeyValues(File(dir, ProbeFiles.STAGE))
        val state = state(stage)
        output.text = runCatching { buildReport(dir, stage, state) }
            .getOrElse { "report failed: " + it.stackTraceToString().take(1500) }
        return state
    }

    private fun line(label: Int, value: String) = "${getString(label)}: $value\n"

    private fun buildReport(dir: File, stage: Map<String, String>, state: State): String {
        val unknown = getString(R.string.unknown)
        val out = StringBuilder()

        val apkBytes = File(applicationInfo.sourceDir).length()
        out.append(line(R.string.apk_label, getString(R.string.apk_value, ProbeFiles.mib(apkBytes), apkBytes.toString())))
        out.append(line(R.string.device_label, getString(R.string.device_value, Build.MODEL, Build.VERSION.SDK_INT.toString(), Build.SUPPORTED_ABIS.joinToString("/"))))
        val memory = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        out.append(line(R.string.ram_label, getString(R.string.ram_value, ProbeFiles.mib(memory.totalMem), ProbeFiles.mib(memory.availMem))))
        val uiRss = ProbeFiles.ownRssKib()
        out.append(line(R.string.ui_rss_label, uiRss?.let { getString(R.string.mib_value, ProbeFiles.mibFromKib(it)) } ?: unknown))

        val crash = runCatching { File(filesDir, ProbeFiles.LAST_CRASH).takeIf { it.isFile }?.readLines()?.take(25)?.joinToString("\n") }.getOrNull()
        if (crash != null) out.append('\n').append(getString(R.string.crash_header)).append('\n').append(crash).append('\n')

        if (state == State.IDLE) {
            out.append('\n').append(getString(R.string.hint_idle)).append('\n')
            return out.toString()
        }

        val native = ProbeFiles.readKeyValues(File(dir, ProbeFiles.NATIVE))
        val probe = ProbeFiles.readKeyValues(File(dir, ProbeFiles.PROBE_OUT))
        val install = ProbeFiles.readKeyValues(File(dir, ProbeFiles.INSTALL))
        // The JLI path reports boot time, RSS and properties from inside the JVM (Probe); the JNI path from Rust.
        val merged = LinkedHashMap<String, String>().apply { putAll(native); putAll(probe) }
        val isServer = (stage["mode"] ?: merged["mode"]) == NativeBoot.MODE_SERVER.toString()

        out.append('\n')
        val status = when (state) {
            State.RUNNING -> getString(R.string.status_running, (stage["stage"] ?: unknown) + (stage["detail"]?.let { " - $it" } ?: ""))
            State.DONE -> if (stage["stage"] == "server_stopping") getString(R.string.status_server_stopped) else getString(R.string.status_done, stage["detail"] ?: unknown)
            State.ERROR -> getString(R.string.status_error, stage["detail"] ?: unknown)
            else -> getString(R.string.status_died)
        }
        out.append(line(R.string.status_label, status))
        if (state == State.DIED) {
            out.append(line(R.string.last_stage_label, (stage["stage"] ?: unknown) + (stage["detail"]?.let { " ($it)" } ?: "")))
        }
        val modeText = when {
            isServer -> R.string.mode_server
            stage["mode"] == NativeBoot.MODE_JLI.toString() || merged["mode"] == NativeBoot.MODE_JLI.toString() -> R.string.mode_jli
            else -> R.string.mode_jni
        }
        out.append(line(R.string.mode_label, getString(modeText)))
        out.append(line(R.string.heap_label, getString(R.string.heap_value, (merged["heap_mib"] ?: ProbeFiles.DEFAULT_HEAP_MIB.toString()))))
        if (install.isNotEmpty()) {
            val text = if (install["cached"] == "true") getString(R.string.install_cached) else getString(
                R.string.install_fresh,
                install["files"] ?: unknown,
                install["bytes"]?.toLongOrNull()?.let { ProbeFiles.mib(it) } ?: unknown,
                install["install_ms"] ?: unknown,
            )
            out.append(line(R.string.install_label, text))
        }
        val beat = ProbeFiles.readKeyValues(File(dir, ProbeFiles.BEAT))
        if (beat.isNotEmpty()) {
            val start = beat["start_ms"]?.toLongOrNull()
            val wall = beat["wall_ms"]?.toLongOrNull()
            val clock = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
            val alive = if (start != null && wall != null) "${wall - start} ms at ${clock.format(java.util.Date(wall))}" else unknown
            out.append(line(R.string.beat_label, getString(R.string.beat_value, alive, kibText(beat["vmrss_kib"]), kibText(beat["vmsize_kib"]), beat["threads"] ?: unknown, beat["oom_score_adj"] ?: unknown, beat["fg"] ?: unknown)))
        }
        if (isServer) {
            appendServer(out, dir, state, beat)
        } else {
            out.append(line(R.string.boot_label, merged["boot_ms"]?.let { getString(R.string.boot_value, it) } ?: unknown))
            out.append(line(R.string.boot_def_label, merged["boot_ms_def"] ?: unknown))
            out.append(line(R.string.rss_before_label, kibText(merged["rss_kib_before"])))
            out.append(line(R.string.rss_after_label, kibText(merged["rss_kib_after"])))
            out.append(line(R.string.java_version_label, merged["java.version"] ?: unknown))
            out.append(line(R.string.vm_label, merged["java.vm.name"] ?: unknown))
            out.append(line(R.string.home_label, merged["java.home"] ?: unknown))
        }

        if (merged.isNotEmpty()) {
            out.append('\n').append(getString(R.string.raw_header)).append('\n')
            merged.forEach { (key, value) -> out.append(key).append('=').append(value).append('\n') }
        }
        if (state == State.DIED || state == State.ERROR || merged["error"] != null || (isServer && state == State.DONE)) {
            val stdio = ProbeFiles.tail(File(dir, ProbeFiles.STDIO), 15)
            if (stdio.isNotEmpty()) out.append('\n').append(getString(R.string.stdio_header)).append('\n').append(stdio).append('\n')
            val exits = ProbeFiles.exitInfoTail(this, 8)
            if (exits.isNotEmpty()) out.append('\n').append(getString(R.string.exit_header)).append('\n').append(exits).append('\n')
            val hsErr = ProbeFiles.hsErrHead(dir, 30)
            if (hsErr.isNotEmpty()) out.append('\n').append(getString(R.string.hserr_header)).append('\n').append(hsErr).append('\n')
            val xlog = ProbeFiles.tail(File(dir, "jvm_xlog.txt"), 12)
            if (xlog.isNotEmpty()) out.append('\n').append(getString(R.string.xlog_header)).append('\n').append(xlog).append('\n')
            val logcat = ProbeFiles.logcatTail(40)
            if (logcat.isNotEmpty()) out.append('\n').append(getString(R.string.logcat_header)).append('\n').append(logcat).append('\n')
        }
        return out.toString()
    }

    /** The server run's section: the log read the way spikes/jvm-server-smoke/summarize.py reads it, plus what the monitor measured. */
    private fun appendServer(out: StringBuilder, dir: File, state: State, beat: Map<String, String>) {
        val unknown = getString(R.string.unknown)
        out.append('\n').append(getString(R.string.server_header)).append('\n')
        val prep = ProbeFiles.readKeyValues(File(dir, ProbeFiles.PREP))
        if (prep.isNotEmpty()) {
            out.append(getString(R.string.server_prep_header)).append('\n')
            prep.forEach { (key, value) -> out.append("  ").append(key).append(": ").append(value).append('\n') }
        }
        val log = File(dir, ProbeFiles.STDIO)
        val lines = if (log.isFile) runCatching { log.readText().take(2 * 1024 * 1024).lines() }.getOrDefault(emptyList()) else emptyList()
        val summary = ServerLog.summarize(lines)
        out.append(getString(if (summary.passed) R.string.server_result_pass else R.string.server_result_fail)).append('\n')
        out.append(summary.doneSeconds?.let { getString(R.string.server_done, it) } ?: getString(R.string.server_not_done)).append('\n')
        val results = ProbeFiles.readKeyValues(File(dir, ProbeFiles.SERVER))
        results["done_ms"]?.let { out.append("Done seen by the probe after ").append(it).append(" ms (from the service start, polled every 500 ms)\n") }
        results["rss_kib_settled"]?.let { out.append("RSS ").append(results["settle_s"] ?: unknown).append(" s after Done: ").append(kibText(it)).append('\n') }
        results["rss_peak_kib"]?.let { out.append("Peak RSS (VmHWM): ").append(kibText(it)).append('\n') }
        results["threads"]?.let { out.append("Threads: ").append(it).append('\n') }
        results["done"]?.let { out.append("Done: ").append(it).append('\n') }
        val ping = results.filterKeys { it.startsWith("ping_") }
        if (ping.isNotEmpty()) {
            out.append(getString(R.string.server_ping_header, ServerConfig.SERVER_HOST, ServerConfig.SERVER_PORT.toString())).append('\n')
            ping.forEach { (key, value) -> out.append("  ").append(key.removePrefix("ping_")).append(": ").append(value).append('\n') }
        }
        results["exit_code"]?.let { out.append("JVM exit code (the JVM returned by itself): ").append(it).append('\n') }
        results["stop"]?.let { out.append("Stop: ").append(it).append('\n') }
        if (results["rss_peak_kib"] == null) beat["vmhwm_kib"]?.let { out.append("Peak RSS so far (VmHWM): ").append(kibText(it)).append('\n') }
        out.append(summary.loading ?: getString(R.string.server_loading_none)).append('\n')
        summary.mods.forEach { out.append("  ").append(it).append('\n') }
        out.append(getString(R.string.server_mixin_lines, summary.mixinLines.toString())).append('\n')
        if (summary.problems.isNotEmpty()) {
            out.append(getString(R.string.server_problems_header, MAX_PROBLEMS.toString())).append('\n')
            summary.problems.take(MAX_PROBLEMS).forEach { out.append("  ").append(it.take(MAX_PROBLEM_CHARS)).append('\n') }
        }
        val gc = ProbeFiles.tail(File(dir, ProbeFiles.GC_LOG), 400).lines().filter { it.contains("[gc,init]") }.take(12)
        if (gc.isNotEmpty()) out.append(getString(R.string.server_gc_header)).append('\n').append(gc.joinToString("\n")).append('\n')
        if (state == State.RUNNING) {
            out.append(getString(R.string.server_live_header)).append('\n').append(lines.takeLast(8).joinToString("\n")).append('\n')
        } else if (!summary.passed) {
            out.append(getString(R.string.server_tail_header)).append('\n').append(lines.takeLast(25).joinToString("\n")).append('\n')
        }
    }

    /** "unknown" stays "unknown"; a number becomes MiB with the KiB value beside it. */
    private fun kibText(value: String?): String {
        val kib = value?.toLongOrNull() ?: return getString(R.string.unknown)
        return getString(R.string.mib_value, ProbeFiles.mibFromKib(kib)) + " ($kib KiB)"
    }
}
