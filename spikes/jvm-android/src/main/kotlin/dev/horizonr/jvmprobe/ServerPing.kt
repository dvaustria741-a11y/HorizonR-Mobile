package dev.horizonr.jvmprobe

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minecraft's Server List Ping (handshake with next state 1, then an empty status request), used to read the version
 * name and protocol number the running server reports. The framing (VarInt length, VarInt packet id, UTF-8 string) is
 * the long-standing status exchange; that 26.3 still uses it is assumed until a run confirms it. The status stage has
 * no compression and no encryption.
 */
object ServerPing {
    private const val MAX_RESPONSE_BYTES = 1 shl 20
    private const val MAX_VARINT_BYTES = 5

    /** Pingers conventionally send -1 here; the real server answers whatever it is. */
    private const val HANDSHAKE_PROTOCOL = -1

    class Status(val versionName: String?, val protocol: Int?, val roundTripMs: Long, val rawJson: String)

    class Outcome(val status: Status?, val error: String?)

    fun varInt(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var rest = value
        while (true) {
            if ((rest and 0x7F.inv()) == 0) {
                out.write(rest)
                break
            }
            out.write((rest and 0x7F) or 0x80)
            rest = rest ushr 7
        }
        return out.toByteArray()
    }

    fun readVarInt(input: InputStream): Int {
        var result = 0
        for (index in 0 until MAX_VARINT_BYTES) {
            val byte = input.read()
            if (byte < 0) throw EOFException("the stream ended inside a VarInt")
            result = result or ((byte and 0x7F) shl (7 * index))
            if ((byte and 0x80) == 0) return result
        }
        throw IOException("a VarInt longer than $MAX_VARINT_BYTES bytes")
    }

    fun string(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return varInt(bytes.size) + bytes
    }

    /** A length-prefixed packet: VarInt length of (VarInt id + payload), then id, then payload. */
    fun packet(id: Int, payload: ByteArray): ByteArray {
        val body = varInt(id) + payload
        return varInt(body.size) + body
    }

    fun handshake(host: String, port: Int): ByteArray {
        val payload = varInt(HANDSHAKE_PROTOCOL) + string(host) + byteArrayOf((port ushr 8).toByte(), port.toByte()) + varInt(1)
        return packet(0, payload)
    }

    fun statusRequest(): ByteArray = packet(0, ByteArray(0))

    /** The JSON text inside a status response body (VarInt id, then a string), or a failure naming what was wrong. */
    fun responseJson(body: ByteArray): String {
        val input = ByteArrayInputStream(body)
        val id = readVarInt(input)
        if (id != 0) throw IOException("status response has packet id $id, expected 0")
        val length = readVarInt(input)
        if (length < 0 || length > input.available()) throw IOException("status string length $length does not fit the packet")
        val bytes = ByteArray(length)
        if (input.read(bytes, 0, length) != length) throw EOFException("short status string")
        return String(bytes, Charsets.UTF_8)
    }

    /** `version.name` and `version.protocol` from the status JSON; a missing field stays null. */
    fun parseVersion(json: String): Pair<String?, Int?> {
        val version = JSONObject(json).optJSONObject("version") ?: return Pair(null, null)
        val name = if (version.has("name")) version.optString("name") else null
        val protocol = if (version.has("protocol")) version.optInt("protocol") else null
        return Pair(name, protocol)
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var done = 0
        while (done < size) {
            val read = input.read(bytes, done, size - done)
            if (read < 0) throw EOFException("the server closed the connection after $done of $size bytes")
            done += read
        }
        return bytes
    }

    fun query(host: String, port: Int, timeoutMs: Int): Status {
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val started = System.nanoTime()
            val out = socket.getOutputStream()
            out.write(handshake(host, port))
            out.write(statusRequest())
            out.flush()
            val input = socket.getInputStream()
            val length = readVarInt(input)
            if (length <= 0 || length > MAX_RESPONSE_BYTES) throw IOException("status response length $length is out of range")
            val json = responseJson(readExactly(input, length))
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val (name, protocol) = parseVersion(json)
            return Status(name, protocol, elapsedMs, json)
        }
    }

    /** A few tries, because the port is bound a moment before the first status request is served. Never throws. */
    fun queryWithRetry(host: String, port: Int, attempts: Int, delayMs: Long, timeoutMs: Int): Outcome {
        var last = "no attempt made"
        for (attempt in 1..attempts) {
            try {
                return Outcome(query(host, port, timeoutMs), null)
            } catch (e: Exception) {
                last = "attempt $attempt: ${e.javaClass.simpleName}: ${e.message}"
            }
            if (attempt < attempts) Thread.sleep(delayMs)
        }
        return Outcome(null, last)
    }
}
