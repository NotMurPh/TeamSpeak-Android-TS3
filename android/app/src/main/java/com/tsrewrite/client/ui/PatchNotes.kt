package com.tsrewrite.client.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray

/** One release in `assets/changelog.json`; each change is a kind ("new", "improved", "fixed") and a sentence. */
private data class Release(val build: Int, val date: String, val title: String, val changes: List<Pair<String, String>>)

private fun loadReleases(context: Context): List<Release> = runCatching {
    val a = JSONArray(context.assets.open("changelog.json").bufferedReader().use { it.readText() })
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val c = o.getJSONArray("changes")
        Release(o.getInt("build"), o.optString("date"), o.optString("title"),
            (0 until c.length()).map { j -> c.getJSONArray(j).let { it.getString(0) to it.getString(1) } })
    }
}.getOrDefault(emptyList())

/** Release number of this install: version code 1000 + n for releases; 0 for local builds. */
@Suppress("DEPRECATION")
private fun installedBuild(context: Context): Int =
    (context.packageManager.getPackageInfo(context.packageName, 0).versionCode - 1000).coerceAtLeast(0)

/** Top-bar button for the patch notes; a dot marks notes not opened since the last update. */
@Composable
fun PatchNotesButton() {
    val context = LocalContext.current
    val releases = remember { loadReleases(context) }
    val prefs = remember { context.getSharedPreferences("store", Context.MODE_PRIVATE) }
    val newest = releases.firstOrNull()?.build ?: 0
    var unseen by remember { mutableStateOf(prefs.getInt("notesSeen", 0) < newest) }
    var open by remember { mutableStateOf(false) }

    IconButton(onClick = { open = true; unseen = false; prefs.edit().putInt("notesSeen", newest).apply() }) {
        Box {
            Icon(Icons.Rounded.AutoAwesome, "What's new")
            if (unseen) Box(Modifier.align(Alignment.TopEnd).size(9.dp).background(Ts.Blue, CircleShape))
        }
    }
    if (open) {
        // A dialog, not a bottom sheet: in a sheet, scrolling the list and dragging the sheet fight each other.
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            PatchNotes(releases, installedBuild(context)) { open = false }
        }
    }
}

@Composable
private fun PatchNotes(releases: List<Release>, installed: Int, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.85f).clip(RoundedCornerShape(28.dp)).background(Ts.Bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("What's new", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Changes in each version of TS Client", style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Ts.TextDim) }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(releases, key = { it.build }) { r -> ReleaseCard(r, current = r.build == installed) }
        }
    }
}

@Composable
private fun ReleaseCard(r: Release, current: Boolean) {
    Column(
        Modifier.fillMaxWidth().background(Ts.Surface, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("v0.1.${r.build} · ${r.date}", style = MaterialTheme.typography.labelMedium, color = Ts.TextDim)
            }
            if (current) Chip("Installed", Ts.Green)
        }
        r.changes.forEach { (kind, text) ->
            Row(verticalAlignment = Alignment.Top) {
                val (label, color) = when (kind) {
                    "new" -> "NEW" to Ts.Blue
                    "fixed" -> "FIX" to Ts.Green
                    else -> "BETTER" to Ts.Purple
                }
                Box(Modifier.width(64.dp).padding(top = 1.dp)) { Chip(label, color) }
                Spacer(Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
