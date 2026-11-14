package dev.horizonr.jvmprobe

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copies what the probe recorded (the report on screen, the probe's own files, the server's `logs/` and
 * `crash-reports/`) to a folder a file manager can open, because the app's private storage cannot be browsed on a
 * phone. With storage access it writes `<storage root>/HorizonR-JvmProbe/`; without it, on Android 10+, it falls back to
 * `Download/HorizonR-JvmProbe/` through MediaStore, which needs no permission for files the app creates.
 */
object LogExport {
    const val FOLDER = "HorizonR-JvmProbe"
    private const val MAX_FILE_BYTES = 16L * 1024 * 1024
    private const val MAX_CRASH_REPORTS = 5

    class Result(val location: String, val copied: Int, val failed: List<String>)

    private class Item(val name: String, val open: () -> InputStream)

    private fun items(context: Context, report: String): List<Item> {
        val out = ArrayList<Item>()
        out.add(Item("report.txt") { report.byteInputStream() })
        fun add(prefix: String, files: List<File>) {
            files.forEach { file -> out.add(Item(prefix + file.name) { file.inputStream() }) }
        }
        add("probe_", ProbeFiles.dir(context).listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }.sortedBy { it.name })
        val server = ServerSetup.serverDir(context)
        add("server_logs_", File(server, "logs").listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name })
        add("server_crash_", File(server, "crash-reports").listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }.take(MAX_CRASH_REPORTS))
        return out
    }

    fun export(context: Context, report: String, sharedStorageAllowed: Boolean): Result {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val direct = File(Environment.getExternalStorageDirectory(), FOLDER)
        val useDirect = sharedStorageAllowed && (direct.isDirectory || direct.mkdirs()) && direct.canWrite()
        val location: String
        val sink: (String) -> OutputStream
        if (useDirect) {
            location = direct.path
            sink = { name -> File(direct, name).outputStream() }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            location = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
            sink = { name -> openMediaStore(context, name) }
        } else {
            return Result("", 0, listOf("no storage access and no MediaStore on this Android version"))
        }
        var copied = 0
        val failed = ArrayList<String>()
        for (item in items(context, report)) {
            try {
                item.open().use { input -> sink(stamp + "_" + item.name).use { output -> copyBounded(input, output) } }
                copied++
            } catch (e: Exception) {
                failed.add(item.name + ": " + (e.message ?: e.javaClass.simpleName))
            }
        }
        return Result(location, copied, failed)
    }

    private fun openMediaStore(context: Context, name: String): OutputStream {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused $name")
        return resolver.openOutputStream(uri) ?: error("no output stream for $name")
    }

    private fun copyBounded(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (total < MAX_FILE_BYTES) {
            val want = minOf(buffer.size.toLong(), MAX_FILE_BYTES - total).toInt()
            val read = input.read(buffer, 0, want)
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
    }
}
