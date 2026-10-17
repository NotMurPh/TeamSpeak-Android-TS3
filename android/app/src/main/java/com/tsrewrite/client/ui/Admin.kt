package com.tsrewrite.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tsrewrite.client.Row as DataRow
import com.tsrewrite.client.Session
import kotlinx.coroutines.launch
import uniffi.tscore.KeyValue

sealed class PermTarget(val title: String) {
    class ServerGroup(val id: ULong, name: String) : PermTarget("Server group · $name")
    class ChannelGroup(val id: ULong, name: String) : PermTarget("Channel group · $name")
    class Channel(val id: ULong, name: String) : PermTarget("Channel · $name")
    class Client(val dbid: ULong, name: String) : PermTarget("Client · $name")
}

/** Loads a list with [Session.query]; reload by bumping the returned counter. */
@Composable
private fun rememberList(session: Session, name: String, args: Map<String, String>, vararg expect: String): Triple<List<DataRow>?, String?, () -> Unit> {
    var rows by remember { mutableStateOf<List<DataRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        rows = null; error = null
        runCatching { session.query(name, args, *expect) }.onSuccess { rows = it }.onFailure { error = it.message; rows = emptyList() }
    }
    return Triple(rows, error, { tick++ })
}

@Composable
private fun ListBody(rows: List<DataRow>?, error: String?, empty: String, content: @Composable () -> Unit) = when {
    rows == null -> Loading()
    error != null -> EmptyState(error)
    rows.isEmpty() -> EmptyState(empty)
    else -> content()
}

private fun toast(ctx: android.content.Context, t: Throwable) = Toast.makeText(ctx, t.message, Toast.LENGTH_LONG).show()

