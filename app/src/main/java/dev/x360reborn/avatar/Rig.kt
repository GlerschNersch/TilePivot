package dev.x360reborn.avatar

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A skeleton: [parents] (-1 = root), rest-pose joint positions ([joints], xyz per bone), named
 * joints for animation ([names]: "pelvis", "spine", "chest", "neck", "head", "upperArm.L",
 * "foreArm.L", "hand.L", "thigh.L", "shin.L", "foot.L" and the ".R" twins; L is the figure's left,
 * +x), and a [base] pose of local rotations applied before any animation (axis xyz + angle).
 */
class Rig(
    val parents: IntArray,
    val joints: FloatArray,
    val names: Map<String, Int>,
    val base: Map<Int, FloatArray> = emptyMap(),
) {
    val size get() = parents.size
    /** Bones ordered so every parent comes before its children. */
    val order: IntArray by lazy {
        val depth = IntArray(size) { b -> var d = 0; var p = parents[b]; while (p >= 0 && d < size) { d++; p = parents[p] }; d }
        (0 until size).sortedBy { depth[it] }.toIntArray()
    }
}

/**
 * A mesh bound to a [Rig]: up to four bones per vertex ([bones], [weights]; weight 0 = unused).
 * Coloured per vertex ([colors], rgb) or by [color]; textured when [texture] is set.
 */
class SkinnedPart(
    val positions: FloatArray,
    val indices: IntArray,
    val bones: IntArray,
    val weights: FloatArray,
    val uvs: FloatArray? = null,
    val colors: FloatArray? = null,
    val texture: IntArray? = null,
    val texW: Int = 0,
    val texH: Int = 0,
    val color: Int = 0xFFFFFF,
    private val restNormals: FloatArray? = null,
) {
    val vertexCount get() = positions.size / 3
    /** Smooth rest-pose normals (area-weighted), skinned along with the positions. */
    val normals: FloatArray by lazy {
        restNormals?.let { return@lazy it }
        val n = FloatArray(positions.size); val p = positions
        for (t in 0 until indices.size / 3) {
            val a = indices[3 * t]; val b = indices[3 * t + 1]; val c = indices[3 * t + 2]
            val ux = p[3*b]-p[3*a]; val uy = p[3*b+1]-p[3*a+1]; val uz = p[3*b+2]-p[3*a+2]
            val wx = p[3*c]-p[3*a]; val wy = p[3*c+1]-p[3*a+1]; val wz = p[3*c+2]-p[3*a+2]
            val nx = uy*wz-uz*wy; val ny = uz*wx-ux*wz; val nz = ux*wy-uy*wx
            for (q in intArrayOf(a, b, c)) { n[3*q] += nx; n[3*q+1] += ny; n[3*q+2] += nz }
        }
        for (i in 0 until n.size / 3) {
            val l = sqrt(n[3*i]*n[3*i] + n[3*i+1]*n[3*i+1] + n[3*i+2]*n[3*i+2]).coerceAtLeast(1e-9f)
            n[3*i] /= l; n[3*i+1] /= l; n[3*i+2] /= l
        }
        n
    }
}

/** 3x3 rotation helpers (row-major FloatArray(9)). */
internal object Rot {
    val I = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    fun axisAngle(x: Float, y: Float, z: Float, a: Float): FloatArray {
        val l = sqrt(x * x + y * y + z * z); if (l < 1e-9f || a == 0f) return I.copyOf()
        val ux = x / l; val uy = y / l; val uz = z / l
        val c = cos(a); val s = sin(a); val t = 1 - c
        return floatArrayOf(
            t*ux*ux + c, t*ux*uy - s*uz, t*ux*uz + s*uy,
            t*ux*uy + s*uz, t*uy*uy + c, t*uy*uz - s*ux,
            t*ux*uz - s*uy, t*uy*uz + s*ux, t*uz*uz + c)
    }
    fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { val i = it / 3; val j = it % 3; a[3*i]*b[j] + a[3*i+1]*b[3+j] + a[3*i+2]*b[6+j] }
}

