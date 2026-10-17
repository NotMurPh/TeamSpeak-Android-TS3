package com.tsrewrite.client

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Badge(val uuid: String, val name: String, val url: String, val description: String)

/**
 * myTeamSpeak badges: the public list (protobuf `BadgeList` at badges-content.teamspeak.com/list)
 * and their `_64.png` images, cached on disk. Clients carry `overwolf=0:badges=<uuid>,<uuid>`.
 */
class BadgeStore(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(context.filesDir, "badges").apply { mkdirs() }
    private val _badges = MutableStateFlow<Map<String, Badge>>(emptyMap())
    val badges: StateFlow<Map<String, Badge>> = _badges
    private val _images = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val images: StateFlow<Map<String, ImageBitmap>> = _images
    private val requested = mutableSetOf<String>()

    init {
        scope.launch {
            val cache = File(dir, "list.bin")
            if (cache.exists()) runCatching { _badges.value = parseList(cache.readBytes()) }
            if (!cache.exists() || System.currentTimeMillis() - cache.lastModified() > 24 * 3600_000L) {
                runCatching { fetch(LIST_URL) }.getOrNull()?.let { bytes ->
                    runCatching { parseList(bytes) }.getOrNull()?.let { _badges.value = it; cache.writeBytes(bytes) }
                }
            }
        }
    }

    /** Badge uuids from a client's `client_badges` value. */
    fun parse(field: String): List<String> =
        field.split(':').firstOrNull { it.startsWith("badges=") }?.removePrefix("badges=")?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    fun load(uuid: String) {
        synchronized(requested) { if (!requested.add(uuid)) return }
        scope.launch {
            val f = File(dir, "$uuid.png")
            if (!f.exists()) {
                var badge = _badges.value[uuid]
                for (i in 0 until 20) { if (badge != null) break; Thread.sleep(500); badge = _badges.value[uuid] }
                val url = badge?.url ?: return@launch
                runCatching { fetch(url + "_64.png") }.getOrNull()?.let { f.writeBytes(it) }
            }
            runCatching { BitmapFactory.decodeFile(f.absolutePath)?.let { PixelScale.upscale(it).asImageBitmap() } }.getOrNull()?.let { bmp -> _images.update { it + (uuid to bmp) } }
        }
    }

    private fun fetch(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        return c.inputStream.use { it.readBytes() }
    }

    companion object {
        private const val LIST_URL = "https://badges-content.teamspeak.com/list"

        /** Minimal protobuf reader for `BadgeList { repeated Badge badges = 3; }`. */
        fun parseList(b: ByteArray): Map<String, Badge> {
            val out = mutableMapOf<String, Badge>()
            for ((field, value) in Proto.fields(b)) {
                if (field != 3 || value !is ByteArray) continue
                val f = Proto.fields(value).filter { it.second is ByteArray }.associate { it.first to String(it.second as ByteArray) }
                val uuid = f[1] ?: continue
                out[uuid] = Badge(uuid, f[2] ?: "", f[3] ?: "", f[4] ?: "")
            }
            return out
        }
    }
}

/** Tiny protobuf wire-format helpers (varints and length-delimited fields). */
object Proto {
    fun fields(b: ByteArray): List<Pair<Int, Any>> {
        val out = mutableListOf<Pair<Int, Any>>()
        var p = 0
        while (p < b.size) {
            val (key, p1) = varint(b, p); p = p1
            val field = (key shr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> { val (v, p2) = varint(b, p); p = p2; out += field to v }
                1 -> { p += 8 }
                2 -> { val (len, p2) = varint(b, p); p = p2; out += field to b.copyOfRange(p, p + len.toInt()); p += len.toInt() }
                5 -> { p += 4 }
                else -> return out
            }
        }
        return out
    }

    fun varint(b: ByteArray, start: Int): Pair<Long, Int> {
        var r = 0L; var s = 0; var p = start
        while (true) {
            val x = b[p].toInt() and 0xff
            r = r or ((x and 0x7f).toLong() shl s); p++
            if (x < 0x80) return r to p
            s += 7
        }
    }
}
