package com.tsrewrite.client.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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

/** "0.1.0+build.11" -> 11; 0 for local builds. */
private fun installedBuild(context: Context): Int =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName?.substringAfterLast("build.", "")?.toIntOrNull() ?: 0

/** Top-bar button for the patch notes; a dot marks notes not opened since the last update. */
@OptIn(ExperimentalMaterial3Api::class)
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
            Icon(Icons.Outlined.NewReleases, "What's new")
            if (unseen) Box(Modifier.align(Alignment.TopEnd).size(9.dp).background(Ts.Blue, CircleShape))
        }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, containerColor = Ts.Bg) {
            PatchNotes(releases, installedBuild(context))
        }
    }
}

@Composable
private fun PatchNotes(releases: List<Release>, installed: Int) {
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 640.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(start = 4.dp, bottom = 4.dp)) {
                Text("What's new", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Changes in each version of TS Client", style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
            }
        }
        items(releases, key = { it.build }) { r -> ReleaseCard(r, current = r.build == installed) }
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
                Text("Build ${r.build} · ${r.date}", style = MaterialTheme.typography.labelMedium, color = Ts.TextDim)
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
