package dev.horizon.android.ui

import android.content.Context
import java.io.File

/**
 * Total time spent in the game, kept in a plain file so the game process (HorizonActivity, `:game`)
 * can write it and the launcher process can read it. [begin] on resume, [end] on pause.
 */
object PlayTime {
    private fun file(context: Context) = File(context.filesDir, "playtime.txt")

    private fun read(context: Context): Pair<Long, Long> = runCatching {
        val parts = file(context).readText().trim().split(' ')
        (parts.getOrNull(0)?.toLongOrNull() ?: 0L) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }.getOrDefault(0L to 0L)

    private fun write(context: Context, total: Long, start: Long) {
        runCatching { file(context).writeText("$total $start") }
    }

    @JvmStatic
    fun begin(context: Context) {
        val (total, _) = read(context)
        write(context, total, System.currentTimeMillis())
    }

    @JvmStatic
    fun end(context: Context) {
        val (total, start) = read(context)
        if (start <= 0L) return
        write(context, total + (System.currentTimeMillis() - start).coerceAtLeast(0L), 0L)
    }

    fun totalMs(context: Context): Long = read(context).first

    /** "8 h", "35 min" or "" when nothing is recorded yet. */
    fun label(context: Context): String {
        val minutes = totalMs(context) / 60_000L
        return when {
            minutes <= 0L -> ""
            minutes < 60L -> "$minutes min"
            else -> "${minutes / 60L} h"
        }
    }
}
