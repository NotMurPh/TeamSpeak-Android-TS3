package com.tsrewrite.client.ui

import android.graphics.Paint
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.withStyle
import com.tsrewrite.client.R

/**
 * Text for channel and client names as TS3 on Windows shows it: characters missing from Android's fonts
 * (e.g. ✦) use the bundled Noto Sans Symbols 2, and line-art characters are stretched to the advance they
 * have on Windows so spacer art spans the same width relative to the text.
 *
 * Windows draws Segoe UI and falls back per character: the light/double box characters of WGL4 come from
 * Tahoma (~0.72 em), heavy box characters from a full-width CJK font (~1 em). Measured from the real client.
 */
object TsText {
    private val symbols = FontFamily(Font(R.font.noto_symbols2))
    private val paint = Paint().apply { textSize = 100f }
    private val scales = HashMap<Int, Float>()
    private val cache = object : LinkedHashMap<String, AnnotatedString>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnnotatedString>?) = size > 2000
    }

    /** Light single and double box drawing in WGL4 (Tahoma has them). */
    private val wgl4 = setOf(0x2500, 0x2502, 0x250C, 0x2510, 0x2514, 0x2518, 0x251C, 0x2524, 0x252C, 0x2534, 0x253C) +
        (0x2550..0x256C) + setOf(0x2580, 0x2584, 0x2588, 0x258C, 0x2590, 0x2591, 0x2592, 0x2593)

    /** Advance on Windows in em, or null to keep the Android width. */
    private fun windowsEm(cp: Int): Float? = when {
        cp in wgl4 -> 0.72f
        cp in 0x2504..0x250B || cp in 0x254C..0x254F || cp in 0x256D..0x257F -> 0.72f
        cp in 0x2500..0x257F -> 1.0f // heavy and mixed weights
        cp in 0x2581..0x259F -> 0.72f
        cp == 0x203E -> 0.4f // overline
        else -> null
    }

    private fun scaleOf(cp: Int, s: String): Float? = synchronized(scales) {
        scales.getOrPut(cp) {
            val em = windowsEm(cp) ?: return@getOrPut 1f
            val w = paint.measureText(s)
            if (w > 0f) (em * 100f / w).coerceIn(0.3f, 3f) else 1f
        }
    }.takeIf { it != 1f }

    fun of(text: String): AnnotatedString = synchronized(cache) { cache[text] } ?: build(text).also { synchronized(cache) { cache[text] = it } }

    private fun build(text: String) = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            val scale = if (cp >= 0x2000) scaleOf(cp, s) else null
            when {
                scale != null -> withStyle(SpanStyle(textGeometricTransform = TextGeometricTransform(scaleX = scale))) { append(s) }
                cp > 0x2000 && !Character.isSurrogate(text[i]) && !paint.hasGlyph(s) -> withStyle(SpanStyle(fontFamily = symbols)) { append(s) }
                else -> append(s)
            }
            i += Character.charCount(cp)
        }
    }
}
