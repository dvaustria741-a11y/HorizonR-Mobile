package dev.horizon.android.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Inspects a Java mod jar (Fabric, Quilt, Forge, NeoForge or legacy Forge) downloaded from Modrinth or CurseForge and,
 * when possible, turns it into a Horizon `.hmod` package.
 *
 * Horizon is a Rust engine with sandboxed WebAssembly mods, so Java bytecode cannot run in it. What can be carried
 * over is content made only of data and assets, which means a jar with no `.class` files whose useful payload is
 * `assets/` (textures, models, lang) and `data/` (recipes, loot tables, tags, ...). Those are repackaged under a
 * `horizon-mod.toml` with the same shape as [HorizonNativeBridge.ensureValidHorizonModZip] writes for mods without a
 * `mod.wasm` (`api = "0.2"`, `capabilities = ["registries"]`).
 *
 * A jar that contains Java code is never installed: [convert] returns [Outcome.NeedsPort] with a readable reason,
 * because writing an empty shell would only show a mod that does nothing.
 */
object ModConverter {

    enum class Loader(val label: String) {
        FABRIC("Fabric"),
        QUILT("Quilt"),
        NEOFORGE("NeoForge"),
        FORGE("Forge"),
        LEGACY_FORGE("Forge (legacy)"),
        UNKNOWN("Unknown loader")
    }

    data class JarAnalysis(
        val loader: Loader,
        val modId: String,
        val name: String,
        val version: String,
        val description: String,
        val authors: List<String>,
        val classCount: Int,
        val nestedJarCount: Int,
        val mixinConfigCount: Int,
        val assetFileCount: Int,
        val dataFileCount: Int,
        val namespaces: List<String>
    ) {
        /** True when the jar ships Java bytecode (directly or as jar-in-jar), which Horizon cannot execute. */
        val hasCode: Boolean get() = classCount > 0 || nestedJarCount > 0

        /** True when the jar carries data or assets that can be repackaged. */
        val hasContent: Boolean get() = assetFileCount + dataFileCount > 0
    }

    sealed class Outcome {
        data class Converted(val analysis: JarAnalysis, val copiedEntries: Int) : Outcome()
        data class NeedsPort(val analysis: JarAnalysis, val reason: String) : Outcome()
        data class Invalid(val reason: String) : Outcome()
    }

    private const val MAX_ENTRIES = 60_000
    private const val MAX_TOTAL_BYTES = 384L * 1024L * 1024L
    private val hmodIdRegex = Regex("^[a-z0-9_]{1,64}$")

    // ------------------------------------------------------------------ Analysis

    /** Reads the jar and returns what it contains, or null when it is not a readable ZIP/JAR archive. */
    fun analyze(jar: File): JarAnalysis? {
        return try {
            ZipFile(jar).use { zf -> analyzeZip(zf) }
        } catch (e: Exception) {
            null
        }
    }

    private fun analyzeZip(zf: ZipFile): JarAnalysis {
        var classCount = 0
        var nestedJars = 0
        var mixinConfigs = 0
        var assets = 0
        var data = 0
        val namespaces = linkedSetOf<String>()

        val entries = zf.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (e.isDirectory) continue
            val n = e.name.replace('\\', '/')
            when {
                n.endsWith(".class", ignoreCase = true) -> classCount++
                (n.startsWith("META-INF/jars/") || n.startsWith("META-INF/jarjar/")) &&
                    n.endsWith(".jar", ignoreCase = true) -> nestedJars++
                !n.contains('/') && n.endsWith(".json", ignoreCase = true) &&
                    n.contains("mixin", ignoreCase = true) -> mixinConfigs++
                n.startsWith("assets/") -> {
                    assets++
                    n.split('/').getOrNull(1)?.takeIf { it.isNotBlank() }?.let { namespaces.add(it) }
                }
                n.startsWith("data/") -> {
                    data++
                    n.split('/').getOrNull(1)?.takeIf { it.isNotBlank() }?.let { namespaces.add(it) }
                }
            }
        }

