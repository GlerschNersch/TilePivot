package dev.x360reborn.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.Button
import android.view.ViewConfiguration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The hub: pivot strip ("twist") over a horizon of channel pages, drawn in the dash's 1280x720
 * design space and fitted to the screen.
 */
@SuppressLint("ViewConstructor")
class HomeView(
    context: Context,
    /** Art for an icon key: an app icon, or a game cover ([GAME_ART] keys). Null while loading. */
    private val iconFor: (String) -> Bitmap?,
    private val onLaunch: (Tile, Rect) -> Unit,
    private val onLongPress: (Tile) -> Unit,
    /** Work-profile badge art for an app key (a full icon canvas, badge in its corner), or null. */
    private val badgeFor: (String) -> Bitmap?,
    /** Asked to rebuild the hub when rotation changes the layout. */
    private val onLayout: (Layout) -> Unit,
    /** A pinch picked a new tile size (persist it, confirm it). */
    private val onTileSize: (TileSize) -> Unit,
    /** Search key (or a typed letter/digit) asks for the search dialog, prefilled with the text. */
    private val onSearch: (String) -> Unit,
) : View(context) {

    var hub: Hub = HubBuilder.build(emptyList(), emptyList(), emptyList(), emptyList())
        private set

    private val L get() = hub.layout

    /** Layout the current view size wants; the hub catches up via [onLayout]. */
    var wanted: Layout = Layout.LANDSCAPE
        private set

    var tileSize = TileSize.LARGE
        set(v) {
            if (field == v) return
            field = v
            relayout()
        }

    /** Background treatment over the active theme's aura ramp. */
    var bgStyle = BgStyle.GRADIENT
        set(v) {
            if (field == v) return
            field = v
            fit()
            invalidate()
        }

    /** 24-hour hub clock instead of the dash's 12-hour one. */
    var clock24h = false
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    /** The active theme changed: rebuild the gradients and the cached text colours. */
    fun onThemeChanged() {
        ambience.clear()
        fit()
        invalidate()
    }

    // The clock is drawn every frame while anything animates; format once per minute.
    private val fmt12 = SimpleDateFormat("h:mm a", Locale.US)
    private val fmt24 = SimpleDateFormat("HH:mm", Locale.US)
    private var clockMinute = -1L
    private var clockWas24 = false
    private var clockString = ""

    private fun hubClock(): String {
        val now = System.currentTimeMillis()
        val minute = now / 60_000L
        if (minute != clockMinute || clock24h != clockWas24) {
            clockMinute = minute
            clockWas24 = clock24h
            clockString = (if (clock24h) fmt24 else fmt12).format(Date(now))
        }
        return clockString
    }

    /** Text paints cache their colours, so a theme change has to push the new ones in. */
    private fun applyColors() {
        twistText.color = Metrics.TEXT
        label.color = Metrics.TEXT
        heroPrimary.color = Metrics.TEXT
        heroSecondary.color = Metrics.TEXT_DIM
        clockText.color = Metrics.TEXT
        profileText.color = Metrics.TEXT
    }

    // Fit transform, design -> screen.
    private var s = 1f
    private var ox = 0f
    private var oy = 0f

    private var scroll = 0f
    private var scrollFrom = 0f
    private var scrollTo = 0f
    private var scrollStart = -1L
    private val scrollMs = 450f

    // Channel-entry cascade (0.5.1): when a channel becomes active, its landing page's
    // tiles pop in on a diagonal wave (see Anim). Purely visual -- input is never gated
    // on it -- and it leaves no redraw loop behind once settled.
    private var cascadePage = -1
    private var cascadeStart = 0L
    private var cascadeTotal = 0f
    /** System animator duration scale captured at cascade start; elapsed divides by it. */
    private var cascadeScale = 1f
    /** Per-slot wave delays for [cascadePage], computed when the cascade starts. */
    private var cascadeDelays = FloatArray(0)

    // Vertical grid scroll (0.9.0): one scrollable page per channel. Offsets and the
    // focused slot are remembered per channel and restored on return; the hero
    // never scrolls -- only the grid region under it moves.
    private val vOffsets = HashMap<Int, Float>()
    private val channelFocus = HashMap<Int, Int>()
    private var vAnimFrom = 0f
    private var vAnimTo = 0f
    private var vAnimStart = -1L
    private var vAnimPage = -1
    private var vFlingVel = 0f
    private var vFlingPage = -1
    private var lastVStep = 0L

    private var focusPage = 0
    private var focusSlot = 1
    private var twistFocused = false
    private var twistFocusStart = 0L

    private val focusAnim = HashMap<Int, Float>()
    private val rising = HashMap<Int, Boolean>()
    private var pressKey = -1
    private var pressStart = 0L
    private var lastFrame = 0L

    private val bgPaint = Paint()
    private val floorPaint = Paint()
    private val glowPaint = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val twistText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Metrics.TEXT; typeface = light; textSize = Metrics.pt(Metrics.TWIST_PT) }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Metrics.TEXT; textSize = Metrics.pt(13f) }
    private val heroPrimary = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Metrics.TEXT; textSize = Metrics.pt(17f) }
    private val heroSecondary = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Metrics.TEXT_DIM; textSize = Metrics.pt(16f) }
    private val clockText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Metrics.TEXT; typeface = light; textSize = Metrics.pt(18f); textAlign = Paint.Align.RIGHT }
    private val profileText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Metrics.TEXT; typeface = light; textSize = Metrics.pt(15f); textAlign = Paint.Align.RIGHT }
    private val rect = RectF()
    private val iconRect = RectF()
    private val srcRect = Rect()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setHub(next: Hub) {
        val title = hub.channels.getOrNull(hub.channelOf(focusPage))?.title
        val inPage = focusPage - (hub.channels.getOrNull(hub.channelOf(focusPage))?.firstPage ?: 0)
        // Landing-page content before the swap, for the entry-cascade decision below.
        val oldTiles = hub.pages.getOrNull(focusPage)?.tiles?.map { it.label to it.target.toString() }
        // Slot indices mean different cells in another layout, so a relayout refocuses the default.
        val relaid = next.layout != hub.layout
        hub = next
        val ch = next.channels.indexOfFirst { it.title == title }.takeIf { it >= 0 } ?: 0
        val c = next.channels[ch]
        focusPage = c.firstPage + min(inPage, c.pageCount - 1)
        if (relaid || !next.pages[focusPage].focusable(focusSlot)) focusSlot = defaultSlot(focusPage)
        // Scroll memory belongs to the old hub's pages; drop what the new hub lacks.
        vOffsets.keys.retainAll(next.pages.indices.toSet())
        channelFocus.keys.retainAll(next.channels.indices.toSet())
        vAnimStart = -1; vFlingPage = -1
        if (relaid) revealSlot(focusPage, focusSlot, animate = false)
        scroll = focusPage * L.w
        scrollStart = -1
        focusAnim.clear()
        // A fresh hub rebuild counts as a channel entry when the landing page's content
        // actually changed -- the first build, recents reordering after a launch, a pin
        // or folder edit, the focused channel itself being swapped. Pure relayouts
        // (rotation, tile size) and same-content rebuilds (a theme change reloads with
        // identical tiles) never cascade.
        if (!relaid) {
            val newTiles = next.pages.getOrNull(focusPage)?.tiles?.map { it.label to it.target.toString() }
            if (oldTiles == null || newTiles != oldTiles || next.channels[ch].title != title) startCascade(focusPage)
        }
        fit()
        invalidate()
        a11yTreeChanged()
    }

    private fun defaultSlot(page: Int): Int {
        val p = hub.pages[page]
        val hero = p.template.hero
        if (p.focusable(hero) && p.tiles[hero].kind == Kind.HERO) return hero
        return Nav.enter(p.template.slots, p::focusable, true, 0f)
    }

    /** Back/B: return to the home channel, like the dash's Home button. */
    fun back() {
        if (twistFocused) { twistFocused = false; invalidate(); return }
        if (focusPage != 0) goToPage(0, defaultSlot(0))
    }

    fun refreshClock() = invalidate()

    /** Full-size icon for the hero tile; defaults to the grid icon. */
    var heroIconFor: (String) -> Bitmap? = { iconFor(it) }

    /**
     * Icon edges [layout] draws, in screen px: (grid, hero). The grid edge is a filled
     * tile's long side, the hero edge the hero's icon. (0, 0) before the view has a size.
     * Apps rasterises icons at these sizes.
     */
    fun iconPxFor(layout: Layout): Pair<Int, Int> {
        if (width == 0 || height == 0) return Pair(0, 0)
        val scale = min(width / layout.w, height / layout.h)
        var grid = 0f
        var hero = 0f
        for (t in listOf(layout.home, layout.grid)) {
            for ((i, b) in t.slots.withIndex()) {
                if (i == t.hero) hero = max(hero, Metrics.heroIconSize(b.w, b.h, true))
                else grid = max(grid, max(b.w, b.h))
            }
        }
        return Pair((grid * scale).roundToInt(), (hero * scale).roundToInt())
    }

    // ---- layout ------------------------------------------------------------------------------

    /** Fade the hover chrome toward its target; true while it is still moving. */
    private fun stepChrome(dt: Float): Boolean {
        val target = if (hovering && !away) 1f else 0f
        if (chromeAlpha == target) return false
        chromeAlpha = if (target > chromeAlpha) min(1f, chromeAlpha + dt / CHROME_FADE_MS)
        else max(0f, chromeAlpha - dt / CHROME_FADE_MS)
        return chromeAlpha != target
    }

    /**
     * Kinect hover chrome (HubSceneStandard): back / home / search hover targets, 60x60
     * at (55,27), (610,27) and (1165,27) in the landscape design space; portrait keeps the
     * row at 48 px along the top (see [Metrics.hoverChromeBoxes]). Like the dash's Kinect
     * buttons it is pointer chrome only: it fades in while a mouse or stylus hovers the hub
     * and lights under the pointer; touch and gamepad never see it and keep their own paths
     * to the same three actions. Colours come from the theme like everything else.
     */
    private fun drawHoverChrome(canvas: Canvas) {
        if (chromeLayout !== L) {
            chromeLayout = L
            chromeBoxList = Metrics.hoverChromeBoxes(L.w, L.portrait, L.twistX)
            chromeBoxes.clear()
            chromeBoxList.forEach { chromeBoxes += RectF(it.x, it.y, it.right, it.bottom) }
        }
        val boxes = chromeBoxList
        if (chromeAlpha <= 0f) return
        for ((i, b) in boxes.withIndex()) {
            val hot = hovering && b.contains(hoverPos.first, hoverPos.second)
            fill.color = Metrics.SLOT_DARK
            fill.alpha = (170 * chromeAlpha).roundToInt()
            canvas.drawRoundRect(b.x, b.y, b.right, b.bottom, 10f, 10f, fill)
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 1.5f
            fill.color = Metrics.TEXT
            fill.alpha = (110 * chromeAlpha).roundToInt()
            canvas.drawRoundRect(b.x, b.y, b.right, b.bottom, 10f, 10f, fill)
            fill.style = Paint.Style.FILL
            if (hot) { // the button under the pointer lights, like a focused slot
                fill.color = Color.WHITE
                fill.alpha = (38 * chromeAlpha).roundToInt()
                canvas.drawRoundRect(b.x, b.y, b.right, b.bottom, 10f, 10f, fill)
            }
            fill.color = Metrics.TEXT
            fill.alpha = (235 * chromeAlpha).roundToInt()
            drawChromeGlyph(canvas, i, b.cx, b.cy, min(b.w, b.h))
        }
        fill.alpha = 255
    }

    /** The three hover-chrome glyphs, stroked in the fill colour: back arrow, home, search. */
    private fun drawChromeGlyph(canvas: Canvas, which: Int, cx: Float, cy: Float, size: Float) {
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = size * 0.085f
        val u = size / 2f
        when (which) {
            0 -> { // back: a left-pointing arrow
                canvas.drawLine(cx - u * 0.55f, cy, cx + u * 0.6f, cy, fill)
                canvas.drawLine(cx - u * 0.55f, cy, cx - u * 0.05f, cy - u * 0.5f, fill)
                canvas.drawLine(cx - u * 0.55f, cy, cx - u * 0.05f, cy + u * 0.5f, fill)
            }
            1 -> { // home: a roof over a simple house body
                canvas.drawLine(cx - u * 0.62f, cy - u * 0.05f, cx, cy - u * 0.6f, fill)
                canvas.drawLine(cx, cy - u * 0.6f, cx + u * 0.62f, cy - u * 0.05f, fill)
                canvas.drawLine(cx - u * 0.42f, cy + u * 0.05f, cx - u * 0.42f, cy + u * 0.58f, fill)
                canvas.drawLine(cx + u * 0.42f, cy + u * 0.05f, cx + u * 0.42f, cy + u * 0.58f, fill)
                canvas.drawLine(cx - u * 0.42f, cy + u * 0.58f, cx + u * 0.42f, cy + u * 0.58f, fill)
            }
            else -> { // search: a magnifier, the search tile's geometry
                val r = size * 0.27f
                val mx = cx - size * 0.08f
                val my = cy - size * 0.08f
                canvas.drawCircle(mx, my, r, fill)
                canvas.drawLine(mx + r * 0.72f, my + r * 0.72f, cx + size * 0.4f, cy + size * 0.4f, fill)
            }
        }
        fill.style = Paint.Style.FILL
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hovering = !away
                hoverPos = toDesign(e.x, e.y)
            }
            MotionEvent.ACTION_HOVER_EXIT -> hovering = false
        }
        invalidate()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    private fun relayout() {
        if (width == 0 || height == 0) return
        val next = Layout.forView(width, height, tileSize)
        wanted = next
        fit()
        if (next != hub.layout) onLayout(next)
    }

    private fun fit() {
        if (width == 0 || height == 0) return
        val w = width
        val h = height
        s = min(w / L.w, h / L.h)
        ox = (w - L.w * s) / 2f
        oy = (h - L.h * s) / 2f
        bgPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), bgColors(), null, Shader.TileMode.CLAMP)
        val floorColor = Metrics.FLOOR and 0xFFFFFF
        floorPaint.shader = LinearGradient(
            0f, oy, 0f, oy + L.h * s,
            intArrayOf(floorColor, (0x38 shl 24) or floorColor, (0xCB shl 24) or floorColor, Metrics.FLOOR, Metrics.FLOOR),
            floatArrayOf(0f, 300f / 720f, 500f / 720f, 600f / 720f, 1f), Shader.TileMode.CLAMP,
        )
        // The aura moves with the channel pan but never changes shape: build it once, at the
        // origin, and translate it per frame (it used to allocate a new gradient every frame).
        auraR = max(L.w, L.h) * 0.56f * s
        auraPaint.shader = RadialGradient(0f, 0f, auraR, intArrayOf(0x40FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        tileGloss.clear()
        heroShade.clear()
        sheens.clear()
        applyColors()
    }

    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auraMatrix = Matrix()
    private var auraR = 0f

    // Tile shaders depend only on a tile's size, so they are built once per size (cleared on fit).
    /** Tile-body top highlight, by tile height. */
    private val tileGloss = HashMap<Float, Shader>()
    /** Hero bottom shade, by hero height. */
    private val heroShade = HashMap<Float, Shader>()
    /** Focus corner sheen at full strength, by tile size; the focus amount rides on paint alpha. */
    private val sheens = HashMap<Long, Shader>()

    private fun sizeKey(w: Float, h: Float) = (w.toRawBits().toLong() shl 32) or (h.toRawBits().toLong() and 0xFFFFFFFFL)

    /** The background ramp for the current style: full gradient, one flat mid colour, or a darkened ramp. */
    private fun bgColors(): IntArray {
        val stops = Metrics.AURA_STOPS
        return when (bgStyle) {
            BgStyle.GRADIENT -> stops
            BgStyle.SOLID -> intArrayOf(stops[2], stops[2])
            BgStyle.DIMMED -> intArrayOf(dimmed(stops.first()), dimmed(stops.last()))
        }
    }

    private fun dimmed(color: Int): Int {
        val f = 0.55f
        return Color.rgb(
            (Color.red(color) * f).roundToInt(),
            (Color.green(color) * f).roundToInt(),
            (Color.blue(color) * f).roundToInt(),
        )
    }

    private fun pageX(page: Int): Float {
        val t = hub.pages[page].template
        return page * L.w - scroll + (L.w - t.w) / 2f
    }

    // ---- vertical grid scroll (0.9.0) ---------------------------------------------------

    /** Height of the scroll viewport on [page]: the panel region below the fixed hero. */
    private fun viewportH(page: Int) = L.panelsH - hub.pages[page].template.gridTop

    /** How far [page]'s grid may scroll: its content overflow past the viewport. */
    private fun maxVOff(page: Int): Float {
        val t = hub.pages[page].template
        return Scroll.maxOffset(t.h - t.gridTop, viewportH(page))
    }

    /** [page]'s current scroll offset, always inside its legal range. */
    private fun vOff(page: Int) = Scroll.clampOffset(vOffsets[page] ?: 0f, maxVOff(page))

    private fun setVOff(page: Int, value: Float) {
        vOffsets[page] = Scroll.clampOffset(value, maxVOff(page))
    }

    /** True when [slot] on [page] lives in the scrolling grid (not the fixed hero). */
    private fun isGridSlot(page: Int, slot: Int) = slot != hub.pages[page].template.hero

    /**
     * Steps the reveal animation and the fling; true while either is still moving.
     * The fling decays exponentially and stops dead at the edges -- no rubber band,
     * per the 0.9.0 gesture rules.
     */
    private fun stepVScroll(now: Long): Boolean {
        var moving = false
        if (vAnimStart >= 0) {
            val t = (now - vAnimStart) / V_REVEAL_MS
            if (t >= 1f) {
                setVOff(vAnimPage, vAnimTo)
                vAnimStart = -1
            } else {
                setVOff(vAnimPage, vAnimFrom + (vAnimTo - vAnimFrom) * Motion.easeOut(t))
                moving = true
            }
        }
        if (vFlingPage >= 0) {
            val dt = if (lastVStep == 0L) 16f else min(64f, (now - lastVStep).toFloat())
            val next = vOff(vFlingPage) + vFlingVel * dt / 1000f
            val clamped = Scroll.clampOffset(next, maxVOff(vFlingPage))
            setVOff(vFlingPage, clamped)
            vFlingVel *= Math.pow(0.5, (dt / V_FLING_HALF_MS).toDouble()).toFloat()
            if (clamped != next || abs(vFlingVel) < V_FLING_STOP) vFlingPage = -1 else moving = true
        }
        lastVStep = now
        return moving
    }

    /**
     * Scrolls [page] the minimum amount that shows [slot] in full (no move when it
     * is already fully visible). The hero and non-focusable slots never scroll.
     */
    private fun revealSlot(page: Int, slot: Int, animate: Boolean) {
        val p = hub.pages.getOrNull(page) ?: return
        if (!isGridSlot(page, slot) || !p.focusable(slot)) return
        val b = p.template.slots[slot]
        val gt = p.template.gridTop
        val target = Scroll.revealOffset(vOff(page), b.y - gt, b.bottom - gt, viewportH(page), maxVOff(page))
        if (target == vOff(page)) return
        vFlingPage = -1
        if (animate) {
            vAnimFrom = vOff(page)
            vAnimTo = target
            vAnimPage = page
            vAnimStart = SystemClock.uptimeMillis()
        } else {
            vAnimStart = -1
            setVOff(page, target)
        }
        invalidate()
    }

    /** Continuous channel index under the current scroll, for the twist and the aura pan. */
    private fun channelPos(): Float {
        val pf = scroll / L.w
        val last = hub.pages.size - 1
        val i0 = floor(pf).toInt().coerceIn(0, last)
        val i1 = min(i0 + 1, last)
        val frac = (pf - i0).coerceIn(0f, 1f)
        val c0 = hub.channelOf(i0)
        val c1 = hub.channelOf(i1)
        return c0 + (c1 - c0) * frac
    }

    // ---- drawing -----------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else min(64f, (now - lastFrame).toFloat())
        lastFrame = now
        var animating = stepScroll(now)
        animating = stepVScroll(now) || animating
        animating = stepFocus(dt) || animating
        animating = stepChrome(dt) || animating
        animating = stepCascade(now) || animating

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val cp = channelPos()
        drawAura(canvas, cp)
        canvas.drawRect(0f, oy, width.toFloat(), height.toFloat(), floorPaint)

        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(s, s)
        val (tScale, tAlpha) = transState(now)
        if (tAlpha <= 0f) {
            canvas.drawText(hubClock(), L.clockX, L.clockY, clockText)
            canvas.restore()
            if (trans != null) postInvalidateOnAnimation() else lastFrame = 0L
            return
        }
        val transforming = tScale != 1f || tAlpha < 1f
        if (transforming) {
            canvas.saveLayerAlpha(0f, 0f, L.w, L.h, (tAlpha * 255).roundToInt())
            canvas.scale(tScale, tScale, L.w / 2f, L.h / 2f)
        }
        val first = max(0, floor(scroll / L.w).toInt() - 1)
        val last = min(hub.pages.size - 1, first + 3)
        if (pinchScale != 1f) {
            canvas.save()
            canvas.scale(pinchScale, pinchScale, L.w / 2f, L.panelsY + L.panelsH / 2f)
        }
        // Only pages that reach the screen are drawn: off-screen ones cost draw time and,
        // worse, ask for icons the icon cache would then have to hold.
        val reach = 1f / min(1f, min(tScale, pinchScale))
        val viewL = (-ox / s - L.w / 2f) * reach + L.w / 2f - CULL_SLACK
        val viewR = ((width - ox) / s - L.w / 2f) * reach + L.w / 2f + CULL_SLACK
        for (p in first..last) {
            val x = pageX(p)
            if (x + hub.pages[p].template.w >= viewL && x <= viewR) drawPage(canvas, p, now)
        }
        if (pinchScale != 1f) canvas.restore()
        drawTwist(canvas, cp, now)
        if (transforming) canvas.restore()
        canvas.drawText(hubClock(), L.clockX, L.clockY, clockText)
        drawGear(canvas)
        drawProfile(canvas)
        drawHoverChrome(canvas)
        canvas.restore()

        if (trans != null) animating = true
        if (twistFocused) animating = true
        if (pressKey >= 0 && now - pressStart < Motion.pressDuration) animating = true
        if (animating) postInvalidateOnAnimation() else lastFrame = 0L
    }

    // ---- scene transitions -------------------------------------------------------------------

    private var trans: Trans? = null
    private var transStart = 0L
    /** After FROM finishes the hub stays hidden until the next transition brings it back. */
    private var hidden = false

    fun play(t: Trans) {
        trans = t
        transStart = SystemClock.uptimeMillis()
        hidden = false
        // Recents reorder while we're away, so the old slot now holds some other app. On home,
        // come back focused on the hero (the app just used), already lit as the hub flies in.
        if (t == Trans.BACK_TO && hub.channelOf(focusPage) == 0) {
            focusSlot = defaultSlot(focusPage)
            focusAnim.clear()
            focusAnim[key(focusPage, focusSlot)] = 1f
        }
        invalidate()
    }

    private fun transState(now: Long): Pair<Float, Float> {
        val t = trans ?: return if (hidden) Pair(1f, 0f) else Pair(1f, 1f)
        val ms = (now - transStart).toFloat()
        if (ms >= t.totalMs) {
            trans = null
            hidden = t.alphaTo == 0f
            return Pair(t.scaleTo, t.alphaTo)
        }
        return t.at(ms)
    }

    /** True while the hub is leaving or gone; input is ignored so nothing launches twice. */
    private val away get() = hidden || trans == Trans.FROM

    /** Brings a hidden (or leaving) hub back with TransTo; a no-op while it is showing. */
    fun reveal() {
        if (away) play(Trans.TO)
    }

    private fun drawAura(canvas: Canvas, cp: Float) {
        // BackgroundImagePanScale 1.3: the aura is 30% wider than the screen and pans across channels.
        val n = max(1, hub.channels.size - 1)
        val pan = L.w * (Metrics.PAN_SCALE - 1f)
        val cx = ox + (L.w / 2f + pan / 2f - pan * cp / n) * s
        val cy = oy + L.h * 0.36f * s
        auraMatrix.setTranslate(cx, cy)
        auraPaint.shader?.setLocalMatrix(auraMatrix)
        canvas.drawCircle(cx, cy, auraR, auraPaint)
    }

    private fun key(page: Int, slot: Int) = page * 64 + slot

    private fun drawPage(canvas: Canvas, page: Int, now: Long) {
        val p = hub.pages[page]
        val x0 = pageX(page)
        val top = L.panelsY
        // Settings tiles are controls whose labels carry state ("theme: Midnight"), so
        // the settings channel keeps the classic icon-over-label rendering (drawSmall).
        val labeled = hub.channels[p.channel].title == "settings"
        // Entry cascade: only the page whose channel just became active animates, and
        // only while its wave is running; every other page draws at rest.
        val entryMs = if (page == cascadePage) cascadeElapsed(now) else -1f
        fun delay(i: Int) = if (entryMs >= 0f) cascadeDelays.getOrElse(i) { 0f } else -1f
        // Draw the focused tile last so its 1.08x growth overlaps its neighbours.
        var focusedLast = -1
        for (i in p.tiles.indices) {
            if (p.focusable(i) && (focusAnim[key(page, i)] ?: 0f) > 0f) focusedLast = i
        }
        val gt = p.template.gridTop
        val off = vOff(page)
        // The scrolling grid, clipped to the panel region under the fixed hero; rows
        // that cannot reach the viewport are culled (plus slack for growth/shadow).
        canvas.save()
        canvas.clipRect(x0, top + gt, x0 + p.template.w, top + L.panelsH)
        for (i in p.tiles.indices) {
            if (!p.focusable(i) || i == focusedLast || !isGridSlot(page, i)) continue
            val b = p.template.slots[i]
            if (b.bottom - off < gt - CULL_SLACK || b.y - off > L.panelsH + CULL_SLACK) continue
            drawTile(canvas, p.tiles[i], b, x0, top - off, key(page, i), now, labeled, delay(i), entryMs)
        }
        if (focusedLast >= 0 && isGridSlot(page, focusedLast)) {
            val i = focusedLast
            drawTile(canvas, p.tiles[i], p.template.slots[i], x0, top - off, key(page, i), now, labeled, delay(i), entryMs)
        }
        canvas.restore()
        // The hero, fixed at the top of the panel: it never scrolls, never clips.
        val hero = p.template.hero
        if (hero >= 0 && p.focusable(hero) && hero != focusedLast) {
            drawTile(canvas, p.tiles[hero], p.template.slots[hero], x0, top, key(page, hero), now, labeled, delay(hero), entryMs)
        }
        if (focusedLast == hero && hero >= 0) {
            drawTile(canvas, p.tiles[hero], p.template.slots[hero], x0, top, key(page, hero), now, labeled, delay(hero), entryMs)
        }
    }

    private fun drawTile(canvas: Canvas, tile: Tile, b: Box, x0: Float, y0: Float, k: Int, now: Long, labeled: Boolean, entryDelayMs: Float = -1f, entryElapsedMs: Float = 0f) {
        val f = focusAnim[k] ?: 0f
        val pressMs = if (k == pressKey) (now - pressStart).toFloat() else -1f
        val sc = Motion.scale(f, pressMs)
        val x = x0 + b.x
        val y = y0 + b.y
        // Entry cascade: scale up from Anim.FROM_SCALE and rise into place, composed
        // into the same centre transform as the focus scale, so the shadow, sheen and
        // flash drawn below ride along and stay aligned with the tile.
        val (entryScale, entryRise) =
            if (entryDelayMs >= 0f) Anim.entryAt(entryElapsedMs, entryDelayMs) else Pair(1f, 0f)
        canvas.save()
        canvas.translate(x + b.w / 2f, y + b.h / 2f + entryRise)
        canvas.scale(sc * entryScale, sc * entryScale)
        canvas.translate(-b.w / 2f, -b.h / 2f)
        rect.set(0f, 0f, b.w, b.h)

        if (f > 0f) {
            // Shadow nine-grid fades to 0.8 alongside the scale-up.
            shadow.color = Color.argb((f * 60).roundToInt(), 0, 0, 0)
            shadow.setShadowLayer(22f, 0f, 6f, Color.argb((Metrics.SHADOW_ALPHA * f * 160).roundToInt(), 0, 0, 0))
            canvas.drawRect(rect, shadow)
        }

        when (tile.kind) {
            Kind.HERO -> drawHero(canvas, tile, b)
            else -> drawSmall(canvas, tile, b, labeled)
        }

        if (f > 0f) {
            // focus_state_white.png: a white sheen from the top-left corner. The shader is
            // cached at full strength and the focus amount scales it through the paint alpha.
            fill.shader = sheens.getOrPut(sizeKey(b.w, b.h)) {
                LinearGradient(0f, 0f, b.w * 0.55f, b.h * 0.55f,
                    Color.argb((Metrics.CORNER_ALPHA * 255).roundToInt(), 255, 255, 255), 0x00FFFFFF, Shader.TileMode.CLAMP)
            }
            fill.color = Color.WHITE
            fill.alpha = (f * 255).roundToInt()
            canvas.drawRect(rect, fill)
            fill.alpha = 255
            fill.shader = null
        }
        val flash = max(Motion.flash(f, rising[k] == true), Motion.pressFlash(pressMs))
        if (flash > 0f) {
            fill.color = Color.argb((flash * 255).roundToInt(), 255, 255, 255)
            canvas.drawRect(rect, fill)
        }
        canvas.restore()
    }

    private fun drawSmall(canvas: Canvas, tile: Tile, b: Box, labeled: Boolean) {
        // Settings controls and the search tile are text first: their labels carry state
        // or name the action, so they keep the icon-over-label layout. Every other grid
        // tile is pure art (0.4.3): the artwork fills the tile and there is no text.
        if (labeled || tile.glyph == Glyph.SEARCH) drawLabeled(canvas, tile, b) else drawFilled(canvas, tile, b)
    }

    /** The themed tile surface: flat colour with a soft top highlight (glass stand-in). */
    private fun drawTileBody(canvas: Canvas, b: Box) {
        fill.color = Metrics.TILE_GREEN
        canvas.drawRect(rect, fill)
        fill.shader = tileGloss.getOrPut(b.h) { LinearGradient(0f, 0f, 0f, b.h, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP) }
        canvas.drawRect(rect, fill)
        fill.shader = null
    }

    /** The pre-0.4.3 grid tile: themed tile, centred icon, label (and sub) underneath. */
    private fun drawLabeled(canvas: Canvas, tile: Tile, b: Box) {
        drawTileBody(canvas, b)

        // Settings labels carry state and must stay fully readable: the line shrinks to
        // fit (down to Metrics.LABEL_MIN_SCALE), and a label that still cannot fit wraps
        // to two lines instead of ellipsizing -- "xbox 360 games: N found" was cut off at
        // every tile size before this. Tiles with a sub keep the one-line ellipsized form.
        val avail = b.w - 20f
        val full = label.measureText(tile.label)
        val scale = Metrics.labelScale(full, avail)
        val wrapped = if (tile.sub.isEmpty() && full * Metrics.LABEL_MIN_SCALE > avail) {
            Metrics.splitLabel(tile.label)
        } else null
        // Tiles with a sub (folder sizes, game formats) give the text block a second line;
        // a wrapped label reserves one more line of its own.
        val block = when {
            tile.sub.isNotEmpty() -> Metrics.LABEL_BLOCK_SUB
            wrapped != null -> Metrics.LABEL_BLOCK + Metrics.LABEL_LINE_H
            else -> Metrics.LABEL_BLOCK
        }
        val icon = min(Metrics.gridIconSize(b.w, b.h, tile.sub.isNotEmpty()), b.h - block - 6f)
        val ix = (b.w - icon) / 2f
        val iy = (b.h - block - icon) / 2f + 4f
        drawIcon(canvas, tile, ix, iy, icon)
        if (wrapped != null) {
            canvas.drawText(wrapped.first, 10f, b.h - 12f - Metrics.LABEL_LINE_H, label)
            canvas.drawText(wrapped.second, 10f, b.h - 12f, label)
            return
        }
        val baseSize = label.textSize
        if (scale < 1f) label.textSize = baseSize * scale
        val text = if (scale < 1f) tile.label
            else TextUtils.ellipsize(tile.label, label, avail, TextUtils.TruncateAt.END)
        if (tile.sub.isEmpty()) {
            canvas.drawText(text, 0, text.length, 10f, b.h - 12f, label)
        } else {
            canvas.drawText(text, 0, text.length, 10f, b.h - 28f, label)
            label.textSize = baseSize
            label.color = Metrics.TEXT_DIM
            val sub = TextUtils.ellipsize(tile.sub, label, b.w - 20f, TextUtils.TruncateAt.END)
            canvas.drawText(sub, 0, sub.length, 10f, b.h - 10f, label)
            label.color = Metrics.TEXT
        }
        label.textSize = baseSize
    }

    /**
     * A grid tile as pure art (0.4.3): the app's icon covers the whole tile (centre-crop,
     * never stretched) and no label or sub is drawn. Glyph tiles (360 games, folders, the
     * home shortcuts) keep their drawn glyph, large and centred on the themed tile.
     */
    private fun drawFilled(canvas: Canvas, tile: Tile, b: Box) {
        val key = tile.iconKey
        val bmp = key?.let(iconFor)
        if (bmp == null) {
            drawTileBody(canvas, b)
            val size = Metrics.gridGlyphSize(b.w, b.h)
            drawIcon(canvas, tile, (b.w - size) / 2f, (b.h - size) / 2f, size)
            return
        }
        // Themed underlay: a legacy icon with transparent margins still sits on the tile colour.
        fill.color = Metrics.TILE_GREEN
        canvas.drawRect(rect, fill)
        val src = Metrics.coverSrc(bmp.width.toFloat(), bmp.height.toFloat(), b.w, b.h)
        srcRect.set(src.left.roundToInt(), src.top.roundToInt(), src.right.roundToInt(), src.bottom.roundToInt())
        iconRect.set(0f, 0f, b.w, b.h)
        canvas.drawBitmap(bmp, srcRect, iconRect, bmpPaint)
        // The badge is separate art (it would be sliced by the cover crop), overlaid at the corner.
        if (key != null) drawBadge(canvas, key, b.w, b.h, min(b.w, b.h) * BADGE_SIDE, BADGE_INSET)
    }

    /**
     * Overlays the work-profile badge with its canvas corner at ([right], [bottom]),
     * inset by [inset]. The badge bitmap is a full icon canvas with the badge drawn in
     * its own bottom-right corner, so a square of [side] keeps its designed placement.
     */
    private fun drawBadge(canvas: Canvas, key: String, right: Float, bottom: Float, side: Float, inset: Float) {
        val badge = badgeFor(key) ?: return
        iconRect.set(right - side - inset, bottom - side - inset, right - inset, bottom - inset)
        canvas.drawBitmap(badge, null, iconRect, bmpPaint)
    }

    // ---- hero ambience ------------------------------------------------------------
    // The hero's backdrop is the focused item's own art, softened: stored at
    // ~1/12 size and drawn back up through the filtering bitmap paint, which
    // reads as a blur. Cached per item and theme, so a settled hero costs one
    // drawBitmap per frame; an entry is recomputed only when its source bitmap
    // changes -- a cover that finished decoding replaces the glyph that stood
    // in for it, an app icon replaces its placeholder the same way.

    /** Softened ambience by cache key, each with the source bitmap it came from. */
    private val ambience = HashMap<String, Pair<Bitmap, Bitmap>>()

    private fun drawAmbience(canvas: Canvas, tile: Tile, b: Box) {
        val key = (tile.iconKey ?: "glyph:${tile.glyph}:${tile.label}") + '|' + Themes.active.id
        val src = tile.iconKey?.let(heroIconFor)
        val cached = ambience[key]
        val soft = if (cached != null && (tile.iconKey == null || cached.first === src)) {
            cached.second
        } else {
            val source = src ?: glyphBitmap(tile)
            soften(source).also {
                if (ambience.size > 12) ambience.clear()
                ambience[key] = source to it
            }
        }
        val win = Metrics.coverSrc(soft.width.toFloat(), soft.height.toFloat(), b.w, b.h)
        srcRect.set(win.left.roundToInt(), win.top.roundToInt(), win.right.roundToInt(), win.bottom.roundToInt())
        iconRect.set(0f, 0f, b.w, b.h)
        // The wash never leaves the panel: clip bitmap and scrim to the hero rect,
        // exact even while the tile's focus/cascade transform is live.
        canvas.save()
        canvas.clipRect(rect)
        canvas.drawBitmap(soft, srcRect, iconRect, bmpPaint)
        // ~60% black scrim: the ambience glows, the name and sub stay legible.
        fill.color = AMBIENCE_SCRIM
        canvas.drawRect(rect, fill)
        canvas.restore()
    }

    /** The tile's glyph drawn on its themed body, for tiles with no bitmap art. */
    private fun glyphBitmap(tile: Tile): Bitmap {
        val bmp = Bitmap.createBitmap(GLYPH_PX, GLYPH_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fill.color = Metrics.TILE_GREEN
        c.drawRect(0f, 0f, GLYPH_PX.toFloat(), GLYPH_PX.toFloat(), fill)
        drawIcon(c, tile, 0f, 0f, GLYPH_PX.toFloat())
        return bmp
    }

    /** Downscale ~12x; drawn back up with filtering, the pixels melt into a wash. */
    private fun soften(src: Bitmap): Bitmap =
        Bitmap.createScaledBitmap(src, max(1, src.width / 12), max(1, src.height / 12), true)

    private fun drawHero(canvas: Canvas, tile: Tile, b: Box) {
        fill.color = Metrics.SLOT_DARK
        canvas.drawRect(rect, fill)
        drawAmbience(canvas, tile, b)
        val areaH = Metrics.heroIconAreaH(b.h, tile.sub.isNotEmpty())
        val art = if (tile.iconKey?.startsWith(GAME_ART) == true) tile.iconKey?.let(heroIconFor) else null
        if (art != null) {
            // Box art keeps its own shape: the whole cover, fit inside the icon
            // area and centred -- never stretched into the square an app icon gets.
            val (w, h) = Metrics.fitSize(art.width.toFloat(), art.height.toFloat(), b.w - 40f, areaH)
            iconRect.set((b.w - w) / 2f, (areaH - h) / 2f, (b.w + w) / 2f, (areaH + h) / 2f)
            canvas.drawBitmap(art, null, iconRect, bmpPaint)
        } else {
            val icon = Metrics.heroIconSize(b.w, b.h, tile.sub.isNotEmpty())
            val ix = (b.w - icon) / 2f
            val iy = (areaH - icon) / 2f
            drawIcon(canvas, tile, ix, iy, icon, hero = true)
            // The hero's badge sits where the baked-in one used to: the icon's bottom-right corner.
            tile.iconKey?.let { drawBadge(canvas, it, ix + icon, iy + icon, icon * BADGE_SIDE, 0f) }
        }
        // mobyslot: gradient from 170/320 of the height, primary 17 pt, secondary 16 pt.
        fill.shader = heroShade.getOrPut(b.h) {
            LinearGradient(0f, b.h * 170f / 320f, 0f, b.h, 0x00000000, 0xC0000000.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, b.h * 170f / 320f, b.w, b.h, fill)
        fill.shader = null
        val primary = TextUtils.ellipsize(tile.label, heroPrimary, b.w - 40f, TextUtils.TruncateAt.END)
        canvas.drawText(primary, 0, primary.length, 20f, b.h - (if (tile.sub.isEmpty()) 18f else 40f), heroPrimary)
        if (tile.sub.isNotEmpty()) canvas.drawText(tile.sub, 20f, b.h - 16f, heroSecondary)
    }

    private fun drawIcon(canvas: Canvas, tile: Tile, x: Float, y: Float, size: Float, hero: Boolean = false) {
        val bmp = tile.iconKey?.let(if (hero) heroIconFor else iconFor)
        if (bmp != null) {
            iconRect.set(x, y, x + size, y + size)
            canvas.drawBitmap(bmp, null, iconRect, bmpPaint)
            return
        }
        fill.color = Metrics.TEXT
        when (tile.glyph) {
            Glyph.ALL_APPS -> {
                val cell = size / 4f
                for (r in 0 until 3) for (c in 0 until 3) {
                    val cx = x + size / 8f + c * cell * 1.15f
                    val cy = y + size / 8f + r * cell * 1.15f
                    canvas.drawRect(cx, cy, cx + cell * 0.85f, cy + cell * 0.85f, fill)
                }
            }
            Glyph.SETTINGS -> drawGearGlyph(canvas, x + size / 2f, y + size / 2f, size)
            Glyph.SEARCH -> {
                // Magnifier: stroked circle with a handle running down-right.
                val cx = x + size * 0.42f
                val cy = y + size * 0.42f
                val r = size * 0.27f
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = size * 0.11f
                canvas.drawCircle(cx, cy, r, fill)
                canvas.drawLine(cx + r * 0.72f, cy + r * 0.72f, x + size * 0.9f, y + size * 0.9f, fill)
                fill.style = Paint.Style.FILL
            }
            Glyph.FOLDER -> {
                // Folder outline: a tabbed back panel, with a solid front panel in front of it.
                val left = x + size * 0.05f
                val right = x + size * 0.95f
                fill.alpha = 110
                canvas.drawRect(left, y + size * 0.3f, right, y + size * 0.88f, fill)
                canvas.drawRect(left, y + size * 0.18f, x + size * 0.5f, y + size * 0.32f, fill)
                fill.alpha = 255
                canvas.drawRect(left + size * 0.07f, y + size * 0.48f, right - size * 0.07f, y + size * 0.8f, fill)
            }
            Glyph.GAMEPAD -> {
                // Controller: a light rounded body on the tile, with the d-pad and buttons
                // punched back out in the tile's own green (dark slots keep them readable too).
                val w = size * 0.94f
                val h = size * 0.6f
                val left = x + (size - w) / 2f
                val top = y + (size - h) / 2f
                canvas.drawRoundRect(left, top, left + w, top + h, h * 0.5f, h * 0.5f, fill)
                fill.color = Metrics.TILE_GREEN
                val cx = left + w * 0.27f
                val cy = top + h / 2f
                val arm = size * 0.075f
                val len = size * 0.12f
                canvas.drawRect(cx - len, cy - arm, cx + len, cy + arm, fill)
                canvas.drawRect(cx - arm, cy - len, cx + arm, cy + len, fill)
                val r = size * 0.06f
                canvas.drawCircle(left + w * 0.7f, cy - size * 0.09f, r, fill)
                canvas.drawCircle(left + w * 0.85f, cy + size * 0.03f, r, fill)
                canvas.drawCircle(left + w * 0.78f, cy + size * 0.13f, r * 0.8f, fill)
                fill.color = Metrics.TEXT
            }
            Glyph.TILE_SIZE -> {
                // Big square shrinking into a 2x2 of small ones.
                val g = size * 0.08f
                val half = (size - g) / 2f
                fill.alpha = 110
                canvas.drawRect(x, y, x + size, y + size, fill)
                fill.alpha = 255
                for (r in 0 until 2) for (c in 0 until 2) {
                    if (r == 0 && c == 0) continue
                    val cx = x + c * (half + g)
                    val cy = y + r * (half + g)
                    canvas.drawRect(cx + g, cy + g, cx + half - g, cy + half - g, fill)
                }
            }
            Glyph.THEME -> {
                // Palette disc in the text colour, with the tile colour punched back out as paint dots.
                val cx = x + size / 2f
                val cy = y + size / 2f
                canvas.drawCircle(cx, cy, size * 0.42f, fill)
                fill.color = Metrics.TILE_GREEN
                canvas.drawCircle(cx - size * 0.16f, cy + size * 0.02f, size * 0.09f, fill)
                canvas.drawCircle(cx + size * 0.1f, cy - size * 0.17f, size * 0.09f, fill)
                canvas.drawCircle(cx + size * 0.17f, cy + size * 0.15f, size * 0.09f, fill)
                fill.color = Metrics.TEXT
            }
            Glyph.BACKUP, Glyph.RESTORE -> {
                // A tray with an arrow: up out of it to back up, down into it to restore.
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = size * 0.1f
                val l = x + size * 0.14f
                val r = x + size * 0.86f
                val trayTop = y + size * 0.6f
                val bottom = y + size * 0.86f
                canvas.drawLine(l, trayTop, l, bottom, fill)
                canvas.drawLine(l, bottom, r, bottom, fill)
                canvas.drawLine(r, bottom, r, trayTop, fill)
                val cx = x + size / 2f
                val top = y + size * 0.1f
                val tip = y + size * 0.66f
                canvas.drawLine(cx, top, cx, tip, fill)
                val head = size * 0.2f
                if (tile.glyph == Glyph.BACKUP) {
                    canvas.drawLine(cx, top, cx - head, top + head, fill)
                    canvas.drawLine(cx, top, cx + head, top + head, fill)
                } else {
                    canvas.drawLine(cx, tip, cx - head, tip - head, fill)
                    canvas.drawLine(cx, tip, cx + head, tip - head, fill)
                }
                fill.style = Paint.Style.FILL
            }
            Glyph.PROFILE -> drawPersonGlyph(canvas, x, y, size)
            Glyph.NONE -> {}
        }
    }

    /** Head-and-shoulders silhouette in the current fill colour, filling a [size] square at ([x], [y]). */
    private fun drawPersonGlyph(canvas: Canvas, x: Float, y: Float, size: Float) {
        val cx = x + size / 2f
        canvas.drawCircle(cx, y + size * 0.36f, size * 0.19f, fill)
        canvas.save()
        canvas.clipRect(x, y, x + size, y + size * 0.94f)
        rect.set(cx - size * 0.36f, y + size * 0.6f, cx + size * 0.36f, y + size * 1.28f)
        canvas.drawOval(rect, fill)
        canvas.restore()
    }

    private val twistBoxes = ArrayList<RectF>()

    private fun drawTwist(canvas: Canvas, cp: Float, now: Long) {
        val widths = hub.channels.map { twistText.measureText(it.title) + Metrics.TWIST_GAP }
        val prefix = FloatArray(widths.size + 1)
        for (i in widths.indices) prefix[i + 1] = prefix[i] + widths[i]
        val i0 = floor(cp).toInt().coerceIn(0, widths.size - 1)
        val frac = cp - i0
        val origin = prefix[i0] + (if (i0 + 1 < widths.size) widths[i0] * frac else 0f)
        val baseline = L.twistY + Metrics.TWIST_H / 2f - (twistText.ascent() + twistText.descent()) / 2f
        twistBoxes.clear()
        for ((i, c) in hub.channels.withIndex()) {
            val x = L.twistX + prefix[i] - origin
            twistBoxes += RectF(x, L.twistY, x + widths[i], L.twistY + Metrics.TWIST_H)
            if (x > L.w || x + widths[i] < -L.twistX) continue
            val d = min(1f, abs(i - cp))
            val a = 1f - (1f - Metrics.TWIST_DIM) * d
            if (twistFocused && i == hub.channelOf(focusPage)) {
                // Selected glow nine-grid (640x54 at -20,18 inside the 84-high item).
                val g = Motion.twistGlow((now - twistFocusStart).toFloat())
                val w = widths[i] - Metrics.TWIST_GAP
                glowPaint.shader = RadialGradient(x + w / 2f, L.twistY + 45f, w * 0.75f + 20f,
                    intArrayOf(Color.argb((g * 200).roundToInt(), 255, 255, 255), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                canvas.save()
                canvas.scale(1f, 0.35f, x + w / 2f, L.twistY + 45f)
                canvas.drawCircle(x + w / 2f, L.twistY + 45f, w * 0.75f + 20f, glowPaint)
                canvas.restore()
            }
            twistText.alpha = (a * 255).roundToInt()
            canvas.drawText(c.title, x, baseline, twistText)
        }
        twistText.alpha = 255
    }

    /** The settings channel's index, or -1 when the hub has none (it always does). */
    private fun settingsIndex() = hub.channels.indexOfFirst { it.title == "settings" }

    /** The settings gear's hit box in design space (see [Metrics.settingsGearBox]). */
    private fun gearBox(): Box =
        Metrics.settingsGearBox(L.clockX, L.clockY, clockText.measureText(hubClock()))

    /**
     * The persistent settings shortcut (0.9.0, tester feedback): a gear in the clock
     * strip, left of the clock, visible on every channel. On the settings channel
     * itself it is drawn dimmed and tapping it does nothing. Pointer and TalkBack
     * only -- it is never in the DPAD focus order.
     */
    private fun drawGear(canvas: Canvas) {
        val gb = gearBox()
        fill.color = Metrics.TEXT
        fill.alpha = if (hub.channelOf(focusPage) == settingsIndex()) GEAR_DIM_ALPHA else 235
        drawGearGlyph(canvas, gb.cx, gb.cy, Metrics.GEAR_GLYPH)
        fill.alpha = 255
    }

    // ---- profile chip -------------------------------------------------------------------

    /** The user's chosen picture, or null for the drawn placeholder. */
    var profilePicture: Bitmap? = null
        set(v) { field = v; invalidate() }

    /** The user's display name; empty shows the picture alone. */
    var profileName: String = ""
        set(v) {
            if (field == v) return
            field = v
            profileShownFor = Float.NaN
            invalidate()
            a11yTreeChanged()
        }

    /** Tapped the profile chip (or activated it with TalkBack). */
    var onProfile: () -> Unit = {}

    /** The ellipsized name and the width it was cut for. */
    private var profileShown = ""
    private var profileShownFor = Float.NaN

    /** Where the chip sits now, left of the gear; null when the strip has no room for it. */
    private fun profileChip(): ProfileChip? =
        Metrics.profileChip(gearBox().x, L.clockY,
            if (profileName.isEmpty()) 0f else profileText.measureText(profileName), L.twistX)

    /**
     * The clock-strip profile chip (0.10.0): the name right-aligned beside a square
     * picture, after the dash's MiniGamercard. With no picture chosen it shows a drawn
     * silhouette on the theme's tile colour. Like the gear: pointer and TalkBack only.
     */
    private fun drawProfile(canvas: Canvas) {
        val chip = profileChip() ?: return
        val p = chip.pic
        val bmp = profilePicture
        if (bmp != null) {
            iconRect.set(p.x, p.y, p.right, p.bottom)
            canvas.drawBitmap(bmp, null, iconRect, bmpPaint)
        } else {
            fill.color = Metrics.TILE_GREEN
            canvas.drawRect(p.x, p.y, p.right, p.bottom, fill)
            fill.color = Metrics.TEXT
            fill.alpha = 235
            drawPersonGlyph(canvas, p.x + p.w * 0.1f, p.y + p.h * 0.1f, p.w * 0.8f)
            fill.alpha = 255
        }
        if (chip.nameMaxW > 0f) {
            if (profileShownFor != chip.nameMaxW) {
                profileShown = TextUtils.ellipsize(profileName, profileText, chip.nameMaxW, TextUtils.TruncateAt.END).toString()
                profileShownFor = chip.nameMaxW
            }
            canvas.drawText(profileShown, chip.nameRight, L.clockY, profileText)
        }
    }

    /** The gear glyph, shared by the top-strip shortcut and the settings tiles. */
    private fun drawGearGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = size * 0.12f
        canvas.drawCircle(cx, cy, size * 0.26f, fill)
        fill.style = Paint.Style.FILL
        for (i in 0 until 8) {
            canvas.save()
            canvas.rotate(i * 45f, cx, cy)
            canvas.drawRect(cx - size * 0.07f, cy - size * 0.46f, cx + size * 0.07f, cy - size * 0.3f, fill)
            canvas.restore()
        }
    }

    // ---- animation ---------------------------------------------------------------------------

    private fun stepScroll(now: Long): Boolean {
        if (scrollStart < 0) return false
        val t = (now - scrollStart) / scrollMs
        scroll = scrollFrom + (scrollTo - scrollFrom) * Motion.easeOut(t)
        if (t >= 1f) { scroll = scrollTo; scrollStart = -1; return false }
        return true
    }

    private fun stepFocus(dt: Float): Boolean {
        var moving = false
        val want = if (twistFocused) -1 else key(focusPage, focusSlot)
        val keys = HashSet(focusAnim.keys)
        if (want >= 0) keys += want
        for (k in keys) {
            val cur = focusAnim[k] ?: 0f
            val target = if (k == want) 1f else 0f
            if (cur == target) { if (target == 0f) focusAnim.remove(k); continue }
            val next = if (target > cur) min(1f, cur + dt * Motion.focusRate) else max(0f, cur - dt * Motion.blurRate)
            rising[k] = target > cur
            if (next == 0f) focusAnim.remove(k) else focusAnim[k] = next
            moving = true
        }
        return moving
    }

    // ---- entry cascade ---------------------------------------------------------------------

    /**
     * Starts the entry cascade for [page], whose channel just became active. The system
     * animator duration scale is honoured: at 0 the tiles simply appear, and larger
     * scales stretch the wave. A restart replaces any running cascade outright, so a
     * fast second channel switch never shows half-animated state from the previous one.
     */
    private fun startCascade(page: Int) {
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        if (scale <= 0f || page !in hub.pages.indices) { cascadePage = -1; return }
        cascadeScale = scale
        cascadePage = page
        cascadeStart = SystemClock.uptimeMillis()
        val p = hub.pages[page]
        // Delays are fixed for the run, so work them out once rather than per tile per
        // frame. The wave runs over the rows visible at the landing scroll offset;
        // tiles below the fold sit at rest (delay -1) until scrolled into view.
        val gridSlots = p.template.slots.filterIndexed { i, _ -> i != p.template.hero }
        val rowTops = gridSlots.map { it.y }.distinct().sorted()
        val colLefts = gridSlots.map { it.x }.distinct().sorted()
        val tileH = gridSlots.firstOrNull()?.h ?: 0f
        val gutterY = if (rowTops.size > 1) rowTops[1] - rowTops[0] - tileH else 0f
        val firstRow = if (rowTops.isNotEmpty() && tileH > 0f && viewportH(page) > 0f)
            Scroll.visibleRows(vOff(page), viewportH(page), tileH, gutterY, rowTops.size).first
            else 0
        cascadeDelays = FloatArray(p.template.slots.size) { slot ->
            if (slot == p.template.hero) 0f
            else {
                val b = p.template.slots[slot]
                val row = rowTops.indexOf(b.y)
                if (row < firstRow) -1f else Anim.delayFor(row - firstRow, colLefts.indexOf(b.x))
            }
        }
        var maxDelay = 0f
        for (i in p.tiles.indices) if (p.focusable(i)) maxDelay = max(maxDelay, cascadeDelays[i])
        cascadeTotal = Anim.totalMs(maxDelay)
        invalidate()
    }

    /** True while the cascade is still running; ends it the moment it settles. */
    private fun stepCascade(now: Long): Boolean {
        if (cascadePage < 0) return false
        if (cascadeElapsed(now) >= cascadeTotal) { cascadePage = -1; return false }
        return true
    }

    /** Cascade clock: wall time since the entry, stretched by the system duration scale. */
    private fun cascadeElapsed(now: Long) = (now - cascadeStart) / cascadeScale

    private fun animateScrollTo(page: Int) {
        scrollFrom = scroll
        scrollTo = page * L.w
        scrollStart = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun goToPage(page: Int, slot: Int) {
        // The cascade fires only on a genuine channel change (0.9.0: one page per
        // channel, so every page change is a channel change).
        val channelChanged = page in hub.pages.indices && focusPage in hub.pages.indices &&
            hub.channelOf(page) != hub.channelOf(focusPage)
        if (channelChanged) {
            // Remember where the old channel was left: its focus (the scroll offset
            // is already per-page in vOffsets and simply stays).
            channelFocus[hub.channelOf(focusPage)] = focusSlot
            vAnimStart = -1
            vFlingPage = -1
        }
        focusPage = page
        focusSlot = slot
        twistFocused = false
        animateScrollTo(page)
        // Land showing the focused tile before the cascade measures its wave.
        if (channelChanged) revealSlot(page, slot, animate = false)
        if (channelChanged) startCascade(page)
        a11yTreeChanged()
        a11yFocusMoved()
    }

    private fun goToChannel(ch: Int, keepTwist: Boolean) {
        val idx = ch.coerceIn(0, hub.channels.size - 1)
        val c = hub.channels[idx]
        // A channel remembers its focused tile across visits (0.9.0).
        val remembered = channelFocus[idx]?.takeIf { hub.pages[c.firstPage].focusable(it) }
        goToPage(c.firstPage, remembered ?: defaultSlot(c.firstPage))
        if (keepTwist) { twistFocused = true }
        playSoundEffect(SoundEffectConstants.NAVIGATION_RIGHT)
    }

    // ---- accessibility -----------------------------------------------------------------------
    //
    // The hub is one custom-drawn view, so screen readers saw nothing in it. This exposes it
    // through the framework AccessibilityNodeProvider (no AndroidX here) as virtual views:
    // every tile on the current page plus every channel in the twist. TalkBack's two-finger
    // swipe (scroll forward/backward on the hub) pages the horizon, touch exploration finds
    // tiles under the finger, double-tap launches, and double-tap-and-hold opens the options.
    // Gamepad / d-pad focus moves are announced too, and accessibility focus moves the
    // visual focus, so a sighted helper sees where the reader is.

    private val a11y = context.getSystemService(AccessibilityManager::class.java)
    /** Virtual view holding accessibility focus, or [NO_VIRTUAL]. */
    private var a11yFocus = NO_VIRTUAL
    /** Virtual view under the exploring finger, or [NO_VIRTUAL]. */
    private var a11yHover = NO_VIRTUAL

    private fun isTileId(id: Int) = id in TILE_BASE until TWIST_BASE
    private fun isTwistId(id: Int) = id in TWIST_BASE until GEAR_ID

    /** Spoken name for [tile]: what it is, plus the state the screen shows beside it. */
    private fun describe(tile: Tile): String = when {
        tile.target is Target.Game -> "${tile.label}, Xbox 360 game, ${tile.sub}"
        tile.glyph == Glyph.FOLDER -> "${tile.label}, folder, ${tile.sub}"
        tile.kind == Kind.HERO && tile.sub.isNotEmpty() -> "${tile.label}, ${tile.sub}"
        tile.target is Target.Channel -> "${tile.label}, channel"
        else -> tile.label
    }

    private fun tileAt(id: Int): Tile? {
        if (!isTileId(id)) return null
        val p = hub.pages.getOrNull(focusPage) ?: return null
        val slot = id - TILE_BASE
        return if (p.focusable(slot)) p.tiles[slot] else null
    }

    private fun describeId(id: Int): String? = when {
        id == GEAR_ID -> "Settings"
        id == PROFILE_ID -> if (profileName.isEmpty()) "Profile" else "Profile, $profileName"
        isTileId(id) -> tileAt(id)?.let(::describe)
        isTwistId(id) -> hub.channels.getOrNull(id - TWIST_BASE)?.title
        else -> null
    }

    /**
     * Where virtual view [id] sits on screen, at rest: the page's settled position, not
     * the mid-scroll one, so the bounds are right while the horizon is still animating.
     */
    private fun a11yBounds(id: Int, out: Rect): Boolean {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val (dx, dy, w, h) = when {
            isTileId(id) -> {
                val p = hub.pages.getOrNull(focusPage) ?: return false
                val slot = id - TILE_BASE
                val b = p.template.slots.getOrNull(slot) ?: return false
                val dy = L.panelsY + b.y - if (isGridSlot(focusPage, slot)) vOff(focusPage) else 0f
                floatArrayOf((L.w - p.template.w) / 2f + b.x, dy, b.w, b.h)
            }
            id == GEAR_ID -> {
                val gb = gearBox()
                floatArrayOf(gb.x, gb.y, gb.w, gb.h)
            }
            id == PROFILE_ID -> {
                val hb = profileChip()?.hit ?: return false
                floatArrayOf(hb.x, hb.y, hb.w, hb.h)
            }
            isTwistId(id) -> {
                val i = id - TWIST_BASE
                if (i !in hub.channels.indices) return false
                val widths = hub.channels.map { twistText.measureText(it.title) + Metrics.TWIST_GAP }
                val cur = hub.channelOf(focusPage)
                var x = L.twistX
                for (j in 0 until i) x += widths[j]
                for (j in 0 until cur) x -= widths[j]
                floatArrayOf(x, L.twistY, widths[i] - Metrics.TWIST_GAP, Metrics.TWIST_H)
            }
            else -> return false
        }
        val l = loc[0] + ox + dx * s
        val t = loc[1] + oy + dy * s
        out.set(l.roundToInt(), t.roundToInt(), (l + w * s).roundToInt(), (t + h * s).roundToInt())
        // Channels scrolled off the strip are left out rather than given off-screen bounds.
        return out.right > loc[0] && out.left < loc[0] + width
    }

    /** Virtual views, in reading order: the twist, then the current page's tiles. */
    private fun a11yChildren(): List<Int> {
        val ids = ArrayList<Int>()
        val r = Rect()
        for (i in hub.channels.indices) if (a11yBounds(TWIST_BASE + i, r)) ids += TWIST_BASE + i
        if (profileChip() != null) ids += PROFILE_ID
        ids += GEAR_ID
        val p = hub.pages.getOrNull(focusPage) ?: return ids
        val order = p.template.slots.indices.filter { p.focusable(it) }
            .sortedWith(compareBy({ p.template.slots[it].y }, { p.template.slots[it].x }))
        for (slot in order) ids += TILE_BASE + slot
        return ids
    }

    private val provider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
            if (id == HOST_VIEW_ID) {
                val info = AccessibilityNodeInfo(this@HomeView)
                onInitializeAccessibilityNodeInfo(info)
                for (child in a11yChildren()) info.addChild(this@HomeView, child)
                val ch = hub.channels.getOrNull(hub.channelOf(focusPage))
                if (ch != null) info.stateDescription = ch.title
                info.isScrollable = hub.pages.size > 1 || maxVOff(focusPage) > 0f
                if (vOff(focusPage) < maxVOff(focusPage) || focusPage < hub.pages.size - 1)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                if (vOff(focusPage) > 0f || focusPage > 0)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                return info
            }
            val text = describeId(id) ?: return null
            val r = Rect()
            if (!a11yBounds(id, r)) return null
            val n = AccessibilityNodeInfo(this@HomeView, id)
            n.setParent(this@HomeView)
            n.packageName = context.packageName
            n.className = Button::class.java.name
            n.contentDescription = text
            n.setBoundsInScreen(r)
            n.isVisibleToUser = true
            n.isEnabled = true
            n.isFocusable = true
            n.isClickable = true
            n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            if (id == PROFILE_ID) {
                n.stateDescription = "change picture or name"
            } else if (id == GEAR_ID) {
                n.stateDescription = "shortcut"
                if (hub.channelOf(focusPage) == settingsIndex()) n.isEnabled = false
            } else if (isTwistId(id)) {
                val current = id - TWIST_BASE == hub.channelOf(focusPage)
                n.isSelected = current
                n.isHeading = current
                n.stateDescription = if (current) "current channel" else "channel"
            } else {
                n.isFocused = !twistFocused && id - TILE_BASE == focusSlot
                val tile = tileAt(id)
                if (tile != null && hasOptions(tile)) {
                    n.isLongClickable = true
                    n.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "options"))
                }
            }
            n.isAccessibilityFocused = a11yFocus == id
            n.addAction(
                if (a11yFocus == id) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS,
            )
            return n
        }

        override fun performAction(id: Int, action: Int, args: Bundle?): Boolean {
            if (id == HOST_VIEW_ID) {
                return when (action) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> a11yScroll(1)
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> a11yScroll(-1)
                    else -> performAccessibilityAction(action, args)
                }
            }
            if (describeId(id) == null) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (a11yFocus == id) return false
                    val old = a11yFocus
                    a11yFocus = id
                    if (old != NO_VIRTUAL) a11yEvent(old, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    // The visual focus follows the reader, so the focused tile lights up.
                    if (isTileId(id)) {
                        focusSlot = id - TILE_BASE; twistFocused = false
                        revealSlot(focusPage, focusSlot, animate = true)
                        invalidate()
                    }
                    a11yEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (a11yFocus != id) return false
                    a11yFocus = NO_VIRTUAL
                    a11yEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    true
                }
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    if (away) return false
                    when {
                        id == GEAR_ID -> settingsIndex().takeIf { it >= 0 }?.let { goToChannel(it, false) }
                        id == PROFILE_ID -> onProfile()
                        isTwistId(id) -> goToChannel(id - TWIST_BASE, false)
                        else -> { focusSlot = id - TILE_BASE; twistFocused = false; activate(focusPage, focusSlot) }
                    }
                    a11yEvent(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
                    true
                }
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                    val tile = tileAt(id) ?: return false
                    if (away || !hasOptions(tile)) return false
                    focusSlot = id - TILE_BASE; twistFocused = false; invalidate()
                    onLongPress(tile)
                    a11yEvent(id, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
                    true
                }
                else -> false
            }
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfo? = when (focus) {
            AccessibilityNodeInfo.FOCUS_ACCESSIBILITY ->
                if (a11yFocus != NO_VIRTUAL) createAccessibilityNodeInfo(a11yFocus) else null
            AccessibilityNodeInfo.FOCUS_INPUT ->
                if (!twistFocused) createAccessibilityNodeInfo(TILE_BASE + focusSlot) else null
            else -> null
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = provider

    /** Long-press options exist for app tiles and folder tiles (see MainActivity.options). */
    private fun hasOptions(tile: Tile) =
        tile.target is Target.App || (tile.target is Target.Channel && tile.folderId != null)

    /**
     * Reader scroll (0.9.0): scrolls the current channel's grid by most of a
     * viewport; at the grid's end it falls through to the next channel, as the
     * old horizon page scroll did.
     */
    private fun a11yScroll(dir: Int): Boolean {
        if (away) return false
        val max = maxVOff(focusPage)
        val cur = vOff(focusPage)
        val target = Scroll.clampOffset(cur + dir * viewportH(focusPage) * 0.9f, max)
        if (target != cur) {
            vFlingPage = -1
            vAnimFrom = cur; vAnimTo = target; vAnimPage = focusPage
            vAnimStart = SystemClock.uptimeMillis()
            invalidate()
            return true
        }
        return a11yPage(dir)
    }

    /** Reader channel move: one channel along the pivot, reader landing on its default tile. */
    private fun a11yPage(step: Int): Boolean {
        val target = focusPage + step
        if (away || target !in hub.pages.indices) return false
        goToPage(target, defaultSlot(target))
        provider.performAction(TILE_BASE + focusSlot, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        return true
    }

    /** Sends [type] for virtual view [id] (or the hub itself for [AccessibilityNodeProvider.HOST_VIEW_ID]). */
    private fun a11yEvent(id: Int, type: Int, changeTypes: Int = 0) {
        if (!a11y.isEnabled) return
        val parent = parent ?: return
        val ev = AccessibilityEvent(type)
        ev.packageName = context.packageName
        if (id == AccessibilityNodeProvider.HOST_VIEW_ID) {
            ev.setSource(this)
            ev.className = javaClass.name
        } else {
            ev.setSource(this, id)
            ev.className = Button::class.java.name
            ev.contentDescription = describeId(id)
        }
        if (changeTypes != 0) ev.contentChangeTypes = changeTypes
        parent.requestSendAccessibilityEvent(this, ev)
    }

    /** The visible tiles changed (new page, new hub): readers must re-read the tree. */
    private fun a11yTreeChanged() {
        if (!a11y.isEnabled) return
        // Focus on a tile of the old page would point at a different tile now.
        if (isTileId(a11yFocus)) a11yFocus = NO_VIRTUAL
        a11yEvent(AccessibilityNodeProvider.HOST_VIEW_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
    }

    /** D-pad / gamepad focus moved: announce the newly focused tile. */
    private fun a11yFocusMoved() {
        if (!a11y.isEnabled || twistFocused) return
        a11yEvent(TILE_BASE + focusSlot, AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    /** Virtual view under a touch-exploration point, or [NO_VIRTUAL]. */
    private fun virtualAt(x: Float, y: Float): Int {
        val r = Rect()
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val sx = (x + loc[0]).roundToInt()
        val sy = (y + loc[1]).roundToInt()
        for (id in a11yChildren()) if (a11yBounds(id, r) && r.contains(sx, sy)) return id
        return NO_VIRTUAL
    }

    override fun dispatchHoverEvent(e: MotionEvent): Boolean {
        // With touch exploration on, a finger arrives as hover events: map them to tiles.
        if (!a11y.isEnabled || !a11y.isTouchExplorationEnabled) return super.dispatchHoverEvent(e)
        val id = when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> virtualAt(e.x, e.y)
            else -> NO_VIRTUAL
        }
        if (id != a11yHover) {
            val old = a11yHover
            a11yHover = id
            if (id != NO_VIRTUAL) a11yEvent(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            if (old != NO_VIRTUAL) a11yEvent(old, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        }
        return true
    }

    // ---- input -------------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (away) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> move(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> move(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(0, 1)
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> goToChannel(hub.channelOf(focusPage) - 1, twistFocused)
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> goToChannel(hub.channelOf(focusPage) + 1, twistFocused)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (twistFocused) { twistFocused = false; invalidate() } else activate(focusPage, focusSlot)
            }
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_MENU -> {
                if (!twistFocused) hub.pages[focusPage].tiles.getOrNull(focusSlot)?.let(onLongPress)
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> back()
            KeyEvent.KEYCODE_SEARCH -> onSearch("")
            else -> {
                // Typing a letter or digit on a hardware keyboard opens search with it.
                val ch = event.unicodeChar
                if (ch != 0 && ch.toChar().isLetterOrDigit()) {
                    onSearch(ch.toChar().toString())
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
        }
        return true
    }

    /** Focusable slots of the current page in reading order (rows, then columns). */
    private fun readingOrder(): List<Int> {
        val p = hub.pages[focusPage]
        return p.template.slots.indices.filter { p.focusable(it) }
            .sortedWith(compareBy({ p.template.slots[it].y }, { p.template.slots[it].x }))
    }

    private fun move(dx: Int, dy: Int) {
        if (twistFocused) {
            when {
                dx != 0 -> goToChannel(hub.channelOf(focusPage) + dx, true)
                dy > 0 -> { twistFocused = false; invalidate() }
            }
            return
        }
        val p = hub.pages[focusPage]
        val boxes = p.template.slots
        val next = if (p.focusable(focusSlot)) Nav.move(boxes, p::focusable, focusSlot, dx, dy) else -1
        when {
            next >= 0 -> {
                focusSlot = next
                revealSlot(focusPage, next, animate = true)
                playSoundEffect(SoundEffectConstants.CLICK)
                invalidate()
                a11yFocusMoved()
            }
            dy < 0 -> { twistFocused = true; twistFocusStart = SystemClock.uptimeMillis(); invalidate() }
            dx != 0 -> {
                // Row edge (0.9.0): left/right wrap through the reading order -- the
                // previous row's last tile / the next row's first tile -- instead of
                // paging or crossing channels. Channel changes via keys are
                // PAGE_UP/PAGE_DOWN and the bumpers now.
                val order = readingOrder()
                val wrapped = order.getOrNull(order.indexOf(focusSlot) + dx)
                if (wrapped != null) {
                    focusSlot = wrapped
                    revealSlot(focusPage, wrapped, animate = true)
                    playSoundEffect(SoundEffectConstants.CLICK)
                    invalidate()
                    a11yFocusMoved()
                }
            }
        }
    }

    private fun activate(page: Int, slot: Int) {
        val p = hub.pages[page]
        if (!p.focusable(slot)) return
        val tile = p.tiles[slot]
        pressKey = key(page, slot)
        pressStart = SystemClock.uptimeMillis()
        playSoundEffect(SoundEffectConstants.CLICK)
        invalidate()
        val target = tile.target
        if (target is Target.Action && !target.external) {
            // Launcher-internal (tile size): no leave transition, the hub stays put.
            postDelayed({ onLaunch(tile, Rect()) }, 150)
            return
        }
        when (val t = target) {
            is Target.Channel -> postDelayed({ goToChannel(t.index, false) }, 150)
            else -> {
                val b = p.template.slots[slot]
                val x = ox + (pageX(page) + b.x) * s
                val y = oy + (L.panelsY + b.y - if (isGridSlot(page, slot)) vOff(page) else 0f) * s
                val r = Rect(x.roundToInt(), y.roundToInt(), (x + b.w * s).roundToInt(), (y + b.h * s).roundToInt())
                // Let the press dip register before the app covers the hub.
                // Press dip, then the hub's TransFrom zoom; the app starts partway through it.
                val dip = (Metrics.PRESS_DOWN_FRAMES * Metrics.FRAME_MS).toLong()
                postDelayed({ play(Trans.FROM) }, dip)
                postDelayed({ onLaunch(tile, r) }, dip + LAUNCH_INTO_FROM_MS)
            }
        }
    }

    private companion object {
        /** The app opens 12 frames into the 28-frame TransFrom, while the hub is still zooming. */
        const val LAUNCH_INTO_FROM_MS = 200L
        /** Badge canvas edge, as a fraction of the icon edge (hero) or the tile's short side (grid). */
        const val BADGE_SIDE = 0.92f
        /** Gap between a filled tile's corner and its badge canvas. */
        const val BADGE_INSET = 3f
        /** Scrim over the hero ambience: ~60% black, so hero text stays legible. */
        const val AMBIENCE_SCRIM = 0x99000000.toInt()
        /** Edge of the offscreen a glyph is rendered at for its ambience source. */
        const val GLYPH_PX = 144
        /** Design px past the screen edge a page may reach and still be drawn (focus growth, shadow). */
        const val CULL_SLACK = 48f
        /** Accessibility virtual-view ids: tiles are TILE_BASE + slot, channels TWIST_BASE + index. */
        const val TILE_BASE = 0
        const val TWIST_BASE = 10_000
        /** The settings gear's accessibility id (kept clear of the twist range). */
        const val GEAR_ID = 20_000
        /** The profile chip's accessibility id. */
        const val PROFILE_ID = 20_001
        const val NO_VIRTUAL = Int.MIN_VALUE
        /** Reveal-scroll animation length (focus moves, reader scrolls), in ms. */
        const val V_REVEAL_MS = 220f
        /** Fling release threshold, in screen px/s, below which the grid just stops. */
        const val V_FLING_MIN = 120f
        /** Fling speed cap, in design px/s. */
        const val V_FLING_MAX = 5000f
        /** Fling velocity halving time, in ms. */
        const val V_FLING_HALF_MS = 150f
        /** Fling stop threshold, in design px/s. */
        const val V_FLING_STOP = 15f
        /** Alpha of the settings gear while the settings channel is already showing. */
        const val GEAR_DIM_ALPHA = 90
        /** Hover chrome fade, in and out. */
        const val CHROME_FADE_MS = 150f
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    // Kinect hover chrome: fades in while a pointer hovers the hub (see drawHoverChrome).
    private var hovering = false
    private var hoverPos = Pair(0f, 0f)
    private var chromeAlpha = 0f
    private val chromeBoxes = ArrayList<RectF>()
    private var chromeBoxList: List<Box> = emptyList()
    /** Layout the chrome boxes were built for; rebuilt when it changes. */
    private var chromeLayout: Layout? = null

    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0f
    private var downVOff = 0f
    private var dragging = false
    /** Gesture axis lock (0.9.0): 0 undecided, 1 horizontal (channel pivot), 2 vertical (grid scroll). */
    private var dragAxis = 0
    private var longFired = false
    private var velocity: VelocityTracker? = null
    private val longPress = Runnable {
        longFired = true
        hitTile(downX, downY)?.let { (page, slot) ->
            focusPage = page; focusSlot = slot; twistFocused = false; invalidate()
            performHapticFeedback(HAPTIC_FEEDBACK_ENABLED)
            onLongPress(hub.pages[page].tiles[slot])
        }
    }

    // Pinch in for smaller tiles, out for bigger; the hub scales live, then snaps to the new size.
    private var pinching = false
    private var pinchScale = 1f
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            pinchScale = 1f
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            pinchScale = (pinchScale * d.scaleFactor).coerceIn(0.6f, 1.5f)
            invalidate()
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            val next = when {
                pinchScale < 0.85f -> tileSize.smaller()
                pinchScale > 1.18f -> tileSize.larger()
                else -> tileSize
            }
            pinchScale = 1f
            if (next != tileSize) { tileSize = next; onTileSize(next) }
            invalidate()
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (away) return true
        pinch.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !pinching) {
            pinching = true
            dragAxis = 0
            removeCallbacks(longPress)
            if (dragging) { dragging = false; goToPage(focusPage, focusSlot) }
        }
        if (pinching) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                pinching = false
                velocity?.recycle(); velocity = null
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downScroll = scroll
                downVOff = vOff(focusPage)
                dragging = false; dragAxis = 0; longFired = false
                // A new touch takes the grid back from any fling or reveal animation.
                vFlingPage = -1; vAnimStart = -1
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                postDelayed(longPress, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (dragAxis == 0 && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                    // Dominant axis wins: a vertical drag scrolls the grid, a
                    // horizontal one pivots channels, as the gestures always did.
                    dragAxis = if (abs(e.y - downY) > abs(e.x - downX)) 2 else 1
                    removeCallbacks(longPress)
                    if (dragAxis == 1) { dragging = true; scrollStart = -1 }
                }
                if (dragAxis == 1) {
                    val maxScroll = (hub.pages.size - 1) * L.w
                    scroll = (downScroll - (e.x - downX) / s).coerceIn(-120f, maxScroll + 120f)
                    invalidate()
                } else if (dragAxis == 2) {
                    setVOff(focusPage, downVOff - (e.y - downY) / s)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val vt = velocity
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val vy = vt?.yVelocity ?: 0f
                velocity?.recycle(); velocity = null
                when {
                    dragAxis == 1 -> {
                        val base = (downScroll / L.w).roundToInt()
                        val moved = (scroll - downScroll) / L.w
                        val step = when {
                            vx < -800f || moved > 0.3f -> 1
                            vx > 800f || moved < -0.3f -> -1
                            else -> 0
                        }
                        val target = (base + step).coerceIn(0, hub.pages.size - 1)
                        goToPage(target, if (target == focusPage) focusSlot else defaultSlot(target))
                    }
                    dragAxis == 2 -> {
                        // Fling: the grid keeps the finger's release velocity,
                        // converted to design px/s and capped; stepVScroll decays
                        // it and stops it dead at the edges.
                        if (abs(vy) > V_FLING_MIN) {
                            vFlingVel = (-vy / s).coerceIn(-V_FLING_MAX, V_FLING_MAX)
                            vFlingPage = focusPage
                            lastVStep = SystemClock.uptimeMillis()
                            invalidate()
                        }
                    }
                    !longFired -> tap(e.x, e.y)
                }
                dragging = false
                dragAxis = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                velocity?.recycle(); velocity = null
                if (dragging) goToPage(focusPage, focusSlot)
                dragging = false
                dragAxis = 0
            }
        }
        return true
    }

    private fun toDesign(x: Float, y: Float) = Pair((x - ox) / s, (y - oy) / s)

    private fun hitTile(x: Float, y: Float): Pair<Int, Int>? {
        val (dx, dy) = toDesign(x, y)
        val page = (scroll / L.w).roundToInt().coerceIn(0, hub.pages.size - 1)
        val p = hub.pages[page]
        val px = dx - pageX(page)
        val py = dy - L.panelsY
        // The hero is hit where it sits; grid tiles only inside the scroll region,
        // tested in content space (panel position plus the scroll offset).
        val hero = p.template.hero
        if (hero >= 0 && p.focusable(hero) && p.template.slots[hero].contains(px, py)) return page to hero
        if (py < p.template.gridTop || py >= L.panelsH) return null
        val cy = py + vOff(page)
        for (i in p.template.slots.indices) {
            if (i == hero || !p.focusable(i)) continue
            if (p.template.slots[i].contains(px, cy)) return page to i
        }
        return null
    }

    private fun tap(x: Float, y: Float) {
        val (dx, dy) = toDesign(x, y)
        if (chromeAlpha > 0.5f) { // hover chrome first: it floats above the hub
            for ((i, r) in chromeBoxes.withIndex()) {
                if (r.contains(dx, dy)) {
                    when (i) {
                        0 -> back()
                        1 -> goToChannel(0, false)
                        else -> onSearch("")
                    }
                    return
                }
            }
        }
        if (profileChip()?.hit?.contains(dx, dy) == true) { onProfile(); return }
        if (gearBox().contains(dx, dy)) {
            // The settings shortcut: a normal channel jump (slide + cascade), and
            // inert on the settings channel itself, where it is drawn dimmed.
            val si = settingsIndex()
            if (si >= 0 && hub.channelOf(focusPage) != si) goToChannel(si, false)
            return
        }
        for ((i, r) in twistBoxes.withIndex()) {
            if (r.contains(dx, dy)) { goToChannel(i, false); return }
        }
        val hit = hitTile(x, y) ?: return
        focusPage = hit.first; focusSlot = hit.second; twistFocused = false
        activate(hit.first, hit.second)
    }
}
