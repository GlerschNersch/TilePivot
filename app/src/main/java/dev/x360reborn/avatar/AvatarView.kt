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
import kotlin.math.sin

/**
 * The 3D avatar: an OpenGL ES 3 view cleared to the launcher theme's colour. It idles with a slow sway;
 * dragging sideways turns the figure. [look] can be changed at any time from the UI thread.
 */
@SuppressLint("ViewConstructor")
class AvatarView(context: Context, look: AvatarLook, background: Int) : GLSurfaceView(context) {
    private val renderer = R(look, background)

    var look: AvatarLook = look
        set(v) { field = v; queueEvent { renderer.setMesh(AvatarModel.build(v)) }; requestRender() }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        contentDescription = "Your avatar. Drag to turn it."
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

    private class R(look: AvatarLook, private val bg: Int) : Renderer {
        @Volatile var yaw = 0.35f
        private var mesh: AvatarMesh? = AvatarModel.build(look)
        private var dirty = true
        private var prog = 0; private var vbo = 0; private var ibo = 0; private var count = 0
        private var uMvp = 0; private var uModel = 0
        private val proj = FloatArray(16); private val view = FloatArray(16); private val model = FloatArray(16)
        private val vp = FloatArray(16); private val mvp = FloatArray(16)
        private val start = SystemClock.uptimeMillis()

        fun setMesh(m: AvatarMesh) { mesh = m; dirty = true }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES30.glClearColor(((bg shr 16) and 255) / 255f, ((bg shr 8) and 255) / 255f, (bg and 255) / 255f, 1f)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            prog = program(VS, FS)
            uMvp = GLES30.glGetUniformLocation(prog, "uMvp"); uModel = GLES30.glGetUniformLocation(prog, "uModel")
            val ids = IntArray(2); GLES30.glGenBuffers(2, ids, 0); vbo = ids[0]; ibo = ids[1]
            dirty = true
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES30.glViewport(0, 0, w, h)
            val aspect = w.toFloat() / h.coerceAtLeast(1)
            // The figure is tall and narrow, so a fixed vertical field of view frames it in both
            // orientations; only very narrow windows (split screen) need it widened.
            val fov = if (aspect >= 0.4f) 32f else (32f * 0.4f / aspect.coerceAtLeast(0.1f)).coerceAtMost(75f)
            Matrix.perspectiveM(proj, 0, fov, aspect, 0.5f, 20f)
            Matrix.setLookAtM(view, 0, 0f, 0.85f, 3.6f, 0f, 0.82f, 0f, 0f, 1f, 0f)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (dirty) upload()
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            val t = (SystemClock.uptimeMillis() - start) / 1000f
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, 0.008f * sin(t * 2.1f), 0f)          // breathing bob
            Matrix.rotateM(model, 0, Math.toDegrees((yaw + 0.12f * sin(t * 0.6f)).toDouble()).toFloat(), 0f, 1f, 0f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0); Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            GLES30.glUseProgram(prog)
            GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0); GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            val st = AvatarMesh.STRIDE * 4
            for ((loc, off) in listOf(0 to 0, 1 to 12, 2 to 24)) {
                GLES30.glEnableVertexAttribArray(loc); GLES30.glVertexAttribPointer(loc, 3, GLES30.GL_FLOAT, false, st, off)
            }
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_INT, 0)
        }

        private fun upload() {
            val m = mesh ?: return
            val vb = ByteBuffer.allocateDirect(m.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(m.vertices).also { it.position(0) }
            val ib = ByteBuffer.allocateDirect(m.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(m.indices).also { it.position(0) }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.vertices.size * 4, vb, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo); GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 4, ib, GLES30.GL_STATIC_DRAW)
            count = m.indices.size; dirty = false
        }

        private fun program(vs: String, fs: String): Int {
            fun sh(type: Int, src: String) = GLES30.glCreateShader(type).also { GLES30.glShaderSource(it, src); GLES30.glCompileShader(it) }
            return GLES30.glCreateProgram().also {
                GLES30.glAttachShader(it, sh(GLES30.GL_VERTEX_SHADER, vs)); GLES30.glAttachShader(it, sh(GLES30.GL_FRAGMENT_SHADER, fs))
                GLES30.glBindAttribLocation(it, 0, "aPos"); GLES30.glBindAttribLocation(it, 1, "aNrm"); GLES30.glBindAttribLocation(it, 2, "aCol")
                GLES30.glLinkProgram(it)
            }
        }

        companion object {
            const val VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNrm; layout(location=2) in vec3 aCol;
uniform mat4 uMvp; uniform mat4 uModel;
out vec3 vN; out vec3 vC;
void main(){ gl_Position = uMvp * vec4(aPos,1.0); vN = mat3(uModel) * aNrm; vC = aCol; }"""
            const val FS = """#version 300 es
precision mediump float;
in vec3 vN; in vec3 vC; out vec4 o;
void main(){
  vec3 n = normalize(vN);
  float d = max(dot(n, normalize(vec3(0.35,0.55,0.76))), 0.0);
  float sky = 0.5 + 0.5*n.y;
  float rim = pow(1.0 - max(n.z, 0.0), 2.0) * 0.22;
  o = vec4(vC * (0.30 + 0.12*sky + 0.62*d) + rim, 1.0);
}"""
        }
    }
}