        val meta = readMetadata(zf)
        return JarAnalysis(
            loader = meta.loader,
            modId = meta.modId,
            name = meta.name,
            version = meta.version,
            description = meta.description,
            authors = meta.authors,
            classCount = classCount,
            nestedJarCount = nestedJars,
            mixinConfigCount = mixinConfigs,
            assetFileCount = assets,
            dataFileCount = data,
            namespaces = namespaces.toList()
        )
    }

    private class Meta(
        val loader: Loader,
        val modId: String = "",
        val name: String = "",
        val version: String = "",
        val description: String = "",
        val authors: List<String> = emptyList()
    )

    private fun readText(zf: ZipFile, name: String): String? {
        val entry = zf.getEntry(name) ?: return null
        if (entry.size > 4L * 1024L * 1024L) return null
        return runCatching { zf.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    }

    private fun readMetadata(zf: ZipFile): Meta {
        readText(zf, "fabric.mod.json")?.let { text ->
            runCatching {
                val o = JSONObject(text)
                val authors = ArrayList<String>()
                val arr = o.optJSONArray("authors")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val a = arr.opt(i)
                        val s = if (a is JSONObject) a.optString("name", "") else a?.toString().orEmpty()
                        if (s.isNotBlank()) authors.add(s)
                    }
                }
                return Meta(
                    Loader.FABRIC, o.optString("id", ""), o.optString("name", ""),
                    cleanPlaceholder(o.optString("version", "")), o.optString("description", ""), authors
                )
            }
        }
        readText(zf, "quilt.mod.json")?.let { text ->
            runCatching {
                val o = JSONObject(text)
                val loader = o.optJSONObject("quilt_loader")
                val metadata = loader?.optJSONObject("metadata")
                val authors = ArrayList<String>()
                metadata?.optJSONObject("contributors")?.keys()?.forEach { authors.add(it) }
                return Meta(
                    Loader.QUILT, loader?.optString("id", "").orEmpty(), metadata?.optString("name", "").orEmpty(),
                    cleanPlaceholder(loader?.optString("version", "").orEmpty()),
                    metadata?.optString("description", "").orEmpty(), authors
                )
            }
        }
        readText(zf, "META-INF/neoforge.mods.toml")?.let { return parseModsToml(Loader.NEOFORGE, it) }
        readText(zf, "META-INF/mods.toml")?.let { return parseModsToml(Loader.FORGE, it) }
        readText(zf, "mcmod.info")?.let { text ->
            runCatching {
                val trimmed = text.trim()
                val first: JSONObject = if (trimmed.startsWith("[")) {
                    JSONArray(trimmed).optJSONObject(0) ?: JSONObject()
                } else {
                    JSONObject(trimmed)
                }
                val inner = first.optJSONArray("modList")?.optJSONObject(0) ?: first
                val authors = ArrayList<String>()
                val arr = inner.optJSONArray("authorList")
                if (arr != null) for (i in 0 until arr.length()) arr.optString(i, "").takeIf { it.isNotBlank() }?.let { authors.add(it) }
                return Meta(
                    Loader.LEGACY_FORGE, inner.optString("modid", ""), inner.optString("name", ""),
                    cleanPlaceholder(inner.optString("version", "")), inner.optString("description", ""), authors
                )
            }
        }
        return Meta(Loader.UNKNOWN)
    }

    private fun parseModsToml(loader: Loader, text: String): Meta {
        // Only the first `[[mods]]` block describes the primary mod; later blocks are extra mods or dependencies.
        val modsIdx = text.indexOf("[[mods]]")
        val block = if (modsIdx >= 0) {
            val rest = text.substring(modsIdx + "[[mods]]".length)
            val nextHeader = Regex("(?m)^\\s*\\[\\[").find(rest)?.range?.first ?: rest.length
            rest.substring(0, nextHeader)
        } else text
        val authors = tomlString(text, "authors").ifBlank { tomlString(block, "authors") }
        return Meta(
            loader,
            tomlString(block, "modId"),
            tomlString(block, "displayName"),
            cleanPlaceholder(tomlString(block, "version")),
            tomlString(block, "description").trim(),
            authors.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        )
    }

    private fun tomlString(text: String, key: String): String {
        val re = Regex(
            "(?m)^\\s*" + Regex.escape(key) + "\\s*=\\s*(?:'''(.*?)'''|\"\"\"(.*?)\"\"\"|\"([^\"\\n]*)\"|'([^'\\n]*)')",
            RegexOption.DOT_MATCHES_ALL
        )
        val m = re.find(text) ?: return ""
        return (1..4).map { m.groupValues[it] }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    /** Build-time placeholders such as `${file.jarVersion}` are not real values. */
    private fun cleanPlaceholder(value: String): String = if (value.contains("\${")) "" else value.trim()

    // ---------------------------------------------------------------- Conversion

    /** Human readable explanation of why [a] cannot be installed as is. */
    fun needsPortReason(a: JarAnalysis): String {
        val parts = ArrayList<String>()
        if (a.classCount > 0) parts.add("${a.classCount} Java ${if (a.classCount == 1) "class" else "classes"}")
        if (a.nestedJarCount > 0) parts.add("${a.nestedJarCount} bundled jar${if (a.nestedJarCount == 1) "" else "s"}")
        if (a.mixinConfigCount > 0) parts.add("${a.mixinConfigCount} mixin config${if (a.mixinConfigCount == 1) "" else "s"}")
        val what = parts.joinToString(", ").ifEmpty { "Java code" }
        return "${a.loader.label} mod with $what. Horizon runs Rust/WebAssembly mods, not Java, so this one has to be " +
            "ported by hand before it can run."
    }

    /**
     * Converts [jar] into a Horizon `.hmod` at [out] when the jar holds only data and assets.
     *
     * [hmodId] must already be a valid Horizon id (`[a-z0-9_]{1,64}`); the launcher passes the store item's sanitized
     * id so install, update and uninstall keep addressing the same file.
     */
    fun convert(
        jar: File,
        out: File,
        hmodId: String,
        title: String,
        version: String,
        summary: String,
        author: String
    ): Outcome {
        val analysis = analyze(jar) ?: return Outcome.Invalid("The downloaded file is not a valid JAR/ZIP archive")
        if (analysis.hasCode) return Outcome.NeedsPort(analysis, needsPortReason(analysis))
        if (!analysis.hasContent) {
            return Outcome.Invalid("The jar has no assets or data to convert, so there is nothing Horizon could load")
        }
        val id = hmodId.take(64)
        if (!hmodIdRegex.matches(id)) return Outcome.Invalid("Invalid Horizon mod id '$hmodId'")

        val finalTitle = title.ifBlank { analysis.name.ifBlank { id } }
        val finalVersion = normalizeVersion(analysis.version.ifBlank { version })
        val finalSummary = summary.ifBlank { analysis.description }.ifBlank { "Converted from a ${analysis.loader.label} jar" }
            .replace('\n', ' ').replace('\r', ' ').trim().take(240)
        val finalAuthor = author.ifBlank { analysis.authors.firstOrNull().orEmpty() }.ifBlank { "Unknown" }

        val manifest = listOf(
            "id = \"$id\"",
            "name = \"${tomlEscape(finalTitle)}\"",
            "version = \"$finalVersion\"",
            "description = \"${tomlEscape(finalSummary)}\"",
            "authors = [\"${tomlEscape(finalAuthor)}\"]",
            "api = \"0.2\"",
            "side = \"both\"",
            "capabilities = [\"registries\"]"
        ).joinToString("\n") + "\n"

        val tmp = File(out.parentFile, out.name + ".convert.tmp")
        var copied = 0
        try {
            out.parentFile?.mkdirs()
            val written = HashSet<String>()
            var totalBytes = 0L
            ZipFile(jar).use { zf ->
                ZipOutputStream(FileOutputStream(tmp)).use { zos ->
                    zos.putNextEntry(ZipEntry("horizon-mod.toml"))
                    zos.write(manifest.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                    written.add("horizon-mod.toml")

                    val entries = zf.entries()
                    var seen = 0
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (++seen > MAX_ENTRIES) return Outcome.Invalid("The jar has too many files to convert safely")
                        if (e.isDirectory) continue
                        val name = e.name.replace('\\', '/')
                        if (!isCarriedOver(name)) continue
                        if (name.startsWith("/") || name.split('/').any { it == ".." }) continue
                        if (!written.add(name)) continue
                        zos.putNextEntry(ZipEntry(name))
                        zf.getInputStream(e).use { input ->
                            val buf = ByteArray(16384)
                            while (true) {
                                val r = input.read(buf)
                                if (r <= 0) break
                                totalBytes += r
                                if (totalBytes > MAX_TOTAL_BYTES) {
                                    return Outcome.Invalid("The jar expands to more than ${MAX_TOTAL_BYTES / (1024 * 1024)} MB, refusing to convert it")
                                }
                                zos.write(buf, 0, r)
                            }
                        }
                        zos.closeEntry()
                        copied++
                    }

                    val langPath = "assets/$id/lang/en_us.json"
                    if (langPath !in written) {
                        zos.putNextEntry(ZipEntry(langPath))
                        zos.write("{\n  \"mod.$id.title\": \"${jsonEscape(finalTitle)}\"\n}\n".toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                }
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            return Outcome.Invalid("Could not convert the jar: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return Outcome.Converted(analysis, copied)
    }

    private fun isCarriedOver(name: String): Boolean =
        name.startsWith("assets/") || name.startsWith("data/") ||
            name.equals("pack.mcmeta", ignoreCase = true) || name.equals("pack.png", ignoreCase = true)

    /** Horizon manifests use plain dotted numbers; pull the first one out of strings like `v1.2.3-fabric`. */
    private fun normalizeVersion(raw: String): String {
        val m = Regex("\\d+(?:\\.\\d+){0,2}").find(raw) ?: return "1.0.0"
        val parts = m.value.split('.').toMutableList()
        while (parts.size < 3) parts.add("0")
        return parts.joinToString(".")
    }

    private fun tomlEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")

    /** One line for logs. */
    fun describe(a: JarAnalysis): String = String.format(
        Locale.US,
        "%s id=%s classes=%d nestedJars=%d mixins=%d assets=%d data=%d",
        a.loader.label, a.modId.ifBlank { "?" }, a.classCount, a.nestedJarCount, a.mixinConfigCount,
        a.assetFileCount, a.dataFileCount
    )
}
