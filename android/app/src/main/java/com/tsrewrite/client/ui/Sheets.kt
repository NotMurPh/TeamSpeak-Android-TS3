package com.tsrewrite.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tsrewrite.client.Session
import uniffi.tscore.WhisperTarget

/** Per-client volume remembered for the app session. */
val clientVolumes = mutableStateMapOf<UShort, Float>()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(session: Session, go: (SPage) -> Unit, onDismiss: () -> Unit) {
    var keyPrompt by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ts.Surface) {
        SheetColumn {
            val v by session.server.collectAsState()
            Text(v?.name ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            ActionRow(Icons.Default.Info, "Server info") { onDismiss(); go(SPage.ServerInfo) }
            ActionRow(Icons.Default.Edit, "Edit virtual server") { onDismiss(); go(SPage.EditServer) }
            ActionRow(Icons.Default.Add, "Create channel") { onDismiss(); go(SPage.EditChannel(null, 0u)) }
            ActionRow(Icons.Default.Groups, "Server groups") { onDismiss(); go(SPage.ServerGroups) }
            ActionRow(Icons.Default.Groups, "Channel groups") { onDismiss(); go(SPage.ChannelGroups) }
            ActionRow(Icons.Default.Gavel, "Ban list") { onDismiss(); go(SPage.Bans) }
            ActionRow(Icons.Default.Report, "Complaints") { onDismiss(); go(SPage.Complaints) }
            ActionRow(Icons.Default.Password, "Temporary passwords") { onDismiss(); go(SPage.TempPasswords) }
            ActionRow(Icons.Default.Key, "Privilege keys") { onDismiss(); go(SPage.PrivilegeKeys) }
            ActionRow(Icons.Default.Security, "Use privilege key") { keyPrompt = true }
        }
    }
    if (keyPrompt) TextInputDialog("Privilege key", "", onDismiss = { keyPrompt = false }) {
        keyPrompt = false; onDismiss(); session.fire(session.client.usePrivilegeKey(it.trim()))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSheet(session: Session, id: ULong, go: (SPage) -> Unit, onDismiss: () -> Unit) {
    val v by session.server.collectAsState()
    val c = v?.channels?.firstOrNull { it.id == id } ?: return onDismiss()
    var pw by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ts.Surface) {
        SheetColumn {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelIcon(if (c.hasPassword) ChannelState.PASSWORD else ChannelState.OPEN, c.subscribed, 22.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(c.name, style = MaterialTheme.typography.titleMedium)
                    if (c.topic.isNotBlank()) Text(c.topic, style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                }
            }
            if (c.id != v?.ownChannelId) ActionRow(Icons.AutoMirrored.Filled.Login, "Join channel") {
                if (c.hasPassword) pw = true else { session.fire(session.client.moveSelf(c.id, null)); onDismiss() }
            }
            WhisperHoldRow(session, "Hold to whisper to channel", WhisperTarget(listOf(c.id), emptyList()))
            ActionRow(if (c.subscribed) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (c.subscribed) "Unsubscribe" else "Subscribe") {
                session.fire(session.client.subscribe(c.id, !c.subscribed)); onDismiss()
            }
            ActionRow(Icons.Default.Info, "Channel info") { onDismiss(); go(SPage.ChannelInfo(c.id)) }
            ActionRow(Icons.Default.Folder, "File browser") { onDismiss(); go(SPage.Files(c.id)) }
            HorizontalDivider(color = Ts.Outline)
            ActionRow(Icons.Default.Add, "Create sub-channel") { onDismiss(); go(SPage.EditChannel(null, c.id)) }
            ActionRow(Icons.Default.Edit, "Edit channel") { onDismiss(); go(SPage.EditChannel(c.id, c.parentId)) }
            ActionRow(Icons.Default.Security, "Channel permissions") { onDismiss(); go(SPage.Permissions(PermTarget.Channel(c.id, c.name))) }
            ActionRow(Icons.Default.Delete, "Delete channel", danger = true) { delete = true }
        }
    }
    if (pw) TextInputDialog("Channel password", "", password = true, onDismiss = { pw = false }) {
        pw = false; session.fire(session.client.moveSelf(c.id, it)); onDismiss()
    }
    if (delete) ConfirmDialog("Delete channel", "Delete \"${c.name}\" and its sub-channels?", "Delete", { delete = false }) {
        delete = false; session.fire(session.client.deleteChannel(c.id, true)); onDismiss()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientSheet(session: Session, id: UShort, go: (SPage) -> Unit, onDismiss: () -> Unit) {
    val v by session.server.collectAsState()
    val cl = v?.clients?.firstOrNull { it.id == id } ?: return onDismiss()
    val own = cl.id == v?.ownClientId
    val avatars by session.icons.avatars.collectAsState()
    LaunchedEffect(cl.id) { session.icons.avatar(cl.uid, cl.avatarHash) }
    var prompt by remember { mutableStateOf<String?>(null) }
    var ban by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(clientVolumes[cl.id] ?: 1f) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ts.Surface) {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val av = avatars[cl.avatarHash]
                if (av != null) Image(av, null, Modifier.size(48.dp).clip(CircleShape))
                else Box(Modifier.size(48.dp).background(Ts.SurfaceHighest, CircleShape), contentAlignment = Alignment.Center) {
                    Text(cl.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(cl.name, style = MaterialTheme.typography.titleMedium)
                    val groupNames = cl.serverGroups.mapNotNull { g -> v?.serverGroups?.firstOrNull { it.id == g }?.name }
                    Text(groupNames.joinToString(" · ").ifBlank { "No groups" }, style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                }
            }
            if (own) {
                ActionRow(Icons.Default.Person, "Change nickname") { prompt = "nick" }
                ActionRow(Icons.Default.Edit, "Set description") { prompt = "desc" }
                ActionRow(Icons.Default.Mic, if (cl.talkRequest) "Cancel talk power request" else "Request talk power") {
                    if (cl.talkRequest) { session.fire(session.client.requestTalkPower(false, "")); onDismiss() } else prompt = "talk"
                }
                ActionRow(Icons.Default.Star, if (cl.isChannelCommander) "Stop being channel commander" else "Become channel commander") {
                    session.fire(session.client.setChannelCommander(!cl.isChannelCommander)); onDismiss()
                }
            } else {
                ActionRow(Icons.AutoMirrored.Filled.Chat, "Send message") { onDismiss(); go(SPage.Chat(session.openPrivate(cl.id, cl.name))) }
                ActionRow(Icons.Default.NotificationsActive, "Poke") { prompt = "poke" }
                WhisperHoldRow(session, "Hold to whisper", WhisperTarget(emptyList(), listOf(cl.id)))
                SliderRow("Volume", volume, 0f..2f, "${(volume * 100).toInt()}%") {
                    volume = it; clientVolumes[cl.id] = it; session.client.setClientVolume(cl.id, it)
                }
                ActionRow(if (cl.locallyMuted) Icons.Default.VolumeUp else Icons.Default.VolumeOff, if (cl.locallyMuted) "Unmute locally" else "Mute locally") {
                    session.client.setClientLocallyMuted(cl.id, !cl.locallyMuted)
                }
            }
            ActionRow(Icons.Default.Info, "Client info") { onDismiss(); go(SPage.ClientInfo(cl.id)) }
            HorizontalDivider(color = Ts.Outline)
            if (!own && cl.channelId != v?.ownChannelId) ActionRow(Icons.Default.SwapHoriz, "Move to my channel") {
                v?.let { session.fire(session.client.moveClient(cl.id, it.ownChannelId, null)) }; onDismiss()
            }
            if (!own) ActionRow(Icons.Default.Mic, if (cl.isTalker) "Revoke talk power" else "Grant talk power") {
                session.fire(session.client.editClient(cl.id, listOf(uniffi.tscore.KeyValue("client_is_talker", if (cl.isTalker) "0" else "1")))); onDismiss()
            }
            ActionRow(Icons.Default.Groups, "Server & channel groups") { groups = true }
            ActionRow(Icons.Default.AdminPanelSettings, "Client permissions") { onDismiss(); go(SPage.Permissions(PermTarget.Client(cl.databaseId, cl.name))) }
            if (!own) {
                ActionRow(Icons.Default.Report, "Complain") { prompt = "complain" }
                ActionRow(Icons.AutoMirrored.Filled.ExitToApp, "Kick from channel", danger = true) { prompt = "kickc" }
                ActionRow(Icons.AutoMirrored.Filled.ExitToApp, "Kick from server", danger = true) { prompt = "kicks" }
                ActionRow(Icons.Default.Block, "Ban", danger = true) { ban = true }
            }
        }
    }

    when (prompt) {
        "nick" -> TextInputDialog("Nickname", cl.name, onDismiss = { prompt = null }) { session.fire(session.client.setNickname(it)); prompt = null }
        "desc" -> TextInputDialog("Description", cl.description, onDismiss = { prompt = null }) {
            session.fire(session.client.editClient(cl.id, listOf(uniffi.tscore.KeyValue("client_description", it)))); prompt = null
        }
        "talk" -> TextInputDialog("Talk power request", "", onDismiss = { prompt = null }) { session.fire(session.client.requestTalkPower(true, it)); prompt = null; onDismiss() }
        "poke" -> TextInputDialog("Poke ${cl.name}", "", onDismiss = { prompt = null }) { session.fire(session.client.poke(cl.id, it)); prompt = null; onDismiss() }
        "complain" -> TextInputDialog("Complaint about ${cl.name}", "", onDismiss = { prompt = null }) {
            session.fire(session.client.sendCommand("complainadd", listOf(uniffi.tscore.KeyValue("tcldbid", cl.databaseId.toString()), uniffi.tscore.KeyValue("message", it))))
            prompt = null
        }
        "kickc" -> TextInputDialog("Kick reason", "", onDismiss = { prompt = null }) { session.fire(session.client.kickFromChannel(cl.id, it)); prompt = null; onDismiss() }
        "kicks" -> TextInputDialog("Kick reason", "", onDismiss = { prompt = null }) { session.fire(session.client.kickFromServer(cl.id, it)); prompt = null; onDismiss() }
        else -> {}
    }
    if (ban) BanDialog(onDismiss = { ban = false }) { secs, reason -> session.fire(session.client.banClient(cl.id, secs, reason)); ban = false; onDismiss() }
    if (groups) GroupMembershipDialog(session, cl.id) { groups = false }
}

/** Press and hold to whisper to the target; release to stop. */
@Composable
fun WhisperHoldRow(session: Session, text: String, target: WhisperTarget) {
    val whisper by session.whisper.collectAsState()
    val active = whisper == target
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(if (active) Ts.Purple.copy(alpha = 0.3f) else Ts.SurfaceHigh)
            .pointerInput(target) { detectTapGestures(onPress = { session.whisper(target); tryAwaitRelease(); session.whisper(null) }) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(Icons.Default.Mic, null, tint = Ts.Purple)
        Spacer(Modifier.width(14.dp))
        Text(if (active) "Whispering…" else text, color = if (active) Ts.Text else Ts.TextDim)
    }
}

@Composable
fun BanDialog(onDismiss: () -> Unit, onOk: (ULong, String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    val options = listOf("10 minutes" to 600uL, "1 hour" to 3600uL, "1 day" to 86400uL, "1 week" to 604800uL, "Permanent" to 0uL)
    var choice by remember { mutableStateOf(options[1]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ban") },
        text = {
            Column {
                Field("Reason", reason) { reason = it }
                options.forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(choice == o, { choice = o }); Text(o.first) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(choice.second, reason) }) { Text("Ban", color = Ts.Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GroupMembershipDialog(session: Session, clientId: UShort, onDismiss: () -> Unit) {
    val v by session.server.collectAsState()
    val cl = v?.clients?.firstOrNull { it.id == clientId } ?: return onDismiss()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Groups of ${cl.name}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("Server groups", style = MaterialTheme.typography.labelLarge, color = Ts.Blue)
                v?.serverGroups?.filter { !it.isTemplate }?.forEach { g ->
                    val member = g.id in cl.serverGroups
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(member, {
                            session.fire(if (member) session.client.removeServerGroup(g.id, cl.databaseId) else session.client.addServerGroup(g.id, cl.databaseId))
                        })
                        Text(g.name)
                    }
                }
                Spacer(Modifier.size(8.dp))
                Text("Channel group (current channel)", style = MaterialTheme.typography.labelLarge, color = Ts.Blue)
                v?.channelGroups?.filter { !it.isTemplate }?.forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(cl.channelGroup == g.id, { session.fire(session.client.setChannelGroup(g.id, cl.channelId, cl.databaseId)) })
                        Text(g.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
