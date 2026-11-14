package dev.horizonr.jvmprobe

/** The Rust side: `spikes/jvm-boot` built as `libjvm_boot_spike.so` (src/android.rs). */
object NativeBoot {
    const val LIBRARY = "jvm_boot_spike"
    const val MODE_JNI = 0
    const val MODE_JLI = 1

    /** Not a mode of [run]: the Fabric server jar through JLI_Launch, see [runServer]. */
    const val MODE_SERVER = 2

    /**
     * Boots a JVM in the calling process and logs to `<workDir>/native.txt`. Returns 0 on success, 1 when the boot
     * failed, 2 for bad arguments, -2 for unreadable strings, -3 for a Rust panic.
     */
    @JvmStatic
    external fun run(mode: Int, heapMib: Int, jreHome: String, probeJar: String, workDir: String): Int

    /**
     * Runs the Fabric server jar in the calling process with `serverDir` as working directory and logs to
     * `<workDir>/native.txt`; the server's console goes to `<workDir>/jvm_stdio.log`. `options` holds one JVM option per
     * line. Returns only if the JVM exits by itself: 0 on success, 1 on failure, 2 for bad arguments, -2 for unreadable
     * strings, -3 for a Rust panic.
     */
    @JvmStatic
    external fun runServer(heapMib: Int, jreHome: String, workDir: String, serverDir: String, serverJar: String, options: String): Int
}
