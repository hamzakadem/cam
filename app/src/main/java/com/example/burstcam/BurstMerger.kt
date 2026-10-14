package com.example.burstcam

import android.graphics.Bitmap
import java.util.stream.IntStream
import kotlin.math.*

/** One captured YUV frame (dimensions already cropped to multiples of 16). */
class Frame(val y: ByteArray, val u: ByteArray, val v: ByteArray)

/**
 * Streaming multi-frame merger.
 *  - first frame = reference
 *  - every next frame: 3-level pyramid block matching + sub-pixel refinement
 *  - scale == 1 : robust weighted average (multi-frame denoise)
 *  - scale == 2 : shift-and-add splatting onto a 2x grid (multi-frame super resolution)
 *  - gain > 1   : HDR+ style tone mapping (frames were captured under-exposed)
 */
class BurstMerger(
    private val w: Int,
    private val h: Int,
    private val scale: Int,
    private val sigma: Float,
    gainIn: Float
) {
    private val gain = max(1f, gainIn)
    private val T = 16
    private val tw = w / T
    private val th = h / T
    private val ow = w * scale
    private val oh = h * scale
    private val cw = w / 2
    private val ch = h / 2
    private val w4 = w / 4
    private val h4 = h / 4
    private val w8 = w4 / 2
    private val h8 = h4 / 2

    private val sumY = FloatArray(ow * oh)
    private val sumW = FloatArray(ow * oh)
    private val sumU = FloatArray(cw * ch)
    private val sumV = FloatArray(cw * ch)
    private val cnt = FloatArray(cw * ch)

    private var ref: Frame? = null
    private var ref4 = ByteArray(0)
    private var ref8 = ByteArray(0)

    private val vx = FloatArray(tw * th)
    private val vy = FloatArray(tw * th)
    private val q = FloatArray(tw * th)

    // tolerance of the pixel-similarity weight
    private val s2 = sigma * sigma * 2f * (if (scale > 1) 2f else 1f)

    private fun par(n: Int, body: (Int) -> Unit) {
        IntStream.range(0, n).parallel().forEach { body(it) }
    }

    // ------------------------------------------------------------------ public

    fun add(f: Frame) {
        if (ref == null) {
            ref = f
            ref4 = down(f.y, w, h, 4)
            ref8 = down(ref4, w4, h4, 2)
            vx.fill(0f); vy.fill(0f); q.fill(0f)
        } else {
            align(f)
        }
        mergeFrame(f)
    }

    fun finish(): Bitmap {
        // normalise luma
        par(oh) { y ->
            for (x in 0 until ow) {
                val o = y * ow + x
                sumY[o] = sumY[o] / sumW[o]
            }
        }
        // unsharp mask (sumW reused as blur buffer)
        val amt = if (scale > 1) 0.7f else 0.3f
        par(oh) { y ->
            val ya = max(y - 1, 0); val yb = min(y + 1, oh - 1)
            for (x in 0 until ow) {
                val xa = max(x - 1, 0); val xb = min(x + 1, ow - 1)
                var s = 0f
                for (yy in intArrayOf(ya, y, yb)) for (xx in intArrayOf(xa, x, xb)) s += sumY[yy * ow + xx]
                sumW[y * ow + x] = s / 9f
            }
        }
        val cu = FloatArray(cw * ch) { sumU[it] / cnt[it] }
        val cv = FloatArray(cw * ch) { sumV[it] / cnt[it] }

        // tone LUT (index = value*4)
        val lut = FloatArray(1024)
        for (i in 0 until 1024) {
            val v = (i / 4f) / 255f
            if (gain < 1.05f) {
                lut[i] = i / 4f
            } else {
                val lin = v.pow(2.2f) * gain
                val m = lin * (1f + lin / (gain * gain)) / (1f + lin)
                lut[i] = min(m, 1f).pow(1f / 2.2f) * 255f
            }
        }

        val pix = IntArray(ow * oh)
        par(oh) { y ->
            val cyf = (y + 0.5f) / scale / 2f - 0.5f
            for (x in 0 until ow) {
                val o = y * ow + x
                val cxf = (x + 0.5f) / scale / 2f - 0.5f
                val yy0 = sumY[o]
                val yy = yy0 + amt * (yy0 - sumW[o])
                val u = bilF(cu, cw, ch, cxf, cyf) - 128f
                val v = bilF(cv, cw, ch, cxf, cyf) - 128f
                val r = (yy + 1.402f * v).coerceIn(0f, 255f)
                val g = (yy - 0.344136f * u - 0.714136f * v).coerceIn(0f, 255f)
                val b = (yy + 1.772f * u).coerceIn(0f, 255f)
                val ri = lut[(r * 4f).toInt()].toInt().coerceIn(0, 255)
                val gi = lut[(g * 4f).toInt()].toInt().coerceIn(0, 255)
                val bi = lut[(b * 4f).toInt()].toInt().coerceIn(0, 255)
                pix[o] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
        val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pix, 0, ow, 0, 0, ow, oh)
        return bmp
    }

    // --------------------------------------------------------------- alignment

    private fun align(f: Frame) {
        val r = ref!!
        val f4 = down(f.y, w, h, 4)
        val f8 = down(f4, w4, h4, 2)

        // level 2 (1/8): blocks 8x8, search +-6  (=> +-48 px at full res)
        val b2x = (w8 + 7) / 8; val b2y = (h8 + 7) / 8
        val v2x = IntArray(b2x * b2y); val v2y = IntArray(b2x * b2y)
        par(b2y) { by ->
            for (bx in 0 until b2x) {
                val p = search(ref8, f8, w8, h8, bx * 8, by * 8, 8, 0, 0, 6, 1)
                v2x[by * b2x + bx] = px(p); v2y[by * b2x + bx] = py(p)
            }
        }
        // level 1 (1/4): blocks 8x8, refine +-2
        val b1x = (w4 + 7) / 8; val b1y = (h4 + 7) / 8
        val v1x = IntArray(b1x * b1y); val v1y = IntArray(b1x * b1y)
        par(b1y) { by ->
            for (bx in 0 until b1x) {
                val pi = min(by / 2, b2y - 1) * b2x + min(bx / 2, b2x - 1)
                val p = search(ref4, f4, w4, h4, bx * 8, by * 8, 8, v2x[pi] * 2, v2y[pi] * 2, 2, 1)
                v1x[by * b1x + bx] = px(p); v1y[by * b1x + bx] = py(p)
            }
        }
        // level 0 (full): tiles 16x16, refine +-2 then sub-pixel parabola
        par(th) { ty ->
            for (tx in 0 until tw) {
                val pi = min(ty / 2, b1y - 1) * b1x + min(tx / 2, b1x - 1)
                val x0 = tx * T; val y0 = ty * T
                val p = search(r.y, f.y, w, h, x0, y0, T, v1x[pi] * 4, v1y[pi] * 4, 2, 2)
                val bx = px(p); val by = py(p)
                val c0 = sad(r.y, f.y, w, h, x0, y0, T, bx, by, 2, Int.MAX_VALUE)
                val i = ty * tw + tx
                if (c0 == Int.MAX_VALUE) {
                    vx[i] = bx.toFloat(); vy[i] = by.toFloat(); q[i] = 255f
                } else {
                    val cl = sad(r.y, f.y, w, h, x0, y0, T, bx - 1, by, 2, Int.MAX_VALUE)
                    val cr = sad(r.y, f.y, w, h, x0, y0, T, bx + 1, by, 2, Int.MAX_VALUE)
                    val cu = sad(r.y, f.y, w, h, x0, y0, T, bx, by - 1, 2, Int.MAX_VALUE)
                    val cd = sad(r.y, f.y, w, h, x0, y0, T, bx, by + 1, 2, Int.MAX_VALUE)
                    vx[i] = bx + sub(c0, cl, cr)
                    vy[i] = by + sub(c0, cu, cd)
                    q[i] = c0.toFloat() / (T * T / 2)
                }
            }
        }
    }

    private fun sub(c0: Int, cm: Int, cp: Int): Float {
        if (cm == Int.MAX_VALUE || cp == Int.MAX_VALUE) return 0f
        val d = cm.toFloat() - 2f * c0 + cp
        if (d <= 0f) return 0f
        return ((cm - cp).toFloat() / (2f * d)).coerceIn(-0.5f, 0.5f)
    }

    private fun px(p: Long) = (p and 0xffffffffL).toInt()
    private fun py(p: Long) = (p shr 32).toInt()

    private fun search(
        a: ByteArray, b: ByteArray, iw: Int, ih: Int,
        x0: Int, y0: Int, bs: Int, cx: Int, cy: Int, r: Int, step: Int
    ): Long {
        var best = Int.MAX_VALUE
        var bx = cx; var by = cy
        for (dy in cy - r..cy + r) {
            for (dx in cx - r..cx + r) {
                val c = sad(a, b, iw, ih, x0, y0, bs, dx, dy, step, best)
                if (c < best) { best = c; bx = dx; by = dy }
            }
        }
        return (by.toLong() shl 32) or (bx.toLong() and 0xffffffffL)
    }

    private fun sad(
        a: ByteArray, b: ByteArray, iw: Int, ih: Int,
        x0: Int, y0: Int, bs: Int, dx: Int, dy: Int, step: Int, limit: Int
    ): Int {
        val bw = min(bs, iw - x0); val bh = min(bs, ih - y0)
        if (x0 + dx < 0 || y0 + dy < 0 || x0 + dx + bw > iw || y0 + dy + bh > ih) return Int.MAX_VALUE
        var s = 0
        var y = 0
        while (y < bh) {
            val ia = (y0 + y) * iw + x0
            val ib = (y0 + y + dy) * iw + x0 + dx
            for (x in 0 until bw) {
                s += abs((a[ia + x].toInt() and 255) - (b[ib + x].toInt() and 255))
            }
            if (s >= limit) return s
            y += step
        }
        return s
    }

    private fun down(s: ByteArray, sw: Int, sh: Int, f: Int): ByteArray {
        val dw = sw / f; val dh = sh / f
        val o = ByteArray(dw * dh)
        par(dh) { y ->
            for (x in 0 until dw) {
                var sum = 0
                for (j in 0 until f) {
                    val row = (y * f + j) * sw + x * f
                    for (i in 0 until f) sum += s[row + i].toInt() and 255
                }
                o[y * dw + x] = (sum / (f * f)).toByte()
            }
        }
        return o
    }

    // ------------------------------------------------------------------ merge

    private fun mergeFrame(f: Frame) {
        val tol = 3f * sigma + 2f
        for (phase in 0..1) {
            val n = (th - phase + 1) / 2
            par(n) { k -> tileRow(f, phase + 2 * k, tol) }
        }
    }

    private fun tileRow(f: Frame, ty: Int, tol: Float) {
        val ry = ref!!.y
        for (tx in 0 until tw) {
            val i = ty * tw + tx
            val dxv = vx[i]; val dyv = vy[i]
            val bad = q[i] > tol
            val pen = if (bad) 0.03f else 1f
            val x0 = tx * T; val y0 = ty * T

            if (scale == 1) {
                for (y in y0 until y0 + T) for (x in x0 until x0 + T) {
                    val sv = bil(f.y, w, h, x + dxv, y + dyv)
                    val rv = (ry[y * w + x].toInt() and 255).toFloat()
                    val d = sv - rv
                    val wt = pen * s2 / (s2 + d * d)
                    val o = y * w + x
                    sumY[o] += wt * sv
                    sumW[o] += wt
                }
            } else {
                val ix = dxv.roundToInt(); val iy = dyv.roundToInt()
                val fu = ix - dxv; val fv = iy - dyv
                for (y in y0 until y0 + T) for (x in x0 until x0 + T) {
                    val xf = x + ix; val yf = y + iy
                    if (xf < 0 || yf < 0 || xf >= w || yf >= h) continue
                    val sv = (f.y[yf * w + xf].toInt() and 255).toFloat()
                    val rv = (ry[y * w + x].toInt() and 255).toFloat()
                    val d = sv - rv
                    val wt = pen * s2 / (s2 + d * d)
                    val ou = (x + fu + 0.5f) * scale - 0.5f
                    val ov = (y + fv + 0.5f) * scale - 0.5f
                    val x1 = floor(ou).toInt(); val y1 = floor(ov).toInt()
                    val ax = ou - x1; val ay = ov - y1
                    splat(x1, y1, wt * (1 - ax) * (1 - ay), sv)
                    splat(x1 + 1, y1, wt * ax * (1 - ay), sv)
                    splat(x1, y1 + 1, wt * (1 - ax) * ay, sv)
                    splat(x1 + 1, y1 + 1, wt * ax * ay, sv)
                }
            }

            // chroma (half resolution, integer shift)
            if (!bad) {
                val cdx = (dxv / 2f).roundToInt(); val cdy = (dyv / 2f).roundToInt()
                for (cy in ty * 8 until ty * 8 + 8) for (cx in tx * 8 until tx * 8 + 8) {
                    val sx = (cx + cdx).coerceIn(0, cw - 1)
                    val sy = (cy + cdy).coerceIn(0, ch - 1)
                    val o = cy * cw + cx
                    sumU[o] += (f.u[sy * cw + sx].toInt() and 255).toFloat()
                    sumV[o] += (f.v[sy * cw + sx].toInt() and 255).toFloat()
                    cnt[o] += 1f
                }
            }
        }
    }

    private fun splat(x: Int, y: Int, wt: Float, v: Float) {
        if (x < 0 || y < 0 || x >= ow || y >= oh) return
        val o = y * ow + x
        sumY[o] += wt * v
        sumW[o] += wt
    }

    private fun bil(s: ByteArray, sw: Int, sh: Int, x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, (sw - 1).toFloat()); val yc = y.coerceIn(0f, (sh - 1).toFloat())
        val x0 = xc.toInt(); val y0 = yc.toInt()
        val x1 = min(x0 + 1, sw - 1); val y1 = min(y0 + 1, sh - 1)
        val ax = xc - x0; val ay = yc - y0
        val a = (s[y0 * sw + x0].toInt() and 255); val b = (s[y0 * sw + x1].toInt() and 255)
        val c = (s[y1 * sw + x0].toInt() and 255); val d = (s[y1 * sw + x1].toInt() and 255)
        return (a * (1 - ax) + b * ax) * (1 - ay) + (c * (1 - ax) + d * ax) * ay
    }

    private fun bilF(s: FloatArray, sw: Int, sh: Int, x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, (sw - 1).toFloat()); val yc = y.coerceIn(0f, (sh - 1).toFloat())
        val x0 = xc.toInt(); val y0 = yc.toInt()
        val x1 = min(x0 + 1, sw - 1); val y1 = min(y0 + 1, sh - 1)
        val ax = xc - x0; val ay = yc - y0
        return (s[y0 * sw + x0] * (1 - ax) + s[y0 * sw + x1] * ax) * (1 - ay) +
                (s[y1 * sw + x0] * (1 - ax) + s[y1 * sw + x1] * ax) * ay
    }
}
