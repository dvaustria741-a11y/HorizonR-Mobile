package dev.horizonr.jvmprobe

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.KeyStore

/**
 * Prepares `filesDir/mcserver` for one server run: launcher jar, pinned mods, `eula.txt`, `server.properties`, a
 * temp dir and the JVM options. Files from earlier runs (launcher, mods, whatever the Fabric launcher cached) stay;
 * `world` and `logs` are removed so every run starts a fresh world.
 */
object ServerSetup {
    class Prepared(val dir: File, val jar: File, val options: List<String>, val facts: Map<String, String>)

    fun serverDir(context: Context): File = File(context.filesDir, "mcserver")

    private const val TRUST_PASSWORD = "changeit"

    /**
     * How the JVM should find trusted CA certificates. The JRE's own `lib/security/cacerts` is used when it exists and
     * is not empty; otherwise the Android system CA store is exported to a PKCS12 file and passed as the trust store.
     * Whether either works with this JRE is unverified: the server run's first download shows it.
     */
    private fun trust(home: File, dir: File): Pair<String, List<String>> {
        val cacerts = File(home, "lib/security/cacerts")
        if (cacerts.isFile && cacerts.length() > 0) return "jre-cacerts" to emptyList()
        return try {
            val system = KeyStore.getInstance("AndroidCAStore")
            system.load(null)
            val exported = KeyStore.getInstance("PKCS12")
            exported.load(null, null)
            var count = 0
            for (alias in system.aliases()) {
                val certificate = system.getCertificate(alias) ?: continue
                exported.setCertificateEntry(alias, certificate)
                count++
            }
            val file = File(dir, "android-cacerts.p12")
            file.outputStream().use { exported.store(it, TRUST_PASSWORD.toCharArray()) }
            "android-export ($count certificates)" to listOf(
                "-Djavax.net.ssl.trustStore=${file.path}",
                "-Djavax.net.ssl.trustStoreType=PKCS12",
                "-Djavax.net.ssl.trustStorePassword=$TRUST_PASSWORD",
            )
        } catch (e: Exception) {
            "none (export failed: ${e.javaClass.simpleName}: ${e.message})" to emptyList()
        }
    }

    fun prepare(context: Context, home: File, eulaAccepted: Boolean, stage: (String) -> Unit): Prepared {
        if (!eulaAccepted) throw IOException("the Minecraft EULA (${ServerConfig.EULA_URL}) was not accepted")
        val dir = serverDir(context)
        val mods = File(dir, "mods")
        val tmp = File(dir, "tmp")
        for (folder in listOf(dir, mods, tmp)) {
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("cannot create ${folder.path}")
        }
        File(dir, "world").deleteRecursively()
        File(dir, "logs").deleteRecursively()
        File(dir, "eula.txt").writeText("eula=true\n")
        File(dir, "server.properties").writeText(ServerConfig.SERVER_PROPERTIES.joinToString("\n", postfix = "\n"))

        val facts = LinkedHashMap<String, String>()
        val launcher = File(dir, ServerConfig.LAUNCHER_FILE)
        if (launcher.isFile && launcher.length() > 0) {
            facts["launcher"] = "cached, ${launcher.length()} bytes"
        } else {
            stage("downloading ${ServerConfig.LAUNCHER_FILE}")
            val started = System.nanoTime()
            val bytes = ServerDownloader.download(ServerConfig.LAUNCHER_URL, launcher) { stage("downloading ${ServerConfig.LAUNCHER_FILE}: ${it / 1024} KiB") }
            facts["launcher"] = "downloaded, $bytes bytes, ${(System.nanoTime() - started) / 1_000_000} ms"
        }
        for (mod in ServerConfig.MODS) {
            val target = File(mods, mod.file)
            if (target.isFile && ServerDownloader.sha512Hex(target) == mod.sha512) {
                facts["mod ${mod.file}"] = "cached, SHA-512 matches the pin"
                continue
            }
            stage("looking up ${mod.file}")
            val url = ServerDownloader.pickModUrl(ServerDownloader.fetchText(ServerDownloader.modVersionUrl(mod.sha1)), mod.sha512)
                ?: throw IOException("Modrinth has no file with the pinned SHA-512 for ${mod.file}")
            if (!ServerDownloader.cdnUrlOk(url)) throw IOException("refusing download URL $url")
            stage("downloading ${mod.file}")
            ServerDownloader.download(url, target) { stage("downloading ${mod.file}: ${it / 1024} KiB") }
            if (ServerDownloader.sha512Hex(target) != mod.sha512) {
                target.delete()
                throw IOException("${mod.file}: downloaded bytes do not match the pinned SHA-512")
            }
            facts["mod ${mod.file}"] = "downloaded, SHA-512 matches the pin"
        }
        val (trustSource, trustOptions) = trust(home, dir)
        facts["trust store"] = trustSource
        val options = listOf("-Djava.io.tmpdir=${tmp.path}", "-Duser.home=${dir.path}") + trustOptions
        return Prepared(dir, launcher, options, facts)
    }
}