@Composable
fun BansPage(session: Session, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (rows, error, reload) = rememberList(session, "banlist", emptyMap(), "notifybanlist")
    var add by remember { mutableStateOf(false) }
    Page("Ban list", onBack, actions = {
        IconButton(onClick = reload) { Icon(Icons.Default.Refresh, "Refresh") }
        IconButton(onClick = { add = true }) { Icon(Icons.Default.Add, "Add ban") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ListBody(rows, error, "No bans") {
                LazyColumn {
                    items(rows!!) { b ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(listOf(b["name"], b["lastnickname"], b["ip"], b["uid"]).firstOrNull { !it.isNullOrBlank() } ?: "ban #${b["banid"]}")
                                val dur = b["duration"]?.toLongOrNull() ?: 0
                                Text(
                                    "${b["reason"]?.ifBlank { null } ?: "no reason"} · ${if (dur == 0L) "permanent" else formatDuration(dur)} · by ${b["invokername"] ?: "?"}",
                                    style = MaterialTheme.typography.bodySmall, color = Ts.TextDim,
                                )
                                listOfNotNull(b["ip"]?.takeIf { it.isNotBlank() }?.let { "IP $it" }, b["uid"]?.takeIf { it.isNotBlank() }?.let { "UID $it" })
                                    .forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Ts.TextFaint) }
                            }
                            IconButton(onClick = { scope.launch { runCatching { session.command("bandel", mapOf("banid" to (b["banid"] ?: ""))) }.onFailure { toast(ctx, it) }; reload() } }) {
                                Icon(Icons.Default.Delete, "Remove ban", tint = Ts.Red)
                            }
                        }
                        HorizontalDivider(color = Ts.Outline)
                    }
                }
            }
        }
    }
    if (add) {
        var ip by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var uid by remember { mutableStateOf("") }
        var reason by remember { mutableStateOf("") }; var secs by remember { mutableStateOf("0") }
        AlertDialog(
            onDismissRequest = { add = false },
            title = { Text("Add ban") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Field("IP (regex)", ip) { ip = it }; Field("Name (regex)", name) { name = it }; Field("Unique ID", uid) { uid = it }
                    Field("Reason", reason) { reason = it }; Field("Duration in seconds (0 = permanent)", secs, KeyboardType.Number) { secs = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    add = false
                    scope.launch {
                        val args = buildMap {
                            if (ip.isNotBlank()) put("ip", ip); if (name.isNotBlank()) put("name", name); if (uid.isNotBlank()) put("uid", uid)
                            put("time", secs.ifBlank { "0" }); put("banreason", reason)
                        }
                        runCatching { session.command("banadd", args) }.onFailure { toast(ctx, it) }
                        reload()
                    }
                }) { Text("Ban") }
            },
            dismissButton = { TextButton(onClick = { add = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun GroupsPage(session: Session, server: Boolean, onBack: () -> Unit, go: (SPage) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val v by session.server.collectAsState()
    val groups = (if (server) v?.serverGroups else v?.channelGroups).orEmpty()
    var add by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<uniffi.tscore.GroupView?>(null) }
    val prefix = if (server) "servergroup" else "channelgroup"
    val idKey = if (server) "sgid" else "cgid"
    LaunchedEffect(Unit) { session.fire(session.client.sendCommand("${prefix}list", emptyList())) }
    Page(if (server) "Server groups" else "Channel groups", onBack, actions = {
        IconButton(onClick = { add = true }) { Icon(Icons.Default.Add, "Add group") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            items(groups, key = { it.id.toLong() }) { g ->
                var menu by remember { mutableStateOf(false) }
                val icons by session.icons.icons.collectAsState()
                Row(
                    Modifier.fillMaxWidth().clickable {
                        go(SPage.Permissions(if (server) PermTarget.ServerGroup(g.id, g.name) else PermTarget.ChannelGroup(g.id, g.name)))
                    }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TsIcon(g.iconId.toLong(), icons, 20.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(g.name)
                        Text("id ${g.id} · sort ${g.sortId}${if (g.isTemplate) " · template" else ""}", style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.Edit, "Edit") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Permissions") }, leadingIcon = { Icon(Icons.Default.Security, null) }, onClick = {
                            menu = false; go(SPage.Permissions(if (server) PermTarget.ServerGroup(g.id, g.name) else PermTarget.ChannelGroup(g.id, g.name)))
                        })
                        if (server) DropdownMenuItem(text = { Text("Members") }, leadingIcon = { Icon(Icons.Default.Groups, null) }, onClick = {
                            menu = false; go(SPage.GroupMembers(g.id, g.name))
                        })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename = g })
                        DropdownMenuItem(text = { Text("Delete", color = Ts.Red) }, onClick = {
                            menu = false
                            scope.launch { runCatching { session.command("${prefix}del", mapOf(idKey to g.id.toString(), "force" to "1")) }.onFailure { toast(ctx, it) } }
                        })
                    }
                }
            }
        }
    }
    if (add) TextInputDialog("New group name", "", onDismiss = { add = false }) { name ->
        add = false
        scope.launch { runCatching { session.command("${prefix}add", mapOf("name" to name, "type" to "1")) }.onFailure { toast(ctx, it) } }
    }
    rename?.let { g ->
        TextInputDialog("Rename group", g.name, onDismiss = { rename = null }) { name ->
            rename = null
            scope.launch { runCatching { session.command("${prefix}rename", mapOf(idKey to g.id.toString(), "name" to name)) }.onFailure { toast(ctx, it) } }
        }
    }
}

@Composable
fun GroupMembersPage(session: Session, sgid: ULong, name: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (rows, error, reload) = rememberList(session, "servergroupclientlist", mapOf("sgid" to sgid.toString(), "-names" to ""), "notifyservergroupclientlist")
    Page("Members · $name", onBack, actions = { IconButton(onClick = reload) { Icon(Icons.Default.Refresh, "Refresh") } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ListBody(rows, error, "No members") {
                LazyColumn {
                    items(rows!!) { r ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r["client_nickname"] ?: "Database ID ${r["cldbid"]}")
                                Text(r["client_unique_identifier"] ?: "", style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching { session.command("servergroupdelclient", mapOf("sgid" to sgid.toString(), "cldbid" to (r["cldbid"] ?: ""))) }.onFailure { toast(ctx, it) }
                                    reload()
                                }
                            }) { Icon(Icons.Default.Delete, "Remove", tint = Ts.Red) }
                        }
                    }
                }
            }
        }
    }
}

