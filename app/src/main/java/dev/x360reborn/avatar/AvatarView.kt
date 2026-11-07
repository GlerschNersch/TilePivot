package dev.x360reborn.avatar

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The 3D avatar: an OpenGL ES 3 view cleared to the launcher theme's colour. It shows TilePivot's
 * own avatar ([look]) or, when [showPack] is given parts, a scene decoded from a user-supplied
 * avatar pack. Idles with a slow sway; dragging sideways turns the figure.
 */
@SuppressLint("ViewConstructor")
class AvatarView(context: Context, look: AvatarLook, background: Int) : GLSurfaceView(context) {
    private val renderer = R(background)

    var look: AvatarLook = look
        set(v) { field = v; if (!packMode) queueEvent { renderer.setScene(listOf(ownPart(AvatarModel.build(v)))) } }

    private var packMode = false

    /** Shows pack parts instead of the built-in avatar; null returns to the built-in avatar. */
    fun showPack(parts: List<PackPart>?) {
        packMode = parts != null
        val scene = parts?.map { packPart(it) } ?: listOf(ownPart(AvatarModel.build(look)))
        queueEvent { renderer.setScene(scene) }
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        contentDescription = "Your avatar. Drag to turn it."
        renderer.setScene(listOf(ownPart(AvatarModel.build(look))))
    }

