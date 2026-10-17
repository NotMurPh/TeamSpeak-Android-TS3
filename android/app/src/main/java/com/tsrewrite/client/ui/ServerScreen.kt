package com.tsrewrite.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MarkChatUnread
import com.tsrewrite.client.AudioRoute
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tsrewrite.client.ChatKey
import com.tsrewrite.client.Poke
import com.tsrewrite.client.Session
import com.tsrewrite.client.SoundManager
import com.tsrewrite.client.Store
import com.tsrewrite.client.renderBbcode
import kotlinx.coroutines.delay
import uniffi.tscore.Status
import uniffi.tscore.VoiceMode

/** Navigation inside a connected server. */
sealed class SPage {
    data object Tree : SPage()
    data class Chat(val key: ChatKey) : SPage()
    data object Log : SPage()
    data object ServerInfo : SPage()
    data class ClientInfo(val id: UShort) : SPage()
    data class ChannelInfo(val id: ULong) : SPage()
    data class Files(val channel: ULong) : SPage()
    data object Bans : SPage()
    data object ServerGroups : SPage()
    data object ChannelGroups : SPage()
    data class Permissions(val target: PermTarget) : SPage()
    data object Complaints : SPage()
    data object TempPasswords : SPage()
    data object PrivilegeKeys : SPage()
    data object EditServer : SPage()
    data class EditChannel(val id: ULong?, val parent: ULong) : SPage()
    data class GroupMembers(val sgid: ULong, val name: String) : SPage()
    data object Settings : SPage()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(session: Session, store: Store, badges: com.tsrewrite.client.BadgeStore) {
    val stack = remember { mutableStateListOf<SPage>(SPage.Tree) }
    val page = stack.last()
    fun go(p: SPage) { stack += p }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(stack.size > 1) { back() }

    var poke by remember { mutableStateOf<Poke?>(null) }
    var hostMsg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { session.pokes.collect { poke = it } }
    LaunchedEffect(Unit) { session.hostMessage.collect { hostMsg = it } }
    LaunchedEffect(Unit) { session.errors.collect { snackbar.showSnackbar(it) } }

