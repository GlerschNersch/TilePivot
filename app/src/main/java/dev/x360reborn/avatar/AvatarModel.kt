package dev.x360reborn.avatar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Triangle mesh with per-vertex colour, ready for GL: [vertices] is interleaved
 * (x, y, z, nx, ny, nz, r, g, b) floats, [indices] are triangle corners.
 */
class AvatarMesh(val vertices: FloatArray, val indices: IntArray) {
    val vertexCount get() = vertices.size / STRIDE
    companion object { const val STRIDE = 9 }
}

/**
 * TilePivot's original avatar: a friendly, chunky figure built from ellipsoids and tapered tubes.
 * Units are roughly metres-ish with the feet on y = 0, facing +z, about 1.6 tall with a large head.
 * Pure math (no Android types), so its shape is unit-tested.
 */
object AvatarModel {
    const val HEAD_Y = 1.30f
    const val HEAD_R = 0.20f
    const val HEIGHT = 1.60f

    fun build(look: AvatarLook): AvatarMesh {
        val b = Builder()
        val skin = rgb(look.skinRgb()); val hair = rgb(look.hairRgb())
        val top = rgb(look.topRgb()); val bottoms = rgb(look.bottomsRgb()); val shoes = rgb(look.shoesRgb())
        val dark = floatArrayOf(0.10f, 0.08f, 0.08f); val white = floatArrayOf(0.97f, 0.97f, 0.95f)
        val lips = mix(skin, floatArrayOf(0.75f, 0.30f, 0.30f), 0.55f)

        // Legs and shoes.
        for (s in SIDES) {
            b.tube(v(0.085f * s, 0.64f, 0f), v(0.09f * s, 0.34f, 0.01f), 0.075f, 0.065f, bottoms)
            b.tube(v(0.09f * s, 0.34f, 0.01f), v(0.09f * s, 0.08f, 0f), 0.065f, 0.055f, bottoms)
            b.ellipsoid(v(0.09f * s, 0.34f, 0.01f), v(0.066f, 0.066f, 0.066f), bottoms)
            b.ellipsoid(v(0.09f * s, 0.045f, 0.035f), v(0.065f, 0.048f, 0.11f), shoes)
        }
        b.ellipsoid(v(0f, 0.66f, 0f), v(0.17f, 0.09f, 0.11f), bottoms) // hips
        // Torso: a soft barrel with a rounded top.
        b.tube(v(0f, 0.66f, 0f), v(0f, 0.98f, 0f), 0.16f, 0.185f, top, zScale = 0.68f)
        b.ellipsoid(v(0f, 0.98f, 0f), v(0.205f, 0.075f, 0.13f), top)
        // Arms, relaxed a little away from the body.
        for (s in SIDES) {
            val sh = v(0.205f * s, 0.965f, 0f); val el = v(0.255f * s, 0.73f, 0.01f); val wr = v(0.275f * s, 0.52f, 0.03f)
            b.ellipsoid(sh, v(0.062f, 0.062f, 0.062f), top)
            b.tube(sh, el, 0.058f, 0.05f, top)
            b.ellipsoid(el, v(0.05f, 0.05f, 0.05f), skin)
            b.tube(el, wr, 0.045f, 0.038f, skin)
            b.ellipsoid(v(0.28f * s, 0.475f, 0.035f), v(0.042f, 0.06f, 0.03f), skin) // hand
        }
        // Neck and head.
        b.tube(v(0f, 1.0f, 0f), v(0f, 1.13f, 0f), 0.055f, 0.05f, skin)
        b.ellipsoid(v(0f, HEAD_Y, 0f), v(HEAD_R * 0.95f, HEAD_R * 1.05f, HEAD_R * 0.97f), skin)
        for (s in SIDES) b.ellipsoid(v(0.19f * s, HEAD_Y, -0.01f), v(0.025f, 0.05f, 0.035f), skin) // ears
        // Face: eyes (white + pupil), brows, nose, mouth.
        for (s in SIDES) {
            b.ellipsoid(v(0.068f * s, 1.325f, 0.162f), v(0.036f, 0.043f, 0.02f), white)
            b.ellipsoid(v(0.068f * s, 1.32f, 0.178f), v(0.017f, 0.022f, 0.008f), dark)
            b.ellipsoid(v(0.07f * s, 1.39f, 0.168f), v(0.042f, 0.009f, 0.012f), hair)
        }
        b.ellipsoid(v(0f, 1.27f, 0.188f), v(0.022f, 0.03f, 0.022f), mix(skin, dark, 0.06f))
        b.ellipsoid(v(0f, 1.205f, 0.172f), v(0.052f, 0.012f, 0.012f), lips)
        hairMesh(b, look.hairStyle(), hair)
        return b.mesh()
    }

