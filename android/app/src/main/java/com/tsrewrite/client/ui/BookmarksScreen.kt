package com.tsrewrite.client.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tsrewrite.client.Bookmark
import com.tsrewrite.client.Session
import com.tsrewrite.client.Store
import com.tsrewrite.client.Updater

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookmarksScreen(
    store: Store, session: Session, updater: Updater, pendingLink: MutableState<Bookmark?>, lastError: String,
    onIdentities: () -> Unit, onSettings: () -> Unit,
) {
    val bookmarks by store.bookmarks.collectAsState()
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    pendingLink.value?.let { editing = it; pendingLink.value = null }
    androidx.compose.runtime.LaunchedEffect(Unit) { updater.check() }

    Scaffold(
        containerColor = Ts.Bg,
        topBar = {
            TopAppBar(
                title = { Text("TS Client", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ts.Bg),
                actions = {
                    IconButton(onClick = onIdentities) { Icon(Icons.Default.Badge, "Identities") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.FloatingActionButton(onClick = { editing = Bookmark() }, containerColor = Ts.Blue,
                contentColor = androidx.compose.ui.graphics.Color.White, shape = androidx.compose.foundation.shape.CircleShape) {
                Icon(Icons.Default.Add, "Add server")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            UpdateCard(updater, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            if (lastError.isNotBlank()) {
                Text(lastError, color = Ts.Red, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().background(Ts.Red.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(12.dp))
            }
            if (bookmarks.isEmpty()) {
                EmptyState("No servers yet.\nTap + to add one.")
                return@Column
            }
            val grouped = bookmarks.groupBy { it.folder }.toSortedMap(compareBy { it.lowercase() })
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                grouped.forEach { (folder, list) ->
                    if (folder.isNotBlank()) item(key = "f:$folder") { SectionTitle(folder) }
                    items(list, key = { it.id }) { b ->
                        var menu by remember { mutableStateOf(false) }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                                .background(Ts.Surface, RoundedCornerShape(14.dp))
                                .combinedClickable(onClick = { session.connect(b) }, onLongClick = { editing = b })
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(40.dp).background(Ts.SurfaceHighest, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                val icon = remember(b.iconPath) {
                                    b.iconPath.takeIf { it.isNotBlank() }?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it)?.let { b -> com.tsrewrite.client.PixelScale.upscale(b).asImageBitmap() } }.getOrNull() }
                                }
                                if (icon != null) CrispImage(icon, 24.dp) else ServerIcon(22.dp)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(TsText.of(b.label.ifBlank { b.address }), fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(
                                    b.address + (b.nickname.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = Ts.TextDim,
                                )
                            }
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Ts.TextDim) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Connect") }, onClick = { menu = false; session.connect(b) })
                                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; editing = b })
                                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; store.upsertBookmark(b.copy(id = java.util.UUID.randomUUID().toString(), label = b.label + " (copy)")) })
                                DropdownMenuItem(text = { Text("Delete", color = Ts.Red) }, onClick = { menu = false; store.deleteBookmark(b.id) })
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { b ->
        BookmarkSheet(store, b, onDismiss = { editing = null }, onSave = { store.upsertBookmark(it); editing = null },
            onConnect = { store.upsertBookmark(it); editing = null; session.connect(it) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkSheet(store: Store, initial: Bookmark, onDismiss: () -> Unit, onSave: (Bookmark) -> Unit, onConnect: (Bookmark) -> Unit) {
    var b by remember { mutableStateOf(initial) }
    val identities by store.identities.collectAsState()
    val settings by store.settings.collectAsState()
    val existing = store.bookmarks.collectAsState().value.any { it.id == initial.id }
    val folders = store.bookmarks.collectAsState().value.map { it.folder }.filter { it.isNotBlank() }.distinct()
    var idMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ts.Surface) {
        Column(Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (existing) "Edit server" else "Add server", style = MaterialTheme.typography.titleLarge)
            Field("Address", b.address, KeyboardType.Uri) { b = b.copy(address = it) }
            androidx.compose.material3.TextButton(onClick = { more = !more }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(if (more) "Fewer options" else "More options", color = Ts.Blue)
            }
            if (more) {
                Field("Nickname (default: ${settings.defaultNickname})", b.nickname) { b = b.copy(nickname = it) }
                Field("Server password", b.serverPassword, password = true) { b = b.copy(serverPassword = it) }
                Box {
                    OutlinedButton(onClick = { idMenu = true }, Modifier.fillMaxWidth()) { Text("Identity: " + store.identity(b.identityId).name) }
                    DropdownMenu(expanded = idMenu, onDismissRequest = { idMenu = false }) {
                        identities.forEach { i -> DropdownMenuItem(text = { Text(i.name) }, onClick = { b = b.copy(identityId = i.id); idMenu = false }) }
                    }
                }
                Field("Default channel", b.channel) { b = b.copy(channel = it) }
                Field("Channel password", b.channelPassword, password = true) { b = b.copy(channelPassword = it) }
                Field("Folder", b.folder) { b = b.copy(folder = it) }
                if (folders.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    folders.take(4).forEach { f -> androidx.compose.material3.AssistChip(onClick = { b = b.copy(folder = f) }, label = { Text(f) }) }
                }
                SwitchRow("Show all channels' users", "Subscribe to every channel after connecting", b.subscribeAll) { b = b.copy(subscribeAll = it) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled = b.address.isNotBlank(), onClick = { onSave(b.withLabel(initial)) }, modifier = Modifier.weight(1f)) { Text("Save") }
                Button(enabled = b.address.isNotBlank(), onClick = { onConnect(b.withLabel(initial)) }, modifier = Modifier.weight(1f)) { Text("Connect") }
            }
        }
    }
}

/** Names come from the server on join; until then (or after the address changed) show the address. */
private fun Bookmark.withLabel(initial: Bookmark) = when {
    label.isBlank() -> copy(label = address.trim())
    address.trim() != initial.address.trim() -> copy(label = address.trim(), iconPath = "")
    else -> this
}
