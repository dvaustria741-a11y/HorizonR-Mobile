package dev.horizon.android.model

import android.content.Context

/**
 * A Minecraft version the launcher knows about.
 *
 * [engineSupported] is true only when the native Rust engine in this build can really run the version.
 * Versions without engine support are listed and selectable, but never installed or launched.
 */
data class GameVersion(
    val id: String,
    val label: String,
    val engineSupported: Boolean,
    val unsupportedReason: String = ""
)

object GameVersions {
    const val DEFAULT_ID = "26.3"
    private const val PREFS = "horizon_game_versions"
    private const val KEY_SELECTED = "selected_version"

    val all: List<GameVersion> = listOf(
        GameVersion(id = "26.3", label = "Minecraft 26.3", engineSupported = true),
        GameVersion(
            id = "1.21.11",
            label = "Minecraft 1.21.11",
            engineSupported = false,
            unsupportedReason = "The Rust engine in this build targets Minecraft 26.3 (protocol 777). " +
                "Support for 1.21.11 (protocol 774) needs an engine port that is not done yet, " +
                "so it cannot be installed or launched."
        )
    )

    fun byId(id: String?): GameVersion = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }

    fun selected(context: Context): GameVersion =
        byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SELECTED, DEFAULT_ID))

    fun select(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SELECTED, byId(id).id).apply()
    }
}
