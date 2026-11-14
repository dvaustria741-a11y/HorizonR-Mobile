package dev.horizonr.jvmprobe

/**
 * Everything about the on-device Fabric server run that comes from outside this code, with where it came from.
 * Nothing here is tuned for the phone.
 */
object ServerConfig {
    /**
     * Fabric's documented server-launcher endpoint for game 26.3, loader 0.20.1, installer 1.1.2 (versions read from the
     * launcher jar the user uploaded). Same URL as spikes/jvm-server-smoke/run_smoke.sh, which CI run #1 downloaded.
     */
    const val LAUNCHER_URL = "https://meta.fabricmc.net/v2/versions/loader/26.3/0.20.1/1.1.2/server/jar"
    const val LAUNCHER_FILE = "fabric-server.jar"

    class ModPin(val file: String, val sha1: String, val sha512: String)

    /** Config `clumps` of spikes/jvm-server-smoke/mods.json: hashes computed from the jar the user uploaded on 2026-11-14. */
    val MODS = listOf(
        ModPin(
            file = "Clumps-fabric-26_3-26_3_2.jar",
            sha1 = "86d6618f772320bb5e66b4696396a450d5cb845e",
            sha512 = "8c166ae97e1999d0f213d0a181d6ef87b99648016c92c9aa1414d2c2e24364b12549569cd7e247a3d675df703ae6a8a88fc175d3f527818c710578408508c8ab",
        ),
    )

    /** run_smoke.sh uses -Xmx1G on the CI runner, so the numbers compare. The phone's right value is unknown. */
    const val HEAP_MIB = 1024

    /**
     * server.properties as written by run_smoke.sh, plus `server-ip=127.0.0.1` so the test server is not reachable
     * from the network while it runs (the later join test would come from this same phone).
     */
    val SERVER_PROPERTIES = listOf(
        "online-mode=false",
        "server-ip=127.0.0.1",
        "server-port=25565",
        "view-distance=2",
        "simulation-distance=2",
    )

    /** run_smoke.sh waits up to 420 s on a CI runner; the phone is untested, so this is a guess. */
    const val START_TIMEOUT_SECONDS = 900

    /** Seconds to let the server idle after "Done" before RSS is read and the stop signal is sent. A guess. */
    const val SETTLE_SECONDS = 10

    /** Seconds the JVM gets to exit after SIGTERM before the process is killed. A guess. */
    const val STOP_TIMEOUT_SECONDS = 30

    const val EULA_URL = "https://aka.ms/MinecraftEULA"
}