    Box(Modifier.fillMaxSize()) {
        when (page) {
            SPage.Tree -> TreePage(session, store, badges, ::go)
            is SPage.Chat -> ChatPage(session, page.key, ::back)
            SPage.Log -> LogPage(session, ::back)
            SPage.ServerInfo -> ServerInfoPage(session, ::back, ::go)
            is SPage.ClientInfo -> ClientInfoPage(session, page.id, ::back)
            is SPage.ChannelInfo -> ChannelInfoPage(session, page.id, ::back)
            is SPage.Files -> FileBrowserPage(session, page.channel, ::back)
            SPage.Bans -> BansPage(session, ::back)
            SPage.ServerGroups -> GroupsPage(session, server = true, ::back, ::go)
            SPage.ChannelGroups -> GroupsPage(session, server = false, ::back, ::go)
            is SPage.Permissions -> PermissionsPage(session, page.target, ::back)
            SPage.Complaints -> ComplaintsPage(session, ::back)
            SPage.TempPasswords -> TempPasswordsPage(session, ::back)
            SPage.PrivilegeKeys -> PrivilegeKeysPage(session, ::back)
            SPage.EditServer -> EditServerPage(session, ::back)
            is SPage.EditChannel -> EditChannelPage(session, page.id, page.parent, ::back)
            is SPage.GroupMembers -> GroupMembersPage(session, page.sgid, page.name, ::back)
            SPage.Settings -> SettingsPage(store, session, ::back)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }

    poke?.let { p ->
        AlertDialog(
            onDismissRequest = { poke = null },
            title = { Text("${p.from} poked you") },
            text = { Text(renderBbcode(p.message, Ts.Blue)) },
            confirmButton = { TextButton(onClick = { poke = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { poke = null; go(SPage.Chat(session.openPrivate(p.fromId, p.from))) }) { Text("Reply") } },
        )
    }
    hostMsg?.let { (msg, quit) ->
        AlertDialog(
            onDismissRequest = { hostMsg = null; if (quit) session.disconnect() },
            title = { Text("Server message") },
            text = { Text(renderBbcode(msg, Ts.Blue)) },
            confirmButton = { TextButton(onClick = { hostMsg = null; if (quit) session.disconnect() }) { Text(if (quit) "Disconnect" else "OK") } },
        )
    }
}

@Composable
private fun TreePage(session: Session, store: Store, badgeStore: com.tsrewrite.client.BadgeStore, go: (SPage) -> Unit) {
    val server by session.server.collectAsState()
    val talking by session.talking.collectAsState()
    val icons by session.icons.icons.collectAsState()
    val settings by store.settings.collectAsState()
    val badgeImages by badgeStore.images.collectAsState()
    var selection by remember { mutableStateOf<TreeSel?>(null) }
    var sheet by remember { mutableStateOf<TreeSel?>(null) }
    var awayPrompt by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Ts.Bg,
        topBar = { Header(session, go) { awayPrompt = true } },
        bottomBar = { VoiceDock(session, store) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val v = server
            LaunchedEffect(v) { v?.clients?.forEach { c -> badgeStore.parse(c.badges).forEach(badgeStore::load) } }
            if (v == null) Connecting(session) else ChannelTree(
                v, talking, icons, badgeImages, badgeStore::parse, settings, selection,
                onSelect = { selection = it },
                onActivate = { sel ->
                    when (sel) {
                        is TreeSel.Channel -> v.channels.firstOrNull { it.id == sel.id }?.let { c ->
                            if (c.id != v.ownChannelId) { if (c.hasPassword) sheet = sel else session.fire(session.client.moveSelf(c.id, null)) }
                        }
                        is TreeSel.Client -> if (sel.id != v.ownClientId) go(SPage.Chat(session.openPrivate(sel.id, v.clients.firstOrNull { it.id == sel.id }?.name ?: "")))
                        TreeSel.Server -> go(SPage.ServerInfo)
                    }
                },
                onMenu = { selection = it; sheet = it },
            )
        }
    }

    if (awayPrompt) TextInputDialog("Away message", "", onDismiss = { awayPrompt = false }) { awayPrompt = false; session.setAway(it) }
    sheet?.let { sel ->
        when (sel) {
            TreeSel.Server -> ServerSheet(session, go) { sheet = null }
            is TreeSel.Channel -> ChannelSheet(session, sel.id, go) { sheet = null }
            is TreeSel.Client -> ClientSheet(session, sel.id, go) { sheet = null }
        }
    }
}

/** Server badge, name, a quiet status line (latency, where audio goes) and two actions. */
@Composable
private fun Header(session: Session, go: (SPage) -> Unit, onAway: () -> Unit) {
    val server by session.server.collectAsState()
    val status by session.status.collectAsState()
    val unread by session.unread.collectAsState()
    val icons by session.icons.icons.collectAsState()
    val away by session.away.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var ping by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { while (true) { ping = session.client.connectionStats().pingMs; delay(2000) } }
    val name = server?.name ?: session.bookmark?.label?.ifBlank { null } ?: session.bookmark?.address ?: ""

    Row(
        Modifier.fillMaxWidth().background(Ts.Bg).statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Ts.SurfaceHigh).clickable { go(SPage.ServerInfo) },
            contentAlignment = Alignment.Center,
        ) {
            val img = server?.iconId?.toLong()?.takeIf { it != 0L }?.let { icons[it] }
            if (img != null) CrispImage(img, 24.dp)
            else Text(name.trim().take(1).uppercase(), color = Ts.Blue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(TsText.of(name), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (dot, text) = when (status.first) {
                    Status.CONNECTED -> (if (ping >= 150) Ts.Yellow else Ts.Green) to (if (ping > 0) "${ping.toInt()} ms" else "Connected")
                    Status.CONNECTING -> Ts.Yellow to "Connecting…"
                    Status.RECONNECTING -> Ts.Yellow to "Reconnecting…"
                    Status.DISCONNECTED -> Ts.Red to "Disconnected"
                }
                Box(Modifier.size(7.dp).background(dot, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.labelMedium, color = Ts.TextDim)
                if (away) Text("  ·  Away", style = MaterialTheme.typography.labelMedium, color = Ts.TextDim)
                RouteChip(session)
            }
        }
        // Opens the conversation with something new in it (pokes included), else the channel chat.
        IconButton(onClick = { go(SPage.Chat(unread.firstOrNull() ?: ChatKey.Channel)) }) {
            if (unread.isNotEmpty()) Icon(Icons.Rounded.MarkChatUnread, "Chat, new messages", tint = Ts.Blue)
            else Icon(Icons.AutoMirrored.Rounded.Chat, "Chat", tint = Ts.Text)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Menu", tint = Ts.Text) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val subscribed = server?.channels?.let { cs -> cs.isNotEmpty() && cs.all { it.subscribed } } ?: false
                DropdownMenuItem(
                    text = { Text("Subscribe to all channels") },
                    trailingIcon = { androidx.compose.material3.Switch(subscribed, null, Modifier.padding(start = 8.dp)) },
                    onClick = { menu = false; session.fire(session.client.subscribeAll(!subscribed)) },
                )
                DropdownMenuItem(text = { Text(if (away) "Back from away" else "Set away") }, onClick = { menu = false; if (away) session.setAway(null) else onAway() })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; go(SPage.Settings) })
            }
        }
    }
}

