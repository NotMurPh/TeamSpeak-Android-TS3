package com.tsrewrite.client

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Upscaling for the tiny (usually 16x16) server/group icons, done on the phone once per icon.
 *
 * Resampled 8x with Catmull-Rom in premultiplied alpha, then lightly blurred (0.2 source pixels) so the
 * stair-steps of the 16 px raster are anti-aliased away without changing the line weight, then
 * box-filtered down to 4x.
 */
object PixelScale {
    /** Images at or below this size are upscaled. */
    private const val SMALL = 32
    private const val WORK = 8
    private const val OUT = 4
    /** Blur in work pixels (0.2 source pixels): enough to hide the steps, not enough to thicken lines. */
    private const val SIGMA = 1.6f
    /** Alpha curve exponent; 1 keeps the original line weight. */
    private const val GAMMA = 1f

    fun upscale(b: Bitmap): Bitmap {
        if (maxOf(b.width, b.height) > SMALL) return b
        val w = b.width
        val h = b.height
        val src = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val pm = Array(4) { FloatArray(w * h) }
        for (i in src.indices) {
            val a = (src[i] ushr 24) / 255f
            pm[0][i] = a
            pm[1][i] = ((src[i] shr 16) and 0xFF) / 255f * a
            pm[2][i] = ((src[i] shr 8) and 0xFF) / 255f * a
            pm[3][i] = (src[i] and 0xFF) / 255f * a
        }
        val ww = w * WORK
        val wh = h * WORK
        val big = Array(4) { FloatArray(ww * wh) }
        bicubic(pm, w, h, big, ww, wh)
        big.forEach { blur(it, ww, wh) }

        // Apply the alpha curve, keep colour from the smooth version, then box-filter to the output size.
        val f = WORK / OUT
        val ow = w * OUT
        val oh = h * OUT
        val out = IntArray(ow * oh)
        for (oy in 0 until oh) for (ox in 0 until ow) {
            var a = 0f; var r = 0f; var g = 0f; var bl = 0f
            for (dy in 0 until f) for (dx in 0 until f) {
                val k = (oy * f + dy) * ww + ox * f + dx
                val sa = big[0][k].coerceIn(0f, 1f)
                val na = sharpen(sa)
                if (na <= 0f) continue
                val inv = 1f / maxOf(sa, 1e-4f)
                // Accumulate premultiplied by the new alpha.
                a += na
                r += (big[1][k] * inv).coerceIn(0f, 1f) * na
                g += (big[2][k] * inv).coerceIn(0f, 1f) * na
                bl += (big[3][k] * inv).coerceIn(0f, 1f) * na
            }
            val n = (f * f).toFloat()
            out[oy * ow + ox] = if (a <= 0f) 0 else {
                fun ch(v: Float) = (v / a * 255).roundToInt().coerceIn(0, 255)
                ((a / n * 255).roundToInt().coerceIn(0, 255) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(bl)
            }
        }
        return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
    }

    private fun sharpen(a: Float): Float = if (a <= 0f) 0f else if (GAMMA == 1f) a else a.toDouble().pow(GAMMA.toDouble()).toFloat()

    private fun bicubic(src: Array<FloatArray>, w: Int, h: Int, dst: Array<FloatArray>, dw: Int, dh: Int) {
        val wx = FloatArray(4)
        val wy = FloatArray(4)
        for (oy in 0 until dh) {
            val sy = (oy + 0.5f) / WORK - 0.5f
            val y0 = floor(sy).toInt()
            weights(sy - y0, wy)
            for (ox in 0 until dw) {
                val sx = (ox + 0.5f) / WORK - 0.5f
                val x0 = floor(sx).toInt()
                weights(sx - x0, wx)
                val o = oy * dw + ox
                for (c in 0 until 4) {
                    var acc = 0f
                    for (j in 0 until 4) {
                        val row = (y0 - 1 + j).coerceIn(0, h - 1) * w
                        for (i in 0 until 4) acc += src[c][row + (x0 - 1 + i).coerceIn(0, w - 1)] * wx[i] * wy[j]
                    }
                    dst[c][o] = acc
                }
            }
        }
    }

    /** Catmull-Rom weights for the four taps around a sample at fraction [t]. */
    private fun weights(t: Float, w: FloatArray) {
        for (i in 0 until 4) {
            val x = abs(t - (i - 1))
            w[i] = when {
                x < 1f -> 1.5f * x * x * x - 2.5f * x * x + 1f
                x < 2f -> -0.5f * x * x * x + 2.5f * x * x - 4f * x + 2f
                else -> 0f
            }
        }
    }

    /** Separable Gaussian blur in place. */
    private fun blur(p: FloatArray, w: Int, h: Int) {
        val r = (SIGMA * 3).toInt()
        val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * SIGMA * SIGMA)) }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        val tmp = FloatArray(p.size)
        for (y in 0 until h) for (x in 0 until w) {
            var acc = 0f
            for (i in -r..r) acc += p[y * w + (x + i).coerceIn(0, w - 1)] * k[i + r]
            tmp[y * w + x] = acc
        }
        for (y in 0 until h) for (x in 0 until w) {
            var acc = 0f
            for (i in -r..r) acc += tmp[(y + i).coerceIn(0, h - 1) * w + x] * k[i + r]
            p[y * w + x] = acc
        }
    }
}
