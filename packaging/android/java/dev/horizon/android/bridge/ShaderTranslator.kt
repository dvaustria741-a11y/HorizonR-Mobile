package dev.horizon.android.bridge

import android.content.Context

/**
 * Which translator the Store runs over a shader pack when it is installed.
 *
 * - [V1]: the original behaviour. The pack is installed exactly as downloaded and Horizon's own loader handles it.
 * - [V2]: `horizon-shaderport` (adapted from Horizon-Translator). Rewrites the programs Horizon would skip, fixes
 *   how Horizon draws the pack, then writes it as a native Horizon `.hshader` (WGSL stages + horizon-shaders.toml).
 *   Falls back to the rewritten GLSL zip when no program can be expressed natively, and to the downloaded pack if it
 *   cannot convert it at all.
 *
 * The choice applies to packs installed from then on; installed packs are not changed.
 */
enum class ShaderTranslator(val label: String, val summary: String) {
    V1("Translator v1", "Original: pack installed as downloaded"),
    V2("Translator v2", "Converts the pack to a native Horizon .hshader (WGSL)");

    companion object {
        private const val PREFS = "horizonr_store"
        private const val KEY = "shader_translator"

        fun current(context: Context): ShaderTranslator {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == saved } ?: V2
        }

        fun save(context: Context, value: ShaderTranslator) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.name).apply()
        }
    }
}
