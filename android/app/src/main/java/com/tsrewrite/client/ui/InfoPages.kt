package com.tsrewrite.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.History
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tsrewrite.client.ChatKey
import com.tsrewrite.client.Row as DataRow
import com.tsrewrite.client.Session
import com.tsrewrite.client.renderBbcode
import kotlinx.coroutines.delay
import uniffi.tscore.EventKind

@Composable
fun ChatPage(session: Session, initial: ChatKey, onBack: () -> Unit) {
    val chats by session.chats.collectAsState()
    val unread by session.unread.collectAsState()
    val composing by session.composing.collectAsState()
    var current by remember { mutableStateOf(initial) }
    var text by remember { mutableStateOf("") }
    val lines = chats[current].orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(current, lines.size) {
        session.markRead(current)
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    val title = when (val k = current) { ChatKey.Server -> "Server chat"; ChatKey.Channel -> "Channel chat"; ChatKey.Pokes -> "Pokes"; is ChatKey.Private -> k.name }
    Page(title, onBack, actions = {
        if (current is ChatKey.Private) IconButton(onClick = { session.closePrivate(current); current = ChatKey.Channel }) { Icon(Icons.Default.Close, "Close conversation") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            LazyRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chats.keys.toList()) { k ->
                    val label = when (k) { ChatKey.Server -> "Server"; ChatKey.Channel -> "Channel"; ChatKey.Pokes -> "Pokes"; is ChatKey.Private -> k.name }
                    FilterChip(
                        selected = k == current, onClick = { current = k },
                        label = { BadgedBox(badge = { if (k in unread && k != current) Badge() }) { Text(label) } },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Ts.Blue.copy(alpha = 0.25f)),
                    )
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp), state = listState) {
                if (current == ChatKey.Pokes && lines.isEmpty()) item {
                    Text("No pokes yet", color = Ts.TextDim, modifier = Modifier.padding(vertical = 24.dp))
                }
                items(lines) { l ->
                    // A poke opens a private chat with whoever sent it.
                    val reply = if (current == ChatKey.Pokes && l.fromId != null) Modifier.clickable { current = session.openPrivate(l.fromId, l.from) } else Modifier
                    Column(Modifier.fillMaxWidth().then(reply).padding(vertical = 5.dp), horizontalAlignment = if (l.own) Alignment.End else Alignment.Start) {
                        Text("${l.from} · ${l.time.take(5)}", style = MaterialTheme.typography.labelSmall, color = Ts.TextFaint)
                        Surface(
                            shape = RoundedCornerShape(16.dp, 16.dp, if (l.own) 4.dp else 16.dp, if (l.own) 16.dp else 4.dp),
                            color = if (l.own) Ts.BlueDim else Ts.SurfaceHigh,
                        ) {
                            Text(renderBbcode(l.text, if (l.own) androidx.compose.ui.graphics.Color.White else Ts.Blue), Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            val partner = (current as? ChatKey.Private)?.clientId
            if (partner != null && partner in composing) Text("typing…", color = Ts.TextDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 16.dp))
            if (current != ChatKey.Pokes) Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                var lastComposing by remember { mutableStateOf(0L) }
                OutlinedTextField(
                    text, {
                        text = it
                        if (partner != null && System.currentTimeMillis() - lastComposing > 3000) {
                            lastComposing = System.currentTimeMillis(); session.client.chatComposing(partner)
                        }
                    },
                    Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 5, shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Ts.Outline),
                )
                IconButton(enabled = text.isNotBlank(), onClick = { session.send(current, text.trim()); text = "" }) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (text.isNotBlank()) Ts.Blue else Ts.TextFaint)
                }
            }
        }
    }
}

