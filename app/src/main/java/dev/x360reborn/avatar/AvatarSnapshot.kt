package dev.x360reborn.avatar

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Software render of an [AvatarMesh] into ARGB pixels: an orthographic, z-buffered, softly lit
 * head-and-shoulders view for the profile chip. No GL context needed, so it runs on the loader
 * thread and is unit-tested; the result goes into an android Bitmap at the call site.
 */
object AvatarSnapshot {
    /** Framing: the square view centred at ([cx], [cy]) spanning [half] units each way, looking down -z. */
    fun headshot(mesh: AvatarMesh, size: Int, cx: Float = 0f, cy: Float = AvatarModel.HEAD_Y - 0.03f, half: Float = 0.33f, yaw: Float = 0.35f): IntArray {
        val px = IntArray(size * size)            // 0 = transparent
        val zb = FloatArray(size * size) { -Float.MAX_VALUE }
        val v = mesh.vertices; val n = mesh.vertexCount; val st = AvatarMesh.STRIDE
        val cs = kotlin.math.cos(yaw); val sn = kotlin.math.sin(yaw)
        val sx = FloatArray(n); val sy = FloatArray(n); val sz = FloatArray(n); val lit = FloatArray(n)
        val lx = 0.35f; val ly = 0.55f; val lz = 0.76f
        for (i in 0 until n) {
            val o = i * st
            val x = v[o] * cs + v[o + 2] * sn; val z = -v[o] * sn + v[o + 2] * cs
            sx[i] = (x - cx) / (2 * half) * size + size / 2f
            sy[i] = size / 2f - (v[o + 1] - cy) / (2 * half) * size
            sz[i] = z
            val nx = v[o + 3] * cs + v[o + 5] * sn; val nz = -v[o + 3] * sn + v[o + 5] * cs; val ny = v[o + 4]
            val d = max(0f, nx * lx + ny * ly + nz * lz)
            val rim = (1f - max(0f, nz)).let { it * it } * 0.18f
            lit[i] = 0.42f + 0.62f * d + rim
        }
        val idx = mesh.indices
        for (t in 0 until idx.size / 3) {
            val a = idx[3 * t]; val b = idx[3 * t + 1]; val c = idx[3 * t + 2]
            val x0 = sx[a]; val y0 = sy[a]; val x1 = sx[b]; val y1 = sy[b]; val x2 = sx[c]; val y2 = sy[c]
            val den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
            if (kotlin.math.abs(den) < 1e-9f) continue
            val minX = max(0, min(x0, min(x1, x2)).toInt()); val maxX = min(size - 1, max(x0, max(x1, x2)).toInt() + 1)
            val minY = max(0, min(y0, min(y1, y2)).toInt()); val maxY = min(size - 1, max(y0, max(y1, y2)).toInt() + 1)
            for (yy in minY..maxY) for (xx in minX..maxX) {
                val fx = xx + 0.5f; val fy = yy + 0.5f
                val l0 = ((y1 - y2) * (fx - x2) + (x2 - x1) * (fy - y2)) / den
                val l1 = ((y2 - y0) * (fx - x2) + (x0 - x2) * (fy - y2)) / den
                val l2 = 1f - l0 - l1
                if (l0 < 0f || l1 < 0f || l2 < 0f) continue
                val z = l0 * sz[a] + l1 * sz[b] + l2 * sz[c]
                val k = yy * size + xx
                if (z <= zb[k]) continue
                zb[k] = z
                val sh = l0 * lit[a] + l1 * lit[b] + l2 * lit[c]
                fun ch(off: Int) = ((l0 * v[a * st + off] + l1 * v[b * st + off] + l2 * v[c * st + off]) * sh * 255f).toInt().coerceIn(0, 255)
                px[k] = (0xff shl 24) or (ch(6) shl 16) or (ch(7) shl 8) or ch(8)
            }
        }
        return px
    }

    /** Fraction of opaque pixels (test helper). */
    fun coverage(px: IntArray) = px.count { it ushr 24 != 0 }.toFloat() / px.size
}
