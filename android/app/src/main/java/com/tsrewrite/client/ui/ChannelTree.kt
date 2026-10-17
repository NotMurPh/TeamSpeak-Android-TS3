package com.tsrewrite.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsrewrite.client.Settings
import uniffi.tscore.ChannelView
import uniffi.tscore.ClientView
import uniffi.tscore.ServerView

sealed class TreeSel {
    data object Server : TreeSel()
    data class Channel(val id: ULong) : TreeSel()
    data class Client(val id: UShort) : TreeSel()
}

private sealed class Row {
    data class Chan(val c: ChannelView, val depth: Int, val expandable: Boolean) : Row()
    data class Cli(val c: ClientView, val depth: Int) : Row()
}

/**
 * Geometry of the TS3 Windows tree in its 96-dpi pixels (measured from the real client):
 * rows ~1.55 em, 16 px icons, 9 pt (12 px) text, 19 px indent per level, the expand arrow in the column
 * left of a channel's icon, text 22 px after the row's indent. [Metrics] scales those pixels to dp.
 */
private class Metrics(s: Float) {
    val px = s
    val row: Dp = (18.6f * s).dp
    val icon: Dp = (16f * s).dp
    val indent: Dp = (19f * s).dp
    val iconStart: Dp = (3f * s).dp
    val textStart: Dp = (22f * s).dp
    val gap: Dp = (2f * s).dp
    val font = (12f * s).sp
}

private val spacerRe = Regex("""^\[(([clr])|(\*))?spacer[^\]]*](.*)$""")