@Composable
fun LogPage(session: Session, onBack: () -> Unit) {
    val log by session.log.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.size - 1) }
    Page("Event log", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), state = listState) {
            items(log) { l ->
                val color = when (l.kind) {
                    EventKind.CLIENT_KICKED_FROM_SERVER, EventKind.CLIENT_BANNED, EventKind.CLIENT_KICKED_FROM_CHANNEL -> Ts.Red
                    EventKind.CLIENT_CONNECTED -> Ts.Green
                    EventKind.CLIENT_DISCONNECTED, EventKind.CLIENT_TIMED_OUT -> Ts.TextDim
                    else -> Ts.Text
                }
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(l.time, color = Ts.TextFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
                    Text(l.text, color = color, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun ServerInfoPage(session: Session, onBack: () -> Unit, go: (SPage) -> Unit) {
    val v by session.server.collectAsState()
    var stats by remember { mutableStateOf(session.client.connectionStats()) }
    // The tree only knows clients in subscribed channels; the server reports the real counts.
    var counts by remember { mutableStateOf<Map<String, String>?>(null) }
    LaunchedEffect(Unit) {
        counts = runCatching { session.query("servergetvariables", emptyMap(), "notifyserverupdated").firstOrNull() }.getOrNull()
    }
    LaunchedEffect(Unit) { while (true) { stats = session.client.connectionStats(); delay(1000) } }
    Page(v?.name ?: "Server", onBack) { pad ->
        val s = v ?: return@Page Loading()
        val banner by session.icons.banner.collectAsState()
        val context = androidx.compose.ui.platform.LocalContext.current
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            banner?.let { f ->
                HostBanner(f, Modifier.fillMaxWidth().padding(16.dp).then(
                    if (s.bannerUrl.isNotBlank()) Modifier.clickable { openUrl(context, s.bannerUrl) } else Modifier))
            }
            if (s.welcomeMessage.isNotBlank()) Text(renderBbcode(s.welcomeMessage, Ts.Blue), Modifier.padding(20.dp))
            SectionTitle("Server")
            KeyValueRow("Name", s.name)
            KeyValueRow("Version", s.version)
            KeyValueRow("Platform", s.platform)
            val c = counts
            val online = c?.get("virtualserver_clientsonline")?.toIntOrNull()?.let { it - (c["virtualserver_queryclientsonline"]?.toIntOrNull() ?: 0) }
                ?: s.clients.count { !it.isQuery }
            val max = c?.get("virtualserver_maxclients")?.toIntOrNull() ?: s.maxClients.toInt()
            val reserved = c?.get("virtualserver_reserved_slots")?.toIntOrNull() ?: 0
            KeyValueRow("Clients", "$online / $max" + if (reserved > 0) " ($reserved reserved)" else "")
            KeyValueRow("Channels", s.channels.size.toString())
            KeyValueRow("Created", formatDate(s.created))
            s.uptimeSecs?.let { KeyValueRow("Uptime", formatDuration(it.toLong())) }
            if (s.buttonUrl.isNotBlank()) Row(Modifier.clickable { openUrl(context, s.buttonUrl) }) { KeyValueRow(s.buttonTooltip.ifBlank { "Host button" }, s.buttonUrl) }
            SectionTitle("Your connection")
            KeyValueRow("Ping", "%.0f ms (± %.0f)".format(stats.pingMs, stats.pingDeviationMs))
            KeyValueRow("Packet loss in", "%.1f %%".format(stats.packetLossIn * 100))
            KeyValueRow("Packet loss out", "%.1f %%".format(stats.packetLossOut * 100))
            KeyValueRow("Packets in / out", "${stats.packetsReceived} / ${stats.packetsSent}")
            KeyValueRow("Data in / out", "${formatBytes(stats.bytesReceived.toLong())} / ${formatBytes(stats.bytesSent.toLong())}")
            KeyValueRow("Bandwidth in / out", "${formatBytes(stats.bandwidthReceivedLastSecond.toLong())}/s / ${formatBytes(stats.bandwidthSentLastSecond.toLong())}/s")
            androidx.compose.material3.OutlinedButton(
                onClick = { go(SPage.Log) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            ) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.History, null, Modifier.size(18.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                Text("Server log")
            }
        }
    }
}

@Composable
fun ClientInfoPage(session: Session, id: UShort, onBack: () -> Unit) {
    val v by session.server.collectAsState()
    val avatars by session.icons.avatars.collectAsState()
    var conn by remember { mutableStateOf<DataRow?>(null) }
    LaunchedEffect(id) {
        session.fire(session.client.requestClientVariables(id))
        conn = runCatching { session.query("getconnectioninfo", mapOf("clid" to id.toString()), "notifyconnectioninfo").firstOrNull() }.getOrNull()
    }
    val cl = v?.clients?.firstOrNull { it.id == id }
    LaunchedEffect(cl?.avatarHash) { cl?.let { session.icons.avatar(it.uid, it.avatarHash) } }
    Page(cl?.name ?: "Client", onBack) { pad ->
        val c = cl ?: return@Page EmptyState("Client left")
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                val av = avatars[c.avatarHash]
                if (av != null) Image(av, null, Modifier.size(72.dp).clip(CircleShape))
                else Box(Modifier.size(72.dp).background(Ts.SurfaceHighest, CircleShape), contentAlignment = Alignment.Center) {
                    Text(c.name.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(c.name, style = MaterialTheme.typography.titleLarge)
                    if (c.country.isNotBlank()) Text("${flagEmoji(c.country)} ${c.country}", color = Ts.TextDim)
                }
            }
            if (c.description.isNotBlank()) Text(c.description, Modifier.padding(horizontal = 20.dp))
            SectionTitle("Client")
            KeyValueRow("Unique ID", c.uid)
            KeyValueRow("Database ID", c.databaseId.toString())
            if (c.version.isNotBlank()) KeyValueRow("Version", c.version)
            if (c.platform.isNotBlank()) KeyValueRow("Platform", c.platform)
            if (c.created > 0) KeyValueRow("First seen", formatDate(c.created))
            if (c.lastConnected > 0) KeyValueRow("Last connected", formatDate(c.lastConnected))
            if (c.totalConnections > 0u) KeyValueRow("Connections", c.totalConnections.toString())
            KeyValueRow("Talk power", c.talkPower.toString())
            v?.let { s ->
                KeyValueRow("Server groups", c.serverGroups.mapNotNull { g -> s.serverGroups.firstOrNull { it.id == g }?.name }.joinToString(", "))
                KeyValueRow("Channel group", s.channelGroups.firstOrNull { it.id == c.channelGroup }?.name ?: "—")
            }
            if (c.away) KeyValueRow("Away", c.awayMessage.ifBlank { "yes" })
            conn?.let { r ->
                SectionTitle("Connection")
                r["connection_ping"]?.let { KeyValueRow("Ping", "$it ms") }
                r["connection_connected_time"]?.toLongOrNull()?.let { KeyValueRow("Connected for", formatDuration(it / 1000)) }
                r["connection_idle_time"]?.toLongOrNull()?.let { KeyValueRow("Idle", formatDuration(it / 1000)) }
                r["connection_client_ip"]?.takeIf { it.isNotBlank() }?.let { KeyValueRow("Address", it) }
                r["connection_server2client_packetloss_total"]?.toDoubleOrNull()?.let { KeyValueRow("Packet loss (in)", "%.2f %%".format(it * 100)) }
                r["connection_client2server_packetloss_total"]?.toDoubleOrNull()?.let { KeyValueRow("Packet loss (out)", "%.2f %%".format(it * 100)) }
                r["connection_bytes_received_total"]?.toLongOrNull()?.let { KeyValueRow("Received", formatBytes(it)) }
                r["connection_bytes_sent_total"]?.toLongOrNull()?.let { KeyValueRow("Sent", formatBytes(it)) }
            }
        }
    }
}

@Composable
fun ChannelInfoPage(session: Session, id: ULong, onBack: () -> Unit) {
    val v by session.server.collectAsState()
    LaunchedEffect(id) { session.fire(session.client.requestChannelDescription(id)) }
    val c = v?.channels?.firstOrNull { it.id == id }
    Page(c?.name ?: "Channel", onBack) { pad ->
        val ch = c ?: return@Page EmptyState("Channel deleted")
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            if (ch.topic.isNotBlank()) Text(ch.topic, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Ts.TextDim)
            ch.description?.takeIf { it.isNotBlank() }?.let { Text(renderBbcode(it, Ts.Blue), Modifier.padding(20.dp)) }
            HorizontalDivider(color = Ts.Outline)
            SectionTitle("Channel")
            KeyValueRow("Type", when { ch.isPermanent -> "Permanent"; ch.isSemiPermanent -> "Semi-permanent"; else -> "Temporary" })
            KeyValueRow("Clients", "${ch.clientCount}${if (ch.maxClients >= 0) " / ${ch.maxClients}" else ""}")
            if (ch.maxFamilyClients >= 0) KeyValueRow("Max family clients", ch.maxFamilyClients.toString())
            KeyValueRow("Codec", "${ch.codec} (quality ${ch.codecQuality})")
            KeyValueRow("Password", if (ch.hasPassword) "yes" else "no")
            KeyValueRow("Needed talk power", ch.neededTalkPower.toString())
            KeyValueRow("Voice encrypted", if (ch.isUnencrypted) "no" else "yes")
            if (ch.deleteDelaySecs > 0u) KeyValueRow("Delete delay", formatDuration(ch.deleteDelaySecs.toLong()))
            KeyValueRow("Subscribed", if (ch.subscribed) "yes" else "no")
            KeyValueRow("Channel ID", ch.id.toString())
        }
    }
}

/** The host banner; animated GIF/WebP banners play (Android 9+). */
@Composable
fun HostBanner(file: java.io.File, modifier: Modifier = Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx -> android.widget.ImageView(ctx).apply { adjustViewBounds = true; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } },
        update = { view ->
            if (view.tag == file.path + file.lastModified()) return@AndroidView
            view.tag = file.path + file.lastModified()
            val d = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 28) android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(file))
                else android.graphics.drawable.BitmapDrawable(view.resources, android.graphics.BitmapFactory.decodeFile(file.path))
            }.getOrNull()
            view.setImageDrawable(d)
            if (android.os.Build.VERSION.SDK_INT >= 28 && d is android.graphics.drawable.AnimatedImageDrawable) { d.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE; d.start() }
        },
        modifier = modifier,
    )
}

fun openUrl(context: android.content.Context, url: String) {
    val u = if (url.contains("://")) url else "https://$url"
    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))) }
}
