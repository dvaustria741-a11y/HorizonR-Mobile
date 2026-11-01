package dev.horizon.android.bridge

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

class NbtTag(val id: Int, val value: Any?)

class ServerEntry(
    var name: String,
    var address: String,
    val raw: LinkedHashMap<String, NbtTag> = LinkedHashMap()
)

data class ServerStatus(
    val online: Boolean,
    val motd: String = "",
    val playersOnline: Int = 0,
    val playersMax: Int = 0,
    val version: String = "",
    val pingMs: Long = -1L
)

object HorizonServerStore {

    @Volatile
    var pendingQuickPlayServer: String? = null

    fun serversFile(profileHome: File): File = File(profileHome, "servers.dat")

    fun load(file: File): MutableList<ServerEntry> {
        val out = mutableListOf<ServerEntry>()
        if (!file.exists()) return out
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { din ->
                val rootId = din.readUnsignedByte()
                if (rootId != 10) return@use
                din.readUTF()
                @Suppress("UNCHECKED_CAST")
                val root = readPayload(10, din) as LinkedHashMap<String, NbtTag>
                val servers = root["servers"] ?: return@use
                if (servers.id != 9) return@use
                @Suppress("UNCHECKED_CAST")
                val list = servers.value as Pair<Int, List<Any?>>
                list.second.forEach { item ->
                    @Suppress("UNCHECKED_CAST")
                    val compound = item as? LinkedHashMap<String, NbtTag> ?: return@forEach
                    val name = compound["name"]?.value as? String ?: ""
                    val ip = compound["ip"]?.value as? String ?: ""
                    out.add(ServerEntry(name, ip, compound))
                }
            }
        }
        return out
    }

    fun save(file: File, entries: List<ServerEntry>) {
        val items = entries.map { e ->
            val compound = LinkedHashMap(e.raw)
            compound["name"] = NbtTag(8, e.name)
            compound["ip"] = NbtTag(8, e.address)
            compound
        }
        val root = LinkedHashMap<String, NbtTag>()
        root["servers"] = NbtTag(9, Pair(10, items))
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { dout ->
            dout.writeByte(10)
            dout.writeUTF("")
            writePayload(10, root, dout)
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bos.toByteArray())
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    private fun readPayload(id: Int, din: DataInputStream): Any? {
        return when (id) {
            1 -> din.readByte()
            2 -> din.readShort()
            3 -> din.readInt()
            4 -> din.readLong()
            5 -> din.readFloat()
            6 -> din.readDouble()
            7 -> ByteArray(din.readInt()).also { din.readFully(it) }
            8 -> din.readUTF()
            9 -> {
                val elemId = din.readUnsignedByte()
                val count = din.readInt()
                val items = ArrayList<Any?>(count.coerceAtLeast(0))
                repeat(count) { items.add(readPayload(elemId, din)) }
                Pair(elemId, items as List<Any?>)
            }
            10 -> {
                val map = LinkedHashMap<String, NbtTag>()
                while (true) {
                    val childId = din.readUnsignedByte()
                    if (childId == 0) break
                    val key = din.readUTF()
                    map[key] = NbtTag(childId, readPayload(childId, din))
                }
                map
            }
            11 -> IntArray(din.readInt()).also { arr -> for (i in arr.indices) arr[i] = din.readInt() }
            12 -> LongArray(din.readInt()).also { arr -> for (i in arr.indices) arr[i] = din.readLong() }
            else -> error("Unknown NBT tag $id")
        }
    }

    private fun writePayload(id: Int, value: Any?, dout: DataOutputStream) {
        when (id) {
            1 -> dout.writeByte((value as Byte).toInt())
            2 -> dout.writeShort((value as Short).toInt())
            3 -> dout.writeInt(value as Int)
            4 -> dout.writeLong(value as Long)
            5 -> dout.writeFloat(value as Float)
            6 -> dout.writeDouble(value as Double)
            7 -> {
                val arr = value as ByteArray
                dout.writeInt(arr.size)
                dout.write(arr)
            }
            8 -> dout.writeUTF(value as String)
            9 -> {
                @Suppress("UNCHECKED_CAST")
                val pair = value as Pair<Int, List<Any?>>
                dout.writeByte(if (pair.second.isEmpty()) 0 else pair.first)
                dout.writeInt(pair.second.size)
                pair.second.forEach { writePayload(pair.first, it, dout) }
            }
            10 -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as Map<String, NbtTag>
                map.forEach { (key, tag) ->
                    dout.writeByte(tag.id)
                    dout.writeUTF(key)
                    writePayload(tag.id, tag.value, dout)
                }
                dout.writeByte(0)
            }
            11 -> {
                val arr = value as IntArray
                dout.writeInt(arr.size)
                arr.forEach { dout.writeInt(it) }
            }
            12 -> {
                val arr = value as LongArray
                dout.writeInt(arr.size)
                arr.forEach { dout.writeLong(it) }
            }
        }
    }

    fun parseAddress(address: String): Pair<String, Int> {
        val trimmed = address.trim()
        val idx = trimmed.lastIndexOf(':')
        if (idx > 0 && idx < trimmed.length - 1 && trimmed.indexOf(':') == idx) {
            val port = trimmed.substring(idx + 1).toIntOrNull()
            if (port != null && port in 1..65535) return trimmed.substring(0, idx) to port
        }
        return trimmed to 25565
    }

    private fun writeVarInt(out: DataOutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                out.writeByte(v)
                return
            }
            out.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    private fun readVarInt(input: InputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) error("Unexpected end of stream")
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 35) error("VarInt too long")
        }
    }

    private fun packet(build: (DataOutputStream) -> Unit): ByteArray {
        val body = ByteArrayOutputStream()
        build(DataOutputStream(body))
        val framed = ByteArrayOutputStream()
        val fo = DataOutputStream(framed)
        writeVarInt(fo, body.size())
        fo.write(body.toByteArray())
        return framed.toByteArray()
    }

    fun ping(address: String, timeoutMs: Int = 4000): ServerStatus {
        val (host, port) = parseAddress(address)
        if (host.isBlank()) return ServerStatus(false)
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                val out = socket.getOutputStream()
                val input = socket.getInputStream()

                out.write(packet { p ->
                    writeVarInt(p, 0)
                    writeVarInt(p, -1)
                    val hostBytes = host.toByteArray(Charsets.UTF_8)
                    writeVarInt(p, hostBytes.size)
                    p.write(hostBytes)
                    p.writeShort(port)
                    writeVarInt(p, 1)
                })
                out.write(packet { p -> writeVarInt(p, 0) })
                out.flush()

                readVarInt(input)
                val packetId = readVarInt(input)
                if (packetId != 0) return@use ServerStatus(false)
                val jsonLen = readVarInt(input)
                if (jsonLen <= 0 || jsonLen > 4_000_000) return@use ServerStatus(false)
                val buf = ByteArray(jsonLen)
                var read = 0
                while (read < jsonLen) {
                    val n = input.read(buf, read, jsonLen - read)
                    if (n < 0) error("Unexpected end of stream")
                    read += n
                }
                val json = JSONObject(String(buf, Charsets.UTF_8))

                var latency = -1L
                runCatching {
                    val sent = System.nanoTime()
                    out.write(packet { p ->
                        writeVarInt(p, 1)
                        p.writeLong(sent)
                    })
                    out.flush()
                    readVarInt(input)
                    readVarInt(input)
                    latency = (System.nanoTime() - sent) / 1_000_000L
                }

                val players = json.optJSONObject("players")
                val versionName = json.optJSONObject("version")?.optString("name").orEmpty()
                ServerStatus(
                    online = true,
                    motd = extractMotd(json.opt("description")),
                    playersOnline = players?.optInt("online", 0) ?: 0,
                    playersMax = players?.optInt("max", 0) ?: 0,
                    version = versionName,
                    pingMs = latency
                )
            }
        } catch (e: Exception) {
            ServerStatus(false)
        }
    }

    private fun extractMotd(desc: Any?): String {
        val raw = when (desc) {
            is String -> desc
            is JSONObject -> {
                val sb = StringBuilder(desc.optString("text"))
                val extra = desc.optJSONArray("extra")
                if (extra != null) {
                    for (i in 0 until extra.length()) sb.append(extractMotd(extra.opt(i)))
                }
                sb.toString()
            }
            else -> ""
        }
        return raw.replace(Regex("\u00A7."), "").replace('\n', ' ').trim()
    }
}
