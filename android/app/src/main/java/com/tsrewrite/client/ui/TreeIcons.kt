package com.tsrewrite.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

/* Flat, minimal tree icons in the TeamSpeak palette. */

enum class ChannelState { OPEN, PASSWORD, FULL }

private val ChannelBlue = Color(0xFF4A9FD8)
private val ChannelAmber = Color(0xFFF2B33D)
private val ChannelRed = Color(0xFFE5534B)

/** Flat speech bubble; hollow when not subscribed. */
@Composable
fun ChannelIcon(state: ChannelState, subscribed: Boolean, size: Dp) {
    val color = when (state) { ChannelState.OPEN -> ChannelBlue; ChannelState.PASSWORD -> ChannelAmber; ChannelState.FULL -> ChannelRed }
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 16f
        val bubble = Path().apply {
            addRoundRect(RoundRect(1.5f * u, 2.5f * u, 14.5f * u, 11.5f * u, CornerRadius(3f * u)))
            moveTo(4f * u, 11f * u); lineTo(3.5f * u, 14.5f * u); lineTo(7.5f * u, 11f * u); close()
        }
        if (subscribed) drawPath(bubble, color) else drawPath(bubble, color, style = Stroke(1.4f * u))
    }
}

@Composable
fun ServerIcon(size: Dp) = Icon(Icons.Rounded.Dns, null, tint = Ts.Blue, modifier = Modifier.size(size))

enum class ClientState { IDLE, TALKING, WHISPERING, AWAY, INPUT_MUTED, OUTPUT_MUTED, HW_INPUT_OFF, HW_OUTPUT_OFF, QUERY }

/** Flat circle talk indicator: grey idle, blue talking, purple whispering, hollow when away. Mutes show a glyph. */
@Composable
fun ClientStatusIcon(state: ClientState, commander: Boolean, size: Dp) {
    when (state) {
        ClientState.OUTPUT_MUTED -> Glyph(Icons.Rounded.HeadsetOff, Ts.Red.copy(alpha = 0.9f), size)
        ClientState.HW_OUTPUT_OFF -> Glyph(Icons.Rounded.HeadsetOff, Ts.TextFaint, size)
        ClientState.INPUT_MUTED -> Glyph(Icons.Rounded.MicOff, Ts.Red.copy(alpha = 0.9f), size)
        ClientState.HW_INPUT_OFF -> Glyph(Icons.Rounded.MicOff, Ts.TextFaint, size)
        ClientState.QUERY -> Glyph(Icons.Rounded.Terminal, Ts.TextDim, size)
        else -> Canvas(Modifier.size(size)) {
            val r = this.size.width * 0.31f
            val c = center
            when (state) {
                ClientState.TALKING -> { drawCircle(Ts.Blue.copy(alpha = 0.28f), r * 1.55f, c); drawCircle(Ts.Blue, r, c) }
                ClientState.WHISPERING -> { drawCircle(Ts.Purple.copy(alpha = 0.28f), r * 1.55f, c); drawCircle(Ts.Purple, r, c) }
                ClientState.AWAY -> drawCircle(Ts.TextFaint, r * 0.9f, c, style = Stroke(this.size.width * 0.09f))
                else -> drawCircle(Color(0xFF59626B), r, c)
            }
            if (commander) drawCircle(Ts.Orange, r * 1.45f, c, style = Stroke(this.size.width * 0.08f))
        }
    }
}

@Composable
private fun Glyph(icon: ImageVector, tint: Color, size: Dp) = Icon(icon, null, tint = tint, modifier = Modifier.size(size))

/** Right-side channel/client flags. */
@Composable
fun FlagIcon(kind: String, size: Dp) = when (kind) {
    "default" -> Glyph(Icons.Rounded.Home, Ts.TextDim, size)
    "password" -> Glyph(Icons.Rounded.Lock, ChannelAmber, size)
    "music" -> Glyph(Icons.Rounded.MusicNote, Ts.TextDim, size)
    "moderated" -> Glyph(Icons.Rounded.RecordVoiceOver, Ts.TextDim, size)
    "talkrequest" -> Glyph(Icons.Rounded.PanTool, Ts.Orange, size)
    "priority" -> Glyph(Icons.Rounded.RecordVoiceOver, Ts.Green, size)
    "talker" -> Glyph(Icons.Rounded.Mic, Ts.Green, size)
    else -> {}
}

/**
 * Server-provided images (group/channel/client icons, badges). Small icons arrive already upscaled by
 * [com.tsrewrite.client.PixelScale] and are filtered smoothly to size; anything still tiny is scaled by a whole
 * factor with nearest-neighbour sampling.
 */
@Composable
fun CrispImage(img: ImageBitmap, size: Dp) {
    val density = LocalDensity.current
    val targetPx = with(density) { size.toPx() }
    val src = max(img.width, img.height)
    if (src <= 32 && targetPx > src) {
        val k = max(1, (targetPx / src).roundToInt())
        Image(img, null, Modifier.size(with(density) { (src * k).toDp() }), filterQuality = FilterQuality.None)
    } else {
        Image(img, null, Modifier.size(size), filterQuality = FilterQuality.High)
    }
}

/** Server/group/client icon: a downloaded image, or a flat badge for the built-in ids (100..600). */
@Composable
fun TsIcon(id: Long, images: Map<Long, ImageBitmap>, size: Dp) {
    if (id == 0L) return
    val img = images[id]
    if (img != null) { CrispImage(img, size); return }
    val builtin = when (id) {
        100L -> Color(0xFFE53935) to "A"
        200L -> Color(0xFFFB8C00) to "O"
        300L -> Color(0xFF43A047) to "V"
        500L -> Color(0xFF1E88E5) to "S"
        600L -> Color(0xFF8E24AA) to "M"
        else -> null
    } ?: return
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) { drawRoundRect(builtin.first, cornerRadius = CornerRadius(this.size.width * 0.25f)) }
        Text(builtin.second, color = Color.White, fontSize = (size.value * 0.58f).sp, fontWeight = FontWeight.Bold)
    }
}

/** Two-letter country code to its flag emoji. */
fun flagEmoji(code: String): String =
    if (code.length != 2 || !code.all { it.isLetter() }) "" else code.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")

