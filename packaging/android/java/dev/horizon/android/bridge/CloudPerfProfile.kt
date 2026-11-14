package dev.horizon.android.bridge

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Experimental Stuff > Cloud Performance Profile.
 *
 * A game's frames are drawn on the phone, so no server can raise FPS by itself. What a server can do is
 * hold the tuning: this fetches a small JSON (host it on GitHub raw, a Cloudflare Worker or R2) that maps a
 * device class to horizon-graphics.txt settings, so tuning can change without shipping a new APK.
 *
 * Safety: the remote file can only set the keys in [ALLOWED], each clamped to a range the engine itself
 * accepts (see `graphics.rs` `parse`). Anything else is dropped. A failed or invalid fetch keeps the last
 * good cache, then the built-in profile. Launching never waits for the network.
 *
 * Every number in [BUILT_IN] is an uncalibrated starting guess, not a measured value.
 */
internal object CloudPerfProfile {
    private const val TAG = "CloudPerfProfile"

    /** Default profile location: the horizon-perf-profile Cloudflare Worker (source in `cloud/worker/`). */
    const val DEFAULT_URL = "https://horizon-perf-profile.dvaustria741.workers.dev/perf-profile.json"

    private const val CACHE_NAME = "cloud-perf-profile.json"
    private const val REFRESH_EVERY_MS = 6L * 60L * 60L * 1000L
    private const val MAX_BYTES = 64 * 1024
    private const val TIMEOUT_MS = 4000

    enum class Tier(val key: String) { LOW("low"), MID("mid"), HIGH("high") }

    /** What a setting accepts: a switch, a number range, or a fixed list of words. */
    private sealed interface Rule {
        data object Flag : Rule
        data class Range(val min: Int, val max: Int) : Rule
        data class Words(val allowed: Set<String>) : Rule
    }

    /** Keys the remote file may set. Ranges follow `crates/horizon-client-render/src/graphics.rs` `parse`. */
    private val ALLOWED: Map<String, Rule> = mapOf(
        "resolutionScale" to Rule.Range(50, 100),
        "dynamicResolution" to Rule.Flag,
        "dynamicResolutionMin" to Rule.Range(40, 100),
        "adaptiveDistance" to Rule.Flag,
        "adaptiveDistanceMin" to Rule.Range(4, 32),
        "animatedTextures" to Rule.Flag,
        "weather" to Rule.Flag,
        "uploadBudgetKiB" to Rule.Range(256, 8192),
        "entityCulling" to Rule.Words(setOf("async")),
        "particleCulling" to Rule.Words(setOf("async")),
        "leafCulling" to Rule.Words(setOf("hidden"))
    )

    /** Used when there is no cache and no network. UNCALIBRATED: starting guesses, tune from real device logs. */
    private val BUILT_IN: Map<Tier, Map<String, String>> = mapOf(
        Tier.LOW to mapOf(
            "resolutionScale" to "80",
            "dynamicResolution" to "true",
            "dynamicResolutionMin" to "60",
            "adaptiveDistance" to "true",
            "adaptiveDistanceMin" to "6",
            "animatedTextures" to "false"
        ),
        Tier.MID to mapOf(
            "dynamicResolution" to "true",
            "dynamicResolutionMin" to "75",
            "adaptiveDistance" to "true",
            "adaptiveDistanceMin" to "8"
        ),
        Tier.HIGH to mapOf(
            "adaptiveDistance" to "true",
            "adaptiveDistanceMin" to "10"
        )
    )

    /** RAM thresholds in MB. UNCALIBRATED guess; a remote `socs` entry overrides it for a known chip. */
    fun tierForRam(totalRamMb: Int): Tier = when {
        totalRamMb <= 0 -> Tier.MID
        totalRamMb <= 4608 -> Tier.LOW
        totalRamMb <= 8704 -> Tier.MID
        else -> Tier.HIGH
    }

