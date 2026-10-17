package com.tsrewrite.client

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import uniffi.tscore.VoiceMode
import uniffi.tscore.createIdentity
import java.util.UUID

data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val address: String = "",
    val nickname: String = "",
    val identityId: String = "",
    val serverPassword: String = "",
    val channel: String = "",
    val channelPassword: String = "",
    val folder: String = "",
    val subscribeAll: Boolean = true,
    /** Server icon copied on first join; empty for the default icon. */
    val iconPath: String = "",
)

data class Identity(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val data: String,
    val nickname: String = "",
)

data class Settings(
    val voiceMode: VoiceMode = VoiceMode.PUSH_TO_TALK,
    val vadThreshold: Float = -45f,
    val micGain: Float = 1f,
    val volume: Float = 1f,
    val bitrate: Int = 96000,
    val speakerphone: Boolean = true,
    val defaultIdentityId: String = "",
    val defaultNickname: String = "Android User",
    val echoCancel: Boolean = true,
    val noiseSuppression: Boolean = true,
    val autoGain: Boolean = true,
    val volumeKeyPtt: Boolean = false,
    val headsetButtonPtt: Boolean = false,
    val pttToggle: Boolean = false,
    val announceEvents: Boolean = true,
    val playSounds: Boolean = true,
    val vibrateOnPoke: Boolean = true,
    val systemNotifications: Boolean = true,
    val autoReconnect: Boolean = true,
    val keepScreenOn: Boolean = false,
    val blockWhispers: Boolean = false,
    val showFlags: Boolean = false,
    val showGroupIcons: Boolean = true,
    val showBadges: Boolean = true,
    val treeTextScale: Float = 1f,
    val quitMessage: String = "",
    val cloudEmail: String = "",
    val announceJoins: Boolean = true,
    val announceLeaves: Boolean = true,
    val announceMoves: Boolean = true,
    val announceKicks: Boolean = true,
    val announcePokes: Boolean = true,
    val announceMessages: Boolean = true,
    val announceSelf: Boolean = false,
    val ignorePokes: Boolean = false,
    val chatTimestamps: Boolean = true,
    val avatarPath: String = "",
    val useHeadsetMic: Boolean = true,
    val deviceNoiseSuppression: Boolean = true,
    val lowLatencyWifi: Boolean = true,
    val playbackAgc: Boolean = true,
    /** Talk through Bluetooth earbuds' mic; Bluetooth then drops what you hear to call quality too. */
    val btHeadsetMic: Boolean = false,
)

