package com.tsrewrite.client.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tsrewrite.client.Identity
import com.tsrewrite.client.MyTeamSpeakCloud
import com.tsrewrite.client.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.tscore.identityUid

internal class CloudIdentityViewModel : ViewModel() {
    private val cloud = MyTeamSpeakCloud()
    var email by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var remote by mutableStateOf<List<MyTeamSpeakCloud.RemoteIdentity>?>(null)
        private set

    private fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        notice = null
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: MyTeamSpeakCloud.CloudException) { error = e.message }
            catch (_: Exception) { error = "Could not complete the cloud request. Check your connection and try again. No local identities were changed." }
            finally { busy = false }
        }
    }

    fun login(email: String, password: String, otp: String) = run {
        this.email = null
        remote = null
        this.email = cloud.login(email, password, otp)
    }

    fun downloadList() = run { remote = null; remote = cloud.listIdentities() }
    fun dismissDownload() { remote = null }
    fun upload(identities: List<Identity>) = run {
        val result = cloud.upload(identities)
        notice = "Uploaded ${result.added}. Already in myTeamSpeak: ${result.skipped}."
    }

    fun download(store: Store, ids: Set<String>) {
        if (busy) return
        val selected = remote.orEmpty().filter { it.id in ids }
        val existing = store.identities.value.mapNotNull { runCatching { identityUid(it.data) }.getOrNull() }.toMutableSet()
        var added = 0
        for (identity in selected) {
            if (existing.add(identity.uid)) {
                store.upsertIdentity(Identity(name = identity.name, data = identity.data, nickname = identity.nickname))
                added++
            }
        }
        remote = null
        notice = "Downloaded $added. Already on this phone: ${selected.size - added}."
    }

    fun logout() {
        if (busy) return
        cloud.close()
        email = null
        remote = null
        error = null
        notice = null
    }

    override fun onCleared() { cloud.close() }
}

@Composable
internal fun MyTeamSpeakCard(store: Store, model: CloudIdentityViewModel = viewModel()) {
    val identities by store.identities.collectAsState()
    var loginOpen by remember { mutableStateOf(false) }
    var uploadOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(Ts.Surface, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("myTeamSpeak (experimental)", style = MaterialTheme.typography.titleSmall, color = Ts.Blue)
        if (model.email == null) {
            Text("Sign in to choose identities to download from or upload to your TeamSpeak cloud. Optional; local identities work without an account.",
                style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
            Button(enabled = !model.busy, onClick = { loginOpen = true }) { Text("Sign in") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.email.orEmpty(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(enabled = !model.busy, onClick = { uploadOpen = false; model.logout() }) { Text("Sign out") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !model.busy, onClick = { model.downloadList() }, modifier = Modifier.weight(1f)) { Text("Download identities") }
                OutlinedButton(enabled = !model.busy && identities.isNotEmpty(), onClick = { uploadOpen = true }, modifier = Modifier.weight(1f)) { Text("Upload identities") }
            }
            Text("Only selected identities are copied. Existing identities aren't overwritten or deleted.", style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
        }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        model.error?.let { Text(it, color = Ts.Red, style = MaterialTheme.typography.bodySmall) }
        model.notice?.let { Text(it, color = Ts.TextDim, style = MaterialTheme.typography.bodySmall) }
    }

    if (loginOpen) {
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var otp by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { password = ""; otp = ""; loginOpen = false },
            containerColor = Ts.Surface,
            title = { Text("Sign in to myTeamSpeak") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    OutlinedTextField(otp, { otp = it.filter(Char::isDigit).take(8) }, label = { Text("2FA code (if enabled)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    Text("Your password isn't saved. The session lasts until you sign out or the app closes. Recovery and account resets must be handled in the official client.",
                        style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                }
            },
            confirmButton = {
                TextButton(enabled = email.isNotBlank() && password.isNotEmpty() && !model.busy, onClick = {
                    model.login(email, password, otp)
                    password = ""; otp = ""; loginOpen = false
                }) { Text("Sign in") }
            },
            dismissButton = { TextButton(onClick = { password = ""; otp = ""; loginOpen = false }) { Text("Cancel") } },
        )
    }

    if (uploadOpen) {
        CloudSelectionDialog("Upload identities", "Choose identities to add to myTeamSpeak. Identities already there will be skipped.",
            identities.map { CloudSelection(it.id, it.name, runCatching { identityUid(it.data) }.getOrDefault("Invalid identity")) },
            "Upload", onDismiss = { uploadOpen = false }) { selected ->
            uploadOpen = false
            model.upload(identities.filter { it.id in selected })
        }
    }
    model.remote?.let { remote ->
        val existing = identities.mapNotNull { runCatching { identityUid(it.data) }.getOrNull() }.toSet()
        CloudSelectionDialog("Download identities", "Choose identities to add to this phone.",
            remote.map { CloudSelection(it.id, it.name, if (it.uid in existing) "Already on this phone" else it.uid, it.uid !in existing) },
            "Download", onDismiss = model::dismissDownload) { selected -> model.download(store, selected) }
    }
}

private data class CloudSelection(val id: String, val name: String, val detail: String, val enabled: Boolean = true)

@Composable
private fun CloudSelectionDialog(title: String, explanation: String, items: List<CloudSelection>, action: String,
    onDismiss: () -> Unit, onConfirm: (Set<String>) -> Unit) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val valid = selected.intersect(items.filter { it.enabled }.map { it.id }.toSet())
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ts.Surface, title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(explanation, style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                if (items.isEmpty()) Text("No cloud identities found.", modifier = Modifier.padding(top = 12.dp))
                items.forEach { item ->
                    fun toggle() { selected = if (item.id in selected) selected - item.id else selected + item.id }
                    Row(Modifier.fillMaxWidth().clickable(enabled = item.enabled) { toggle() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.id in valid, enabled = item.enabled, onCheckedChange = { toggle() })
                        Column(Modifier.weight(1f)) {
                            Text(item.name)
                            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = valid.isNotEmpty(), onClick = { onConfirm(valid) }) { Text("$action (${valid.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
