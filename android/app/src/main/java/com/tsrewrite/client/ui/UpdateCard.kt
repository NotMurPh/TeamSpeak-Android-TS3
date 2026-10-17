package com.tsrewrite.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tsrewrite.client.UpdateInfo
import com.tsrewrite.client.UpdateState
import com.tsrewrite.client.Updater

/** Shown above the server list while a newer release is available or being installed. */
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
        modifier.fillMaxWidth().background(Ts.Surface, RoundedCornerShape(14.dp)).padding(start = 14.dp, top = 6.dp, end = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SystemUpdate, null, tint = Ts.Blue)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Update available", fontWeight = FontWeight.Medium)
                Text("${updater.installedVersion} → ${info.versionName}", style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
            }
            if (state !is UpdateState.Downloading && state !is UpdateState.Installing) {
                IconButton(onClick = updater::dismiss) { Icon(Icons.Default.Close, "Later", tint = Ts.TextDim) }
            }
        }
        Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val s = state) {
                is UpdateState.Downloading -> {
                    LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth(), color = Ts.Blue)
                    Text("Downloading… ${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                }
                is UpdateState.Installing -> {
                    Text("Confirm the install in the Android prompt.", style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                    OutlinedButton(onClick = ::install, Modifier.fillMaxWidth()) { Text("Show install prompt again") }
                }
                is UpdateState.SavedToDownloads -> {
                    Text("Downloading to your Downloads folder. Tap the finished download notification to install it.",
                        style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                    OutlinedButton(onClick = ::install, Modifier.fillMaxWidth()) { Text("Install in the app instead") }
                }
                else -> {
                    if (s is UpdateState.Failed) Text(s.message, style = MaterialTheme.typography.bodySmall, color = Ts.Red)
                    if (s is UpdateState.Available && !s.sameKey) {
                        Text("This copy is signed with a different key, so Android can't update it in place. Export a backup in Settings, uninstall, then install the downloaded file.",
                            style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { updater.saveToDownloads(info) }, Modifier.weight(1f)) { Text("Save to Downloads") }
                        Button(onClick = ::install, Modifier.weight(1f)) { Text(if (s is UpdateState.Failed) "Retry" else "Update") }
                    }
                }
            }
        }
    }
}