/**
 * The channel tree laid out like the TS3 Windows client (dark colors). Tap selects, double tap joins a
 * channel / opens a private chat, long press opens the context menu, the arrow collapses a channel.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelTree(
    v: ServerView,
    talking: Map<UShort, Boolean>,
    icons: Map<Long, ImageBitmap>,
    badges: Map<String, ImageBitmap>,
    badgeIds: (String) -> List<String>,
    settings: Settings,
    selection: TreeSel?,
    onSelect: (TreeSel) -> Unit,
    onActivate: (TreeSel) -> Unit,
    onMenu: (TreeSel) -> Unit,
    modifier: Modifier = Modifier,
) {
    var collapsed by rememberSaveable { mutableStateOf(setOf<Long>()) }
    val rows = remember(v, collapsed) { flatten(v, collapsed) }
    val groupsById = remember(v) { v.serverGroups.associateBy { it.id } }
    val cgroupsById = remember(v) { v.channelGroups.associateBy { it.id } }
    val channelsById = remember(v) { v.channels.associateBy { it.id } }
    val m = remember(settings.treeTextScale) { Metrics(1.15f * settings.treeTextScale) }
    // Like TS3 on Windows, every row is a left-to-right paragraph; Persian/Arabic runs flow inside it
    // instead of turning the whole row right-to-left.
    val style = TextStyle(fontSize = m.font, lineHeight = m.font * 1.2f, color = Ts.Text, textDirection = TextDirection.Ltr)

    LazyColumn(modifier.fillMaxSize().padding(top = 2.dp)) {
        item(key = "server") {
            TreeRow(m, selection == TreeSel.Server, { onSelect(TreeSel.Server) }, { onActivate(TreeSel.Server) }, { onMenu(TreeSel.Server) }) {
                Spacer(Modifier.width((2f * m.px).dp))
                ServerIcon(m.icon)
                Spacer(Modifier.width((3f * m.px).dp))
                Text(TsText.of(v.name), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                RightIcons(m) { TsIcon(v.iconId.toLong(), icons, m.icon) }
            }
        }
        items(
            rows,
            key = { when (it) { is Row.Chan -> "c${it.c.id}"; is Row.Cli -> "u${it.c.id}" } },
            contentType = { if (it is Row.Chan) 0 else 1 },
        ) { row ->
            when (row) {
                is Row.Chan -> {
                    val c = row.c
                    val sel = TreeSel.Channel(c.id)
                    val spacer = if (c.parentId == 0uL) spacerRe.find(c.name) else null
                    TreeRow(m, selection == sel, { onSelect(sel) }, { onActivate(sel) }, { onMenu(sel) }) {
                        Spacer(Modifier.width(m.indent * (row.depth - 1)))
                        Arrow(m, row.expandable, c.id.toLong() in collapsed) {
                            val id = c.id.toLong(); collapsed = if (id in collapsed) collapsed - id else collapsed + id
                        }
                        if (spacer != null) {
                            SpacerLine(spacer.groupValues[2], spacer.groupValues[3].isNotEmpty(), spacer.groupValues[4], style, m)
                            Spacer(Modifier.width(m.indent))
                        } else {
                            Spacer(Modifier.width(m.iconStart))
                            val full = c.maxClients >= 0 && c.clientCount.toInt() >= c.maxClients
                            ChannelIcon(
                                when { full -> ChannelState.FULL; c.hasPassword -> ChannelState.PASSWORD; else -> ChannelState.OPEN },
                                c.subscribed || c.id == v.ownChannelId, m.icon,
                            )
                            Spacer(Modifier.width(m.textStart - m.iconStart - m.icon))
                            Text(TsText.of(c.name), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            RightIcons(m) {
                                if (c.isDefault) FlagIcon("default", m.icon)
                                if (c.hasPassword) FlagIcon("password", m.icon)
                                if (c.codec.contains("Music")) FlagIcon("music", m.icon)
                                if (c.neededTalkPower > 0) FlagIcon("moderated", m.icon)
                                TsIcon(c.iconId.toLong(), icons, m.icon)
                            }
                        }
                    }
                }
                is Row.Cli -> {
                    val cl = row.c
                    val sel = TreeSel.Client(cl.id)
                    val whisper = talking[cl.id]
                    val own = cl.id == v.ownClientId
                    val state = when {
                        cl.isQuery -> ClientState.QUERY
                        !cl.outputHardware -> ClientState.HW_OUTPUT_OFF
                        cl.outputMuted -> ClientState.OUTPUT_MUTED
                        !cl.inputHardware -> ClientState.HW_INPUT_OFF
                        cl.inputMuted -> ClientState.INPUT_MUTED
                        whisper == true -> ClientState.WHISPERING
                        whisper == false -> ClientState.TALKING
                        cl.away -> ClientState.AWAY
                        else -> ClientState.IDLE
                    }
                    TreeRow(m, selection == sel, { onSelect(sel) }, { onActivate(sel) }, { onMenu(sel) }) {
                        Spacer(Modifier.width(m.indent * row.depth + m.iconStart))
                        ClientStatusIcon(state, cl.isChannelCommander, m.icon)
                        Spacer(Modifier.width(m.textStart - m.iconStart - m.icon))
                        val name = remember(cl, own, groupsById, cgroupsById) { buildAnnotatedString {
                            val groups = (listOfNotNull(cgroupsById[cl.channelGroup]) + cl.serverGroups.mapNotNull { groupsById[it] }.sortedBy { it.sortId })
                            groups.filter { it.namingMode == 1.toUByte() }.forEach { append("[${it.name}] ") }
                            withStyle(SpanStyle(fontWeight = if (own) FontWeight.Bold else FontWeight.Normal, color = if (cl.locallyMuted) Ts.TextFaint else Ts.Text)) {
                                append(TsText.of(cl.name))
                            }
                            groups.filter { it.namingMode == 2.toUByte() }.forEach { append(" [${it.name}]") }
                            if (cl.away && cl.awayMessage.isNotBlank()) append(" [${cl.awayMessage}]")
                            if (cl.isRecording) withStyle(SpanStyle(color = Ts.Red)) { append(" [RECORDING]") }
                        } }
                        Text(name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        RightIcons(m) {
                            if (cl.talkRequest) FlagIcon("talkrequest", m.icon)
                            if (cl.isPrioritySpeaker) FlagIcon("priority", m.icon)
                            if (cl.isTalker) FlagIcon("talker", m.icon)
                            if (settings.showBadges) badgeIds(cl.badges).forEach { id -> badges[id]?.let { CrispImage(it, m.icon) } }
                            if (settings.showGroupIcons) {
                                cgroupsById[cl.channelGroup]?.let { TsIcon(it.iconId.toLong(), icons, m.icon) }
                                cl.serverGroups.mapNotNull { groupsById[it] }.sortedBy { it.sortId }.forEach { TsIcon(it.iconId.toLong(), icons, m.icon) }
                            }
                            TsIcon(cl.iconId.toLong(), icons, m.icon)
                            if (settings.showFlags && cl.country.isNotBlank()) {
                                Box(Modifier.size(m.icon), contentAlignment = Alignment.Center) { Text(flagEmoji(cl.country), fontSize = m.font * 0.95f) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(m: Metrics, selected: Boolean, onTap: () -> Unit, onDouble: () -> Unit, onLong: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(m.row)
            .combinedClickable(onClick = onTap, onDoubleClick = onDouble, onLongClick = onLong)
            .padding(start = (2f * m.px).dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** TS3's expand/collapse chevron, drawn in the column left of the channel icon. */
