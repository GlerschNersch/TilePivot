package dev.x360reborn.avatar

import android.opengl.GLES30
import android.opengl.Matrix
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * GL side of the avatar, shared by the full-screen [AvatarView] and the home-screen overlay:
 * animates the current scene with an [Animator], skins it on the CPU each frame and draws it lit
 * (textured or vertex-coloured). All methods except [setScene]/[emote]/[lookAt]/[yaw] run on the GL thread.
 * [clear] is ARGB; alpha 0 gives a transparent background (overlay).
 */
class AvatarRenderer(private val clear: Int) {
    private class Scene(val parts: List<SkinnedPart>, val rig: Rig)
    private class Gpu(val vbo: Int, val ibo: Int, val tex: Int, val count: Int, val part: SkinnedPart,
                      val buf: FloatBuffer, val pos: FloatArray, val nrm: FloatArray, val inter: FloatArray)

    @Volatile private var pending: Scene? = null
    private var scene: Scene? = null
    private var animator: Animator? = null
    private val gpu = ArrayList<Gpu>()
    @Volatile var yaw = 0.35f
    /** Extra framing control: 1 = whole figure; larger zooms toward the upper body. */
    @Volatile var zoom = 1f

    private var prog = 0; private var uMvp = 0; private var uModel = 0; private var uTex = 0; private var uUseTex = 0
    private val proj = FloatArray(16); private val view = FloatArray(16); private val model = FloatArray(16)
    private val vp = FloatArray(16); private val mvp = FloatArray(16)
    private val start = SystemClock.uptimeMillis(); private var last = 0f
    private var aspect = 1f
    private var bounds = floatArrayOf(-0.4f, 0f, 0.4f, 1.7f)
    @Volatile private var queuedEmote: Animator.Emote? = null
    @Volatile private var queuedLook: Float? = null

    fun setScene(parts: List<SkinnedPart>, rig: Rig) { pending = Scene(parts, rig) }
    fun emote(e: Animator.Emote) { queuedEmote = e }
    fun lookAt(yaw: Float) { queuedLook = yaw }
    val hasScene get() = scene != null || pending != null

    fun onSurfaceCreated() {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        prog = program(VS, FS)
        uMvp = GLES30.glGetUniformLocation(prog, "uMvp"); uModel = GLES30.glGetUniformLocation(prog, "uModel")
        uTex = GLES30.glGetUniformLocation(prog, "uTex"); uUseTex = GLES30.glGetUniformLocation(prog, "uUseTex")
        gpu.clear()                       // new context: everything is re-uploaded
        if (pending == null) scene?.let { pending = it }
        scene = null
    }

    fun onSurfaceChanged(w: Int, h: Int) { GLES30.glViewport(0, 0, w, h); aspect = w.toFloat() / h.coerceAtLeast(1) }

