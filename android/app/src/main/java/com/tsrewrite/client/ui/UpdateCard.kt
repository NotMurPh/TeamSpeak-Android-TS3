package com.tsrewrite.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tsrewrite.client.UpdateInfo
import com.tsrewrite.client.UpdateState
import com.tsrewrite.client.Updater

/** Blue download icon in the top bar while a newer release is available; opens [UpdateCard] in a dialog. */
@Composable
fun UpdateButton(updater: Updater) {
    val state by updater.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    val shown = when (val s = state) {
        is UpdateState.Available, is UpdateState.SavedToDownloads, is UpdateState.Installing, is UpdateState.Downloading -> true
        is UpdateState.Failed -> s.info != null
        else -> false
    }
    if (!shown) { open = false; return }
    IconButton(onClick = { open = true }) {
        Box(contentAlignment = Alignment.Center) {
            // While downloading, a ring around the arrow shows the progress.
            (state as? UpdateState.Downloading)?.let { d ->
                CircularProgressIndicator(progress = { d.progress }, Modifier.size(34.dp), color = Ts.Blue, strokeWidth = 2.5.dp, trackColor = Ts.SurfaceHigh)
            }
            Icon(Icons.Rounded.Download, "Update available", tint = Ts.Blue)
        }
    }
    if (open) {
        Dialog(onDismissRequest = { open = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxWidth(0.94f).clip(RoundedCornerShape(24.dp)).background(Ts.Surface)) { UpdateCard(updater) }
        }
    }
}

/** What to do with a newer release: install it in the app, or save it to Downloads. */
@Composable
fun UpdateCard(updater: Updater, modifier: Modifier = Modifier) {
    val state by updater.state.collectAsState()
    val info: UpdateInfo = when (val s = state) {
        is UpdateState.Available -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.Installing -> s.info
        is UpdateState.SavedToDownloads -> s.info
        is UpdateState.Failed -> s.info ?: return
        else -> return
    }
    fun install() { if (updater.ensureInstallAllowed()) updater.downloadAndInstall(info) }

    Column(
        modifier.fillMaxWidth().padding(start = 18.dp, top = 10.dp, end = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Download, null, tint = Ts.Blue)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("Update available", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text("${updater.installedVersion} → ${com.tsrewrite.client.displayVersion(info.versionName)}", style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
            }
            if (state !is UpdateState.Downloading && state !is UpdateState.Installing) {
                IconButton(onClick = updater::dismiss) { Icon(Icons.Rounded.Close, "Later", tint = Ts.TextDim) }
            }
        }
        Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // One line each and the same height, side by side.
            val button = Modifier.height(46.dp)
            when (val s = state) {
                is UpdateState.Downloading -> {
                    LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Ts.Blue, trackColor = Ts.SurfaceHigh)
                    Text("Downloading… ${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                }
                is UpdateState.Installing -> {
                    OutlinedButton(onClick = ::install, button.fillMaxWidth()) { Text("Show install prompt again") }
                }
                is UpdateState.SavedToDownloads -> {
                    Text("Saving to your Downloads folder. Tap the finished download notification to install it.",
                        style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                    OutlinedButton(onClick = ::install, button.fillMaxWidth()) { Text("Install in the app instead") }
                }
                else -> {
                    if (s is UpdateState.Failed) Text(s.message, style = MaterialTheme.typography.bodySmall, color = Ts.Red)
                    if (s is UpdateState.Available && !s.sameKey) {
                        Text("This copy is signed with a different key, so Android can't update it in place. Export a backup in Settings, uninstall, then install the downloaded file.",
                            style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { updater.saveToDownloads(info) }, button.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                            Text("Save to Downloads", maxLines = 1, softWrap = false)
                        }
                        Button(onClick = ::install, button.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Ts.Blue, contentColor = Color.White)) {
                            Text(if (s is UpdateState.Failed) "Retry" else "Update", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