/**
 * Procedural animation over a [Rig]'s named joints: an always-on idle (breathing, gentle sway,
 * glances) plus one-shot [Emote]s blended in and out, and a smoothed body turn ([lookAt]).
 * Pure math: [pose] fills world rotations/positions per bone for a time in seconds.
 */
class Animator(private val rig: Rig) {
    enum class Emote(val seconds: Float) { WAVE(2.4f), CHEER(1.6f), NOD(1.0f), SHRUG(1.4f) }

    private var emote: Emote? = null
    private var emoteStart = 0f
    private var yawNow = 0f
    @Volatile var lookAt = 0f          // target body yaw, radians (+ = turn to the figure's left)

    fun play(e: Emote, now: Float) { emote = e; emoteStart = now }
    val busy get() = emote != null

    private val local = Array(rig.size) { Rot.I }
    val worldR = Array(rig.size) { Rot.I }
    val worldP = FloatArray(rig.size * 3)

    private fun j(name: String) = rig.names[name] ?: -1
    private fun add(bone: Int, x: Float, y: Float, z: Float, a: Float) {
        if (bone < 0 || a == 0f) return
        local[bone] = Rot.mul(local[bone], Rot.axisAngle(x, y, z, a))
    }

    fun pose(t: Float, dt: Float) {
        for (b in 0 until rig.size) {
            val base = rig.base[b]
            local[b] = if (base != null) Rot.axisAngle(base[0], base[1], base[2], base[3]) else Rot.I
        }
        // Idle: breathing, weight shift, a slow look around, arms settling.
        add(j("chest"), 1f, 0f, 0f, 0.025f * sin(t * 2.2f))
        add(j("spine"), 0f, 0f, 1f, 0.02f * sin(t * 0.55f))
        add(j("pelvis"), 0f, 0f, 1f, -0.015f * sin(t * 0.55f))
        add(j("head"), 0f, 1f, 0f, 0.18f * sin(t * 0.31f) * sin(t * 0.13f))
        add(j("head"), 1f, 0f, 0f, 0.04f * sin(t * 0.47f))
        add(j("upperArm.L"), 0f, 0f, 1f, 0.03f * sin(t * 0.9f))
        add(j("upperArm.R"), 0f, 0f, 1f, -0.03f * sin(t * 0.9f + 1f))
        // One-shot emote, faded in and out.
        emote?.let { e ->
            val u = t - emoteStart
            if (u >= e.seconds) { emote = null; return@let }
            val env = smooth((u / 0.3f).coerceAtMost(1f)) * smooth(((e.seconds - u) / 0.35f).coerceAtMost(1f))
            when (e) {
                Emote.WAVE -> {
                    add(j("upperArm.R"), 0f, 0f, 1f, -2.3f * env)
                    add(j("foreArm.R"), 0f, 0f, 1f, (-0.5f + 0.45f * sin(u * 13f)) * env)
                    add(j("head"), 0f, 0f, 1f, -0.12f * env)
                }
                Emote.CHEER -> {
                    add(j("upperArm.L"), 0f, 0f, 1f, 2.4f * env); add(j("upperArm.R"), 0f, 0f, 1f, -2.4f * env)
                    add(j("foreArm.L"), 0f, 0f, 1f, 0.4f * env); add(j("foreArm.R"), 0f, 0f, 1f, -0.4f * env)
                    add(j("chest"), 1f, 0f, 0f, -0.12f * env)
                }
                Emote.NOD -> add(j("head"), 1f, 0f, 0f, 0.28f * sin(u * 9f) * env)
                Emote.SHRUG -> {
                    add(j("upperArm.L"), 0f, 0f, 1f, 0.5f * env); add(j("upperArm.R"), 0f, 0f, 1f, -0.5f * env)
                    add(j("foreArm.L"), 0f, 1f, 0f, -0.9f * env); add(j("foreArm.R"), 0f, 1f, 0f, 0.9f * env)
                    add(j("head"), 0f, 0f, 1f, 0.18f * env)
                }
            }
        }
        // Smoothed turn toward lookAt (body, then a little extra from the head).
        yawNow += (lookAt - yawNow) * (1f - kotlin.math.exp(-dt * 4f))
        add(j("pelvis"), 0f, 1f, 0f, yawNow * 0.7f)
        add(j("head"), 0f, 1f, 0f, yawNow * 0.3f)
        // Forward kinematics.
        val J = rig.joints
        for (b in rig.order) {
            val p = rig.parents[b]
            if (p < 0) { worldR[b] = local[b]; worldP[3*b] = J[3*b]; worldP[3*b+1] = J[3*b+1]; worldP[3*b+2] = J[3*b+2] }
            else {
                val R = worldR[p]
                val ox = J[3*b]-J[3*p]; val oy = J[3*b+1]-J[3*p+1]; val oz = J[3*b+2]-J[3*p+2]
                worldP[3*b] = worldP[3*p] + R[0]*ox + R[1]*oy + R[2]*oz
                worldP[3*b+1] = worldP[3*p+1] + R[3]*ox + R[4]*oy + R[5]*oz
                worldP[3*b+2] = worldP[3*p+2] + R[6]*ox + R[7]*oy + R[8]*oz
                worldR[b] = Rot.mul(R, local[b])
            }
        }
        // A small hop while cheering.
        emote?.takeIf { it == Emote.CHEER }?.let { val u = t - emoteStart; val h = 0.05f * kotlin.math.abs(sin(u * 6f)); for (b in 0 until rig.size) worldP[3*b+1] += h }
    }

