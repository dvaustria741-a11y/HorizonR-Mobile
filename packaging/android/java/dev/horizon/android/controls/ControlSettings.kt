package dev.horizon.android.controls

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import dev.horizon.android.R
import dev.horizon.android.bridge.HorizonNativeBridge
import java.io.File

class ControlSettings(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        ControlPaths.init(context)
        // Before the Zalith layout existed the active layout was saved as Horizon's own, and a saved
        // value beats the new default. Switch to Zalith (default) once per revision; saved layouts stay in Load.
        // Rev 2: Zalith (default) is now ZalithLauncher's own default.json, so installs still on Horizon's
        // joystick layout (or a copy saved from it) move to it too.
        if (prefs.getInt("layout_rev", 0) < 2) {
            prefs.edit().putString("layout", ControlLayouts.ZALITH_ID).putInt("layout_rev", 2).apply()
        }
    }

    /** Draw the touch layout over the game and feed it to the engine. Off keeps the engine's own touch controls. */
    var overlayEnabled: Boolean
        get() = prefs.getBoolean("overlay_enabled", false)
        set(v) = prefs.edit().putBoolean("overlay_enabled", v).apply()
    var layout: String
        get() = prefs.getString("layout", ControlLayouts.ZALITH_ID) ?: ControlLayouts.ZALITH_ID
        set(v) = prefs.edit().putString("layout", v).apply()
    /** Editor: snap dragged buttons to the others' edges. */
    var snapEnabled: Boolean
        get() = prefs.getBoolean("snap_enabled", true)
        set(v) = prefs.edit().putBoolean("snap_enabled", v).apply()
    var snapDp: Float
        get() = prefs.getFloat("snap_dp", 8f)
        set(v) = prefs.edit().putFloat("snap_dp", v).apply()
    var opacity: Float
        get() = prefs.getFloat("opacity", 0.70f)
        set(v) = prefs.edit().putFloat("opacity", v).apply()
    var buttonScale: Float
        get() = prefs.getFloat("button_scale", 1.00f)
        set(v) = prefs.edit().putFloat("button_scale", v).apply()
    var lookSensitivity: Float
        get() = prefs.getFloat("look_sensitivity", 1.00f)
        set(v) = prefs.edit().putFloat("look_sensitivity", v).apply()
    var longPressMs: Int
        get() = prefs.getInt("long_press_ms", 350)
        set(v) = prefs.edit().putInt("long_press_ms", v).apply()
    var tapToUse: Boolean
        get() = prefs.getBoolean("tap_to_use", true)
        set(v) = prefs.edit().putBoolean("tap_to_use", v).apply()
    var vibrate: Boolean
        get() = prefs.getBoolean("vibrate", true)
        set(v) = prefs.edit().putBoolean("vibrate", v).apply()
    var hotbarTouch: Boolean
        get() = prefs.getBoolean("hotbar_touch", true)
        set(v) = prefs.edit().putBoolean("hotbar_touch", v).apply()
    var virtualMouse: Boolean
        get() = prefs.getBoolean("virtual_mouse", false)
        set(v) = prefs.edit().putBoolean("virtual_mouse", v).apply()
    var mouseSpeed: Float
        get() = prefs.getFloat("mouse_speed", 1.00f)
        set(v) = prefs.edit().putFloat("mouse_speed", v).apply()
    var cursorSize: Float
        get() = prefs.getFloat("cursor_size", 1.00f)
        set(v) = prefs.edit().putFloat("cursor_size", v).apply()
    var tapToClick: Boolean
        get() = prefs.getBoolean("tap_to_click", true)
        set(v) = prefs.edit().putBoolean("tap_to_click", v).apply()
    var renderScale: Float
        get() = prefs.getFloat("render_scale", 1.00f)
        set(v) = prefs.edit().putFloat("render_scale", v).apply()
    var fixedResolution: Boolean
        get() = prefs.getBoolean("fixed_resolution", false)
        set(v) = prefs.edit().putBoolean("fixed_resolution", v).apply()
    var fixedWidth: Int
        get() = prefs.getInt("fixed_width", 1280)
        set(v) = prefs.edit().putInt("fixed_width", v).apply()
    var fixedHeight: Int
        get() = prefs.getInt("fixed_height", 720)
        set(v) = prefs.edit().putInt("fixed_height", v).apply()
    var menuX: Float
        get() = prefs.getFloat("menu_x", 0.50f)
        set(v) = prefs.edit().putFloat("menu_x", v).apply()
    var menuY: Float
        get() = prefs.getFloat("menu_y", 0.00f)
        set(v) = prefs.edit().putFloat("menu_y", v).apply()
    var editorGearX: Float
        get() = prefs.getFloat("editor_gear_x", 0.50f)
        set(v) = prefs.edit().putFloat("editor_gear_x", v).apply()
    var editorGearY: Float
        get() = prefs.getFloat("editor_gear_y", 0.06f)
        set(v) = prefs.edit().putFloat("editor_gear_y", v).apply()
    var cursorImage: String
        get() = prefs.getString("cursor_image", "").orEmpty()
        set(v) = prefs.edit().putString("cursor_image", v).apply()
    var javaHeapMb: Int
        get() = prefs.getInt("java_heap_mb", 0)
        set(v) = prefs.edit().putInt("java_heap_mb", v).apply()
    var cursorCenter: Boolean
        get() = prefs.getBoolean("cursor_center", false)
        set(v) = prefs.edit().putBoolean("cursor_center", v).apply()

    fun renderSize(viewWidth: Int, viewHeight: Int): Pair<Int, Int> =
        if (fixedResolution) fixedWidth.coerceIn(64, 7680) to fixedHeight.coerceIn(64, 4320)
        else {
            val s = renderScale.coerceIn(0.10f, 1.00f)
            (viewWidth * s).toInt().coerceAtLeast(1) to (viewHeight * s).toInt().coerceAtLeast(1)
        }

    companion object {
        private const val PREFS = "horizon_touch_controls"
    }
}

