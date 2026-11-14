package dev.horizonr.jvmprobe

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Copies the Java runtime out of the APK's assets into `filesDir/jre25`. CI wrote `assets/jre25/manifest.txt`
 * (`<size><TAB><relative path>` per file) next to the files, so a short or missing file is an error here.
 */
object RuntimeInstaller {
    const val ASSET_ROOT = "jre25"
    private const val MARKER = ".installed"

    data class Entry(val size: Long, val path: String)
    data class Result(val cached: Boolean, val files: Int, val bytes: Long, val ms: Long)

    /** Pure. Rejects empty, absolute and `..` paths, bad sizes and duplicates. */
    fun parseManifest(lines: List<String>): List<Entry> {
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        for ((index, raw) in lines.withIndex()) {
            if (raw.isBlank()) continue
            val tab = raw.indexOf('\t')
            val size = if (tab > 0) raw.substring(0, tab).toLongOrNull() else null
            val path = if (tab > 0) raw.substring(tab + 1) else ""
            val unsafe = path.isEmpty() || path.startsWith("/") || path.split('/').any { it == ".." || it.isEmpty() }
            if (size == null || size < 0 || unsafe) throw IOException("manifest line ${index + 1} is malformed: $raw")
            if (!seen.add(path)) throw IOException("manifest lists $path twice")
            out.add(Entry(size, path))
        }
        return out
    }

    fun ensure(context: Context, home: File, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result {
        val started = System.nanoTime()
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        val entries = context.assets.open("$ASSET_ROOT/manifest.txt").bufferedReader().use { parseManifest(it.readLines()) }
        if (entries.isEmpty()) throw IOException("manifest.txt lists no files")
        val marker = File(home, MARKER)
        val total = entries.sumOf { it.size }
        if (marker.isFile && marker.readText() == stamp && entries.all { File(home, it.path).length() == it.size }) {
            return Result(true, entries.size, total, 0)
        }
        home.deleteRecursively()
        for ((index, entry) in entries.withIndex()) {
            if (index % 10 == 0) progress(index, entries.size)
            val target = File(home, entry.path)
            target.parentFile?.mkdirs()
            val copied = context.assets.open("$ASSET_ROOT/${entry.path}").use { input ->
                target.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            }
            if (copied != entry.size) throw IOException("${entry.path}: copied $copied bytes, manifest says ${entry.size}")
        }
        marker.writeText(stamp) // last, so an interrupted copy is never taken for a finished one
        return Result(false, entries.size, total, (System.nanoTime() - started) / 1_000_000)
    }

    /** `probe.jar` is small and always refreshed. */
    fun copyProbeJar(context: Context, target: File) {
        context.assets.open(ProbeFiles.PROBE_JAR).use { input -> target.outputStream().use { input.copyTo(it) } }
    }
}