@Composable
private fun Arrow(m: Metrics, visible: Boolean, collapsed: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(m.indent, m.row).clickable(enabled = visible, onClick = onClick), contentAlignment = Alignment.Center) {
        if (visible) Canvas(Modifier.size((8f * m.px).dp)) {
            val w = size.width
            val c = Color(0xFFB0B6BB)
            if (collapsed) {
                drawLine(c, Offset(w * 0.3f, w * 0.1f), Offset(w * 0.7f, w * 0.5f), w * 0.14f, StrokeCap.Round)
                drawLine(c, Offset(w * 0.7f, w * 0.5f), Offset(w * 0.3f, w * 0.9f), w * 0.14f, StrokeCap.Round)
            } else {
                drawLine(c, Offset(w * 0.1f, w * 0.3f), Offset(w * 0.5f, w * 0.7f), w * 0.14f, StrokeCap.Round)
                drawLine(c, Offset(w * 0.5f, w * 0.7f), Offset(w * 0.9f, w * 0.3f), w * 0.14f, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun RowScope.RightIcons(m: Metrics, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(m.gap), content = content)
}

/** TS3 spacers: [cspacer] centered, [rspacer] right, [*spacer] repeated; "---", "...", "-.-", "___", "-.." draw lines. */
@Composable
private fun RowScope.SpacerLine(align: String, repeat: Boolean, text: String, style: TextStyle, m: Metrics) {
    if (repeat && text in setOf("---", "...", "-.-", "___", "-..")) {
        Canvas(Modifier.weight(1f).height(m.row)) {
            val y = size.height / 2
            val px = m.px * density
            val effect = when (text) {
                "..." -> PathEffect.dashPathEffect(floatArrayOf(1f * px, 2f * px))
                "-.-" -> PathEffect.dashPathEffect(floatArrayOf(6f * px, 2f * px, 1f * px, 2f * px))
                "-.." -> PathEffect.dashPathEffect(floatArrayOf(6f * px, 2f * px, 1f * px, 2f * px, 1f * px, 2f * px))
                else -> null
            }
            drawLine(Ts.TextDim, Offset(0f, y), Offset(size.width, y), strokeWidth = (if (text == "___") 2f else 1f) * px, pathEffect = effect)
        }
        return
    }
    if (repeat && text.isNotEmpty()) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val measurer = rememberTextMeasurer()
            val unit = remember(text, style) { measurer.measure(TsText.of(text), style).size.width }.coerceAtLeast(1)
            val avail = with(LocalDensity.current) { maxWidth.roundToPx() }
            Text(TsText.of(text.repeat(maxOf(1, avail / unit))), style = style, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false)
        }
        return
    }
    Text(
        TsText.of(text), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        textAlign = when (align) { "c" -> TextAlign.Center; "r" -> TextAlign.End; else -> TextAlign.Start },
    )
}

/** Depth 1 = top-level channel (child of the server row); clients are one level deeper than their channel. */
private fun flatten(v: ServerView, collapsed: Set<Long>): List<Row> {
    val byChannel = v.clients.groupBy { it.channelId }
    val out = mutableListOf<Row>()
    var hideBelow = Int.MAX_VALUE
    for (c in v.channels) {
        val d = c.depth.toInt() + 1
        if (d > hideBelow) continue
        hideBelow = Int.MAX_VALUE
        val clients = byChannel[c.id].orEmpty()
        out += Row.Chan(c, d, c.hasChildren || clients.isNotEmpty())
        if (c.id.toLong() in collapsed) { hideBelow = d; continue }
        clients.forEach { out += Row.Cli(it, d + 1) }
    }
    return out
}
