package dev.x360reborn.avatar

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Full-screen 3D avatar (the avatar screen): an [AvatarRenderer] on a GLSurfaceView cleared to the
 * launcher theme's colour. Shows TilePivot's own avatar ([look]) or a pack outfit ([showPack]);
 * dragging sideways turns the figure, a tap plays a wave.
 */
@SuppressLint("ViewConstructor")
class AvatarView(context: Context, look: AvatarLook, background: Int) : GLSurfaceView(context) {
    val renderer = AvatarRenderer((0xff shl 24) or (background and 0xffffff))
    private var packMode = false

    var look: AvatarLook = look
        set(v) { field = v; if (!packMode) renderer.setScene(listOf(AvatarModel.buildRigged(v)), AvatarModel.rig) }

    /** Shows an animated pack outfit; null returns to the built-in avatar. */
    fun showPack(model: PackModel?) {
        packMode = model != null
        if (model != null) renderer.setScene(model.parts, model.rig)
        else renderer.setScene(listOf(AvatarModel.buildRigged(look)), AvatarModel.rig)
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = renderer.onSurfaceCreated()
            override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) = renderer.onSurfaceChanged(w, h)
            override fun onDrawFrame(gl: GL10?) = renderer.onDrawFrame()
        })
        renderMode = RENDERMODE_CONTINUOUSLY
        contentDescription = "Your avatar. Drag to turn it, tap to wave."
        renderer.setScene(listOf(AvatarModel.buildRigged(look)), AvatarModel.rig)
    }

    private var downX = 0f; private var lastX = 0f; private var moved = false
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; lastX = e.x; moved = false }
            MotionEvent.ACTION_MOVE -> {
                renderer.yaw += (e.x - lastX) / width * 4f; lastX = e.x
                if (kotlin.math.abs(e.x - downX) > 20f) moved = true
            }
            MotionEvent.ACTION_UP -> if (!moved) { renderer.emote(Animator.Emote.WAVE); performClick() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
