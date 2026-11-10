package dev.horizon.android.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.horizon.android.model.HorizonConfigState
import dev.horizon.android.model.LauncherAccount
import dev.horizon.android.model.SkinDetectionResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Skin / cape helpers behind the Account & Custom Skin Wardrobe.
 *
 * Everything here is blocking (network / disk): call from Dispatchers.IO.
 * Ely.by and Mojang are used as public texture *lookups* by player name – no password or token is ever
 * handled, so an "Ely.by account" here is a name whose skin and cape are fetched from skinsystem.ely.by.
 */
object SkinTools {
    enum class Source(val label: String) { ELYBY("Ely.by"), MOJANG("Mojang"), URL("Link") }

    class Textures(
        val skin: ByteArray?,
        val slim: Boolean,
        val cape: ByteArray?,
        val source: String,
        val name: String
    )

    class CapePreset(val id: String, val title: String)

    val CAPE_PRESETS = listOf(
        CapePreset("volt", "Volt"),
        CapePreset("crown", "Crown"),
        CapePreset("stealth", "Stealth")
    )

    private const val MAX_BYTES = 2_000_000
    private const val UA = "HorizonLauncher/1.0 (Android)"

    // ------------------------------------------------------------------ network

    private fun get(url: String, maxBytes: Int = MAX_BYTES): ByteArray? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) return null
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    private fun isPng(b: ByteArray?): Boolean =
        b != null && b.size > 64 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    /** Parses a Mojang/Ely style `{"SKIN":{"url":..,"metadata":{"model":"slim"}},"CAPE":{"url":..}}` object. */
    private fun fromTexturesJson(obj: JSONObject, name: String, source: String): Textures? {
        val skinObj = obj.optJSONObject("SKIN")
        val capeObj = obj.optJSONObject("CAPE")
        val skin = skinObj?.optString("url")?.takeIf { it.isNotBlank() }?.let { get(it) }?.takeIf { isPng(it) }
        val cape = capeObj?.optString("url")?.takeIf { it.isNotBlank() }?.let { get(it) }?.takeIf { isPng(it) }
        if (skin == null && cape == null) return null
        val slim = skinObj?.optJSONObject("metadata")?.optString("model") == "slim"
        return Textures(skin, slim, cape, source, name)
    }

    fun fetchElyBy(name: String): Textures? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val json = get("https://skinsystem.ely.by/textures/${enc(n)}", 64_000) ?: return null
        return runCatching { fromTexturesJson(JSONObject(String(json)), n, "Ely.by") }.getOrNull()
    }

    fun fetchMojang(name: String): Textures? {
        val n = name.trim()
        if (n.isEmpty()) return null
        return runCatching {
            val prof = get("https://api.minecraftservices.com/minecraft/profile/lookup/name/${enc(n)}", 64_000)
                ?: get("https://api.mojang.com/users/profiles/minecraft/${enc(n)}", 64_000)
                ?: return null
            val id = JSONObject(String(prof)).optString("id")
            if (id.isBlank()) return null
            val session = get("https://sessionserver.mojang.com/session/minecraft/profile/$id", 128_000) ?: return null
            val props = JSONObject(String(session)).optJSONArray("properties") ?: return null
            var value = ""
            for (i in 0 until props.length()) {
                val p = props.getJSONObject(i)
                if (p.optString("name") == "textures") value = p.optString("value")
            }
            if (value.isBlank()) return null
            val decoded = String(android.util.Base64.decode(value, android.util.Base64.DEFAULT))
            val tex = JSONObject(decoded).optJSONObject("textures") ?: return null
            fromTexturesJson(tex, n, "Mojang")
        }.getOrNull()
    }

    /** Direct link to a PNG (skin) – used by "Import from URL". */
    fun fetchSkinUrl(url: String): ByteArray? = get(url.trim())?.takeIf { isPng(it) }

    /** Looks a name up on the chosen service, falling back to the other one. */
    fun lookup(name: String, preferred: Source): Textures? = when (preferred) {
        Source.ELYBY -> fetchElyBy(name) ?: fetchMojang(name)
        else -> fetchMojang(name) ?: fetchElyBy(name)
    }

    // ------------------------------------------------------------------ skin import

    /** Runs fetched skin bytes through the normal importer so it is normalised and added to the Skin Library. */
    fun importSkinBytes(
        context: Context,
        bytes: ByteArray,
        account: LauncherAccount,
        config: HorizonConfigState,
        label: String
    ): SkinDetectionResult {
        val tmp = File(context.cacheDir, "fetched_skin_${System.nanoTime()}.png")
        return try {
            tmp.writeBytes(bytes)
            HorizonNativeBridge.detectAndImportSkinFromUri(context, Uri.fromFile(tmp), account, config, label)
        } finally {
            tmp.delete()
        }
    }

    // ------------------------------------------------------------------ capes

    private fun capesDir(home: File) = File(home, "skins/capes").apply { mkdirs() }

    /** Validates (decodes) and stores a cape PNG for an account; returns the file or null if it is not an image. */
    fun saveCape(context: Context, accountId: String, bytes: ByteArray): File? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (bmp.width < 22 || bmp.height < 17) return null
        val home = HorizonNativeBridge.ensureHomeDirectories(context)
        val out = File(capesDir(home), "cape_${accountId}.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out
    }

    fun saveCapeFromUri(context: Context, accountId: String, uri: Uri): File? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.size > MAX_BYTES) return null
        return saveCape(context, accountId, bytes)
    }

    fun savePresetCape(context: Context, accountId: String, presetId: String): File? {
        val bmp = generateCapePreset(presetId)
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return saveCape(context, accountId, bytes)
    }

    fun deleteCape(account: LauncherAccount) {
        if (account.capePath.isNotBlank()) runCatching { File(account.capePath).delete() }
    }

    /** Makes the active account's cape the one the game engine loads on next launch (or removes it). */
    fun syncCape(home: File, active: LauncherAccount) {
        val dest = File(home, "custom_cape_active.png")
        val src = if (active.capePath.isNotBlank()) File(active.capePath) else null
        if (src != null && src.isFile && src.length() > 64L) {
            runCatching { src.copyTo(dest, overwrite = true) }
        } else {
            runCatching { dest.delete() }
        }
    }

    fun loadCapeBitmap(path: String): Bitmap? =
        if (path.isBlank()) null else runCatching { BitmapFactory.decodeFile(path) }.getOrNull()

    /** Cropped outer face (10x16 at 1,1 of a 64x32 cape) scaled up, for previews. */
    fun capeFace(cape: Bitmap): Bitmap {
        val sx = cape.width / 64f
        val x = (1 * sx).toInt().coerceAtLeast(0)
        val w = (10 * sx).toInt().coerceAtLeast(1).coerceAtMost(cape.width - x)
        val h = (16 * sx).toInt().coerceAtLeast(1).coerceAtMost(cape.height - x)
        return Bitmap.createBitmap(cape, x, x, w, h)
    }

    // ------------------------------------------------------------------ cape presets (64x32 vanilla layout)

    fun generateCapePreset(id: String): Bitmap {
        val bmp = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        val (base, edge, mark) = when (id) {
            "crown" -> Triple(0xFF3B2A6B.toInt(), 0xFF2A1D4D.toInt(), 0xFFFFC94D.toInt())
            "stealth" -> Triple(0xFF1B1D24.toInt(), 0xFF12141A.toInt(), 0xFF3A3F4B.toInt())
            else -> Triple(0xFF173A26.toInt(), 0xFF0E2417.toInt(), 0xFF79E59C.toInt())
        }
        // Whole cape area (back + front + edges) in the base colour, edges slightly darker.
        for (y in 0 until 17) for (x in 0 until 22) bmp.setPixel(x, y, base)
        for (x in 0 until 22) { bmp.setPixel(x, 0, edge); }
        for (y in 0 until 17) { bmp.setPixel(0, y, edge); bmp.setPixel(11, y, edge); bmp.setPixel(21, y, edge) }
        // Inner face (x 12..21) stays plain; draw the emblem on the outer face (x 1..10, y 1..16).
        fun dot(x: Int, y: Int, c: Int = mark) { if (x in 1..10 && y in 1..16) bmp.setPixel(x, y, c) }
        when (id) {
            "crown" -> {
                for (x in 2..9) dot(x, 11)
                for (x in 2..9) dot(x, 10)
                for (x in listOf(2, 5, 6, 9)) { dot(x, 9); dot(x, 8) }
                dot(2, 7); dot(5, 7); dot(6, 7); dot(9, 7)
                dot(3, 9); dot(4, 8); dot(7, 8); dot(8, 9)
            }
            "stealth" -> {
                for (y in 2..15) { dot(3, y); dot(8, y) }
                for (x in 1..10) dot(x, 8, 0xFF2A2E38.toInt())
            }
            else -> {
                // Chunky "V"
                for (i in 0..6) {
                    val y = 4 + 2 * i
                    dot(2 + i / 2, y); dot(3 + i / 2, y)
                    dot(9 - i / 2, y); dot(8 - i / 2, y)
                }
            }
        }
        return bmp
    }
}