    private var lastX = 0f
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> lastX = e.x
            MotionEvent.ACTION_MOVE -> { renderer.yaw += (e.x - lastX) / width * 4f; lastX = e.x }
        }
        return true
    }

    /** Interleaved (pos3, normal3, colour3, uv2) + indices + optional texture, ready to upload. */
    private class Part(val v: FloatArray, val i: IntArray, val tex: IntArray?, val tw: Int, val th: Int)
    private companion object {
        const val ST = 11
        fun ownPart(m: AvatarMesh): Part {
            val n = m.vertexCount; val v = FloatArray(n * ST)
            for (k in 0 until n) { System.arraycopy(m.vertices, k * AvatarMesh.STRIDE, v, k * ST, 9) }
            return Part(v, m.indices, null, 0, 0)
        }
        fun packPart(p: PackPart): Part {
            val n = p.positions.size / 3; val v = FloatArray(n * ST); val nrm = FloatArray(n * 3)
            val pos = p.positions; val ix = p.indices
            for (t in 0 until ix.size / 3) {
                val a = ix[3 * t]; val b = ix[3 * t + 1]; val c = ix[3 * t + 2]
                val ux = pos[3*b]-pos[3*a]; val uy = pos[3*b+1]-pos[3*a+1]; val uz = pos[3*b+2]-pos[3*a+2]
                val wx = pos[3*c]-pos[3*a]; val wy = pos[3*c+1]-pos[3*a+1]; val wz = pos[3*c+2]-pos[3*a+2]
                val nx = uy*wz-uz*wy; val ny = uz*wx-ux*wz; val nz = ux*wy-uy*wx
                for (q in intArrayOf(a, b, c)) { nrm[3*q] += nx; nrm[3*q+1] += ny; nrm[3*q+2] += nz }
            }
            val r = ((p.color shr 16) and 255) / 255f; val g = ((p.color shr 8) and 255) / 255f; val bl = (p.color and 255) / 255f
            for (k in 0 until n) {
                val o = k * ST
                v[o] = pos[3*k]; v[o+1] = pos[3*k+1]; v[o+2] = pos[3*k+2]
                val l = sqrt(nrm[3*k]*nrm[3*k] + nrm[3*k+1]*nrm[3*k+1] + nrm[3*k+2]*nrm[3*k+2]).coerceAtLeast(1e-9f)
                v[o+3] = nrm[3*k]/l; v[o+4] = nrm[3*k+1]/l; v[o+5] = nrm[3*k+2]/l
                v[o+6] = r; v[o+7] = g; v[o+8] = bl
                v[o+9] = p.uvs[2*k]; v[o+10] = p.uvs[2*k+1]
            }
            return Part(v, ix, p.texture, p.texW, p.texH)
        }
    }

    private class R(private val bg: Int) : Renderer {
        @Volatile var yaw = 0.35f
        @Volatile private var pending: List<Part>? = null
        private var lastParts: List<Part>? = null
        private class Gpu(val vbo: Int, val ibo: Int, val tex: Int, val count: Int)
        private val gpu = ArrayList<Gpu>()
        private var prog = 0; private var uMvp = 0; private var uModel = 0; private var uTex = 0; private var uUseTex = 0
        private val proj = FloatArray(16); private val view = FloatArray(16); private val model = FloatArray(16)
        private val vp = FloatArray(16); private val mvp = FloatArray(16)
        private val start = SystemClock.uptimeMillis()
        private var aspect = 1f
        @Volatile private var bounds = floatArrayOf(-0.4f, 0f, 0.4f, 1.7f)  // minX, minY, maxX, maxY

        fun setScene(parts: List<Part>) {
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (p in parts) for (k in 0 until p.v.size / ST) {
                // The figure turns, so frame its widest horizontal extent (x or z) symmetrically.
                val r = max(kotlin.math.abs(p.v[k * ST]), kotlin.math.abs(p.v[k * ST + 2]))
                x0 = minOf(x0, -r); x1 = maxOf(x1, r); y0 = minOf(y0, p.v[k * ST + 1]); y1 = maxOf(y1, p.v[k * ST + 1])
            }
            if (x0 < x1) bounds = floatArrayOf(x0, y0, x1, y1)
            pending = parts
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES30.glClearColor(((bg shr 16) and 255) / 255f, ((bg shr 8) and 255) / 255f, (bg and 255) / 255f, 1f)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            prog = program(VS, FS)
            uMvp = GLES30.glGetUniformLocation(prog, "uMvp"); uModel = GLES30.glGetUniformLocation(prog, "uModel")
            uTex = GLES30.glGetUniformLocation(prog, "uTex"); uUseTex = GLES30.glGetUniformLocation(prog, "uUseTex")
            gpu.clear()  // a new context: re-upload whatever was showing
            if (pending == null) pending = lastParts
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES30.glViewport(0, 0, w, h); aspect = w.toFloat() / h.coerceAtLeast(1)
        }

        private fun frame() {
            val fov = 30f; val t = tan(Math.toRadians(fov / 2.0)).toFloat()
            val b = bounds
            val bw = (b[2] - b[0]) * 1.1f; val bh = (b[3] - b[1]) * 1.1f
            val cy = (b[1] + b[3]) / 2f
            val dist = max(bh / 2f / t, bw / 2f / (t * aspect)) + 0.4f
            Matrix.perspectiveM(proj, 0, fov, aspect, 0.2f, dist + 5f)
            Matrix.setLookAtM(view, 0, 0f, cy + 0.05f, dist, 0f, cy, 0f, 0f, 1f, 0f)
        }

        override fun onDrawFrame(gl: GL10?) {
            pending?.let { upload(it); lastParts = it; pending = null }
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            frame()
            val t = (SystemClock.uptimeMillis() - start) / 1000f
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, 0.008f * sin(t * 2.1f), 0f)
            Matrix.rotateM(model, 0, Math.toDegrees((yaw + 0.12f * sin(t * 0.6f)).toDouble()).toFloat(), 0f, 1f, 0f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0); Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            GLES30.glUseProgram(prog)
            GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0); GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0)
            GLES30.glUniform1i(uTex, 0)
            val st = ST * 4
            for (g in gpu) {
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.vbo)
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

        private fun upload(parts: List<Part>) {
            for (g in gpu) {
                GLES30.glDeleteBuffers(2, intArrayOf(g.vbo, g.ibo), 0)
                if (g.tex != 0) GLES30.glDeleteTextures(1, intArrayOf(g.tex), 0)
            }
            gpu.clear()
            for (p in parts) {
                val ids = IntArray(2); GLES30.glGenBuffers(2, ids, 0)
                val vb = ByteBuffer.allocateDirect(p.v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(p.v).also { it.position(0) }
                val ib = ByteBuffer.allocateDirect(p.i.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(p.i).also { it.position(0) }
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0]); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, p.v.size * 4, vb, GLES30.GL_STATIC_DRAW)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[1]); GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, p.i.size * 4, ib, GLES30.GL_STATIC_DRAW)
                var tex = 0
                val px = p.tex
                if (px != null && p.tw > 0 && p.th > 0) {
                    val t = IntArray(1); GLES30.glGenTextures(1, t, 0); tex = t[0]
                    val bb = ByteBuffer.allocateDirect(px.size * 4).order(ByteOrder.nativeOrder())
                    for (c in px) { bb.put(((c shr 16) and 255).toByte()); bb.put(((c shr 8) and 255).toByte()); bb.put((c and 255).toByte()); bb.put(((c ushr 24) and 255).toByte()) }
                    bb.position(0)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, p.tw, p.th, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                }
                gpu += Gpu(ids[0], ids[1], tex, p.i.size)
            }
        }

        private fun program(vs: String, fs: String): Int {
            fun sh(type: Int, src: String) = GLES30.glCreateShader(type).also { GLES30.glShaderSource(it, src); GLES30.glCompileShader(it) }
            return GLES30.glCreateProgram().also {
                GLES30.glAttachShader(it, sh(GLES30.GL_VERTEX_SHADER, vs)); GLES30.glAttachShader(it, sh(GLES30.GL_FRAGMENT_SHADER, fs))
                GLES30.glLinkProgram(it)
            }
        }

        companion object {
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
  float rim = pow(1.0 - max(n.z, 0.0), 2.0) * 0.18;
  o = vec4(base.rgb * (0.30 + 0.12*sky + 0.62*d) + rim, 1.0);
}"""
        }
    }
}
