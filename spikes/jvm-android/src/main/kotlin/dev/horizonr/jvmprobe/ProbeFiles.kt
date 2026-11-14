package dev.horizonr.jvmprobe

import android.content.Context
import android.os.Process
import java.io.File

/**
 * The two processes of this app talk through plain files in one directory, because the `:jvm` process can die
 * inside the JVM and a file still holds what it wrote before.
 */
object ProbeFiles {
    const val STAGE = "stage.txt"
    const val NATIVE = "native.txt"
    const val PROBE_OUT = "probe_out.txt"
    const val INSTALL = "install.txt"
    const val STDIO = "jvm_stdio.log"
    const val PROBE_JAR = "probe.jar"
    const val EXTRA_MODE = "mode"
    const val EXTRA_HEAP_MIB = "heap_mib"

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

    fun reset(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun tail(file: File, lines: Int): String =
        if (!file.isFile) "" else runCatching { file.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")

    /** Own-app log lines about a crash. Whether a normal app may read them on this device is unverified. */
    fun logcatTail(lines: Int): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-t", "400").redirectErrorStream(true).start()
        val keep = Regex("(?i)fatal|signal|abort|sigsegv|dlopen|linker|libjvm|libjli|jvmprobe|dev\\.horizonr")
        val text = process.inputStream.bufferedReader().readLines().filter { keep.containsMatchIn(it) }
        process.destroy()
        text.takeLast(lines).joinToString("\n")
    }.getOrDefault("")
}
