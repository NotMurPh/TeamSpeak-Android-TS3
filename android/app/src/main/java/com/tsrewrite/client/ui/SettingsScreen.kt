package com.tsrewrite.client.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tsrewrite.client.MicTest
import com.tsrewrite.client.Session
import com.tsrewrite.client.Settings
import com.tsrewrite.client.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.tscore.VoiceMode
import java.io.File

@Composable
fun SettingsPage(store: Store, session: Session, onBack: () -> Unit) {
    val s by store.settings.collectAsState()
    val connected by session.server.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun update(f: (Settings) -> Settings) { store.updateSettings(f(store.settings.value)); session.applySettings() }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bmp = decodeUpright(context, uri) ?: return@rememberLauncherForActivityResult
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, 256, 256, true)
        val f = File(context.filesDir, "avatar.png")
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        update { it.copy(avatarPath = f.absolutePath) }
        if (connected != null) scope.launch { session.syncAvatar() }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) context.contentResolver.openOutputStream(uri)?.use { it.write(store.exportBackup().toByteArray()) }
            ?.also { Toast.makeText(context, "Backup saved", Toast.LENGTH_SHORT).show() }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
        Toast.makeText(context, if (store.importBackup(text)) "Backup restored" else "Not a TS Client backup", Toast.LENGTH_SHORT).show()
    }

    Page("Settings", onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Card("Profile") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val avatar = remember(s.avatarPath) { runCatching { BitmapFactory.decodeFile(s.avatarPath)?.asImageBitmap() }.getOrNull() }
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Ts.SurfaceHighest).clickable {
                        avatarPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, contentAlignment = Alignment.Center) {
                        if (avatar != null) Image(avatar, "Avatar", Modifier.size(64.dp), contentScale = ContentScale.Crop)
                        else Text(s.defaultNickname.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Field("Nickname", s.defaultNickname) { v -> update { it.copy(defaultNickname = v) } }
                        Row {
                            androidx.compose.material3.TextButton(onClick = {
                                avatarPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) { Text(if (avatar == null) "Add avatar" else "Change avatar") }
                            if (avatar != null) androidx.compose.material3.TextButton(onClick = {
                                File(s.avatarPath).delete(); update { it.copy(avatarPath = "") }
                                if (connected != null) scope.launch { session.syncAvatar() }
                            }) { Text("Remove", color = Ts.Red) }
                        }
                    }
                }
                Hint("Used on every server unless a bookmark sets its own nickname. The avatar is shown to others on each server you join.")
            }

            Card("How you talk") {
                ModeOption(Icons.Rounded.TouchApp, "Push to talk", "Hold the talk button (or a hardware key) to speak", s.voiceMode == VoiceMode.PUSH_TO_TALK) {
                    update { it.copy(voiceMode = VoiceMode.PUSH_TO_TALK) }
                }
                ModeOption(Icons.Rounded.GraphicEq, "Voice activation", "Transmits automatically when you speak", s.voiceMode == VoiceMode.VOICE_ACTIVATION) {
                    update { it.copy(voiceMode = VoiceMode.VOICE_ACTIVATION) }
                }
                ModeOption(Icons.Rounded.Mic, "Always on", "Microphone is always open", s.voiceMode == VoiceMode.CONTINUOUS) {
                    update { it.copy(voiceMode = VoiceMode.CONTINUOUS) }
                }
                if (s.voiceMode == VoiceMode.PUSH_TO_TALK) {
                    Toggle("Tap to toggle", "Tap once to start talking and again to stop", s.pttToggle) { v -> update { it.copy(pttToggle = v) } }
                    Toggle("Volume keys", "Hold volume up or down to talk while the app is open", s.volumeKeyPtt) { v -> update { it.copy(volumeKeyPtt = v) } }
                    Toggle("Headphone button", "The play/pause button on headphones toggles talking", s.headsetButtonPtt) { v -> update { it.copy(headsetButtonPtt = v) } }
                }
                if (s.voiceMode == VoiceMode.VOICE_ACTIVATION) {
                    Labeled("Sensitivity", "${s.vadThreshold.toInt()} dB") {
                        androidx.compose.material3.Slider(s.vadThreshold, { v -> update { it.copy(vadThreshold = v) } }, valueRange = -70f..-10f)
                    }
                    LevelMeter(session, store, connected != null)
                    Hint("Speak normally: the bar turns green when your voice is loud enough to transmit.")
                }
            }

            Card("Sound") {
                Labeled("Microphone volume", "%.1fx".format(s.micGain)) {
                    androidx.compose.material3.Slider(s.micGain, { v -> update { it.copy(micGain = v) } }, valueRange = 0.2f..4f)
                }
                Labeled("Playback volume", "${(s.volume * 100).toInt()}%") {
                    androidx.compose.material3.Slider(s.volume, { v -> update { it.copy(volume = v) } }, valueRange = 0f..2f)
                }
                Toggle("Use headphone mic", "Talk through your headphones' mic. LE Audio earbuds keep full sound quality; older Bluetooth earbuds switch to call quality while their mic is used", s.useHeadsetMic) { v -> update { it.copy(useHeadsetMic = v) } }
                Toggle("Use loudspeaker", "When no headphones are connected. Off plays through the phone's top speaker, like a call", s.speakerphone) { v -> session.setSpeakerphone(v) }
                if (connected == null) MicTestRow(store)
            }

            Card("Announcements") {
                Toggle("Spoken announcements", "A voice tells you what happens in your channel", s.announceEvents) { v -> update { it.copy(announceEvents = v) } }
                if (s.announceEvents) {
                    Toggle("Someone joins", null, s.announceJoins) { v -> update { it.copy(announceJoins = v) } }
                    Toggle("Someone leaves", null, s.announceLeaves) { v -> update { it.copy(announceLeaves = v) } }
                    Toggle("Someone switches channel", null, s.announceMoves) { v -> update { it.copy(announceMoves = v) } }
                    Toggle("Kicks and bans", null, s.announceKicks) { v -> update { it.copy(announceKicks = v) } }
                    Toggle("Pokes", "\"You were poked\"", s.announcePokes) { v -> update { it.copy(announcePokes = v) } }
                    Toggle("Private messages", null, s.announceMessages) { v -> update { it.copy(announceMessages = v) } }
                    Toggle("My mute buttons", "Say \"microphone muted\" and so on", s.announceSelf) { v -> update { it.copy(announceSelf = v) } }
                }
                Toggle("Sounds", "TeamSpeak's sounds for pokes and private messages", s.playSounds) { v -> update { it.copy(playSounds = v) } }
                Toggle("Vibrate on poke", null, s.vibrateOnPoke) { v -> update { it.copy(vibrateOnPoke = v) } }
                Toggle("Notifications", "Pokes and private messages while the app is in the background", s.systemNotifications) { v -> update { it.copy(systemNotifications = v) } }
            }

            Card("Privacy") {
                Toggle("Ignore pokes", "Pokes are only written to the server log", s.ignorePokes) { v -> update { it.copy(ignorePokes = v) } }
                Toggle("Ignore whispers", "Do not play whispered voice", s.blockWhispers) { v -> update { it.copy(blockWhispers = v) } }
            }

            Card("Channel list") {
                Labeled("Text size", "${(s.treeTextScale * 100).toInt()}%") {
                    androidx.compose.material3.Slider(s.treeTextScale, { v -> update { it.copy(treeTextScale = v) } }, valueRange = 0.8f..1.5f)
                }
                Toggle("Group icons", null, s.showGroupIcons) { v -> update { it.copy(showGroupIcons = v) } }
                Toggle("myTeamSpeak badges", null, s.showBadges) { v -> update { it.copy(showBadges = v) } }
                Toggle("Country flags", null, s.showFlags) { v -> update { it.copy(showFlags = v) } }
                Toggle("Chat timestamps", null, s.chatTimestamps) { v -> update { it.copy(chatTimestamps = v) } }
            }

            Card("Connection") {
                Toggle("Reconnect automatically", "After a lost connection, not after kicks or bans", s.autoReconnect) { v -> update { it.copy(autoReconnect = v) } }
                Toggle("Keep screen on", "While connected", s.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
                Toggle("Low-latency Wi-Fi", "Stops Wi-Fi power saving while connected for a steady ping. Uses a little more battery; Android applies it while the app is open", s.lowLatencyWifi) { v -> update { it.copy(lowLatencyWifi = v) } }
                Field("Leave message", s.quitMessage) { v -> update { it.copy(quitMessage = v) } }
            }

            Card("Backup") {
                Hint("Servers, identities and settings in one file. Identities are your accounts on servers, so keep the file private.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { exporter.launch("ts-client-backup.json") }, Modifier.weight(1f)) { Text("Export") }
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/*")) }, Modifier.weight(1f)) { Text("Import") }
                }
            }

            Text("TS Client ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}",
                style = MaterialTheme.typography.bodySmall, color = Ts.TextFaint, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ts.Surface, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Ts.Blue, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
        }
        androidx.compose.material3.Switch(checked, onChange)
    }
}

@Composable
private fun Labeled(title: String, value: String?, content: @Composable () -> Unit) {
    Column {
        Row { Text(title, Modifier.weight(1f)); if (value != null) Text(value, color = Ts.TextDim, style = MaterialTheme.typography.bodySmall) }
        content()
    }
}

@Composable
private fun ModeOption(icon: ImageVector, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Ts.Blue else Ts.Outline), RoundedCornerShape(14.dp))
            .background(if (selected) Ts.Blue.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) Ts.Blue else Ts.TextDim)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
        }
    }
}

@Composable
private fun LevelMeter(session: Session, store: Store, connected: Boolean) {
    val context = LocalContext.current
    val s by store.settings.collectAsState()
    var level by remember { mutableFloatStateOf(-100f) }
    val test = remember { MicTest(context, store) }
    DisposableEffect(connected) {
        if (!connected) test.start(silent = true)
        onDispose { test.stop() }
    }
    LaunchedEffect(connected) { while (true) { level = if (connected) session.client.micLevel() else test.levelDb; delay(70) } }
    LinearProgressIndicator(
        progress = { ((level + 70f) / 60f).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        color = if (level >= s.vadThreshold) Ts.Green else Ts.Blue,
        trackColor = Ts.SurfaceHighest,
    )
}

@Composable
private fun MicTestRow(store: Store) {
    val context = LocalContext.current
    val s by store.settings.collectAsState()
    val test = remember { MicTest(context, store) }
    var testing by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(-100f) }
    DisposableEffect(Unit) { onDispose { test.stop() } }
    LaunchedEffect(s) { test.refresh() }
    LaunchedEffect(testing) { while (testing) { level = test.levelDb; delay(70) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (testing) Button(onClick = { test.stop(); testing = false }) { Text("Stop") }
        else OutlinedButton(onClick = { test.start(); testing = true }) { Text("Hear yourself") }
        Spacer(Modifier.width(12.dp))
        if (testing) LinearProgressIndicator(progress = { ((level + 70f) / 60f).coerceIn(0f, 1f) }, Modifier.weight(1f), trackColor = Ts.SurfaceHighest)
    }
}

/** Decodes a picked photo the right way up (camera photos store their rotation separately). */
private fun decodeUpright(context: android.content.Context, uri: android.net.Uri): Bitmap? = runCatching {
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        // ImageDecoder applies the EXIF orientation itself.
        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            val max = maxOf(info.size.width, info.size.height)
            if (max > 1024) d.setTargetSampleSize(max / 1024)
        }
    } else {
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val o = context.contentResolver.openInputStream(uri)?.use {
            android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        }
        val degrees = when (o) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, android.graphics.Matrix().apply { postRotate(degrees) }, true)
    }
}.getOrNull()
