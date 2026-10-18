package com.tsrewrite.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tsrewrite.client.Identity
import com.tsrewrite.client.Store
import uniffi.tscore.createIdentity
import uniffi.tscore.identityLevel
import uniffi.tscore.identityUid
import uniffi.tscore.improveIdentity

@Composable
fun IdentitiesScreen(store: Store, onBack: () -> Unit) {
    val identities by store.identities.collectAsState()
    val settings by store.settings.collectAsState()
    var rename by remember { mutableStateOf<Identity?>(null) }
    var nickname by remember { mutableStateOf<Identity?>(null) }
    var importing by remember { mutableStateOf(false) }
    var improving by remember { mutableStateOf<Identity?>(null) }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val context = LocalContext.current

    Page("Identities", onBack, actions = {
        IconButton(onClick = { importing = true }) { Icon(Icons.Default.Download, "Import") }
        IconButton(onClick = { store.upsertIdentity(Identity(name = "Identity ${identities.size + 1}", data = createIdentity())) }) { Icon(Icons.Default.Add, "New identity") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item(key = "myteamspeak") { MyTeamSpeakCard(store) }
            item(key = "sync") { IdentitySyncCard(store) }
            items(identities, key = { it.id }) { i ->
                val level = remember(i.data) { runCatching { identityLevel(i.data).toInt() }.getOrDefault(-1) }
                val uid = remember(i.data) { runCatching { identityUid(i.data) }.getOrDefault("invalid") }
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(Ts.Surface, RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(36.dp).background(Ts.SurfaceHighest, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Icon(if (i.id == settings.defaultIdentityId) Icons.Default.Star else Icons.Default.Badge, null, tint = if (i.id == settings.defaultIdentityId) Ts.Yellow else Ts.TextDim)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(i.name, fontWeight = FontWeight.Medium)
                        Text(uid, style = MaterialTheme.typography.bodySmall, color = Ts.TextDim, maxLines = 1)
                        Text("Security level $level" + (i.nickname.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Ts.TextFaint)
                        if (busy[i.id] == true) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Ts.TextDim) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Make default") }, onClick = { menu = false; store.updateSettings(settings.copy(defaultIdentityId = i.id)) })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename = i })
                        DropdownMenuItem(text = { Text("Nickname for this identity") }, onClick = { menu = false; nickname = i })
                        DropdownMenuItem(text = { Text("Improve security level") }, onClick = { menu = false; improving = i })
                        DropdownMenuItem(text = { Text("Copy identity") }, onClick = {
                            menu = false
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("identity", i.data))
                            Toast.makeText(context, "Identity copied", Toast.LENGTH_SHORT).show()
                        })
                        DropdownMenuItem(text = { Text("Share as TS3 export") }, onClick = {
                            menu = false
                            val ini = "[Identity]\nid=${i.name}\nidentity=\"${i.data}\"\nnickname=${i.nickname}\n"
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ini), "Export identity"))
                        })
                        if (identities.size > 1) DropdownMenuItem(text = { Text("Delete", color = Ts.Red) }, onClick = { menu = false; store.deleteIdentity(i.id) })
                    }
                }
            }
        }
    }

    rename?.let { i -> TextInputDialog("Name", i.name, onDismiss = { rename = null }) { store.upsertIdentity(i.copy(name = it)); rename = null } }
    nickname?.let { i -> TextInputDialog("Nickname", i.nickname, onDismiss = { nickname = null }) { store.upsertIdentity(i.copy(nickname = it)); nickname = null } }
    if (importing) {
        TextInputDialog("Paste identity or TS3 export", "", multiline = true, onDismiss = { importing = false }) { text ->
            importing = false
            val data = Regex("""identity="?([0-9]+V[^"\s]+)""").find(text)?.groupValues?.get(1) ?: text.trim()
            val name = Regex("""(?m)^id=(.+)$""").find(text)?.groupValues?.get(1)?.trim() ?: "Imported"
            val nick = Regex("""(?m)^nickname=(.*)$""").find(text)?.groupValues?.get(1)?.trim() ?: ""
            if (runCatching { identityUid(data) }.isSuccess) store.upsertIdentity(Identity(name = name, data = data, nickname = nick))
            else Toast.makeText(context, "Not a valid identity", Toast.LENGTH_SHORT).show()
        }
    }
    improving?.let { i ->
        var target by remember { mutableStateOf("25") }
        AlertDialog(
            onDismissRequest = { improving = null },
            title = { Text("Improve security level") },
            text = { Field("Target level (each level doubles the time)", target, KeyboardType.Number) { target = it.filter(Char::isDigit) } },
            confirmButton = {
                TextButton(onClick = {
                    improving = null
                    val lvl = target.toIntOrNull()?.coerceIn(1, 60) ?: return@TextButton
                    busy[i.id] = true
                    Thread {
                        runCatching { improveIdentity(i.data, lvl.toUByte()) }.getOrNull()?.let { store.upsertIdentity(i.copy(data = it)) }
                        busy.remove(i.id)
                    }.start()
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { improving = null }) { Text("Cancel") } },
        )
    }
}
