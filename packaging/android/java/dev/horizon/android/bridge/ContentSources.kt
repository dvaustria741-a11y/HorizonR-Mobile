package dev.horizon.android.bridge

import dev.horizon.android.BuildConfig
import dev.horizon.android.model.StoreCatalogItem
import dev.horizon.android.model.StoreCategory
import dev.horizon.android.model.StoreSearchPageResult
import dev.horizon.android.model.StoreSortOption
import dev.horizon.android.model.StoreSource
import dev.horizon.android.model.StoreVersionRelease
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ContentSourceException(message: String) : IOException(message)

/**
 * Resource pack and shader pack browsing for Modrinth and CurseForge, following the same API usage as ZalithLauncher 2:
 * Modrinth `/search` with `project_type:resourcepack` facets and `/project/{id}/version`, CurseForge
 * `/mods/search` with `classId=12` (texture packs) and `/mods/{id}/files`, picking the primary file per version,
 * and falling back to the forgecdn edge URL when CurseForge returns a null `downloadUrl`.
 * Shader packs use the same calls with `project_type:shader` (Modrinth) and `classId=6552` (CurseForge).
 * Mods and Native Mods use `project_type:mod` (Modrinth) and `classId=6` (CurseForge); they are listed here but only
 * installed after [ModConverter] has checked that the jar can be carried over to a Horizon `.hmod`.
 */
object ContentSources {
    const val MODRINTH_API = "https://api.modrinth.com/v2"
    const val CURSEFORGE_API = "https://api.curseforge.com/v1"

    private const val CF_GAME_ID = 432
    private const val CF_CLASS_RESOURCE_PACKS = 12
    private const val CF_CLASS_SHADERS = 6552
    private const val CF_CLASS_MODS = 6
    private const val PAGE_SIZE = 20
    private const val USER_AGENT = "dvaustria741-a11y/HorizonR-Mobile/1.8 (Android resource pack browser)"
    const val PREFERRED_GAME_VERSION = "26.3"

    private val releaseVersionRegex = Regex("^\\d+(\\.\\d+)+$")

    fun curseForgeKey(): String = BuildConfig.CURSEFORGE_API_KEY.trim()

    fun isCurseForgeConfigured(): Boolean = curseForgeKey().isNotEmpty()

    private fun isShader(category: StoreCategory): Boolean = category == StoreCategory.SHADER_PACK

    private fun isMod(category: StoreCategory): Boolean =
        category == StoreCategory.MOD || category == StoreCategory.NATIVE_MOD

    /** The category an external search result is stored under; anything that is not shader or mod is a resource pack. */
    private fun resultCategory(category: StoreCategory): StoreCategory = when (category) {
        StoreCategory.SHADER_PACK, StoreCategory.MOD, StoreCategory.NATIVE_MOD -> category
        else -> StoreCategory.RESOURCE_PACK
    }

