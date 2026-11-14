package dev.horizonr.jvmprobe

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
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

    private enum class State { IDLE, RUNNING, DONE, ERROR, DIED }

    private lateinit var output: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var startedAtMs = 0L

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

    private fun start(mode: Int, foreground: Boolean) {
        val dir = ProbeFiles.dir(this)
        File(filesDir, ProbeFiles.LAST_CRASH).delete()
        ProbeFiles.killStaleJvm(dir)
        ProbeFiles.reset(dir)
        ProbeFiles.writeKeyValues(File(dir, ProbeFiles.STAGE), mapOf("pid" to "0", "stage" to "starting", "mode" to mode.toString()))
        startedAtMs = System.currentTimeMillis()
        val intent = Intent(this, JvmService::class.java)
            .putExtra(ProbeFiles.EXTRA_MODE, mode)
            .putExtra(ProbeFiles.EXTRA_HEAP_MIB, ProbeFiles.DEFAULT_HEAP_MIB)
            .putExtra(ProbeFiles.EXTRA_FOREGROUND, foreground)
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
                if (state == State.RUNNING) handler.postDelayed(this, 500) else polling = false
            }
        })
    }

    private fun state(stage: Map<String, String>): State {
        val name = stage["stage"] ?: return State.IDLE
        val pid = stage["pid"]?.toIntOrNull() ?: 0
        return when {
            name == "done" -> State.DONE
            name == "error" -> State.ERROR
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

        out.append('\n')
        val status = when (state) {
            State.RUNNING -> getString(R.string.status_running, stage["stage"] ?: unknown)
            State.DONE -> getString(R.string.status_done, stage["detail"] ?: unknown)
            State.ERROR -> getString(R.string.status_error, stage["detail"] ?: unknown)
            else -> getString(R.string.status_died)
        }
        out.append(line(R.string.status_label, status))
        out.append(line(R.string.mode_label, getString(if (stage["mode"] == NativeBoot.MODE_JLI.toString() || merged["mode"] == NativeBoot.MODE_JLI.toString()) R.string.mode_jli else R.string.mode_jni)))
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
        out.append(line(R.string.boot_label, merged["boot_ms"]?.let { getString(R.string.boot_value, it) } ?: unknown))
        out.append(line(R.string.boot_def_label, merged["boot_ms_def"] ?: unknown))
        out.append(line(R.string.rss_before_label, kibText(merged["rss_kib_before"])))
        out.append(line(R.string.rss_after_label, kibText(merged["rss_kib_after"])))
        out.append(line(R.string.java_version_label, merged["java.version"] ?: unknown))
        out.append(line(R.string.vm_label, merged["java.vm.name"] ?: unknown))
        out.append(line(R.string.home_label, merged["java.home"] ?: unknown))

        if (merged.isNotEmpty()) {
            out.append('\n').append(getString(R.string.raw_header)).append('\n')
            merged.forEach { (key, value) -> out.append(key).append('=').append(value).append('\n') }
        }
        if (state == State.DIED || state == State.ERROR || merged["error"] != null) {
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

    /** "unknown" stays "unknown"; a number becomes MiB with the KiB value beside it. */
    private fun kibText(value: String?): String {
        val kib = value?.toLongOrNull() ?: return getString(R.string.unknown)
        return getString(R.string.mib_value, ProbeFiles.mibFromKib(kib)) + " ($kib KiB)"
    }
}