    private fun hairMesh(b: Builder, style: HairStyle, c: FloatArray) {
        val cap = { b.ellipsoid(v(0f, HEAD_Y + 0.012f, -0.012f), v(HEAD_R * 1.05f, HEAD_R * 1.1f, HEAD_R * 1.07f), c, latMax = 0.5f, tiltX = 0.5f) }
        when (style) {
            HairStyle.SHORT -> cap()
            HairStyle.LONG -> {
                cap()
                b.ellipsoid(v(0f, 1.2f, -0.09f), v(0.19f, 0.2f, 0.09f), c)
                for (s in SIDES) b.ellipsoid(v(0.165f * s, 1.22f, 0.0f), v(0.045f, 0.15f, 0.08f), c)
            }
            HairStyle.SPIKY -> {
                cap()
                for (i in 0 until 7) {
                    val a = (i - 3) * 0.32f
                    val base = v(sin(a) * 0.12f, HEAD_Y + 0.17f, -0.03f + cos(a) * 0.02f)
                    val tip = v(sin(a) * 0.2f, HEAD_Y + 0.29f, -0.04f)
                    b.tube(base, tip, 0.05f, 0.004f, c)
                }
            }
            HairStyle.BUN -> {
                cap()
                b.ellipsoid(v(0f, HEAD_Y + 0.16f, -0.13f), v(0.08f, 0.08f, 0.08f), c)
            }
            HairStyle.NONE -> {}
        }
    }

    private val SIDES = floatArrayOf(1f, -1f)
    private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
    fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
    private fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t }

    /** Accumulates parts into one interleaved mesh. */
    private class Builder {
        val vs = ArrayList<Float>(40000); val ix = ArrayList<Int>(40000)
        private fun add(p: FloatArray, n: FloatArray, c: FloatArray): Int {
            val i = vs.size / AvatarMesh.STRIDE
            vs += p[0]; vs += p[1]; vs += p[2]; vs += n[0]; vs += n[1]; vs += n[2]; vs += c[0]; vs += c[1]; vs += c[2]
            return i
        }
        private fun quad(a: Int, b: Int, c: Int, d: Int) { ix += a; ix += b; ix += c; ix += a; ix += c; ix += d }

        /** Ellipsoid, optionally only its top: [latMax] is the fraction of the pole-to-pole arc kept. */
        fun ellipsoid(c: FloatArray, r: FloatArray, col: FloatArray, lat: Int = 12, lon: Int = 18, latMax: Float = 1f, tiltX: Float = 0f) {
            val ct = cos(tiltX); val stt = sin(tiltX)
            val start = vs.size / AvatarMesh.STRIDE
            for (i in 0..lat) {
                val th = PI.toFloat() * latMax * i / lat
                for (j in 0..lon) {
                    val ph = 2f * PI.toFloat() * j / lon
                    val ux = sin(th) * cos(ph); val uy = cos(th); val uz = sin(th) * sin(ph)
                    val nx = ux / r[0]; val ny = uy / r[1]; val nz = uz / r[2]
                    val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                    // Optional tilt about x (positive lifts the front edge of a cap).
                    val py = uy * r[1]; val pz = uz * r[2]
                    val qy = py * ct + pz * stt; val qz = -py * stt + pz * ct
                    val my = ny / l; val mz = nz / l
                    add(floatArrayOf(c[0] + ux * r[0], c[1] + qy, c[2] + qz), floatArrayOf(nx / l, my * ct + mz * stt, -my * stt + mz * ct), col)
                }
            }
            for (i in 0 until lat) for (j in 0 until lon) {
                val a = start + i * (lon + 1) + j
                quad(a, a + 1, a + lon + 2, a + lon + 1)
            }
        }

        /** Tapered open tube from [a] (radius [ra]) to [b] (radius [rb]); [zScale] flattens it front-to-back. */
        fun tube(a: FloatArray, b: FloatArray, ra: Float, rb: Float, col: FloatArray, seg: Int = 18, zScale: Float = 1f) {
            val d = FloatArray(3) { b[it] - a[it] }
            val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).coerceAtLeast(1e-6f)
            val ax = FloatArray(3) { d[it] / len }
            val ref = if (kotlin.math.abs(ax[1]) < 0.9f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
            val u = norm(cross(ax, ref)); val w = cross(ax, u)
            val start = vs.size / AvatarMesh.STRIDE
            for (k in 0..1) {
                val c = if (k == 0) a else b; val r = if (k == 0) ra else rb
                for (j in 0..seg) {
                    val ph = 2f * PI.toFloat() * j / seg
                    val dir = FloatArray(3) { u[it] * cos(ph) + w[it] * sin(ph) }
                    dir[2] *= zScale
                    val n = norm(floatArrayOf(dir[0], dir[1], dir[2] / (zScale * zScale)))
                    add(FloatArray(3) { c[it] + dir[it] * r }, n, col)
                }
            }
            for (j in 0 until seg) quad(start + j, start + seg + 1 + j, start + seg + 2 + j, start + j + 1)
        }

        fun mesh() = AvatarMesh(vs.toFloatArray(), ix.toIntArray())
        private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        private fun norm(a: FloatArray): FloatArray { val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).coerceAtLeast(1e-6f); return FloatArray(3) { a[it] / l } }
    }
}