    /** MemTotal from /proc/meminfo in MB, or 0 when it can't be read. */
    fun readTotalRamMb(): Int = try {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemTotal:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.let { (it / 1024L).toInt() } ?: 0
        }
    } catch (_: Exception) {
        0
    }

    /** The SoC model string on Android 12+, or null. Informational and used only for the remote `socs` map. */
    fun socModel(): String? =
        if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL?.trim()?.takeIf { it.isNotEmpty() && it != Build.UNKNOWN } else null

    /** Keeps only allowed keys with in-range values. Pure. */
    fun sanitize(raw: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((key, value) in raw) {
            when (val rule = ALLOWED[key] ?: continue) {
                Rule.Flag -> if (value == "true" || value == "false") out[key] = value
                is Rule.Range -> value.toIntOrNull()?.let { out[key] = it.coerceIn(rule.min, rule.max).toString() }
                is Rule.Words -> if (value in rule.allowed) out[key] = value
            }
        }
        return out
    }

    /**
     * Picks the settings for a device from a profile document. Pure.
     * Shape: `{"version":1,"tiers":{"low":{...},"mid":{...},"high":{...}},"socs":{"<SOC_MODEL>":"low"}}`.
     * Returns null when the document has no usable tier.
     */
    fun select(json: String, tier: Tier, soc: String?): Map<String, String>? {
        val root = try { JSONObject(json) } catch (_: Exception) { return null }
        if (root.optInt("version", 0) != 1) return null
        val tiers = root.optJSONObject("tiers") ?: return null
        val socTier = soc
            ?.let { root.optJSONObject("socs")?.optString(it, "") }
            ?.let { name -> Tier.entries.firstOrNull { it.key == name } }
        val block = tiers.optJSONObject((socTier ?: tier).key) ?: return null
        val raw = linkedMapOf<String, String>()
        for (key in block.keys()) raw[key] = block.opt(key)?.toString() ?: continue
        return sanitize(raw)
    }

    /** What to write for this launch: cached remote profile if valid, else the built-in one. Never touches the network. */
    fun resolve(cacheDir: File): Map<String, String> {
        val tier = tierForRam(readTotalRamMb())
        val cached = try {
            File(cacheDir, CACHE_NAME).takeIf { it.isFile && it.length() in 1..MAX_BYTES.toLong() }?.readText()
        } catch (_: Exception) {
            null
        }
        val fromCache = cached?.let { select(it, tier, socModel()) }
        if (!fromCache.isNullOrEmpty()) return fromCache
        return sanitize(BUILT_IN.getValue(tier))
    }

    @Volatile
    private var refreshing = false

    /** Fetches the profile in the background at most once per six hours and caches it only when it validates. */
    fun refreshAsync(cacheDir: File, url: String = DEFAULT_URL) {
        val cache = File(cacheDir, CACHE_NAME)
        if (refreshing) return
        if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < REFRESH_EVERY_MS) return
        refreshing = true
        Thread({
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                }
                try {
                    if (conn.responseCode != 200) {
                        Log.w(TAG, "profile fetch HTTP ${conn.responseCode}")
                        return@Thread
                    }
                    val bytes = conn.inputStream.use { readCapped(it, MAX_BYTES + 1) }
                    if (bytes.isEmpty() || bytes.size > MAX_BYTES) {
                        Log.w(TAG, "profile fetch size ${bytes.size} rejected")
                        return@Thread
                    }
                    val text = String(bytes, Charsets.UTF_8)
                    val ok = Tier.entries.any { select(text, it, null) != null }
                    if (!ok) {
                        Log.w(TAG, "profile fetch rejected: no usable tier")
                        return@Thread
                    }
                    val tmp = File(cacheDir, "$CACHE_NAME.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(cache)) {
                        cache.writeText(text)
                        tmp.delete()
                    }
                    Log.i(TAG, "profile cached (${bytes.size} bytes)")
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "profile fetch failed: ${e.javaClass.simpleName}")
            } finally {
                refreshing = false
            }
        }, "cloud-perf-profile").apply { isDaemon = true }.start()
    }

    /** Reads at most `limit` bytes (readNBytes needs API 33, minSdk is 28). */
    private fun readCapped(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** One log line saying what was applied, so a test run can be read back from logcat. */
    fun describe(applied: Map<String, String>): String =
        String.format(Locale.US, "tier=%s soc=%s applied=%s", tierForRam(readTotalRamMb()).key, socModel() ?: "n/a", applied)
}