object ControlLayouts {
    data class Entry(val id: String, val name: String, val removable: Boolean)

    /** What the layout manager and the editor's info dialogs show about a layout. */
    data class Info(
        val id: String,
        val name: String,
        val fileName: String,
        val author: String,
        val version: String,
        val description: String,
        val removable: Boolean,
    )

    fun info(context: Context, entry: Entry, defaultName: String): Info {
        val layout = load(context, entry.id, defaultName)
        val fileName = when {
            entry.id.startsWith("file:") -> entry.id.removePrefix("file:")
            entry.id.startsWith("asset:") -> entry.id.removePrefix("asset:")
            else -> "default.json"
        }
        val shownName = if (entry.removable) layout.name.ifBlank { entry.name } else entry.name
        return Info(entry.id, shownName, fileName, layout.author, layout.version, layout.description, entry.removable)
    }

    private fun safeFileName(raw: String): String =
        raw.trim().removeSuffix(".json").replace(Regex("[^A-Za-z0-9._ ()-]"), "_").trim().take(48).ifBlank { "layout" }

    private fun uniqueFile(base: String): File {
        var f = File(dir, "$base.json")
        var n = 2
        while (f.exists()) f = File(dir, "$base ($n).json").also { n++ }
        return f
    }

    /** Writes [layout] to <fileName>.json. Overwrites only the file [currentId] already points at; otherwise picks a free name. */
    fun saveAs(layout: ControlLayout, fileName: String, currentId: String): String {
        dir.mkdirs()
        val base = safeFileName(fileName)
        val current = currentId.takeIf { it.startsWith("file:") }?.let { File(dir, it.removePrefix("file:")) }
        val wanted = File(dir, "$base.json")
        val target = if (current != null && current.isFile && current.name.equals(wanted.name, ignoreCase = true)) current else uniqueFile(base)
        // Saving under another name leaves the old file alone: Save and Exit is "save as" for a renamed layout.
        target.writeText(ControlLayout.write(layout))
        return "file:${target.name}"
    }

    fun copy(context: Context, id: String, defaultName: String, suffix: String): String {
        dir.mkdirs()
        val layout = load(context, id, defaultName)
        val base = safeFileName(layout.name + " " + suffix)
        val target = uniqueFile(base)
        target.writeText(ControlLayout.write(layout.copy(name = layout.name + " " + suffix)))
        return "file:${target.name}"
    }

    fun create(defaultName: String, name: String): String {
        dir.mkdirs()
        val target = uniqueFile(safeFileName(name))
        target.writeText(ControlLayout.write(DefaultLayout.create(name)))
        return "file:${target.name}"
    }

    /** Renames the file of a saved layout; returns the new id, or null for a built-in or a name that is taken. */
    fun renameFile(id: String, newFileName: String): String? {
        if (!id.startsWith("file:")) return null
        val from = File(dir, id.removePrefix("file:"))
        val to = File(dir, safeFileName(newFileName) + ".json")
        if (!from.isFile || (to.exists() && !to.name.equals(from.name, ignoreCase = true))) return null
        return if (from.renameTo(to)) "file:${to.name}" else null
    }

