package dev.horizonr.jvmprobe

/**
 * What a server log says about the start. A port of spikes/jvm-server-smoke/summarize.py, so a phone result and a
 * CI result read the same way. PASS means vanilla's "Done (...s)!" line and no line counted as a problem; it says
 * nothing about mod behaviour.
 */
object ServerLog {
    private val DONE = Regex("""Done \((\d+(?:\.\d+)?)s\)""")
    private val LOADING = Regex("""Loading (\d+) mods?""")
    private val LIST_ITEM = Regex("""^\s+- \S""")
    private val PROBLEM = Regex(
        """\[(?:[^\]]*/)?ERROR\]|Exception|Caused by|InvalidInjection|Mixin apply failed|Could not execute entrypoint""",
    )
    private const val MAX_MODS = 60

    data class Summary(
        val doneSeconds: String?,
        val loading: String?,
        val mods: List<String>,
        val problems: List<String>,
        /** Lines that mention Mixin (any case): a hint that Mixin ran, not proof that a mixin applied. */
        val mixinLines: Int,
    ) {
        val passed: Boolean get() = doneSeconds != null && problems.isEmpty()
    }

    fun summarize(lines: List<String>): Summary {
        var done: String? = null
        var loading: String? = null
        val mods = ArrayList<String>()
        var capturing = false
        val problems = ArrayList<String>()
        var mixin = 0
        for (raw in lines) {
            val line = raw.trimEnd('\n', '\r')
            if (line.contains("mixin", ignoreCase = true)) mixin++
            if (done == null) DONE.find(line)?.let { done = it.groupValues[1] }
            if (loading == null && LOADING.containsMatchIn(line)) {
                loading = line.trim()
                capturing = true
                continue
            }
            if (capturing) {
                if (LIST_ITEM.containsMatchIn(line) && mods.size < MAX_MODS) {
                    mods.add(line.trim())
                    continue
                }
                capturing = false
            }
            if (PROBLEM.containsMatchIn(line)) problems.add(line.trim())
        }
        return Summary(done, loading, mods, problems, mixin)
    }
}
