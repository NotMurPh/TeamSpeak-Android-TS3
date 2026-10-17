package com.tsrewrite.client

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.tscore.CommandFailure
import uniffi.tscore.ConnectOptions
import uniffi.tscore.EventKind
import uniffi.tscore.EventListener
import uniffi.tscore.KeyValue
import uniffi.tscore.LogEvent
import uniffi.tscore.ServerView
import uniffi.tscore.Status
import uniffi.tscore.TextMessage
import uniffi.tscore.TextTarget
import uniffi.tscore.TsClient
import uniffi.tscore.WhisperTarget
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

sealed class ChatKey {
    data object Server : ChatKey()
    data object Channel : ChatKey()
    /** Received pokes, newest last; read only. */
    data object Pokes : ChatKey()
    data class Private(val clientId: UShort, val name: String) : ChatKey()
}

data class ChatLine(val time: String, val from: String, val text: String, val own: Boolean, val fromId: UShort? = null)
data class Poke(val from: String, val fromId: UShort, val message: String)
data class LogLine(val time: String, val text: String, val kind: EventKind)
data class Transfer(val id: UInt, val name: String, val upload: Boolean, val done: Long, val total: Long, val finished: Boolean, val error: String?)

typealias Row = Map<String, String>

class CommandException(val failure: CommandFailure) : Exception(humanizeError(failure))

/** "PermissionsClientInsufficient" -> "Permissions client insufficient (missing b_client_...)". */
fun humanizeError(f: CommandFailure): String {
    val words = f.message.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }
    return words + (f.missingPermission?.let { " — missing permission ${it.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()}" } ?: "")
}

/** One server connection: wraps the Rust core and exposes its state as flows. */
class Session(private val context: Context, private val store: Store) : EventListener {
    val client = TsClient(this)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sounds = SoundManager(context, store) { _outputMuted.value }
    val icons = IconStore(context, this)

    private val _status = MutableStateFlow(Status.DISCONNECTED to "")
    val status: StateFlow<Pair<Status, String>> = _status
    private val _active = MutableStateFlow(false)
    /** A connection is wanted: connecting, connected or waiting to reconnect. The service lives this long. */
    val active: StateFlow<Boolean> = _active
    private val _server = MutableStateFlow<ServerView?>(null)
    val server: StateFlow<ServerView?> = _server
    private val _talking = MutableStateFlow<Map<UShort, Boolean>>(emptyMap()) // id -> whispering
    val talking: StateFlow<Map<UShort, Boolean>> = _talking
    private val _chats = MutableStateFlow<Map<ChatKey, List<ChatLine>>>(emptyChats())
    val chats: StateFlow<Map<ChatKey, List<ChatLine>>> = _chats
    private val _unread = MutableStateFlow<Set<ChatKey>>(emptySet())
    val unread: StateFlow<Set<ChatKey>> = _unread
    private val _composing = MutableStateFlow<Set<UShort>>(emptySet())
    val composing: StateFlow<Set<UShort>> = _composing
    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log
    private val _pokes = MutableSharedFlow<Poke>(extraBufferCapacity = 8)
    val pokes: SharedFlow<Poke> = _pokes
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors
    private val _notifications = MutableSharedFlow<Pair<String, List<Row>>>(extraBufferCapacity = 64)
    val notifications: SharedFlow<Pair<String, List<Row>>> = _notifications
    private val _transfers = MutableStateFlow<Map<UInt, Transfer>>(emptyMap())
    val transfers: StateFlow<Map<UInt, Transfer>> = _transfers
    private val _inputMuted = MutableStateFlow(false)
    val inputMuted: StateFlow<Boolean> = _inputMuted
    private val _outputMuted = MutableStateFlow(false)
    val outputMuted: StateFlow<Boolean> = _outputMuted
    private val _away = MutableStateFlow(false)
    val away: StateFlow<Boolean> = _away
    private val _speakerphone = MutableStateFlow(store.settings.value.speakerphone)
    val speakerphone: StateFlow<Boolean> = _speakerphone
    private val _route = MutableStateFlow<AudioRoute?>(null)
    /** Where voice currently goes (headset, speaker, ...). */
    val route: StateFlow<AudioRoute?> = _route
    private val _whisper = MutableStateFlow<WhisperTarget?>(null)
    val whisper: StateFlow<WhisperTarget?> = _whisper
    /** Our own client's current channel group & permission-relevant state, for the UI. */
    private val _hostMessage = MutableSharedFlow<Pair<String, Boolean>>(replay = 0, extraBufferCapacity = 2)
    val hostMessage: SharedFlow<Pair<String, Boolean>> = _hostMessage

