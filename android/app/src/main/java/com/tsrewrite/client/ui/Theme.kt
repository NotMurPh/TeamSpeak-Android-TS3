package com.tsrewrite.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** TeamSpeak palette: near-black charcoal surfaces with the TeamSpeak blue accent. */
object Ts {
    val Blue = Color(0xFF1A91D0)
    val BlueDim = Color(0xFF14709F)
    val Bg = Color(0xFF111111)
    val Surface = Color(0xFF181A1C)
    val SurfaceHigh = Color(0xFF212325)
    val SurfaceHighest = Color(0xFF2A2D30)
    val Outline = Color(0xFF34383C)
    val Text = Color(0xFFE6E8EA)
    val TextDim = Color(0xFF9BA1A6)
    val TextFaint = Color(0xFF6B7176)
    val Red = Color(0xFFF44336)
    val Green = Color(0xFF4CAF50)
    val Yellow = Color(0xFFFFC107)
    val Orange = Color(0xFFFF9800)
    val Purple = Color(0xFFAB47BC)
    val Selection = Color(0x331A91D0)
}

private val scheme = darkColorScheme(
    primary = Ts.Blue,
    onPrimary = Color.White,
    primaryContainer = Ts.BlueDim,
    onPrimaryContainer = Color.White,
    secondary = Ts.Blue,
    onSecondary = Color.White,
    secondaryContainer = Ts.SurfaceHighest,
    onSecondaryContainer = Ts.Text,
    tertiary = Ts.Green,
    background = Ts.Bg,
    onBackground = Ts.Text,
    surface = Ts.Bg,
    onSurface = Ts.Text,
    surfaceVariant = Ts.SurfaceHigh,
    onSurfaceVariant = Ts.TextDim,
    surfaceContainerLowest = Ts.Bg,
    surfaceContainerLow = Ts.Surface,
    surfaceContainer = Ts.Surface,
    surfaceContainerHigh = Ts.SurfaceHigh,
    surfaceContainerHighest = Ts.SurfaceHighest,
    outline = Ts.Outline,
    outlineVariant = Ts.Outline,
    error = Ts.Red,
)

private val type = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(letterSpacing = 0.4.sp),
    )
}

@Composable
fun TsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = type,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}

val TreeText = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, color = Ts.Text)
