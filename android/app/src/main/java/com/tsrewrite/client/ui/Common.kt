package com.tsrewrite.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun Field(
    label: String, value: String, type: KeyboardType = KeyboardType.Text, password: Boolean = false,
    singleLine: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = if (label.isEmpty()) null else ({ Text(label) }), singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else type),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier,
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, password: Boolean = false, multiline: Boolean = false, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Field("", text, password = password, singleLine = !multiline) { text = it } },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "OK", onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onOk) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Full-screen page with a back arrow, used for secondary screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String, onBack: () -> Unit, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ts.Bg),
            )
        },
        containerColor = Ts.Bg,
        content = content,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ts.Blue,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp),
    )
}

@Composable
fun ActionRow(icon: ImageVector?, text: String, subtitle: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (danger) Ts.Red else Ts.TextDim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(text, color = if (danger) Ts.Red else Ts.Text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
        }
    }
}

@Composable
fun SwitchRow(text: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
        }
        Switch(checked, onChange)
    }
}

@Composable
fun SliderRow(text: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f))
            Text(label, color = Ts.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        Slider(value, onChange, valueRange = range)
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(key, color = Ts.TextDim, modifier = Modifier.weight(0.42f))
        Text(value, modifier = Modifier.weight(0.58f))
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier.fillMaxSize()) {
    androidx.compose.foundation.layout.Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun EmptyState(text: String) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Ts.TextDim)
    }
}

@Composable
fun SheetColumn(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.Top, content = content)

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KiB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MiB".format(b / 1048576.0)
    else -> "%.2f GiB".format(b / 1073741824.0)
}

fun formatDuration(secs: Long): String {
    val d = secs / 86400; val h = secs % 86400 / 3600; val m = secs % 3600 / 60; val s = secs % 60
    return when { d > 0 -> "${d}d ${h}h ${m}m"; h > 0 -> "${h}h ${m}m"; m > 0 -> "${m}m ${s}s"; else -> "${s}s" }
}

fun formatDate(unix: Long): String =
    if (unix <= 0) "—" else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(unix * 1000))