/**
 * Where audio goes. Tapping switches loudspeaker and the phone's top speaker (like a call), or loudspeaker and
 * the headphones when they are connected; the choice lasts for this connection only.
 */
@Composable
private fun RouteChip(session: Session) {
    val route by session.route.collectAsState()
    val r = route ?: return
    val (icon, label) = when (r.output) {
        AudioRoute.Output.SPEAKER -> Icons.AutoMirrored.Rounded.VolumeUp to "Loudspeaker"
        AudioRoute.Output.EARPIECE -> Icons.Rounded.PhoneInTalk to "Phone"
        AudioRoute.Output.HEADSET -> Icons.Rounded.Headphones to "Headphones"
        AudioRoute.Output.BLUETOOTH -> Icons.Rounded.Bluetooth to "Bluetooth"
        AudioRoute.Output.NONE -> return
    }
    Text("  ·  ", style = MaterialTheme.typography.labelMedium, color = Ts.TextFaint)
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable { session.toggleOutput() }.padding(horizontal = 2.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Ts.TextDim, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Ts.TextDim)
        Icon(Icons.Rounded.SwapHoriz, "Switch audio output", tint = Ts.TextFaint, modifier = Modifier.padding(start = 2.dp).size(14.dp))
    }
}

/** Floating dock: mute mic, mute sound, the talk button and leave. */
@Composable
private fun VoiceDock(session: Session, store: Store) {
    val inMuted by session.inputMuted.collectAsState()
    val outMuted by session.outputMuted.collectAsState()
    val settings by store.settings.collectAsState()
    val talking by session.talking.collectAsState()
    val whisper by session.whisper.collectAsState()
    val server by session.server.collectAsState()
    val me = server?.ownClientId
    val active = me != null && talking.containsKey(me)
    var toggled by remember { mutableStateOf(false) }
    val ptt = settings.voiceMode == VoiceMode.PUSH_TO_TALK || whisper != null
    Box(Modifier.fillMaxWidth().background(Ts.Bg).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Ts.Surface)
                .border(1.dp, Ts.Outline.copy(alpha = 0.5f), RoundedCornerShape(32.dp)).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            DockButton(if (inMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic, inMuted, if (inMuted) "Unmute microphone" else "Mute microphone") {
                session.setInputMuted(!inMuted)
                session.sounds.event(if (inMuted) SoundManager.Cue.MIC_UNMUTED else SoundManager.Cue.MIC_MUTED, null)
            }
            DockButton(if (outMuted) Icons.Rounded.HeadsetOff else Icons.Rounded.Headset, outMuted, if (outMuted) "Unmute sound" else "Mute sound") {
                session.setOutputMuted(!outMuted)
                session.sounds.event(if (outMuted) SoundManager.Cue.SOUND_UNMUTED else SoundManager.Cue.SOUND_MUTED, null)
            }
            // Push to talk: "Push to talk", "Talking" while held, disabled while muted.
            // Otherwise it is a status display: "Listening", "Talking", "Muted" or "Deafened".
            var pressed by remember { mutableStateOf(false) }
            val talkingNow = active || (ptt && (pressed || toggled) && !inMuted)
            val deafened = outMuted && whisper == null && !talkingNow
            val (label, icon) = when {
                whisper != null -> "Whispering" to Icons.Rounded.RecordVoiceOver
                inMuted && outMuted -> "Muted & deafened" to Icons.Rounded.HeadsetOff
                inMuted -> "Muted" to Icons.Rounded.MicOff
                talkingNow -> "Talking" to Icons.Rounded.GraphicEq
                deafened -> "Deafened" to Icons.Rounded.HeadsetOff
                ptt -> "Push to talk" to Icons.Rounded.TouchApp
                else -> "Listening" to Icons.Rounded.Hearing
            }
            val target = when {
                whisper != null -> Ts.Purple
                inMuted || deafened -> Ts.Red.copy(alpha = 0.18f)
                talkingNow -> Ts.Green
                ptt -> Ts.Blue
                else -> Ts.SurfaceHigh
            }
            val bg by animateColorAsState(target, label = "talk")
            Row(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(bg)
                    .then(when {
                        ptt && whisper == null && inMuted -> Modifier // disabled while muted
                        ptt -> Modifier.pointerInput(settings.pttToggle, inMuted) {
                            detectTapGestures(onPress = {
                                if (settings.pttToggle) { toggled = !toggled; session.client.setPushToTalk(toggled) }
                                else {
                                    pressed = true; session.client.setPushToTalk(true)
                                    tryAwaitRelease()
                                    pressed = false; session.client.setPushToTalk(false)
                                }
                            })
                        }
                        // Without push to talk it only shows the state; the mic and sound buttons change it.
                        else -> Modifier
                    }),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                val onColor = when {
                    (inMuted || deafened) && whisper == null -> Ts.Red
                    whisper != null || talkingNow || ptt -> Color.White
                    else -> Ts.Text
                }
                Icon(icon, null, tint = onColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, fontWeight = FontWeight.SemiBold, color = onColor, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
            IconButton(onClick = { session.disconnect() }, modifier = Modifier.size(48.dp).background(Ts.Red, CircleShape)) {
                Icon(Icons.Rounded.CallEnd, "Disconnect", tint = Color.White)
            }
        }
    }
}

/** Until the channel list arrives: what is happening, and a way out. */
@Composable
private fun Connecting(session: Session) {
    val status by session.status.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.CircularProgressIndicator(color = Ts.Blue)
        Spacer(Modifier.height(20.dp))
        Text(if (status.first == Status.RECONNECTING) "Reconnecting…" else "Connecting…", style = MaterialTheme.typography.titleMedium)
        // The core reports the address, or what it is doing (e.g. improving the identity level).
        if (status.second.isNotBlank()) {
            Text(status.second, style = MaterialTheme.typography.bodySmall, color = Ts.TextDim,
                modifier = Modifier.padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        androidx.compose.material3.OutlinedButton(onClick = { session.disconnect() }) { Text("Cancel") }
    }
}

@Composable
private fun DockButton(icon: androidx.compose.ui.graphics.vector.ImageVector, off: Boolean, desc: String, onClick: () -> Unit) {
    val bg by animateColorAsState(if (off) Ts.Red.copy(alpha = 0.18f) else Ts.SurfaceHigh, label = "dock")
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).background(bg, CircleShape)) {
        Icon(icon, desc, tint = if (off) Ts.Red else Ts.Text)
    }
}