    var bookmark: Bookmark? = null
        private set
    private var identityId = ""
    private var audio: AudioEngine? = null
    @Volatile private var userDisconnect = false
    private var reconnectAttempt = 0
    /** This bookmark's connection worked since the user connected; only then a lost one reconnects. */
    private var hadConnected = false
    private var reconnectJob: kotlinx.coroutines.Job? = null
    /** Bumped by every connect and disconnect, so a connect still resolving its address can be cancelled. */
    @Volatile private var attempt = 0
    /** The previous connection is being closed for a new one; its "disconnected" is not ours. */
    @Volatile private var replacing = false

    private val pending = ConcurrentHashMap<UInt, CompletableDeferred<CommandFailure?>>()
    private val transferWaits = ConcurrentHashMap<UInt, CompletableDeferred<String?>>()
    private class Collector(val names: Set<String>, val rows: MutableList<Row> = mutableListOf())
    private val collectors = CopyOnWriteArrayList<Collector>()

    fun connect(b: Bookmark) {
        if (b.id != bookmark?.id) { reconnectAttempt = 0; hadConnected = false }
        bookmark = b
        userDisconnect = false
        reconnectJob?.cancel()
        val mine = ++attempt
        val id = store.identity(b.identityId)
        identityId = id.id
        _chats.value = emptyChats()
        _unread.value = emptySet()
        _log.value = emptyList()
        _talking.value = emptyMap()
        _inputMuted.value = false; _outputMuted.value = false; _away.value = false
        permCache = null
        icons.reset(b.address)
        applySettings()
        val nick = b.nickname.ifBlank { id.nickname.ifBlank { store.settings.value.defaultNickname } }
        val options = ConnectOptions(
            address = b.address.trim(), nickname = nick, identity = id.data,
            serverPassword = b.serverPassword.ifBlank { null }, channel = b.channel.ifBlank { null },
            channelPassword = b.channelPassword.ifBlank { null },
            inputMuted = false, outputMuted = false, subscribeAll = b.subscribeAll,
        )
        _status.value = Status.CONNECTING to b.address
        _active.value = true
        // Also when it already runs: each start re-posts its notification, so this never races its shutdown.
        runCatching { ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java)) }
            .onFailure { Log.w(TAG, "connection service", it) }
        Thread {
            // Close the previous session first (this may block briefly); its callbacks are queued before
            // the reset below, so its "disconnected" is ignored instead of ending this attempt.
            replacing = true
            client.disconnect("")
            scope.launch(serial) { replacing = false }
            val resolved = ServerResolver.resolve(options.address)
            Log.i(TAG, "resolved ${options.address} -> $resolved")
            if (attempt != mine) return@Thread // cancelled while resolving
            runCatching { client.connect(options.copy(address = resolved)) }
                .onFailure { onStatus(Status.DISCONNECTED, it.message ?: "connect failed") }
        }.start()
    }

    /** Disconnects, or cancels connecting and reconnecting. */
    fun disconnect() {
        userDisconnect = true
        attempt++
        reconnectJob?.cancel()
        reconnectAttempt = 0
        hadConnected = false
        Thread {
            client.disconnect(store.settings.value.quitMessage)
            // Without a running session (still resolving, or waiting to reconnect) nothing else reports it.
            scope.launch(serial) { if (_status.value.first != Status.DISCONNECTED) handleStatus(Status.DISCONNECTED, "") }
        }.start()
    }

    fun applySettings() {
        val s = store.settings.value
        client.setVoiceMode(s.voiceMode)
        client.setVadThreshold(s.vadThreshold)
        client.setMicGain(s.micGain)
        client.setMasterVolume(s.volume)
        client.setBitrate(s.bitrate)
        client.setBlockWhispers(s.blockWhispers)
        client.setPlaybackAgc(true)
        audio?.refresh()
    }

    /** Starts voice for the connection (called by the foreground service). */
    fun startAudio() {
        audio?.stop()
        forcedOutput = null
        audio = AudioEngine(context, store, ClientIo(client), onRoute = { _route.value = it }, forced = { forcedOutput }).also { it.start() }
    }

    fun stopAudio() {
        audio?.stop()
        audio = null
        _route.value = null
        forcedOutput = null
    }

    /** Loudspeaker/earpiece (or loudspeaker/headset) chosen in the voice screen; lasts for this connection only. */
    @Volatile private var forcedOutput: AudioRoute.Output? = null

    /**
     * Switches where voice goes for this connection: with a headset between it and the loudspeaker
     * (mic included), without one between loudspeaker and earpiece. Settings are left untouched.
     */
    fun toggleOutput() {
        val r = _route.value ?: return
        forcedOutput = when {
            r.headsetPresent && (r.output == AudioRoute.Output.HEADSET || r.output == AudioRoute.Output.BLUETOOTH) -> AudioRoute.Output.SPEAKER
            r.headsetPresent -> null
            r.output == AudioRoute.Output.SPEAKER -> AudioRoute.Output.EARPIECE
            else -> AudioRoute.Output.SPEAKER
        }
        audio?.refresh()
    }

    fun setInputMuted(m: Boolean) { _inputMuted.value = m; fire(client.setInputMuted(m)) }
    fun setOutputMuted(m: Boolean) { _outputMuted.value = m; fire(client.setOutputMuted(m)) }
    fun setAway(message: String?) { _away.value = message != null; fire(client.setAway(message)) }
    fun setSpeakerphone(on: Boolean) {
        _speakerphone.value = on
        store.updateSettings(store.settings.value.copy(speakerphone = on))
        audio?.refresh()
    }

    /** Hold-to-whisper: set a target and press PTT; clearing stops the whisper. */
    fun whisper(target: WhisperTarget?) {
        _whisper.value = target
        client.setWhisper(target)
        client.setPushToTalk(target != null)
    }

    fun send(key: ChatKey, text: String) {
        val target = when (key) {
            ChatKey.Pokes -> return
            ChatKey.Server -> TextTarget.Server
            ChatKey.Channel -> TextTarget.Channel
            is ChatKey.Private -> TextTarget.Client(key.clientId)
        }
        fire(client.sendText(target, text))
    }

    fun openPrivate(clientId: UShort, name: String): ChatKey {
        val key = ChatKey.Private(clientId, name)
        _chats.update { if (it.keys.any { k -> k is ChatKey.Private && k.clientId == clientId }) it else it + (key to emptyList()) }
        return _chats.value.keys.first { it is ChatKey.Private && it.clientId == clientId }
    }

    fun closePrivate(key: ChatKey) { if (key is ChatKey.Private) _chats.update { it - key } }

    fun markRead(key: ChatKey) = _unread.update { it - key }

    // ---- commands ----

    /** Reports a command's failure to the user without waiting for it. */
    fun fire(id: UInt) {
        val d = pending.getOrPut(id) { CompletableDeferred() }
        scope.launch {
            val f = withTimeoutOrNull(30_000) { d.await() }
            pending.remove(id)
            if (f != null) _errors.tryEmit(humanizeError(f))
        }
    }

    /** Waits for a command; throws [CommandException] on failure. */
    suspend fun await(id: UInt) {
        val d = pending.getOrPut(id) { CompletableDeferred() }
        val f = withTimeoutOrNull(30_000) { d.await() }
        pending.remove(id)
        if (f != null) throw CommandException(f)
    }

    /** Sends a command and collects the rows of the given notifications until it completes. */
    suspend fun query(name: String, args: Map<String, String>, vararg expect: String): List<Row> {
        val c = Collector(expect.toSet())
        collectors += c
        try {
            val id = client.sendCommand(name, args.map { KeyValue(it.key, it.value) })
            try {
                await(id)
            } catch (e: CommandException) {
                if (e.failure.code != 1281u) throw e // database empty result = empty list
            }
            return synchronized(c.rows) { c.rows.toList() }
        } finally {
            collectors -= c
        }
    }

    private var permCache: List<Row>? = null

    /** All permissions of the server (`permid`, `permname`, `permdesc`), cached per connection. */
    suspend fun permissionNames(): List<Row> =
        permCache ?: query("permissionlist", emptyMap(), "notifypermissionlist").filter { it.containsKey("permname") }.also { permCache = it }

    suspend fun command(name: String, args: Map<String, String> = emptyMap()) = await(client.sendCommand(name, args.map { KeyValue(it.key, it.value) }))

    suspend fun commandList(name: String, parts: List<Map<String, String>>) =
        await(client.sendCommandList(name, parts.map { p -> p.map { KeyValue(it.key, it.value) } }))

    suspend fun download(channel: ULong, path: String, password: String, dest: File): String? {
        val id = client.downloadFile(channel, path, password, dest.absolutePath)
        return awaitTransfer(id, path.substringAfterLast('/'), false)
    }

    suspend fun upload(channel: ULong, path: String, password: String, src: File, overwrite: Boolean = true): String? {
        val id = client.uploadFile(channel, path, password, src.absolutePath, overwrite)
        return awaitTransfer(id, path.substringAfterLast('/'), true)
    }

    private suspend fun awaitTransfer(id: UInt, name: String, upload: Boolean): String? {
        val d = transferWaits.getOrPut(id) { CompletableDeferred() }
        _transfers.update { it + (id to Transfer(id, name, upload, 0, 0, false, null)) }
        val err = d.await()
        transferWaits.remove(id)
        return err
    }

    fun clearTransfer(id: UInt) = _transfers.update { it - id }

    /** Uploads the avatar chosen in settings (or removes ours) so other clients see it. */
    suspend fun syncAvatar() {
        val f = File(store.settings.value.avatarPath)
        val me = _server.value?.clients?.firstOrNull { it.id == _server.value?.ownClientId }
        if (store.settings.value.avatarPath.isBlank() || !f.exists()) {
            if (me != null && me.avatarHash.isNotBlank()) {
                runCatching { command("ftdeletefile", mapOf("cid" to "0", "cpw" to "", "name" to "/avatar")) }
                runCatching { await(client.updateSelf(listOf(KeyValue("client_flag_avatar", "")))) }
            }
            return
        }
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (me?.avatarHash == md5) return
        if (upload(0u, "/avatar", "", f) == null) runCatching { await(client.updateSelf(listOf(KeyValue("client_flag_avatar", md5)))) }
    }

    private fun now() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun addLog(text: String, kind: EventKind = EventKind.OTHER) =
        _log.update { (it + LogLine(now(), text.replace(SPACER_TAG, ""), kind)).takeLast(1000) }

    private fun emptyChats(): Map<ChatKey, List<ChatLine>> =
        mapOf(ChatKey.Server to emptyList(), ChatKey.Channel to emptyList(), ChatKey.Pokes to emptyList())

    // ---- core callbacks ----
    // The core calls these on its network thread; doing the work there delays packet handling (pongs
    // read late show up as ping spikes). Everything is handed to one ordered background queue, and
    // snapshot refreshes are merged while one is pending.

    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val statePending = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onStatus(status: Status, message: String) { scope.launch(serial) { handleStatus(status, message) } }

    override fun onStateChanged() {
        if (statePending.getAndSet(true)) return
        scope.launch(serial) { statePending.set(false); handleStateChanged() }
    }

    override fun onTextMessage(msg: TextMessage) { scope.launch(serial) { handleTextMessage(msg) } }

    override fun onPoke(fromId: UShort, fromName: String, message: String) { scope.launch(serial) { handlePoke(fromId, fromName, message) } }

    override fun onLog(event: LogEvent) { scope.launch(serial) { handleLog(event) } }

    override fun onCommandResult(requestId: UInt, error: CommandFailure?) { scope.launch(serial) { handleCommandResult(requestId, error) } }

    override fun onIdentityChanged(identity: String) { scope.launch(serial) { handleIdentityChanged(identity) } }

    override fun onNotification(name: String, parts: List<List<KeyValue>>) { scope.launch(serial) { handleNotification(name, parts) } }

    override fun onTransfer(transferId: UInt, done: ULong, total: ULong, finished: Boolean, error: String?) { scope.launch(serial) { handleTransfer(transferId, done, total, finished, error) } }


    private fun handleStatus(status: Status, message: String) {
        Log.i(TAG, "status $status $message")
        if (status == Status.DISCONNECTED && replacing) return
        if (status != Status.DISCONNECTED) _status.value = status to message
        when (status) {
            Status.CONNECTED -> {
                reconnectAttempt = 0
                hadConnected = true
                scope.launch { delay(1500); syncAvatar() }
                scope.launch {
                    while (_status.value.first == Status.CONNECTED) {
                        delay(5000)
                        val st = client.connectionStats()
                        Log.i(TAG, "ping %.0f ms ± %.0f, loss in %.1f%% out %.1f%%, mic %.0f dB".format(st.pingMs, st.pingDeviationMs, st.packetLossIn * 100, st.packetLossOut * 100, client.micLevel()))
                    }
                }
                addLog("Connected to ${bookmark?.label?.ifBlank { null } ?: bookmark?.address}")
                sounds.event(SoundManager.Cue.CONNECTED, null)
            }
            Status.RECONNECTING -> addLog("Connection lost, reconnecting… $message")
            Status.DISCONNECTED -> {
                addLog(if (message.isBlank()) "Disconnected" else "Disconnected: $message")
                _server.value = null
                _talking.value = emptyMap()
                pending.values.forEach { it.complete(CommandFailure(0u, "NotConnected", null)) }
                transferWaits.values.forEach { it.complete("disconnected") }
                if (!userDisconnect) sounds.event(SoundManager.Cue.DISCONNECTED, null)
                if (!userDisconnect && maybeReconnect(message)) {
                    _status.value = Status.RECONNECTING to "Reconnecting in a moment (attempt $reconnectAttempt of $MAX_RECONNECTS)"
                } else {
                    _status.value = status to message
                    _active.value = false
                }
            }
            Status.CONNECTING -> if (message.isNotBlank() && !message.contains(':')) addLog(message)
        }
    }

    /** Schedules a reconnect after a lost connection; false when it gives up. */
    private fun maybeReconnect(reason: String): Boolean {
        val b = bookmark ?: return false
        val kicked = reason.contains("kicked", true) || reason.contains("banned", true)
        // Only connections that worked before come back by themselves; a first attempt that fails reports why.
        if (!store.settings.value.autoReconnect || kicked || !hadConnected || reconnectAttempt >= MAX_RECONNECTS) return false
        reconnectAttempt++
        val mine = attempt
        reconnectJob = scope.launch {
            delay(minOf(20_000L, 2_000L * reconnectAttempt))
            if (attempt == mine && !userDisconnect) connect(b)
        }
        return true
    }

    private fun handleStateChanged() {
        val snap = client.snapshot()
        val first = _server.value == null && snap != null
        _server.value = snap
        if (snap != null) icons.prefetch(snap)
        if (first && snap != null) adoptServerLook(snap)
        if (first && snap != null && snap.hostMessage.isNotBlank() && snap.hostMessageMode >= 2u) {
            _hostMessage.tryEmit(snap.hostMessage to (snap.hostMessageMode == 3u.toUByte()))
        }
    }

    /**
     * On joining, a bookmark takes the server's current name and icon,
     * so the list shows what the server looks like.
     */
    private fun adoptServerLook(v: ServerView) {
        val id = bookmark?.id ?: return
        scope.launch {
            val b = store.bookmarks.value.firstOrNull { it.id == id } ?: return@launch
            if (v.name.isNotBlank() && b.label != v.name.trim()) store.upsertBookmark(b.copy(label = v.name.trim()))
            val icon = v.iconId.toLong() and 0xffffffffL
            if (icon == 0L) return@launch
            // Server icons download in the background; wait for this one.
            withTimeoutOrNull(30_000) { icons.icons.first { it.containsKey(icon) } } ?: return@launch
            val dest = File(context.filesDir, "bookmark_icons/$id").apply { parentFile?.mkdirs() }
            runCatching { icons.iconFile(icon).copyTo(dest, overwrite = true) }.onSuccess {
                store.bookmarks.value.firstOrNull { it.id == id }?.let { store.upsertBookmark(it.copy(iconPath = dest.absolutePath)) }
            }
        }
    }

    private fun handleTextMessage(msg: TextMessage) {
        val own = msg.fromId == _server.value?.ownClientId
        val key = when (val t = msg.target) {
            TextTarget.Server -> ChatKey.Server
            TextTarget.Channel -> ChatKey.Channel
            is TextTarget.Client -> {
                val name = _server.value?.clients?.firstOrNull { it.id == t.id }?.name ?: msg.fromName
                openPrivate(t.id, name)
            }
        }
        _composing.update { it - msg.fromId }
        _chats.update { it + (key to ((it[key] ?: emptyList()) + ChatLine(now(), msg.fromName, msg.message, own)).takeLast(1000)) }
        if (!own) {
            _unread.update { it + key }
            if (key is ChatKey.Private) {
                sounds.event(SoundManager.Cue.PRIVATE_MESSAGE, msg.fromName)
                Notifier.message(context, msg.fromName, stripBbcode(msg.message))
            }
        }
    }

    private fun handlePoke(fromId: UShort, fromName: String, message: String) {
        addLog("$fromName poked you: ${stripBbcode(message)}")
        _chats.update { it + (ChatKey.Pokes to ((it[ChatKey.Pokes] ?: emptyList()) + ChatLine(now(), fromName, message, false, fromId)).takeLast(200)) }
        _unread.update { it + ChatKey.Pokes }
        if (store.settings.value.ignorePokes) return
        sounds.event(SoundManager.Cue.POKE, fromName)
        Notifier.poke(context, fromName, stripBbcode(message))
        _pokes.tryEmit(Poke(fromName, fromId, message))
    }

    override fun onTalkStatus(clientId: UShort, talking: Boolean, whisper: Boolean) {
        _talking.update { if (talking) it + (clientId to whisper) else it - clientId }
    }

    private fun handleLog(event: LogEvent) {
        addLog(event.text, event.kind)
        sounds.log(event)
    }

    private fun handleCommandResult(requestId: UInt, error: CommandFailure?) {
        // The result may arrive before fire()/await() registered; keep it for them briefly.
        val d = pending.getOrPut(requestId) { CompletableDeferred() }
        d.complete(error)
        scope.launch { delay(60_000); pending.remove(requestId, d) }
    }

    private fun handleIdentityChanged(identity: String) {
        store.identities.value.firstOrNull { it.id == identityId }?.let { store.upsertIdentity(it.copy(data = identity)) }
    }

    private fun handleNotification(name: String, parts: List<List<KeyValue>>) {
        val rows = parts.map { p -> p.associate { it.key to it.value } }
        for (c in collectors) if (name in c.names) synchronized(c.rows) { c.rows += rows }
        when (name) {
            "notifyclientchatcomposing" -> rows.firstOrNull()?.get("clid")?.toUShortOrNull()?.let { id ->
                _composing.update { it + id }
                scope.launch { delay(4000); _composing.update { it - id } }
            }
        }
        _notifications.tryEmit(name to rows)
    }

    private fun handleTransfer(transferId: UInt, done: ULong, total: ULong, finished: Boolean, error: String?) {
        _transfers.update { m ->
            val t = m[transferId] ?: Transfer(transferId, "", false, 0, 0, false, null)
            m + (transferId to t.copy(done = done.toLong(), total = total.toLong(), finished = finished, error = error))
        }
        if (finished) {
            (transferWaits[transferId] ?: CompletableDeferred<String?>().also { transferWaits[transferId] = it }).complete(error)
        }
    }

    companion object {
        private const val TAG = "Session"
        private const val MAX_RECONNECTS = 5
        /** "[cspacer0]" etc. in channel names; the log shows the plain name. */
        private val SPACER_TAG = Regex("""\[[*clr]?spacer[^\]]*]""")
    }
}
