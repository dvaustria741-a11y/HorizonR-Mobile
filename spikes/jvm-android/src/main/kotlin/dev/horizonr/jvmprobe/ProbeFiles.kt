package dev.horizonr.jvmprobe

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The two processes of this app talk through plain files in one directory, because the `:jvm` process can die
 * inside the JVM and a file still holds what it wrote before.
 */
object ProbeFiles {
    const val BEAT = "beat.txt"
    const val LAST_CRASH = "last_crash.txt"
    const val STAGE = "stage.txt"
    const val NATIVE = "native.txt"
    const val PROBE_OUT = "probe_out.txt"
    const val INSTALL = "install.txt"
    const val STDIO = "jvm_stdio.log"
    const val PROBE_JAR = "probe.jar"
    const val EXTRA_MODE = "mode"
    const val EXTRA_HEAP_MIB = "heap_mib"
    const val EXTRA_FOREGROUND = "foreground"
    const val EXTRA_EULA = "eula"
    /** Server run only: keep the server up after the measurements until a stop request arrives. */
    const val EXTRA_KEEP_RUNNING = "keep_running"
    /** An intent carrying only this extra asks the running `:jvm` process to stop its server. */
    const val EXTRA_STOP = "stop_request"

    /** Results the `:jvm` process writes while a server run is going (see JvmService). */
    const val SERVER = "server.txt"
    const val PREP = "server_prep.txt"
    const val GC_LOG = "gc.txt"

    /** The desktop spike's value, so the numbers compare. Not tuned for the phone. */
    const val DEFAULT_HEAP_MIB = 128

    fun dir(context: Context): File = File(context.filesDir, "jvmprobe").also { it.mkdirs() }

    fun runtimeHome(context: Context): File = File(context.filesDir, "jre25")

    /** `key=value` lines. A line without a key is ignored; a repeated key keeps the last value. */
    fun parseKeyValues(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq > 0) out[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        return out
    }

    fun readKeyValues(file: File): Map<String, String> =
        if (file.isFile) parseKeyValues(runCatching { file.readText() }.getOrDefault("")) else emptyMap()

    fun writeKeyValues(file: File, values: Map<String, String>) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(values.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
        if (!temp.renameTo(file)) file.writeText(temp.readText())
    }

    /** VmRSS in KiB from the text of /proc/<pid>/status; null when absent, never 0 for "unknown". */
    fun parseVmRssKib(status: String): Long? {
        val line = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return null
        val parts = line.removePrefix("VmRSS:").trim().split(Regex("\\s+"))
        if (parts.size != 2 || parts[1] != "kB") return null
        return parts[0].toLongOrNull()
    }

    /** First number after `key:` in the text of /proc/<pid>/status (`VmSize:`, `Threads:`); null when absent. */
    fun parseStatusNumber(status: String, key: String): Long? {
        val line = status.lineSequence().firstOrNull { it.startsWith("$key:") } ?: return null
        return line.removePrefix("$key:").trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
    }

