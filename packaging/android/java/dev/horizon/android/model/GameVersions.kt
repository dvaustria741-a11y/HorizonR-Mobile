package dev.horizon.android.model

import android.content.Context

/**
 * A Minecraft version the launcher offers.
 *
 * Horizon's native Rust engine and its assets are Minecraft 26.3's. [joinsOlderServers] marks a version the
 * engine reaches through its protocol adapter (`HORIZON_OLDER_SERVERS=1`, crates/horizon-protocol/src/adapter):
 * it runs on the same engine and the same asset install as 26.3, and translates a server's packets. It is not
 * a separate engine, so singleplayer is unavailable for it (the engine refuses, see
 * `horizon_client::net::singleplayer_unavailable`); the adapter is still in development.
 */
data class GameVersion(
    val id: String,
    val label: String,
    val joinsOlderServers: Boolean = false,
    val note: String = ""
)

object GameVersions {
    const val DEFAULT_ID = "26.3"
    private const val PREFS = "horizon_game_versions"
    private const val KEY_SELECTED = "selected_version"

    val all: List<GameVersion> = listOf(
        GameVersion(id = "26.3", label = "Minecraft 26.3"),
        GameVersion(
            id = "1.21.11",
            label = "Minecraft 1.21.11",
            joinsOlderServers = true,
            note = "Singleplayer is not available for 1.21.11 yet: the native engine port is in progress. " +
                "You can join 1.21.11 servers through Horizon's protocol adapter (in development), " +
                "which uses the shared 26.3 asset install."
        )
    )

    fun byId(id: String?): GameVersion = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }

    fun selected(context: Context): GameVersion =
        byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SELECTED, DEFAULT_ID))

    fun select(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SELECTED, byId(id).id).apply()
    }
}
