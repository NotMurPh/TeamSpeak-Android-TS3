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
        Dialog(onDismissRequest = { open = false }) {
            Box(Modifier.clip(RoundedCornerShape(28.dp)).background(Ts.Surface)) { UpdateCard(updater) }
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
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).background(Ts.Blue.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Download, null, tint = Ts.Blue, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Update available", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("${updater.installedVersion}  →  ${com.tsrewrite.client.displayVersion(info.versionName)}",
            style = MaterialTheme.typography.bodyMedium, color = Ts.TextDim, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(20.dp))
        val wide = Modifier.fillMaxWidth().height(48.dp)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (val s = state) {
                is UpdateState.Downloading -> {
                    LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Ts.Blue, trackColor = Ts.SurfaceHigh)
                    Text("Downloading… ${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                }
                is UpdateState.Installing -> {
                    OutlinedButton(onClick = ::install, wide) { Text("Show install prompt again") }
                }
                is UpdateState.SavedToDownloads -> {
                    Text("Saving to your Downloads folder. Tap the finished download notification to install it.",
                        style = MaterialTheme.typography.bodySmall, color = Ts.TextDim, textAlign = TextAlign.Center)
                    OutlinedButton(onClick = ::install, wide) { Text("Install in the app instead") }
                }
                else -> {
                    if (s is UpdateState.Failed) Text(s.message, style = MaterialTheme.typography.bodySmall, color = Ts.Red, textAlign = TextAlign.Center)
                    if (s is UpdateState.Available && !s.sameKey) {
                        Text("This copy is signed with a different key, so Android can't update it in place. Export a backup in Settings, uninstall, then install the downloaded file.",
                            style = MaterialTheme.typography.bodySmall, color = Ts.TextDim, textAlign = TextAlign.Center)
                    }
                    Button(onClick = ::install, wide, colors = ButtonDefaults.buttonColors(containerColor = Ts.Blue, contentColor = Color.White)) {
                        Text(if (s is UpdateState.Failed) "Try again" else "Update now", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(onClick = { updater.saveToDownloads(info) }, wide) { Text("Save to Downloads") }
                    TextButton(onClick = updater::dismiss) { Text("Later", color = Ts.TextDim) }
                }
            }
        }
    }
}