    fun onDrawFrame() {
        pending?.let { upload(it); pending = null }
        val t = (SystemClock.uptimeMillis() - start) / 1000f; val dt = (t - last).coerceIn(0f, 0.1f); last = t
        GLES30.glClearColor(((clear shr 16) and 255) / 255f, ((clear shr 8) and 255) / 255f, (clear and 255) / 255f, ((clear ushr 24) and 255) / 255f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val a = animator ?: return
        queuedEmote?.let { a.play(it, t); queuedEmote = null }
        queuedLook?.let { a.lookAt = it; queuedLook = null }
        a.pose(t, dt)
        frame()
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0); Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0); GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES30.glUniform1i(uTex, 0)
        val st = ST * 4
        for (g in gpu) {
            a.skin(g.part, g.pos, g.nrm)
            val n = g.part.vertexCount; val I = g.inter
            for (v in 0 until n) {
                val o = v * ST
                I[o] = g.pos[3*v]; I[o+1] = g.pos[3*v+1]; I[o+2] = g.pos[3*v+2]
                I[o+3] = g.nrm[3*v]; I[o+4] = g.nrm[3*v+1]; I[o+5] = g.nrm[3*v+2]
            }
            g.buf.position(0); g.buf.put(I); g.buf.position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.vbo)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, I.size * 4, g.buf)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, st, 0)
            GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, st, 12)
            GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, st, 24)
            GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, st, 36)
            GLES30.glUniform1i(uUseTex, if (g.tex != 0) 1 else 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, g.tex)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.ibo)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, g.count, GLES30.GL_UNSIGNED_INT, 0)
        }
    }

    /** Fits the scene's rest bounds (widest of x/z, since the figure turns) into the view. */
    private fun frame() {
        val fov = 30f; val t = tan(Math.toRadians(fov / 2.0)).toFloat(); val b = bounds
        val h = b[3] - b[1]
        val bw = (b[2] - b[0]) * 1.08f; val bh = h * 1.08f / zoom
        // zoom > 1 frames the top 1/zoom of the figure (head and shoulders first).
        val cy = if (zoom <= 1f) b[1] + h / 2f else b[3] - h / zoom / 2f
        val dist = max(bh / 2f / t, bw / zoom / 2f / (t * aspect)) + 0.3f
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.1f, dist + 5f)
        Matrix.setLookAtM(view, 0, 0f, cy + 0.04f, dist, 0f, cy, 0f, 0f, 1f, 0f)
    }

    private fun upload(s: Scene) {
        for (g in gpu) { GLES30.glDeleteBuffers(2, intArrayOf(g.vbo, g.ibo), 0); if (g.tex != 0) GLES30.glDeleteTextures(1, intArrayOf(g.tex), 0) }
        gpu.clear(); scene = s; animator = Animator(s.rig)
        // Bounds of the posed rest frame (base pose applied), so framing matches what is drawn.
        val a = animator!!; a.pose(0f, 0f)
        var x0 = 0f; var y0 = Float.MAX_VALUE; var x1 = 0f; var y1 = -Float.MAX_VALUE
        for (p in s.parts) {
            val ps = FloatArray(p.positions.size); val ns = FloatArray(p.positions.size); a.skin(p, ps, ns)
            for (v in 0 until p.vertexCount) {
                val r = max(abs(ps[3*v]), abs(ps[3*v+2])); x0 = minOf(x0, -r); x1 = maxOf(x1, r)
                y0 = minOf(y0, ps[3*v+1]); y1 = maxOf(y1, ps[3*v+1])
            }
        }
        if (y0 < y1) bounds = floatArrayOf(x0, y0, x1, y1)
        for (p in s.parts) {
            val n = p.vertexCount; val inter = FloatArray(n * ST)
            val r = ((p.color shr 16) and 255) / 255f; val gg = ((p.color shr 8) and 255) / 255f; val bl = (p.color and 255) / 255f
            for (v in 0 until n) {
                val o = v * ST
                if (p.colors != null) { inter[o+6] = p.colors[3*v]; inter[o+7] = p.colors[3*v+1]; inter[o+8] = p.colors[3*v+2] }
                else { inter[o+6] = r; inter[o+7] = gg; inter[o+8] = bl }
                p.uvs?.let { inter[o+9] = it[2*v]; inter[o+10] = it[2*v+1] }
            }
            val ids = IntArray(2); GLES30.glGenBuffers(2, ids, 0)
            val buf = ByteBuffer.allocateDirect(inter.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(inter).position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0]); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, inter.size * 4, buf, GLES30.GL_DYNAMIC_DRAW)
            val ib = ByteBuffer.allocateDirect(p.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(p.indices).also { it.position(0) }
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[1]); GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, p.indices.size * 4, ib, GLES30.GL_STATIC_DRAW)
            var tex = 0
            val px = p.texture
            if (px != null && p.texW > 0 && p.texH > 0) {
                val tt = IntArray(1); GLES30.glGenTextures(1, tt, 0); tex = tt[0]
                val bb = ByteBuffer.allocateDirect(px.size * 4).order(ByteOrder.nativeOrder())
                for (c in px) { bb.put(((c shr 16) and 255).toByte()); bb.put(((c shr 8) and 255).toByte()); bb.put((c and 255).toByte()); bb.put(((c ushr 24) and 255).toByte()) }
                bb.position(0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, p.texW, p.texH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            }
            gpu += Gpu(ids[0], ids[1], tex, p.indices.size, p, buf, FloatArray(n * 3), FloatArray(n * 3), inter)
        }
    }

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String) = GLES30.glCreateShader(type).also { GLES30.glShaderSource(it, src); GLES30.glCompileShader(it) }
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, sh(GLES30.GL_VERTEX_SHADER, vs)); GLES30.glAttachShader(it, sh(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(it)
        }
    }

    private companion object {
        const val ST = 11
        const val VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNrm; layout(location=2) in vec3 aCol; layout(location=3) in vec2 aUv;
uniform mat4 uMvp; uniform mat4 uModel;
out vec3 vN; out vec3 vC; out vec2 vUv;
void main(){ gl_Position = uMvp * vec4(aPos,1.0); vN = mat3(uModel) * aNrm; vC = aCol; vUv = aUv; }"""
        const val FS = """#version 300 es
precision mediump float;
in vec3 vN; in vec3 vC; in vec2 vUv; out vec4 o;
uniform sampler2D uTex; uniform int uUseTex;
void main(){
  vec4 base = uUseTex == 1 ? texture(uTex, vUv) : vec4(vC, 1.0);
  if (base.a < 0.35) discard;
  vec3 n = normalize(vN);
  if (!gl_FrontFacing) n = -n;
  float d = max(dot(n, normalize(vec3(0.35,0.55,0.76))), 0.0);
  float sky = 0.5 + 0.5*n.y;
  float rim = pow(1.0 - max(n.z, 0.0), 2.0) * 0.16;
  o = vec4(base.rgb * (0.32 + 0.12*sky + 0.6*d) + rim, 1.0);
}"""
    }
}
