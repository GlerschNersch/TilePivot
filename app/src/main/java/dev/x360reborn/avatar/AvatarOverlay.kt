package dev.x360reborn.avatar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.TextureView
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * The avatar on the home screen: a transparent TextureView (so it composites over the hub's own
 * drawing) with its own GL thread running an [AvatarRenderer]. MainActivity places it on the
 * home hero's figure stage every hub frame. Touches on the figure play an emote (tap) or open
 * the avatar screen (long press); touches beside it fall through to the hub.
 */
@SuppressLint("ViewConstructor")
class AvatarOverlay(context: Context, private val onLongPress: () -> Unit) : TextureView(context), TextureView.SurfaceTextureListener {
    // Framing: the renderer fits the figure plus this much headroom into the
    // stage, anchored at the feet, so a smaller value reads as a larger
    // figure growing upward from the hero floor. 0.113 (0.28 up to 0.10.1)
    // makes the hero figure about 15% larger while its head still clears
    // the hero's top edge (RE_NOTES, App v0.10.2).
    val renderer = AvatarRenderer(0x00000000).apply { headroom = 0.113f }
    private var thread: GlThread? = null
    @Volatile private var running = false

    init {
        isOpaque = false
        surfaceTextureListener = this
        contentDescription = "Your avatar. Tap for an emote, long-press to customise."
    }

    /** Pauses rendering (hidden, hub away, activity paused) without losing the GL scene. */
    var active = false
        set(v) { field = v; running = v && isAvailable }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        thread = GlThread(st, w, h).also { it.start() }
        running = active
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { thread?.resize(w, h) }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { thread?.quit(); thread = null; return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // ---- touch: only the figure's middle column belongs to the avatar --------------------------

    private var downAt = 0L; private var downX = 0f; private var downY = 0f; private var longFired = false
    private val longPress = Runnable { longFired = true; performHapticFeedback(HAPTIC_FEEDBACK_ENABLED); onLongPress() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (abs(e.x - width / 2f) > width * 0.3f) return false   // beside the figure: let the hub have it
                downAt = SystemClock.uptimeMillis(); downX = e.x; downY = e.y; longFired = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - downX) > 24 || abs(e.y - downY) > 24) removeCallbacks(longPress)
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!longFired && abs(e.x - downX) < 24 && abs(e.y - downY) < 24) { performClick() }
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        renderer.emote(listOf(Animator.Emote.WAVE, Animator.Emote.NOD, Animator.Emote.SHRUG, Animator.Emote.CHEER).random())
        return true
    }

    // ---- GL thread -------------------------------------------------------------------------------

    private inner class GlThread(private val st: SurfaceTexture, @Volatile var w: Int, @Volatile var h: Int) : Thread("AvatarOverlay") {
        @Volatile private var quit = false
        @Volatile private var resized = true
        fun resize(nw: Int, nh: Int) { w = nw; h = nh; resized = true }
        fun quit() { quit = true; interrupt(); runCatching { join(500) } }

        override fun run() {
            val dpy: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2); EGL14.eglInitialize(dpy, ver, 0, ver, 1)
            val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */, EGL14.EGL_NONE)
            val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0) || n[0] == 0) return
            val ctx: EGLContext = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            val surf: EGLSurface = EGL14.eglCreateWindowSurface(dpy, cfgs[0], st, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
            renderer.onSurfaceCreated()
            try {
                while (!quit) {
                    if (!running) { try { sleep(100) } catch (_: InterruptedException) {}; continue }
                    if (resized) { renderer.onSurfaceChanged(w, h); resized = false }
                    val t0 = SystemClock.uptimeMillis()
                    renderer.onDrawFrame()
                    EGL14.eglSwapBuffers(dpy, surf)
                    val spent = SystemClock.uptimeMillis() - t0
                    if (spent < 16) try { sleep(16 - spent) } catch (_: InterruptedException) {}
                }
            } finally {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                // No eglTerminate: the default display is shared with the avatar screen's GL view.
                EGL14.eglDestroySurface(dpy, surf); EGL14.eglDestroyContext(dpy, ctx)
            }
        }
    }
}
