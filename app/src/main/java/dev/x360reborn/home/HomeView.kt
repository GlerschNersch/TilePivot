package dev.x360reborn.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
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
    private val onLaunch: (Tile, Rect) -> Unit,
    private val onLongPress: (Tile) -> Unit,
    private val iconFor: (String) -> Bitmap?,
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
        fit()
        invalidate()
    }

    private fun hubClock(): String =
        SimpleDateFormat(if (clock24h) "HH:mm" else "h:mm a", Locale.US).format(Date())

    /** Text paints cache their colours, so a theme change has to push the new ones in. */
    private fun applyColors() {
        twistText.color = Metrics.TEXT
        label.color = Metrics.TEXT
        heroPrimary.color = Metrics.TEXT
        heroSecondary.color = Metrics.TEXT_DIM
        clockText.color = Metrics.TEXT
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
        // Slot indices mean different cells in another layout, so a relayout refocuses the default.
        val relaid = next.layout != hub.layout
        hub = next
        val ch = next.channels.indexOfFirst { it.title == title }.takeIf { it >= 0 } ?: 0
        val c = next.channels[ch]
        focusPage = c.firstPage + min(inPage, c.pageCount - 1)
        if (relaid || !next.pages[focusPage].focusable(focusSlot)) focusSlot = defaultSlot(focusPage)
        scroll = focusPage * L.w
        scrollStart = -1
        focusAnim.clear()
        fit()
        invalidate()
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

    // ---- layout ------------------------------------------------------------------------------

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
        applyColors()
    }

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

    private fun pageY(page: Int): Float {
        val t = hub.pages[page].template
        return L.panelsY + (L.panelsH - t.h) / 2f
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
        animating = stepFocus(dt) || animating

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
        for (p in first..last) drawPage(canvas, p, now)
        if (pinchScale != 1f) canvas.restore()
        drawTwist(canvas, cp, now)
        if (transforming) canvas.restore()
        canvas.drawText(hubClock(), L.clockX, L.clockY, clockText)
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

    private fun drawAura(canvas: Canvas, cp: Float) {
        // BackgroundImagePanScale 1.3: the aura is 30% wider than the screen and pans across channels.
        val n = max(1, hub.channels.size - 1)
        val pan = L.w * (Metrics.PAN_SCALE - 1f)
        val cx = ox + (L.w / 2f + pan / 2f - pan * cp / n) * s
        val cy = oy + L.h * 0.36f * s
        val r = max(L.w, L.h) * 0.56f * s
        glowPaint.shader = RadialGradient(cx, cy, r, intArrayOf(0x40FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, glowPaint)
    }

    private fun key(page: Int, slot: Int) = page * 64 + slot

    private fun drawPage(canvas: Canvas, page: Int, now: Long) {
        val p = hub.pages[page]
        val x0 = pageX(page)
        val y0 = pageY(page)
        // Settings tiles are controls whose labels carry state ("theme: Midnight"), so
        // the settings channel keeps the classic icon-over-label rendering (drawSmall).
        val labeled = hub.channels[p.channel].title == "settings"
        // Draw the focused tile last so its 1.08x growth overlaps its neighbours.
        var focusedLast = -1
        for (i in p.tiles.indices) {
            if (!p.focusable(i)) continue
            if ((focusAnim[key(page, i)] ?: 0f) > 0f) { focusedLast = i; continue }
            drawTile(canvas, p.tiles[i], p.template.slots[i], x0, y0, key(page, i), now, labeled)
        }
        if (focusedLast >= 0) drawTile(canvas, p.tiles[focusedLast], p.template.slots[focusedLast], x0, y0, key(page, focusedLast), now, labeled)
    }

    private fun drawTile(canvas: Canvas, tile: Tile, b: Box, x0: Float, y0: Float, k: Int, now: Long, labeled: Boolean) {
        val f = focusAnim[k] ?: 0f
        val pressMs = if (k == pressKey) (now - pressStart).toFloat() else -1f
        val sc = Motion.scale(f, pressMs)
        val x = x0 + b.x
        val y = y0 + b.y
        canvas.save()
        canvas.translate(x + b.w / 2f, y + b.h / 2f)
        canvas.scale(sc, sc)
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
            // focus_state_white.png: a white sheen from the top-left corner.
            fill.shader = LinearGradient(0f, 0f, b.w * 0.55f, b.h * 0.55f,
                Color.argb((Metrics.CORNER_ALPHA * f * 255).roundToInt(), 255, 255, 255), 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(rect, fill)
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
        fill.shader = LinearGradient(0f, 0f, 0f, b.h, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
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

    private fun drawHero(canvas: Canvas, tile: Tile, b: Box) {
        fill.color = Metrics.SLOT_DARK
        canvas.drawRect(rect, fill)
        val icon = Metrics.heroIconSize(b.w, b.h, tile.sub.isNotEmpty())
        val areaH = Metrics.heroIconAreaH(b.h, tile.sub.isNotEmpty())
        val ix = (b.w - icon) / 2f
        val iy = (areaH - icon) / 2f
        drawIcon(canvas, tile, ix, iy, icon)
        // The hero's badge sits where the baked-in one used to: the icon's bottom-right corner.
        tile.iconKey?.let { drawBadge(canvas, it, ix + icon, iy + icon, icon * BADGE_SIDE, 0f) }
        // mobyslot: gradient from 170/320 of the height, primary 17 pt, secondary 16 pt.
        fill.shader = LinearGradient(0f, b.h * 170f / 320f, 0f, b.h, 0x00000000, 0xC0000000.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, b.h * 170f / 320f, b.w, b.h, fill)
        fill.shader = null
        val primary = TextUtils.ellipsize(tile.label, heroPrimary, b.w - 40f, TextUtils.TruncateAt.END)
        canvas.drawText(primary, 0, primary.length, 20f, b.h - (if (tile.sub.isEmpty()) 18f else 40f), heroPrimary)
        if (tile.sub.isNotEmpty()) canvas.drawText(tile.sub, 20f, b.h - 16f, heroSecondary)
    }

    private fun drawIcon(canvas: Canvas, tile: Tile, x: Float, y: Float, size: Float) {
        val bmp = tile.iconKey?.let(iconFor)
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
            Glyph.SETTINGS -> {
                val cx = x + size / 2f
                val cy = y + size / 2f
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
            Glyph.NONE -> {}
        }
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

    private fun animateScrollTo(page: Int) {
        scrollFrom = scroll
        scrollTo = page * L.w
        scrollStart = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun goToPage(page: Int, slot: Int) {
        focusPage = page
        focusSlot = slot
        twistFocused = false
        animateScrollTo(page)
    }

    private fun goToChannel(ch: Int, keepTwist: Boolean) {
        val c = hub.channels[ch.coerceIn(0, hub.channels.size - 1)]
        goToPage(c.firstPage, defaultSlot(c.firstPage))
        if (keepTwist) { twistFocused = true }
        playSoundEffect(SoundEffectConstants.NAVIGATION_RIGHT)
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
            next >= 0 -> { focusSlot = next; playSoundEffect(SoundEffectConstants.CLICK); invalidate() }
            dy < 0 -> { twistFocused = true; twistFocusStart = SystemClock.uptimeMillis(); invalidate() }
            dx != 0 -> {
                val target = focusPage + dx
                if (target !in hub.pages.indices) return
                val y = if (p.focusable(focusSlot)) boxes[focusSlot].cy else 0f
                val tp = hub.pages[target]
                val slot = Nav.enter(tp.template.slots, tp::focusable, dx > 0, y)
                goToPage(target, if (slot >= 0) slot else 0)
                playSoundEffect(if (dx > 0) SoundEffectConstants.NAVIGATION_RIGHT else SoundEffectConstants.NAVIGATION_LEFT)
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
                val y = oy + (pageY(page) + b.y) * s
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
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0f
    private var dragging = false
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
                dragging = false; longFired = false
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                postDelayed(longPress, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (!dragging && abs(e.x - downX) > slop && abs(e.x - downX) > abs(e.y - downY)) {
                    dragging = true
                    removeCallbacks(longPress)
                    scrollStart = -1
                }
                if (abs(e.y - downY) > slop) removeCallbacks(longPress)
                if (dragging) {
                    val maxScroll = (hub.pages.size - 1) * L.w
                    scroll = (downScroll - (e.x - downX) / s).coerceIn(-120f, maxScroll + 120f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val vt = velocity
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                velocity?.recycle(); velocity = null
                if (dragging) {
                    val base = (downScroll / L.w).roundToInt()
                    val moved = (scroll - downScroll) / L.w
                    val step = when {
                        vx < -800f || moved > 0.3f -> 1
                        vx > 800f || moved < -0.3f -> -1
                        else -> 0
                    }
                    val target = (base + step).coerceIn(0, hub.pages.size - 1)
                    goToPage(target, if (target == focusPage) focusSlot else defaultSlot(target))
                } else if (!longFired) {
                    tap(e.x, e.y)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                velocity?.recycle(); velocity = null
                if (dragging) goToPage(focusPage, focusSlot)
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
        val py = dy - pageY(page)
        for (i in p.template.slots.indices) if (p.focusable(i) && p.template.slots[i].contains(px, py)) return page to i
        return null
    }

    private fun tap(x: Float, y: Float) {
        val (dx, dy) = toDesign(x, y)
        for ((i, r) in twistBoxes.withIndex()) {
            if (r.contains(dx, dy)) { goToChannel(i, false); return }
        }
        val hit = hitTile(x, y) ?: return
        focusPage = hit.first; focusSlot = hit.second; twistFocused = false
        activate(hit.first, hit.second)
    }
}
