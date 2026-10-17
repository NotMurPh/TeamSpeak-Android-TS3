package com.tsrewrite.client

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

private val tags = Regex("""\[(/?)(b|i|u|s|url|color|size|img|left|right|center|quote|list|\*|time|hr|table|tr|td|th)(=[^\]]*)?]""", RegexOption.IGNORE_CASE)
private val bareUrl = Regex("""https?://[^\s\[\]]+""")

fun stripBbcode(s: String) = s.replace(tags, "")

/** Renders TS3 BBCode (bold, italic, underline, strike, color, url) to an AnnotatedString. */
fun renderBbcode(src: String, link: Color): AnnotatedString = buildAnnotatedString {
    val stack = ArrayDeque<String>()
    var pos = 0
    for (m in tags.findAll(src)) {
        appendPlain(src.substring(pos, m.range.first), link)
        pos = m.range.last + 1
        val closing = m.groupValues[1] == "/"
        val tag = m.groupValues[2].lowercase()
        val arg = m.groupValues[3].removePrefix("=").trim('"')
        if (closing) {
            if (stack.lastOrNull() == tag) { stack.removeLast(); pop() }
            continue
        }
        val style = when (tag) {
            "b" -> SpanStyle(fontWeight = FontWeight.Bold)
            "i" -> SpanStyle(fontStyle = FontStyle.Italic)
            "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
            "s" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            "color" -> parseColor(arg)?.let { SpanStyle(color = it) }
            else -> null
        }
        if (tag == "url") {
            val end = src.indexOf("[/url]", pos, ignoreCase = true).takeIf { it >= 0 } ?: src.length
            val text = src.substring(pos, end)
            val url = arg.ifBlank { text }
            pushLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))))
            append(stripBbcode(text)); pop()
            pos = minOf(src.length, end + 6)
            continue
        }
        if (style != null) { pushStyle(style); stack.addLast(tag) }
    }
    appendPlain(src.substring(minOf(pos, src.length)), link)
    while (stack.isNotEmpty()) { stack.removeLast(); pop() }
}

private fun AnnotatedString.Builder.appendPlain(s: String, link: Color) {
    var p = 0
    for (m in bareUrl.findAll(s)) {
        append(s.substring(p, m.range.first))
        pushLink(LinkAnnotation.Url(m.value, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))))
        append(m.value); pop()
        p = m.range.last + 1
    }
    append(s.substring(p))
}

private val named = mapOf(
    "red" to 0xFFFF4444, "green" to 0xFF44CC44, "blue" to 0xFF4488FF, "yellow" to 0xFFFFDD33, "orange" to 0xFFFF9900,
    "purple" to 0xFFAA44CC, "white" to 0xFFFFFFFF, "black" to 0xFF000000, "gray" to 0xFF888888, "grey" to 0xFF888888,
    "pink" to 0xFFFF77AA, "cyan" to 0xFF33DDDD, "brown" to 0xFF996633, "darkred" to 0xFF8B0000, "darkgreen" to 0xFF006400,
    "darkblue" to 0xFF00008B, "lime" to 0xFF00FF00, "navy" to 0xFF000080, "teal" to 0xFF008080, "maroon" to 0xFF800000,
)

private fun parseColor(s: String): Color? {
    named[s.lowercase()]?.let { return Color(it) }
    val h = s.removePrefix("#")
    return when (h.length) {
        6 -> h.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
        3 -> h.map { "$it$it" }.joinToString("").toLongOrNull(16)?.let { Color(0xFF000000 or it) }
        else -> null
    }
}