    private fun kindLabel(category: StoreCategory): String = when (category) {
        StoreCategory.SHADER_PACK -> "SHADER PACK"
        StoreCategory.MOD -> "MOD"
        StoreCategory.NATIVE_MOD -> "NATIVE MOD"
        else -> "RESOURCE PACK"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun curseForgeHeaders(): Map<String, String> = mapOf("x-api-key" to curseForgeKey())

    fun httpGetText(url: String, headers: Map<String, String> = emptyMap()): String {
        var lastError: IOException? = null
        for (attempt in 0 until 3) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    return conn.inputStream.bufferedReader().use { it.readText() }
                }
                if (code == 429 || code >= 500) {
                    lastError = IOException("HTTP $code")
                    Thread.sleep(600L * (attempt + 1))
                    continue
                }
                throw ContentSourceException(
                    when (code) {
                        401, 403 -> "HTTP $code: access denied (check the API key)"
                        404 -> "HTTP 404: not found"
                        else -> "HTTP $code"
                    }
                )
            } catch (e: ContentSourceException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                Thread.sleep(500L * (attempt + 1))
            } finally {
                conn?.disconnect()
            }
        }
        throw lastError ?: IOException("Network error")
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val v = optString(i, "")
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }

    private fun compareMcVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun cleanMcVersions(raw: List<String>): List<String> {
        val releases = raw.filter { releaseVersionRegex.matches(it) }.distinct()
        val pool = if (releases.isNotEmpty()) releases else raw.distinct()
        return pool.sortedWith { x, y -> compareMcVersions(y, x) }
    }

    private fun pagesFor(total: Int): Int = ((total + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)

    // ------------------------------------------------------------------ Modrinth

    fun searchModrinth(
        query: String,
        gameVersion: String,
        sort: StoreSortOption,
        page: Int,
        category: StoreCategory = StoreCategory.RESOURCE_PACK
    ): StoreSearchPageResult {
        val projectType = when {
            isShader(category) -> "shader"
            isMod(category) -> "mod"
            else -> "resourcepack"
        }
        val facets = JSONArray().put(JSONArray().put("project_type:$projectType"))
        if (gameVersion.isNotBlank()) {
            facets.put(JSONArray().put("versions:${gameVersion.trim()}"))
        }
        val index = when (sort) {
            StoreSortOption.MOST_DOWNLOADED -> "downloads"
            StoreSortOption.BEST_RATED -> "follows"
            StoreSortOption.RECENTLY_UPDATED -> "updated"
            StoreSortOption.NAME_AZ -> "relevance"
        }
        val safePage = page.coerceAtLeast(1)
        val url = "$MODRINTH_API/search?query=${enc(query.trim())}&facets=${enc(facets.toString())}" +
            "&index=$index&limit=$PAGE_SIZE&offset=${(safePage - 1) * PAGE_SIZE}"
        val root = JSONObject(httpGetText(url))
        val hits = root.optJSONArray("hits") ?: JSONArray()
        val items = ArrayList<StoreCatalogItem>()
        for (i in 0 until hits.length()) {
            val h = hits.optJSONObject(i) ?: continue
            val projectId = h.optString("project_id", "")
            if (projectId.isEmpty()) continue
            val slug = h.optString("slug", projectId).ifBlank { projectId }
            val mcVersions = cleanMcVersions(h.optJSONArray("versions").toStringList())
            val tags = h.optJSONArray("display_categories").toStringList()
            val license = h.optString("license", "")
            items.add(
                StoreCatalogItem(
                    id = "mr-$projectId",
                    slug = slug,
                    title = h.optString("title", slug),
                    summary = h.optString("description", ""),
                    author = h.optString("author", "Unknown"),
                    version = "",
                    supportedMcVersions = mcVersions,
                    downloadUrl = "",
                    fileName = "",
                    sizeBytes = 0L,
                    category = resultCategory(category),
                    kindBadge = "MODRINTH • ${kindLabel(category)}",
                    pageUrl = "https://modrinth.com/$projectType/$slug",
                    iconUrl = h.optString("icon_url", ""),
                    downloadsCount = h.optLong("downloads", 0L),
                    ratingScore = 0f,
                    ratingCount = 0,
                    isSignedOrOfficial = false,
                    side = "client",
                    license = license.ifBlank { "See Modrinth" },
                    displayTags = (listOf("MODRINTH") + tags).take(5),
                    source = StoreSource.MODRINTH,
                    sourceProjectId = projectId
                )
            )
        }
        val ordered = if (sort == StoreSortOption.NAME_AZ) items.sortedBy { it.title.lowercase(Locale.US) } else items
        val total = root.optInt("total_hits", ordered.size)
        return StoreSearchPageResult(ordered, safePage, pagesFor(total), total)
    }

    fun modrinthVersions(
        projectId: String,
        gameVersion: String = "",
        category: StoreCategory = StoreCategory.RESOURCE_PACK
    ): List<StoreVersionRelease> {
        var url = "$MODRINTH_API/project/${enc(projectId)}/version?include_changelog=false"
        if (gameVersion.isNotBlank()) {
            url += "&game_versions=${enc(JSONArray().put(gameVersion.trim()).toString())}"
        }
        val arr = JSONArray(httpGetText(url))
        val out = ArrayList<StoreVersionRelease>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val files = v.optJSONArray("files") ?: continue
            if (files.length() == 0) continue
            var chosen: JSONObject? = null
            for (f in 0 until files.length()) {
                val fo = files.optJSONObject(f) ?: continue
                if (fo.optBoolean("primary", false)) {
                    chosen = fo
                    break
                }
            }
            val file = chosen ?: files.optJSONObject(0) ?: continue
            val fileUrl = file.optString("url", "")
            if (fileUrl.isEmpty()) continue
            val versionId = v.optString("id", "")
            val number = v.optString("version_number", versionId).ifBlank { versionId }
            val baseName = v.optString("name", number).ifBlank { number }
            val loaders = if (isMod(category)) v.optJSONArray("loaders").toStringList() else emptyList()
            out.add(
                StoreVersionRelease(
                    id = "mr-$projectId-$versionId",
                    name = if (loaders.isEmpty()) baseName else "$baseName • ${loaders.joinToString(", ")}",
                    versionNumber = number,
                    mcVersions = cleanMcVersions(v.optJSONArray("game_versions").toStringList()),
                    kindLabel = kindLabel(category),
                    releaseType = v.optString("version_type", "release"),
                    downloadsCount = v.optLong("downloads", 0L),
                    datePublished = v.optString("date_published", "").take(10).ifBlank { "Recently" },
                    fileName = file.optString("filename", ""),
                    downloadUrl = fileUrl,
                    sizeBytes = file.optLong("size", 0L),
                    sha256 = "",
                    signed = false,
                    sha1 = file.optJSONObject("hashes")?.optString("sha1", "").orEmpty()
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------- CurseForge

    fun searchCurseForge(
        query: String,
        gameVersion: String,
        sort: StoreSortOption,
        page: Int,
        category: StoreCategory = StoreCategory.RESOURCE_PACK
    ): StoreSearchPageResult {
        if (!isCurseForgeConfigured()) {
            return StoreSearchPageResult(
                emptyList(), 1, 1, 0,
                "CurseForge needs an API key. Add the CURSEFORGE_API_KEY secret and rebuild the app."
            )
        }
        val sortField = when (sort) {
            StoreSortOption.MOST_DOWNLOADED -> 6
            StoreSortOption.BEST_RATED -> 2
            StoreSortOption.RECENTLY_UPDATED -> 3
            StoreSortOption.NAME_AZ -> 4
        }
        val sortOrder = if (sort == StoreSortOption.NAME_AZ) "asc" else "desc"
        val safePage = page.coerceAtLeast(1)
        val sb = StringBuilder("$CURSEFORGE_API/mods/search?gameId=$CF_GAME_ID&classId=${classIdFor(category)}")
        if (query.isNotBlank()) sb.append("&searchFilter=").append(enc(query.trim()))
        if (gameVersion.isNotBlank()) sb.append("&gameVersion=").append(enc(gameVersion.trim()))
        sb.append("&sortField=$sortField&sortOrder=$sortOrder")
        sb.append("&index=${(safePage - 1) * PAGE_SIZE}&pageSize=$PAGE_SIZE")

        val root = JSONObject(httpGetText(sb.toString(), curseForgeHeaders()))
        val data = root.optJSONArray("data") ?: JSONArray()
        val items = ArrayList<StoreCatalogItem>()
        for (i in 0 until data.length()) {
            val d = data.optJSONObject(i) ?: continue
            if (d.optInt("status", 4) != 4) continue
            val id = d.optLong("id", 0L)
            if (id == 0L) continue
            val slug = d.optString("slug", id.toString())
            val authors = d.optJSONArray("authors")
            val author = authors?.optJSONObject(0)?.optString("name", "Unknown") ?: "Unknown"
            val logo = d.optJSONObject("logo")
            val icon = logo?.optString("thumbnailUrl", "")?.takeIf { it.isNotBlank() }
                ?: logo?.optString("url", "").orEmpty()
            val websiteUrl = d.optJSONObject("links")?.optString("websiteUrl", "")?.takeIf { it.isNotBlank() }
                ?: "https://www.curseforge.com/minecraft/${curseForgeSection(category)}/$slug"
            val indexes = d.optJSONArray("latestFilesIndexes")
            val rawVersions = ArrayList<String>()
            if (indexes != null) {
                for (k in 0 until indexes.length()) {
                    val gv = indexes.optJSONObject(k)?.optString("gameVersion", "").orEmpty()
                    if (gv.isNotEmpty()) rawVersions.add(gv)
                }
            }
            val distributionBlocked = d.has("allowModDistribution") && !d.isNull("allowModDistribution") &&
                !d.optBoolean("allowModDistribution", true)
            items.add(
                StoreCatalogItem(
                    id = "cf-$id",
                    slug = slug,
                    title = d.optString("name", slug),
                    summary = d.optString("summary", ""),
                    author = author,
                    version = "",
                    supportedMcVersions = cleanMcVersions(rawVersions),
                    downloadUrl = "",
                    fileName = "",
                    sizeBytes = 0L,
                    category = resultCategory(category),
                    kindBadge = "CURSEFORGE • ${kindLabel(category)}",
                    pageUrl = websiteUrl,
                    iconUrl = icon,
                    downloadsCount = d.optLong("downloadCount", 0L),
                    ratingScore = 0f,
                    ratingCount = 0,
                    isSignedOrOfficial = false,
                    side = "client",
                    license = "See CurseForge",
                    displayTags = listOfNotNull(
                        "CURSEFORGE",
                        if (distributionBlocked) "THIRD-PARTY DOWNLOADS OFF" else null
                    ),
                    source = StoreSource.CURSEFORGE,
                    sourceProjectId = id.toString()
                )
            )
        }
        val pagination = root.optJSONObject("pagination")
        val total = pagination?.optInt("totalCount", items.size) ?: items.size
        return StoreSearchPageResult(items, safePage, pagesFor(total), total)
    }

    private fun classIdFor(category: StoreCategory): Int = when {
        isShader(category) -> CF_CLASS_SHADERS
        isMod(category) -> CF_CLASS_MODS
        else -> CF_CLASS_RESOURCE_PACKS
    }

    private fun curseForgeSection(category: StoreCategory): String = when {
        isShader(category) -> "shaders"
        isMod(category) -> "mc-mods"
        else -> "texture-packs"
    }

    fun curseForgeFixedUrl(fileId: Long, fileName: String): String {
        val encodedName = enc(fileName).replace("+", "%20")
        return "https://edge.forgecdn.net/files/${fileId / 1000}/${fileId % 1000}/$encodedName"
    }

    fun curseForgeVersions(
        modId: String,
        gameVersion: String = "",
        category: StoreCategory = StoreCategory.RESOURCE_PACK
    ): List<StoreVersionRelease> {
        if (!isCurseForgeConfigured()) {
            throw ContentSourceException("CurseForge API key is missing in this build")
        }
        val sb = StringBuilder("$CURSEFORGE_API/mods/${enc(modId)}/files?index=0&pageSize=50")
        if (gameVersion.isNotBlank()) sb.append("&gameVersion=").append(enc(gameVersion.trim()))
        val root = JSONObject(httpGetText(sb.toString(), curseForgeHeaders()))
        val data = root.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<StoreVersionRelease>()
        for (i in 0 until data.length()) {
            val f = data.optJSONObject(i) ?: continue
            if (!f.optBoolean("isAvailable", true)) continue
            val fileId = f.optLong("id", 0L)
            val fileName = f.optString("fileName", "")
            if (fileId == 0L || fileName.isEmpty()) continue
            val directUrl = if (f.isNull("downloadUrl")) "" else f.optString("downloadUrl", "")
            val url = directUrl.ifBlank { curseForgeFixedUrl(fileId, fileName) }
            var sha1 = ""
            val hashes = f.optJSONArray("hashes")
            if (hashes != null) {
                for (h in 0 until hashes.length()) {
                    val ho = hashes.optJSONObject(h) ?: continue
                    if (ho.optInt("algo", -1) == 1) {
                        sha1 = ho.optString("value", "")
                        break
                    }
                }
            }
            val display = f.optString("displayName", fileName).ifBlank { fileName }
            out.add(
                StoreVersionRelease(
                    id = "cf-$modId-$fileId",
                    name = display,
                    versionNumber = display.trim(),
                    mcVersions = cleanMcVersions(f.optJSONArray("gameVersions").toStringList()),
                    kindLabel = kindLabel(category),
                    releaseType = when (f.optInt("releaseType", 1)) {
                        2 -> "beta"
                        3 -> "alpha"
                        else -> "release"
                    },
                    downloadsCount = f.optLong("downloadCount", 0L),
                    datePublished = f.optString("fileDate", "").take(10).ifBlank { "Recently" },
                    fileName = fileName,
                    downloadUrl = url,
                    sizeBytes = f.optLong("fileLength", 0L),
                    sha256 = "",
                    signed = false,
                    sha1 = sha1
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------------- Shared

    fun versionsFor(item: StoreCatalogItem, gameVersion: String = ""): List<StoreVersionRelease> {
        return when (item.source) {
            StoreSource.MODRINTH -> modrinthVersions(item.sourceProjectId, gameVersion, item.category)
            StoreSource.CURSEFORGE -> curseForgeVersions(item.sourceProjectId, gameVersion, item.category)
            StoreSource.HORIZON -> emptyList()
        }
    }

    /**
     * Fills in the concrete download target for a search-result item that was not opened through the
     * version list: picks the newest file for 26.3 when one exists, otherwise the newest file overall.
     */
    fun resolveInstallTarget(item: StoreCatalogItem): StoreCatalogItem {
        if (item.downloadUrl.isNotBlank() && item.fileName.isNotBlank()) return item
        val preferred = item.supportedMcVersions.firstOrNull { it.contains(PREFERRED_GAME_VERSION) }.orEmpty()
        var releases = if (preferred.isNotEmpty()) versionsFor(item, preferred) else emptyList()
        if (releases.isEmpty()) releases = versionsFor(item, "")
        val pick = releases.firstOrNull { it.releaseType == "release" } ?: releases.firstOrNull()
            ?: throw ContentSourceException(
                "No downloadable file was found for ${item.title}. Open ${item.pageUrl} to download it manually."
            )
        return item.copy(
            version = pick.versionNumber,
            downloadUrl = pick.downloadUrl,
            fileName = pick.fileName,
            sizeBytes = pick.sizeBytes,
            sha1 = pick.sha1,
            supportedMcVersions = pick.mcVersions.ifEmpty { item.supportedMcVersions }
        )
    }

    /**
     * Resource pack file names end up inside `resourcePacks:["file/<name>"]` in options.txt, which is split on
     * commas, so commas, quotes, brackets and path separators are replaced and the extension is forced to `.zip`.
     */
    fun sanitizePackFileName(raw: String, fallback: String = "resourcepack"): String {
        var n = raw.trim().substringAfterLast('/').substringAfterLast('\\')
        n = n.replace(Regex("[^A-Za-z0-9._()+ \\-]"), "_")
        n = n.replace(Regex("\\s+"), " ").trim().trim('.')
        if (n.endsWith(".zip", ignoreCase = true)) n = n.dropLast(4)
        n = n.trim().trim('.')
        if (n.length > 96) n = n.take(96).trim()
        if (n.isEmpty()) n = fallback
        return "$n.zip"
    }

    /**
     * Returns null when the archive is a loadable resource pack, otherwise a human readable reason.
     * Packs zipped with an extra top-level folder (`Pack/pack.mcmeta`) are rewritten in place so the
     * game finds `pack.mcmeta` at the archive root.
     */
    fun validateAndNormalizePackZip(zip: File): String? {
        val names: List<String> = try {
            ZipFile(zip).use { zf -> zf.entries().toList().map { it.name } }
        } catch (e: Exception) {
            return "The downloaded file is not a valid ZIP archive"
        }
        if (names.any { it.equals("pack.mcmeta", ignoreCase = true) }) return null

        val nested = names.filter { it.count { c -> c == '/' } == 1 && it.endsWith("/pack.mcmeta", ignoreCase = true) }
        if (nested.size != 1) {
            return "The archive has no pack.mcmeta, so it is not a Minecraft resource pack"
        }
        return repackWithoutPrefix(zip, nested[0].removeSuffix("pack.mcmeta"))
    }

    /**
     * Shader packs (Iris / OptiFine layout) keep their GLSL under `shaders/` at the archive root. Archives zipped
     * with one extra top-level folder (`Pack/shaders/...`) are rewritten so the loader finds `shaders/` at the root.
     * Returns null when the archive is loadable, otherwise a human readable reason.
     */
    fun validateAndNormalizeShaderZip(zip: File): String? {
        val names: List<String> = try {
            ZipFile(zip).use { zf -> zf.entries().toList().map { it.name } }
        } catch (e: Exception) {
            return "The downloaded file is not a valid ZIP archive"
        }
        if (names.any { it.startsWith("shaders/", ignoreCase = true) }) return null
        if (names.any { it.equals("horizon-shaders.toml", ignoreCase = true) }) return null

        val folders = names.filter { it.count { c -> c == '/' } >= 2 }
            .filter { it.substringAfter('/').startsWith("shaders/", ignoreCase = true) }
            .map { it.substringBefore('/') + "/" }
            .distinct()
        if (folders.size != 1) {
            return "The archive has no shaders folder, so it is not a shader pack"
        }
        return repackWithoutPrefix(zip, folders[0])
    }

    private fun repackWithoutPrefix(zip: File, prefix: String): String? {
        val tmp = File(zip.parentFile, zip.name + ".norm")
        try {
            ZipFile(zip).use { zf ->
                ZipOutputStream(FileOutputStream(tmp)).use { out ->
                    for (entry in zf.entries()) {
                        if (!entry.name.startsWith(prefix) || entry.name == prefix) continue
                        val newName = entry.name.removePrefix(prefix)
                        if (newName.contains("..")) continue
                        out.putNextEntry(ZipEntry(newName))
                        if (!entry.isDirectory) {
                            zf.getInputStream(entry).use { it.copyTo(out) }
                        }
                        out.closeEntry()
                    }
                }
            }
            if (!zip.delete() || !tmp.renameTo(zip)) {
                tmp.delete()
                return "Could not repack the archive"
            }
        } catch (e: Exception) {
            tmp.delete()
            return "Could not repack the archive: ${e.message}"
        }
        return null
    }
}
