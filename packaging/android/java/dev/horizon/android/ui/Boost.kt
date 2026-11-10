package dev.horizon.android.ui

import android.content.Context
import java.io.File

/**
 * Home "Boost": frees the launcher's memory when it is switched on and, while it stays on, the game process
 * starts its main thread (and every thread the engine spawns from it) at display priority. It never lowers
 * resolution, clocks or effects, so it cannot cost frames.
 */
object Boost {
    private fun flag(context: Context) = File(context.filesDir, "boost.flag")

    fun isOn(context: Context): Boolean = flag(context).exists()

    fun set(context: Context, on: Boolean) {
        runCatching { if (on) flag(context).writeText("1") else flag(context).delete() }
        if (on) freeMemory()
    }

    private fun freeMemory() {
        System.runFinalization()
        Runtime.getRuntime().gc()
    }
}
