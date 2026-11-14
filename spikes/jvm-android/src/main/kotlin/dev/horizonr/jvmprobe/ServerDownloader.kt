package dev.horizonr.jvmprobe

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/** HTTPS downloads for the server run, run in the `:jvm` process. Nothing downloaded is ever committed or bundled. */
object ServerDownloader {
    private const val USER_AGENT = "HorizonR-Mobile jvm-android-probe (github.com/dvaustria741-a11y/HorizonR-Mobile)"
    private const val CDN_HOST = "cdn.modrinth.com"
    private const val VERSION_FILE = "https://api.modrinth.com/v2/version_file/%s?algorithm=sha1"
    private const val MAX_BYTES = 256L * 1024 * 1024

    /** Pure. Only an https URL on Modrinth's CDN is accepted for a mod download. */
    fun cdnUrlOk(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == CDN_HOST
    }.getOrDefault(false)

    /** Pure. The `url` of the file in a Modrinth version JSON whose SHA-512 equals `sha512`; null when there is none. */
    fun pickModUrl(json: String, sha512: String): String? {
        val files = runCatching { JSONObject(json).getJSONArray("files") }.getOrNull() ?: return null
        for (index in 0 until files.length()) {
            val entry = files.optJSONObject(index) ?: continue
            if (entry.optJSONObject("hashes")?.optString("sha512") == sha512) return entry.optString("url").ifEmpty { null }
        }
        return null
    }

    fun modVersionUrl(sha1: String): String = VERSION_FILE.format(sha1)

    fun sha512Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-512")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    fun fetchText(url: String): String {
        val connection = open(url)
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode} for $url")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads `url` to `target` through a `.part` file; returns the byte count. `progress` gets the bytes so far. */
    fun download(url: String, target: File, progress: (Long) -> Unit): Long {
        val part = File(target.parentFile, target.name + ".part")
        val connection = open(url)
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode} for $url")
            var total = 0L
            var reported = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("download from $url is larger than $MAX_BYTES bytes")
                        output.write(buffer, 0, n)
                        if (total - reported >= 256 * 1024) {
                            reported = total
                            progress(total)
                        }
                    }
                }
            }
            if (total == 0L) throw IOException("empty download from $url")
            target.delete()
            if (!part.renameTo(target)) throw IOException("could not move ${part.name} to ${target.name}")
            return total
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
