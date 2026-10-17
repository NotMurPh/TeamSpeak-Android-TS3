package com.tsrewrite.client

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import uniffi.tscore.ServerView
import uniffi.tscore.avatarPath
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Server icons (`/icon_<id>` in channel 0), client avatars and host banners, cached on disk per server.
 * Icon ids below 1000 are the client's built-in icons and are drawn by the UI instead.
 */
class IconStore(private val context: Context, private val session: Session) {
    private val _icons = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())
    val icons: StateFlow<Map<Long, ImageBitmap>> = _icons
    private val _avatars = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val avatars: StateFlow<Map<String, ImageBitmap>> = _avatars
    private val _banner = MutableStateFlow<File?>(null)
    /** The host banner as downloaded (may be an animated GIF/WebP); a new File on every refresh. */
    val banner: StateFlow<File?> = _banner

    private val requested = mutableSetOf<Long>()
    private val requestedAvatars = mutableSetOf<String>()
    private var bannerUrl = ""
    private val limit = Semaphore(3)
    private var dir = File(context.cacheDir, "icons")

    fun reset(server: String) {
        dir = File(context.cacheDir, "icons/" + md5(server.lowercase()).take(12)).apply { mkdirs() }
        synchronized(this) { requested.clear(); requestedAvatars.clear() }
        _icons.value = emptyMap(); _avatars.value = emptyMap(); _banner.value = null; bannerUrl = ""; bannerJob?.cancel()
    }

    fun prefetch(v: ServerView) {
        val ids = buildSet {
            add(v.iconId.toLong())
            v.channels.forEach { add(it.iconId.toLong()) }
            v.clients.forEach { add(it.iconId.toLong()) }
            v.serverGroups.forEach { add(it.iconId.toLong()) }
            v.channelGroups.forEach { add(it.iconId.toLong()) }
        }.filter { it >= 1000 || it < 0 }.map { it and 0xffffffffL }
        for (id in ids) icon(id)
        if (v.bannerGfxUrl.isNotBlank() && v.bannerGfxUrl != bannerUrl) loadBanner(v.bannerGfxUrl, v.bannerIntervalSecs.toLong())
    }

    /** The cached file of a server icon (exists once [icons] contains it). */
    fun iconFile(id: Long): File = File(dir, "icon_$id")

    private fun icon(id: Long) {
        synchronized(this) { if (!requested.add(id)) return }
        val f = File(dir, "icon_$id")
        session.scope.launch {
            limit.withPermit {
                if (!f.exists()) session.download(0u, "/icon_$id", "", f)
                decode(f, icon = true)?.let { bmp -> _icons.update { it + (id to bmp) } }
            }
        }
    }

    /** Loads a client's avatar if it has one; returns the cached image when ready. */
    fun avatar(uid: String, hash: String) {
        if (hash.isBlank()) return
        synchronized(this) { if (!requestedAvatars.add(hash)) return }
        val f = File(dir, "avatar_$hash")
        session.scope.launch {
            limit.withPermit {
                if (!f.exists()) session.download(0u, avatarPath(uid), "", f)
                decode(f)?.let { bmp -> _avatars.update { it + (hash to bmp) } }
            }
        }
    }

    private var bannerJob: kotlinx.coroutines.Job? = null

    /** Downloads the banner, and again every [intervalSecs] (at least 60 s) like TS3 does when set. */
    private fun loadBanner(url: String, intervalSecs: Long) {
        bannerUrl = url
        bannerJob?.cancel()
        bannerJob = session.scope.launch {
            var n = 0
            while (true) {
                val f = File(dir, "banner_${n++ % 2}")
                val ok = runCatching {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 5000; c.readTimeout = 15000
                    c.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
                }.isSuccess
                if (ok && f.length() > 0) _banner.value = f
                if (intervalSecs <= 0) break
                kotlinx.coroutines.delay(maxOf(60L, intervalSecs) * 1000)
            }
        }
    }

    /** Icons are tiny pixel art and get upscaled; avatars are only shrunk. */
    private fun decode(f: File, icon: Boolean = false): ImageBitmap? =
        runCatching { BitmapFactory.decodeFile(f.absolutePath)?.let { (if (icon) PixelScale.upscale(it) else scale(it)).asImageBitmap() } }.getOrNull()

    private fun scale(b: Bitmap): Bitmap = if (b.width > 256) Bitmap.createScaledBitmap(b, 256, 256 * b.height / b.width, true) else b

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
