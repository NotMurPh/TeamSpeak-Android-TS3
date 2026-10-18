package com.tsrewrite.client.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tsrewrite.client.Identity
import com.tsrewrite.client.Store
import org.json.JSONArray
import org.json.JSONObject
import uniffi.tscore.identityUid

/**
 * Keeps chosen identities in a file the user picks, e.g. on Google Drive through Android's file picker,
 * and brings chosen ones back. Reads its own files, TS3 identity exports (.ini) and app backups.
 */
@Composable
fun IdentitySyncCard(store: Store) {
    val context = LocalContext.current
    val identities by store.identities.collectAsState()
    var uploadPick by remember { mutableStateOf(false) }
    var toUpload by remember { mutableStateOf<List<Identity>>(emptyList()) }
    var found by remember { mutableStateOf<List<Identity>?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) toast(context, if (write(context, uri, toUpload)) "Saved ${plural(toUpload.size)}" else "Couldn't save the file")
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val list = read(context, uri)
        if (list.isEmpty()) toast(context, "No identities in that file") else found = list
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(Ts.Surface, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Identity files", style = MaterialTheme.typography.titleSmall, color = Ts.Blue, fontWeight = FontWeight.SemiBold)
        Text("Keep identities in a file on Google Drive or anywhere else, and bring them to another phone.",
            style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { uploadPick = true }, Modifier.weight(1f)) {
                Icon(Icons.Rounded.CloudUpload, null); Text("Export file", Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = { opener.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, Modifier.weight(1f)) {
                Icon(Icons.Rounded.CloudDownload, null); Text("Import file", Modifier.padding(start = 8.dp))
            }
        }
    }

    if (uploadPick) {
        val all = identities
        PickDialog(
            title = "Export identities",
            note = "Identities are your accounts on servers: keep the file private.",
            items = all.map { it to null }, initiallyChecked = all.toSet(), confirm = "Choose file",
            onDismiss = { uploadPick = false },
        ) { picked ->
            uploadPick = false
            toUpload = picked
            saver.launch(if (picked.size == 1) "${picked[0].name}.json" else "ts-identities.json")
        }
    }
    found?.let { list ->
        val have = identities.mapNotNull { uidOf(it.data) }.toSet()
        val items = list.map { it to (if (uidOf(it.data) in have) "Already on this phone" else null) }
        PickDialog(
            title = "Import identities", note = null, items = items,
            initiallyChecked = items.filter { it.second == null }.map { it.first }.toSet(), confirm = "Add",
            onDismiss = { found = null },
        ) { picked ->
            found = null
            picked.forEach { store.upsertIdentity(Identity(name = it.name, data = it.data, nickname = it.nickname)) }
            toast(context, "Added ${plural(picked.size)}")
        }
    }
}

@Composable
private fun PickDialog(
    title: String, note: String?, items: List<Pair<Identity, String?>>, initiallyChecked: Set<Identity>, confirm: String,
    onDismiss: () -> Unit, onConfirm: (List<Identity>) -> Unit,
) {
    val checked = remember { mutableStateListOf<Identity>().apply { addAll(initiallyChecked) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ts.Surface,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ts.TextDim, modifier = Modifier.padding(bottom = 8.dp)) }
                items.forEach { (i, hint) ->
                    val on = i in checked
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { if (on) checked.remove(i) else checked.add(i) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(on, { if (on) checked.remove(i) else checked.add(i) }, colors = CheckboxDefaults.colors(checkedColor = Ts.Blue))
                        Column(Modifier.weight(1f)) {
                            Text(i.name, fontWeight = FontWeight.Medium)
                            Text(hint ?: uidOf(i.data) ?: "", style = MaterialTheme.typography.bodySmall, color = Ts.TextDim, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = checked.isNotEmpty(), onClick = { onConfirm(items.map { it.first }.filter { it in checked }) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun uidOf(data: String) = runCatching { identityUid(data) }.getOrNull()

private fun plural(n: Int) = if (n == 1) "1 identity" else "$n identities"

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

private fun write(context: Context, uri: Uri, list: List<Identity>): Boolean = runCatching {
    val json = JSONObject().put("format", "ts-client-identities").put("version", 1).put("identities", JSONArray(list.map {
        JSONObject().put("name", it.name).put("identity", it.data).put("nickname", it.nickname)
    }))
    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toString(2).toByteArray()) }
}.isSuccess

/** Identities in this app's sync file, an app backup, or one or more TS3 `[Identity]` exports. */
private fun read(context: Context, uri: Uri): List<Identity> {
    val text = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull() ?: return emptyList()
    val fromJson = runCatching {
        val arr = JSONObject(text).getJSONArray("identities")
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            Identity(name = o.optString("name", "Imported"), data = o.optString("identity").ifBlank { o.optString("data") }, nickname = o.optString("nickname"))
        }
    }.getOrNull()
    val list = fromJson ?: text.split(Regex("""(?m)^\[Identity]""")).mapNotNull { block ->
        val data = Regex("""identity="?([0-9]+V[^"\s]+)""").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
        Identity(
            name = Regex("""(?m)^id=(.+)$""").find(block)?.groupValues?.get(1)?.trim() ?: "Imported",
            data = data,
            nickname = Regex("""(?m)^nickname=(.*)$""").find(block)?.groupValues?.get(1)?.trim() ?: "",
        )
    }
    return list.filter { uidOf(it.data) != null }.distinctBy { uidOf(it.data) }
}