/** Bookmarks, identities and settings, persisted as JSON in shared preferences. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("store", Context.MODE_PRIVATE)

    private val _bookmarks = MutableStateFlow(loadBookmarks())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks
    private val _identities = MutableStateFlow(loadIdentities())
    val identities: StateFlow<List<Identity>> = _identities
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings

    init {
        // Voice quality defaults to the best (Studio, 96 kbps); move earlier installs up once.
        if (!prefs.getBoolean("qualityV2", false)) {
            updateSettings(_settings.value.copy(bitrate = 96000))
            prefs.edit().putBoolean("qualityV2", true).apply()
        }
        if (_identities.value.isEmpty()) {
            val id = Identity(name = "Default", data = createIdentity())
            saveIdentities(listOf(id))
            updateSettings(_settings.value.copy(defaultIdentityId = id.id))
        }
    }

    fun upsertBookmark(b: Bookmark) = saveBookmarks(_bookmarks.value.filter { it.id != b.id } + b)
    fun replaceAll(bookmarks: List<Bookmark>, identities: List<Identity>) { saveBookmarks(bookmarks); saveIdentities(identities) }
    fun deleteBookmark(id: String) = saveBookmarks(_bookmarks.value.filter { it.id != id })
    fun upsertIdentity(i: Identity) =
        saveIdentities(_identities.value.map { if (it.id == i.id) i else it }.let { if (it.any { x -> x.id == i.id }) it else it + i })
    fun deleteIdentity(id: String) = saveIdentities(_identities.value.filter { it.id != id })
    fun identity(id: String): Identity =
        _identities.value.firstOrNull { it.id == id }
            ?: _identities.value.firstOrNull { it.id == _settings.value.defaultIdentityId }
            ?: _identities.value.first()

    /** Everything the user set up, as JSON for a backup file. */
    fun exportBackup(): String = JSONObject().apply {
        put("version", 1)
        put("bookmarks", JSONArray(prefs.getString("bookmarks", "[]")))
        put("identities", JSONArray(prefs.getString("identities", "[]")))
        put("settings", JSONObject(prefs.getString("settings", "{}")!!))
    }.toString(2)

    /** Restores a backup; returns false if the text is not a backup. */
    fun importBackup(text: String): Boolean = runCatching {
        val o = JSONObject(text)
        prefs.edit().putString("bookmarks", o.getJSONArray("bookmarks").toString())
            .putString("identities", o.getJSONArray("identities").toString())
            .putString("settings", o.optJSONObject("settings")?.toString() ?: "{}").apply()
        _bookmarks.value = loadBookmarks(); _identities.value = loadIdentities(); _settings.value = loadSettings()
    }.isSuccess

    fun updateSettings(s: Settings) {
        _settings.value = s
        prefs.edit().putString("settings", JSONObject().apply {
            put("voiceMode", s.voiceMode.name); put("vadThreshold", s.vadThreshold.toDouble())
            put("micGain", s.micGain.toDouble()); put("volume", s.volume.toDouble())
            put("bitrate", s.bitrate); put("speakerphone", s.speakerphone)
            put("defaultIdentityId", s.defaultIdentityId); put("defaultNickname", s.defaultNickname)
            put("echoCancel", s.echoCancel); put("noiseSuppression", s.noiseSuppression); put("autoGain", s.autoGain)
            put("volumeKeyPtt", s.volumeKeyPtt); put("headsetButtonPtt", s.headsetButtonPtt); put("pttToggle", s.pttToggle)
            put("announceEvents", s.announceEvents); put("playSounds", s.playSounds); put("vibrateOnPoke", s.vibrateOnPoke)
            put("systemNotifications", s.systemNotifications); put("autoReconnect", s.autoReconnect)
            put("keepScreenOn", s.keepScreenOn); put("blockWhispers", s.blockWhispers); put("showFlags", s.showFlags)
            put("showGroupIcons", s.showGroupIcons); put("showBadges", s.showBadges); put("treeTextScale", s.treeTextScale.toDouble())
            put("quitMessage", s.quitMessage); put("cloudEmail", s.cloudEmail)
            put("announceJoins", s.announceJoins); put("announceLeaves", s.announceLeaves); put("announceMoves", s.announceMoves); put("announceKicks", s.announceKicks); put("announcePokes", s.announcePokes); put("announceMessages", s.announceMessages); put("announceSelf", s.announceSelf); put("ignorePokes", s.ignorePokes); put("chatTimestamps", s.chatTimestamps); put("avatarPath", s.avatarPath)
            put("useHeadsetMic", s.useHeadsetMic); put("deviceNoiseSuppression", s.deviceNoiseSuppression); put("lowLatencyWifi", s.lowLatencyWifi); put("playbackAgc", s.playbackAgc); put("btHeadsetMic", s.btHeadsetMic)
        }.toString()).apply()
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        _bookmarks.value = list.sortedBy { it.label.lowercase() }
        prefs.edit().putString("bookmarks", JSONArray(list.map {
            JSONObject().apply {
                put("id", it.id); put("label", it.label); put("address", it.address); put("nickname", it.nickname)
                put("identityId", it.identityId); put("serverPassword", it.serverPassword)
                put("channel", it.channel); put("channelPassword", it.channelPassword)
                put("folder", it.folder); put("subscribeAll", it.subscribeAll); put("iconPath", it.iconPath)
            }
        }).toString()).apply()
    }

    private fun saveIdentities(list: List<Identity>) {
        _identities.value = list
        prefs.edit().putString("identities", JSONArray(list.map {
            JSONObject().apply { put("id", it.id); put("name", it.name); put("data", it.data); put("nickname", it.nickname) }
        }).toString()).apply()
    }

    private fun array(key: String) = JSONArray(prefs.getString(key, "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun loadBookmarks() = array("bookmarks").map {
        Bookmark(it.getString("id"), it.optString("label"), it.optString("address"), it.optString("nickname"),
            it.optString("identityId"), it.optString("serverPassword"), it.optString("channel"), it.optString("channelPassword"),
            it.optString("folder"), it.optBoolean("subscribeAll", true), it.optString("iconPath"))
    }.sortedBy { it.label.lowercase() }

    private fun loadIdentities() = array("identities").map {
        Identity(it.getString("id"), it.optString("name"), it.getString("data"), it.optString("nickname"))
    }

    private fun loadSettings(): Settings {
        val o = JSONObject(prefs.getString("settings", "{}")!!)
        val d = Settings()
        return Settings(
            voiceMode = runCatching { VoiceMode.valueOf(o.getString("voiceMode")) }.getOrDefault(d.voiceMode),
            vadThreshold = o.optDouble("vadThreshold", d.vadThreshold.toDouble()).toFloat(),
            micGain = o.optDouble("micGain", d.micGain.toDouble()).toFloat(),
            volume = o.optDouble("volume", d.volume.toDouble()).toFloat(),
            bitrate = o.optInt("bitrate", d.bitrate),
            speakerphone = o.optBoolean("speakerphone", d.speakerphone),
            defaultIdentityId = o.optString("defaultIdentityId", d.defaultIdentityId),
            defaultNickname = o.optString("defaultNickname", d.defaultNickname),
            echoCancel = o.optBoolean("echoCancel", d.echoCancel),
            noiseSuppression = o.optBoolean("noiseSuppression", d.noiseSuppression),
            autoGain = o.optBoolean("autoGain", d.autoGain),
            volumeKeyPtt = o.optBoolean("volumeKeyPtt", d.volumeKeyPtt),
            headsetButtonPtt = o.optBoolean("headsetButtonPtt", d.headsetButtonPtt),
            pttToggle = o.optBoolean("pttToggle", d.pttToggle),
            announceEvents = o.optBoolean("announceEvents", d.announceEvents),
            playSounds = o.optBoolean("playSounds", d.playSounds),
            vibrateOnPoke = o.optBoolean("vibrateOnPoke", d.vibrateOnPoke),
            systemNotifications = o.optBoolean("systemNotifications", d.systemNotifications),
            autoReconnect = o.optBoolean("autoReconnect", d.autoReconnect),
            keepScreenOn = o.optBoolean("keepScreenOn", d.keepScreenOn),
            blockWhispers = o.optBoolean("blockWhispers", d.blockWhispers),
            showFlags = o.optBoolean("showFlags", d.showFlags),
            showGroupIcons = o.optBoolean("showGroupIcons", d.showGroupIcons),
            showBadges = o.optBoolean("showBadges", d.showBadges),
            treeTextScale = o.optDouble("treeTextScale", d.treeTextScale.toDouble()).toFloat(),
            quitMessage = o.optString("quitMessage", d.quitMessage),
            cloudEmail = o.optString("cloudEmail", d.cloudEmail),
            announceJoins = o.optBoolean("announceJoins", d.announceJoins),
            announceLeaves = o.optBoolean("announceLeaves", d.announceLeaves),
            announceMoves = o.optBoolean("announceMoves", d.announceMoves),
            announceKicks = o.optBoolean("announceKicks", d.announceKicks),
            announcePokes = o.optBoolean("announcePokes", d.announcePokes),
            announceMessages = o.optBoolean("announceMessages", d.announceMessages),
            announceSelf = o.optBoolean("announceSelf", d.announceSelf),
            ignorePokes = o.optBoolean("ignorePokes", d.ignorePokes),
            chatTimestamps = o.optBoolean("chatTimestamps", d.chatTimestamps),
            avatarPath = o.optString("avatarPath", d.avatarPath),
            useHeadsetMic = o.optBoolean("useHeadsetMic", d.useHeadsetMic),
            deviceNoiseSuppression = o.optBoolean("deviceNoiseSuppression", d.deviceNoiseSuppression),
            lowLatencyWifi = o.optBoolean("lowLatencyWifi", d.lowLatencyWifi),
            playbackAgc = o.optBoolean("playbackAgc", d.playbackAgc),
            btHeadsetMic = o.optBoolean("btHeadsetMic", d.btHeadsetMic),
        )
    }
}
