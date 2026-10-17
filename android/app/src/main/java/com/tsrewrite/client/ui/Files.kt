package com.tsrewrite.client.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tsrewrite.client.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class FileEntry(val name: String, val size: Long, val time: Long, val dir: Boolean)

@Composable
fun FileBrowserPage(session: Session, channel: ULong, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val v by session.server.collectAsState()
    val transfers by session.transfers.collectAsState()
    val ch = v?.channels?.firstOrNull { it.id == channel }
    var password by remember { mutableStateOf<String?>(if (ch?.hasPassword == true) null else "") }
    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var prompt by remember { mutableStateOf<Pair<String, FileEntry?>?>(null) }

    LaunchedEffect(path, password, reload) {
        val pw = password ?: return@LaunchedEffect
        entries = null; error = null
        runCatching {
            session.query("ftgetfilelist", mapOf("cid" to channel.toString(), "cpw" to pw, "path" to path), "notifyfilelist")
        }.onSuccess { rows ->
            entries = rows.map { FileEntry(it["name"] ?: "", it["size"]?.toLongOrNull() ?: 0, it["datetime"]?.toLongOrNull() ?: 0, it["type"] == "0") }
                .sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
        }.onFailure { error = it.message; entries = emptyList() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pw = password ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            val (name, tmp) = withContext(Dispatchers.IO) { copyToCache(context, uri) }
            val err = session.upload(channel, path.trimEnd('/') + "/" + name, pw, tmp)
            tmp.delete()
            Toast.makeText(context, err?.let { "Upload failed: $it" } ?: "Uploaded $name", Toast.LENGTH_SHORT).show()
            reload++
        }
    }

    Page(if (path == "/") "Files · ${ch?.name ?: ""}" else path, onBack = { if (path != "/") path = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" } else onBack() }, actions = {
        IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Refresh") }
        IconButton(onClick = { prompt = "mkdir" to null }) { Icon(Icons.Default.CreateNewFolder, "New folder") }
        IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Upload, "Upload") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            transfers.values.filter { !it.finished }.forEach { t ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text("${if (t.upload) "Uploading" else "Downloading"} ${t.name}", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { if (t.total > 0) t.done.toFloat() / t.total else 0f }, Modifier.fillMaxWidth())
                }
            }
            when {
                password == null -> EmptyState("This channel is password protected")
                entries == null -> Loading()
                error != null -> EmptyState(error!!)
                entries!!.isEmpty() -> EmptyState("No files")
                else -> LazyColumn {
                    items(entries!!, key = { it.name }) { e ->
                        var menu by remember { mutableStateOf(false) }
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (e.dir) path = path.trimEnd('/') + "/" + e.name
                                else scope.launch { download(context, session, channel, password!!, path, e) }
                            }.padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (e.dir) Icons.Default.Folder else Icons.Default.Description, null, tint = if (e.dir) Ts.Yellow else Ts.TextDim)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.name)
                                Text(
                                    (if (e.dir) "" else formatBytes(e.size) + " · ") + formatDate(e.time),
                                    style = MaterialTheme.typography.bodySmall, color = Ts.TextDim,
                                )
                            }
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                            DropdownMenu(menu, { menu = false }) {
                                if (!e.dir) DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; scope.launch { download(context, session, channel, password!!, path, e) } })
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; prompt = "rename" to e })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = {
                                    menu = false
                                    scope.launch {
                                        runCatching { session.command("ftdeletefile", mapOf("cid" to channel.toString(), "cpw" to password!!, "name" to path.trimEnd('/') + "/" + e.name)) }
                                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                                        reload++
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    if (password == null) TextInputDialog("Channel password", "", password = true, onDismiss = onBack) { password = it }
    when (prompt?.first) {
        "mkdir" -> TextInputDialog("New folder", "", onDismiss = { prompt = null }) { name ->
            prompt = null
            scope.launch {
                runCatching { session.command("ftcreatedir", mapOf("cid" to channel.toString(), "cpw" to password!!, "dirname" to path.trimEnd('/') + "/" + name)) }
                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                reload++
            }
        }
        "rename" -> {
            val e = prompt!!.second!!
            TextInputDialog("Rename", e.name, onDismiss = { prompt = null }) { name ->
                prompt = null
                scope.launch {
                    val base = path.trimEnd('/') + "/"
                    runCatching {
                        session.command("ftrenamefile", mapOf("cid" to channel.toString(), "cpw" to password!!, "oldname" to base + e.name, "newname" to base + name))
                    }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                    reload++
                }
            }
        }
        else -> {}
    }
}

private suspend fun download(context: Context, session: Session, channel: ULong, pw: String, dir: String, e: FileEntry) {
    val tmp = File(context.cacheDir, "dl_" + e.name)
    val err = session.download(channel, dir.trimEnd('/') + "/" + e.name, pw, tmp)
    val msg = if (err != null) "Download failed: $err" else withContext(Dispatchers.IO) { saveToDownloads(context, tmp, e.name) }
    tmp.delete()
    withContext(Dispatchers.Main) { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
}

private fun saveToDownloads(context: Context, src: File, name: String): String {
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TS Client")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return "Could not save $name"
        context.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
        return "Saved to Downloads/TS Client/$name"
    }
    val dest = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)
    src.copyTo(dest, overwrite = true)
    return "Saved to ${dest.absolutePath}"
}

private fun copyToCache(context: Context, uri: Uri): Pair<String, File> {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "upload.bin"
    val tmp = File(context.cacheDir, "ul_$name")
    context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
    return name to tmp
}