private data class PermValue(val id: String, val name: String, val value: Int, val negated: Boolean, val skip: Boolean)

/** Lists and edits the permissions of a group, channel or client; all permissions can be added. */
@Composable
fun PermissionsPage(session: Session, target: PermTarget, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<DataRow>>(emptyList()) }
    var values by remember { mutableStateOf<List<PermValue>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<PermValue?>(null) }

    val (listCmd, notify, idArgs, addCmd, delCmd) = when (target) {
        is PermTarget.ServerGroup -> listOf("servergrouppermlist", "notifyservergrouppermlist", "sgid=${target.id}", "servergroupaddperm", "servergroupdelperm")
        is PermTarget.ChannelGroup -> listOf("channelgrouppermlist", "notifychannelgrouppermlist", "cgid=${target.id}", "channelgroupaddperm", "channelgroupdelperm")
        is PermTarget.Channel -> listOf("channelpermlist", "notifychannelpermlist", "cid=${target.id}", "channeladdperm", "channeldelperm")
        is PermTarget.Client -> listOf("clientpermlist", "notifyclientpermlist", "cldbid=${target.dbid}", "clientaddperm", "clientdelperm")
    }
    val idArg = idArgs.substringBefore('=') to idArgs.substringAfter('=')

    LaunchedEffect(Unit) { all = runCatching { session.permissionNames() }.getOrDefault(emptyList()) }
    LaunchedEffect(tick, all) {
        values = null; error = null
        val names = all.associate { (it["permid"] ?: "") to (it["permname"] ?: "") }
        runCatching { session.query(listCmd, mapOf(idArg), notify) }
            .onSuccess { rows ->
                values = rows.mapNotNull { r ->
                    val id = r["permid"] ?: return@mapNotNull null
                    PermValue(id, r["permsid"] ?: names[id] ?: "#$id", r["permvalue"]?.toIntOrNull() ?: 0, r["permnegated"] == "1", r["permskip"] == "1")
                }.sortedBy { it.name }
            }
            .onFailure { error = it.message; values = emptyList() }
    }

    Page(target.title, onBack, actions = { IconButton(onClick = { tick++ }) { Icon(Icons.Default.Refresh, "Refresh") } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Field("Search", filter, modifier = Modifier.weight(1f)) { filter = it }
                Spacer(Modifier.width(8.dp))
                FilterChip(showAll, { showAll = !showAll }, label = { Text("All") })
            }
            val granted = values
            when {
                granted == null -> Loading()
                error != null && granted.isEmpty() && !showAll -> EmptyState(error!!)
                else -> {
                    val set = granted.associateBy { it.id }
                    val list = if (showAll) all.map { r -> set[r["permid"]] ?: PermValue(r["permid"] ?: "", r["permname"] ?: "", 0, false, false).copy(value = Int.MIN_VALUE) }
                    else granted
                    LazyColumn {
                        items(list.filter { filter.isBlank() || it.name.contains(filter, true) }, key = { it.id }) { p ->
                            val isSet = p.value != Int.MIN_VALUE
                            Row(Modifier.fillMaxWidth().clickable { edit = p }.padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, Modifier.weight(1f), color = if (isSet) Ts.Text else Ts.TextFaint, style = MaterialTheme.typography.bodyMedium)
                                if (isSet) Text(
                                    p.value.toString() + (if (p.negated) " ¬" else "") + (if (p.skip) " ⤼" else ""),
                                    color = Ts.Blue, style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    edit?.let { p ->
        var value by remember { mutableStateOf(if (p.value == Int.MIN_VALUE) "1" else p.value.toString()) }
        var negated by remember { mutableStateOf(p.negated) }
        var skip by remember { mutableStateOf(p.skip) }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(p.name) },
            text = {
                Column {
                    Field("Value", value, KeyboardType.Number) { value = it }
                    if (target !is PermTarget.Channel) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(negated, { negated = it }); Text("Negate") }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(skip, { skip = it }); Text("Skip") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    edit = null
                    scope.launch {
                        val args = buildMap {
                            put(idArg.first, idArg.second); put("permsid", p.name); put("permvalue", value.ifBlank { "0" })
                            if (target !is PermTarget.Channel) { put("permnegated", if (negated) "1" else "0"); put("permskip", if (skip) "1" else "0") }
                        }
                        runCatching { session.command(addCmd, args) }.onFailure { toast(ctx, it) }
                        tick++
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (p.value != Int.MIN_VALUE) TextButton(onClick = {
                        edit = null
                        scope.launch { runCatching { session.command(delCmd, mapOf(idArg, "permsid" to p.name)) }.onFailure { toast(ctx, it) }; tick++ }
                    }) { Text("Remove", color = Ts.Red) }
                    TextButton(onClick = { edit = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
fun ComplaintsPage(session: Session, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (rows, error, reload) = rememberList(session, "complainlist", emptyMap(), "notifycomplainlist")
    Page("Complaints", onBack, actions = { IconButton(onClick = reload) { Icon(Icons.Default.Refresh, "Refresh") } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ListBody(rows, error, "No complaints") {
                LazyColumn {
                    items(rows!!) { c ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${c["tname"]} ← ${c["fname"]}")
                                Text(c["message"] ?: "", style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                                Text(formatDate(c["timestamp"]?.toLongOrNull() ?: 0), style = MaterialTheme.typography.bodySmall, color = Ts.TextFaint)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching { session.command("complaindel", mapOf("tcldbid" to (c["tcldbid"] ?: ""), "fcldbid" to (c["fcldbid"] ?: ""))) }.onFailure { toast(ctx, it) }
                                    reload()
                                }
                            }) { Icon(Icons.Default.Delete, "Delete", tint = Ts.Red) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TempPasswordsPage(session: Session, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (rows, error, reload) = rememberList(session, "servertemppasswordlist", emptyMap(), "notifyservertemppasswordlist")
    var add by remember { mutableStateOf(false) }
    Page("Temporary passwords", onBack, actions = {
        IconButton(onClick = reload) { Icon(Icons.Default.Refresh, "Refresh") }
        IconButton(onClick = { add = true }) { Icon(Icons.Default.Add, "Add") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ListBody(rows, error, "No temporary passwords") {
                LazyColumn {
                    items(rows!!) { p ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p["pw_clear"] ?: "")
                                Text("${p["desc"] ?: ""} · until ${formatDate(p["end"]?.toLongOrNull() ?: 0)} · by ${p["nickname"] ?: ""}", style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                            }
                            IconButton(onClick = {
                                scope.launch { runCatching { session.command("servertemppassworddel", mapOf("pw" to (p["pw_clear"] ?: ""))) }.onFailure { toast(ctx, it) }; reload() }
                            }) { Icon(Icons.Default.Delete, "Delete", tint = Ts.Red) }
                        }
                    }
                }
            }
        }
    }
    if (add) {
        var pw by remember { mutableStateOf("") }; var desc by remember { mutableStateOf("") }; var mins by remember { mutableStateOf("60") }
        AlertDialog(
            onDismissRequest = { add = false },
            title = { Text("Temporary password") },
            text = {
                Column {
                    Field("Password", pw) { pw = it }; Field("Description", desc) { desc = it }
                    Field("Valid for (minutes)", mins, KeyboardType.Number) { mins = it }
                }
            },
            confirmButton = {
                TextButton(enabled = pw.isNotBlank(), onClick = {
                    add = false
                    scope.launch {
                        runCatching {
                            session.command("servertemppasswordadd", mapOf("pw" to pw, "desc" to desc, "duration" to ((mins.toLongOrNull() ?: 60) * 60).toString(), "tcid" to "0", "tcpw" to ""))
                        }.onFailure { toast(ctx, it) }
                        reload()
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { add = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun PrivilegeKeysPage(session: Session, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val v by session.server.collectAsState()
    val (rows, error, reload) = rememberList(session, "privilegekeylist", emptyMap(), "notifyprivilegekeylist", "notifytokenlist")
    var add by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<String?>(null) }
    Page("Privilege keys", onBack, actions = {
        IconButton(onClick = reload) { Icon(Icons.Default.Refresh, "Refresh") }
        IconButton(onClick = { add = true }) { Icon(Icons.Default.Add, "Create") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ListBody(rows, error, "No privilege keys") {
                LazyColumn {
                    items(rows!!) { k ->
                        val token = k["token"] ?: ""
                        val gid = k["token_id1"]?.toULongOrNull()
                        val group = if (k["token_type"] == "1") v?.channelGroups?.firstOrNull { it.id == gid }?.name else v?.serverGroups?.firstOrNull { it.id == gid }?.name
                        Row(Modifier.fillMaxWidth().clickable {
                            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("token", token))
                            Toast.makeText(ctx, "Key copied", Toast.LENGTH_SHORT).show()
                        }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(token, style = MaterialTheme.typography.bodySmall)
                                Text("${group ?: "group ${k["token_id1"]}"} · ${k["token_description"] ?: ""}", style = MaterialTheme.typography.bodySmall, color = Ts.TextDim)
                            }
                            IconButton(onClick = {
                                scope.launch { runCatching { session.command("privilegekeydelete", mapOf("token" to token)) }.onFailure { toast(ctx, it) }; reload() }
                            }) { Icon(Icons.Default.Delete, "Delete", tint = Ts.Red) }
                        }
                    }
                }
            }
        }
    }
    if (add) {
        var channelGroup by remember { mutableStateOf(false) }
        var group by remember { mutableStateOf<ULong?>(null) }
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { add = false },
            title = { Text("Create privilege key") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(!channelGroup, { channelGroup = false; group = null }); Text("Server group")
                        RadioButton(channelGroup, { channelGroup = true; group = null }); Text("Channel group")
                    }
                    (if (channelGroup) v?.channelGroups else v?.serverGroups).orEmpty().filter { !it.isTemplate }.forEach { g ->
                        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(group == g.id, { group = g.id }); Text(g.name) }
                    }
                    Field("Description", desc) { desc = it }
                }
            },
            confirmButton = {
                TextButton(enabled = group != null, onClick = {
                    add = false
                    scope.launch {
                        runCatching {
                            val res = session.query("privilegekeyadd", mapOf(
                                "tokentype" to if (channelGroup) "1" else "0", "tokenid1" to group.toString(),
                                "tokenid2" to (if (channelGroup) (v?.ownChannelId ?: 0uL).toString() else "0"), "tokendescription" to desc,
                            ), "notifyprivilegekeyadd", "notifytokenadd")
                            created = res.firstOrNull()?.get("token")
                        }.onFailure { toast(ctx, it) }
                        reload()
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { add = false }) { Text("Cancel") } },
        )
    }
    created?.let { t ->
        AlertDialog(onDismissRequest = { created = null }, title = { Text("Privilege key created") }, text = { Text(t) },
            confirmButton = {
                TextButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("token", t)); created = null
                }) { Text("Copy") }
            })
    }
}

@Composable
fun EditServerPage(session: Session, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val v by session.server.collectAsState()
    val s = v ?: return Page("Edit server", onBack) { Loading() }
    var name by remember { mutableStateOf(s.name) }
    var welcome by remember { mutableStateOf(s.welcomeMessage) }
    var maxClients by remember { mutableStateOf(s.maxClients.toString()) }
    var password by remember { mutableStateOf("") }
    var hostMessage by remember { mutableStateOf(s.hostMessage) }
    var hostMode by remember { mutableIntStateOf(s.hostMessageMode.toInt()) }
    var bannerUrl by remember { mutableStateOf(s.bannerUrl) }
    var bannerGfx by remember { mutableStateOf(s.bannerGfxUrl) }
    var buttonUrl by remember { mutableStateOf(s.buttonUrl) }
    var buttonTip by remember { mutableStateOf(s.buttonTooltip) }
    var phonetic by remember { mutableStateOf(s.phoneticName) }
    Page("Edit virtual server", onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Field("Server name", name) { name = it }
            Field("Phonetic name", phonetic) { phonetic = it }
            Field("Welcome message", welcome, singleLine = false) { welcome = it }
            Field("Max clients", maxClients, KeyboardType.Number) { maxClients = it }
            Field("New server password (blank = unchanged)", password, password = true) { password = it }
            Field("Host message", hostMessage, singleLine = false) { hostMessage = it }
            Text("Host message mode", color = Ts.TextDim)
            listOf("None", "Log", "Modal", "Modal and quit").forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(hostMode == i, { hostMode = i }); Text(label) }
            }
            Field("Host banner URL", bannerUrl) { bannerUrl = it }
            Field("Host banner image URL", bannerGfx) { bannerGfx = it }
            Field("Host button URL", buttonUrl) { buttonUrl = it }
            Field("Host button tooltip", buttonTip) { buttonTip = it }
            Button(onClick = {
                scope.launch {
                    val props = buildList {
                        if (name != s.name) add(KeyValue("virtualserver_name", name))
                        if (phonetic != s.phoneticName) add(KeyValue("virtualserver_name_phonetic", phonetic))
                        if (welcome != s.welcomeMessage) add(KeyValue("virtualserver_welcomemessage", welcome))
                        if (maxClients != s.maxClients.toString()) add(KeyValue("virtualserver_maxclients", maxClients))
                        if (password.isNotBlank()) add(KeyValue("virtualserver_password", password))
                        if (hostMessage != s.hostMessage) add(KeyValue("virtualserver_hostmessage", hostMessage))
                        if (hostMode != s.hostMessageMode.toInt()) add(KeyValue("virtualserver_hostmessage_mode", hostMode.toString()))
                        if (bannerUrl != s.bannerUrl) add(KeyValue("virtualserver_hostbanner_url", bannerUrl))
                        if (bannerGfx != s.bannerGfxUrl) add(KeyValue("virtualserver_hostbanner_gfx_url", bannerGfx))
                        if (buttonUrl != s.buttonUrl) add(KeyValue("virtualserver_hostbutton_url", buttonUrl))
                        if (buttonTip != s.buttonTooltip) add(KeyValue("virtualserver_hostbutton_tooltip", buttonTip))
                    }
                    if (props.isEmpty()) { onBack(); return@launch }
                    runCatching { session.await(session.client.editServer(props)) }
                        .onSuccess { onBack() }.onFailure { toast(ctx, it) }
                }
            }, Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text("Save") }
        }
    }
}

@Composable
fun EditChannelPage(session: Session, id: ULong?, parent: ULong, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val v by session.server.collectAsState()
    val c = id?.let { cid -> v?.channels?.firstOrNull { it.id == cid } }
    LaunchedEffect(id) { if (id != null) session.fire(session.client.requestChannelDescription(id)) }
    var name by remember { mutableStateOf(c?.name ?: "") }
    var topic by remember { mutableStateOf(c?.topic ?: "") }
    var description by remember { mutableStateOf(c?.description ?: "") }
    var password by remember { mutableStateOf("") }
    var removePassword by remember { mutableStateOf(false) }
    var type by remember { mutableIntStateOf(when { c == null -> 0; c.isPermanent -> 0; c.isSemiPermanent -> 1; else -> 2 }) }
    var isDefault by remember { mutableStateOf(c?.isDefault ?: false) }
    var maxClients by remember { mutableStateOf(c?.maxClients?.takeIf { it >= 0 }?.toString() ?: "") }
    var talkPower by remember { mutableStateOf(c?.neededTalkPower?.toString() ?: "0") }
    var codec by remember { mutableIntStateOf(if (c?.codec?.contains("Music") == true) 5 else 4) }
    var quality by remember { mutableStateOf((c?.codecQuality ?: 6u).toFloat()) }
    var phonetic by remember { mutableStateOf(c?.phoneticName ?: "") }
    var deleteDelay by remember { mutableStateOf(c?.deleteDelaySecs?.toString() ?: "0") }
    Page(if (c == null) "Create channel" else "Edit channel", onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Field("Name", name) { name = it }
            Field("Phonetic name", phonetic) { phonetic = it }
            Field("Topic", topic) { topic = it }
            Field("Description", description, singleLine = false) { description = it }
            Field(if (c?.hasPassword == true) "New password (blank = unchanged)" else "Password", password, password = true) { password = it }
            if (c?.hasPassword == true) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(removePassword, { removePassword = it }); Text("Remove password") }
            Text("Type", color = Ts.TextDim)
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("Permanent", "Semi-permanent", "Temporary").forEachIndexed { i, l -> RadioButton(type == i, { type = i }); Text(l, style = MaterialTheme.typography.bodySmall) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(isDefault, { isDefault = it }); Text("Default channel") }
            Field("Max clients (blank = unlimited)", maxClients, KeyboardType.Number) { maxClients = it }
            Field("Needed talk power", talkPower, KeyboardType.Number) { talkPower = it }
            if (type == 2) Field("Delete delay (seconds)", deleteDelay, KeyboardType.Number) { deleteDelay = it }
            Text("Codec", color = Ts.TextDim)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(codec == 4, { codec = 4 }); Text("Opus Voice"); RadioButton(codec == 5, { codec = 5 }); Text("Opus Music")
            }
            SliderRow("Quality", quality, 0f..10f, quality.toInt().toString()) { quality = it }
            Button(enabled = name.isNotBlank(), onClick = {
                scope.launch {
                    val props = buildList {
                        fun put(k: String, value: String, old: String?) { if (c == null || value != old) add(KeyValue(k, value)) }
                        put("channel_name", name, c?.name)
                        put("channel_name_phonetic", phonetic, c?.phoneticName)
                        put("channel_topic", topic, c?.topic)
                        put("channel_description", description, c?.description)
                        if (password.isNotBlank()) add(KeyValue("channel_password", password))
                        if (removePassword) add(KeyValue("channel_password", ""))
                        val oldType = when { c == null -> -1; c.isPermanent -> 0; c.isSemiPermanent -> 1; else -> 2 }
                        if (type != oldType) {
                            add(KeyValue("channel_flag_permanent", if (type == 0) "1" else "0"))
                            add(KeyValue("channel_flag_semi_permanent", if (type == 1) "1" else "0"))
                        }
                        if (isDefault && c?.isDefault != true) add(KeyValue("channel_flag_default", "1"))
                        val mc = maxClients.toIntOrNull()
                        if (mc != (c?.maxClients?.takeIf { it >= 0 })) {
                            if (mc == null) add(KeyValue("channel_flag_maxclients_unlimited", "1"))
                            else { add(KeyValue("channel_flag_maxclients_unlimited", "0")); add(KeyValue("channel_maxclients", mc.toString())) }
                        }
                        put("channel_needed_talk_power", talkPower.ifBlank { "0" }, c?.neededTalkPower?.toString())
                        if (type == 2) put("channel_delete_delay", deleteDelay.ifBlank { "0" }, c?.deleteDelaySecs?.toString())
                        put("channel_codec", codec.toString(), if (c == null) null else if (c.codec.contains("Music")) "5" else "4")
                        put("channel_codec_quality", quality.toInt().toString(), c?.codecQuality?.toString())
                        if (c == null && parent != 0uL) add(KeyValue("cpid", parent.toString()))
                    }
                    if (props.isEmpty()) { onBack(); return@launch }
                    runCatching { session.await(if (c == null) session.client.createChannel(props) else session.client.editChannel(c.id, props)) }
                        .onSuccess { onBack() }.onFailure { toast(ctx, it) }
                }
            }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text(if (c == null) "Create" else "Save") }
        }
    }
}