    /** Linear-blend skins [part] with the last [pose] into [outPos]/[outNrm] (xyz per vertex). */
    fun skin(part: SkinnedPart, outPos: FloatArray, outNrm: FloatArray) {
        val P = part.positions; val N = part.normals; val B = part.bones; val W = part.weights; val J = rig.joints
        for (v in 0 until part.vertexCount) {
            var px = 0f; var py = 0f; var pz = 0f; var nx = 0f; var ny = 0f; var nz = 0f; var wsum = 0f
            for (k in 0 until 4) {
                val w = W[4*v+k]; if (w <= 0f) continue
                val b = B[4*v+k]; if (b !in 0 until rig.size) continue
                val R = worldR[b]
                val rx = P[3*v]-J[3*b]; val ry = P[3*v+1]-J[3*b+1]; val rz = P[3*v+2]-J[3*b+2]
                px += w * (R[0]*rx + R[1]*ry + R[2]*rz + worldP[3*b]); py += w * (R[3]*rx + R[4]*ry + R[5]*rz + worldP[3*b+1]); pz += w * (R[6]*rx + R[7]*ry + R[8]*rz + worldP[3*b+2])
                nx += w * (R[0]*N[3*v] + R[1]*N[3*v+1] + R[2]*N[3*v+2]); ny += w * (R[3]*N[3*v] + R[4]*N[3*v+1] + R[5]*N[3*v+2]); nz += w * (R[6]*N[3*v] + R[7]*N[3*v+1] + R[8]*N[3*v+2])
                wsum += w
            }
            if (wsum < 1e-6f) { px = P[3*v]; py = P[3*v+1]; pz = P[3*v+2]; nx = N[3*v]; ny = N[3*v+1]; nz = N[3*v+2] }
            else if (wsum < 0.999f) { px /= wsum; py /= wsum; pz /= wsum }
            outPos[3*v] = px; outPos[3*v+1] = py; outPos[3*v+2] = pz
            outNrm[3*v] = nx; outNrm[3*v+1] = ny; outNrm[3*v+2] = nz
        }
    }

    private fun smooth(x: Float) = x * x * (3f - 2f * x)
}