    /**
     * Writes `beat.txt` every 50 ms from the `:jvm` process, atomically: the last file left after a kill says how long
     * the process lived and what it looked like. Runs on a daemon thread; the JVM in the same process does not stop it.
     */
    fun startHeartbeat(dir: File, foreground: Boolean) {
        val startMs = System.currentTimeMillis()
        Thread({
            var tick = 0L
            while (true) {
                val status = runCatching { File("/proc/self/status").readText() }.getOrDefault("")
                val values = LinkedHashMap<String, String>()
                values["start_ms"] = startMs.toString()
                values["wall_ms"] = System.currentTimeMillis().toString()
                values["tick"] = (tick++).toString()
                values["vmrss_kib"] = parseStatusNumber(status, "VmRSS")?.toString() ?: "unknown"
                values["vmhwm_kib"] = parseStatusNumber(status, "VmHWM")?.toString() ?: "unknown"
                values["vmsize_kib"] = parseStatusNumber(status, "VmSize")?.toString() ?: "unknown"
                values["threads"] = parseStatusNumber(status, "Threads")?.toString() ?: "unknown"
                values["oom_score_adj"] = runCatching { File("/proc/self/oom_score_adj").readText().trim() }.getOrDefault("unknown")
                values["fg"] = foreground.toString()
                runCatching { writeKeyValues(File(dir, BEAT), values) }
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }, "jvmprobe-beat").apply { isDaemon = true }.start()
    }

    fun ownRssKib(): Long? = runCatching { parseVmRssKib(File("/proc/self/status").readText()) }.getOrNull()

    fun mib(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0)

    fun mibFromKib(kib: Long): String = String.format(java.util.Locale.ROOT, "%.1f", kib / 1024.0)

    /** True when /proc/<pid>/cmdline names this app's `:jvm` process (guards against a reused pid). */
    fun isJvmProcess(pid: Int): Boolean {
        if (pid <= 0) return false
        val cmdline = runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull() ?: return false
        return cmdline.contains(":jvm")
    }

    /** One JVM per process: a previous run's `:jvm` process must be gone before the next boot. */
    fun killStaleJvm(dir: File) {
        val pid = readKeyValues(File(dir, STAGE))["pid"]?.toIntOrNull() ?: return
        if (isJvmProcess(pid)) Process.killProcess(pid)
    }

    /** First lines of the newest HotSpot crash report in `dir`, or empty when there is none. */
    fun hsErrHead(dir: File, lines: Int): String {
        val file = dir.listFiles { f -> f.name.startsWith("hs_err_") }?.maxByOrNull { it.lastModified() } ?: return ""
        return runCatching { file.name + "\n" + file.readLines().take(lines).joinToString("\n") }.getOrDefault("")
    }

    /** Writes the stack of an uncaught Java exception to `filesDir/last_crash.txt` (either process), then lets Android continue. */
    fun installCrashRecorder(context: Context) {
        val file = File(context.filesDir, LAST_CRASH)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText("process=${Application.getProcessName()} thread=${thread.name}\n" + error.stackTraceToString().take(4000))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        else -> "reason_$reason"
    }

    /** Readable runs of the native-crash trace (a tombstone); empty when the system kept none. */
    private fun nativeTraceStrings(info: ApplicationExitInfo): String = runCatching {
        val out = ByteArrayOutputStream()
        info.traceInputStream?.use { stream ->
            val buffer = ByteArray(8192)
            while (out.size() < 262144) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }
        val runs = ArrayList<String>()
        val current = StringBuilder()
        for (b in out.toByteArray()) {
            if (b in 0x20..0x7e) {
                current.append(b.toInt().toChar())
            } else {
                if (current.length >= 6) runs.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.length >= 6) runs.add(current.toString())
        val wanted = Regex("lib|SIG|sig|abort|Java|jvm|hs_err|\\.so")
        runs.filter { wanted.containsMatchIn(it) }.distinct().take(25).joinToString("\n") { "  " + it.take(120) }
    }.getOrDefault("")

    /** Why Android says this app's recent processes ended (API 30+); the `:jvm` process shows up here even when it left no file. */
    fun exitInfoTail(context: Context, count: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        return runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
            manager.getHistoricalProcessExitReasons(context.packageName, 0, count).joinToString("\n") { info ->
                val head = "${clock.format(Date(info.timestamp))} pid=${info.pid} ${info.processName} " +
                    "reason=${reasonName(info.reason)}(${info.reason}) status=${info.status} " +
                    "importance=${info.importance} rssKiB=${info.rss} desc=${info.description ?: "none"}"
                val trace = if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) nativeTraceStrings(info) else ""
                if (trace.isEmpty()) head else head + "\n" + trace
            }
        }.getOrDefault("")
    }

    fun reset(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun tail(file: File, lines: Int): String =
        if (!file.isFile) "" else runCatching { file.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")

    /** Own-app log lines about a crash. Whether a normal app may read them on this device is unverified. */
    fun logcatTail(lines: Int): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-t", "1500").redirectErrorStream(true).start()
        val keep = Regex("(?i)fatal|signal|abort|sigsegv|dlopen|linker|libjvm|libjli|jvmprobe|dev\\.horizonr|DEBUG|tombstone|backtrace|hs_err|\\*\\*\\*|libc ")
        val text = process.inputStream.bufferedReader().readLines().filter { keep.containsMatchIn(it) }
        process.destroy()
        text.takeLast(lines).joinToString("\n")
    }.getOrDefault("")
}