    fun updateInfo(context: Context, id: String, defaultName: String, name: String, version: String, author: String, description: String): Boolean {
        if (!id.startsWith("file:")) return false
        val file = File(dir, id.removePrefix("file:"))
        val layout = load(context, id, defaultName)
        file.writeText(ControlLayout.write(layout.copy(name = name.ifBlank { layout.name }, version = version, author = author, description = description)))
        return true
    }

    fun exportText(context: Context, id: String, defaultName: String): String =
        ControlLayout.write(load(context, id, defaultName))

    /** ZalithLauncher's own default control map (assets/controls/zalith_default.json). */
    const val ZALITH_ID = "asset:zalith_default.json"

    val dir: File get() = File(ControlPaths.root, "controls")

    fun list(context: Context, defaultName: String): List<Entry> {
        val builtIn = listOf(
            Entry(ZALITH_ID, context.getString(R.string.controls_zalith_name), false),
            Entry(DefaultLayout.ID, defaultName, false),
        )
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json", true) }.orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { Entry("file:${it.name}", nameOf(it), true) }
        return builtIn + files
    }

    private fun nameOf(file: File): String =
        runCatching { ControlLayout.parse(file.readText(), file.nameWithoutExtension).name }.getOrDefault(file.nameWithoutExtension)

    fun load(context: Context, id: String, defaultName: String): ControlLayout = runCatching {
        when {
            id.startsWith("asset:") -> context.assets.open("controls/${id.removePrefix("asset:")}").bufferedReader().use {
                ControlLayout.parse(it.readText(), if (id == ZALITH_ID) context.getString(R.string.controls_zalith_name) else id.removePrefix("asset:"))
            }
            id.startsWith("file:") -> File(dir, id.removePrefix("file:")).let { ControlLayout.parse(it.readText(), it.nameWithoutExtension) }
            else -> null
        }
    }.onFailure {
        android.util.Log.e("ControlLayouts", "Could not load layout $id: ${it.message}", it)
        // Never swap in Horizon's joystick layout silently: say which layout failed and why.
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context.applicationContext, "Could not load layout $id: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
        .getOrNull() ?: DefaultLayout.create(defaultName)

    fun import(context: Context, uri: Uri): Entry {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalArgumentException("Empty file")
        val base = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.ifBlank { null } ?: "layout"
        val layout = ControlLayout.parse(text, base)
        dir.mkdirs()
        val safe = base.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(48)
        var file = File(dir, "$safe.json")
        var n = 2
        while (file.exists()) file = File(dir, "$safe ($n).json").also { n++ }
        file.writeText(text)
        return Entry("file:${file.name}", layout.name, true)
    }

    fun save(layout: ControlLayout, currentId: String, suffix: String): String {
        dir.mkdirs()
        val existing = currentId.takeIf { it.startsWith("file:") }?.let { File(dir, it.removePrefix("file:")) }
        val target = if (existing != null && existing.isFile && runCatching { org.json.JSONObject(existing.readText()).has("pojarush") }.getOrDefault(false)) {
            existing
        } else {
            val base = "${layout.name} $suffix".replace(Regex("[^A-Za-z0-9._ ()-]"), "_").trim().take(48)
            var f = File(dir, "$base.json")
            var n = 2
            while (f.exists()) f = File(dir, "$base $n.json").also { n++ }
            f
        }
        val named = if (target == existing) layout else layout.copy(name = target.nameWithoutExtension)
        target.writeText(ControlLayout.write(named))
        return "file:${target.name}"
    }

    fun cursorDir(): File = File(ControlPaths.root, "cursor")

    fun importCursor(context: Context, uri: Uri): File {
        val type = context.contentResolver.getType(uri).orEmpty()
        val ext = when {
            type.contains("gif") -> "gif"
            type.contains("png") -> "png"
            type.contains("webp") -> "webp"
            type.contains("jpeg") || type.contains("jpg") -> "jpg"
            else -> uri.lastPathSegment?.substringAfterLast('.', "png")?.lowercase()?.take(4) ?: "png"
        }
        val dir = cursorDir().apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "cursor.$ext")
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalArgumentException("Empty file")
        return file
    }

    fun delete(id: String) {
        if (id.startsWith("file:")) File(dir, id.removePrefix("file:")).delete()
    }
}

/** Where layouts and cursor images live: inside Horizon's home directory. */
object ControlPaths {
    @Volatile private var base: File? = null

    val root: File get() = base ?: error("ControlPaths.init(context) has not run")

    fun init(context: Context) {
        if (base != null) return
        val home = HorizonNativeBridge.ensureHomeDirectories(context.applicationContext)
        base = File(home, "touch-controls").also { it.mkdirs() }
    }
}
