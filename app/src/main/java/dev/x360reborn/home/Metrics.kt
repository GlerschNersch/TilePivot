package dev.x360reborn.home

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Axis-aligned box in the dash's 1280x720 design space. */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
    fun contains(px: Float, py: Float) = px >= x && px < right && py >= y && py < bottom
}

/** Numbers measured from the 17559 dash scenes; see docs/RE_NOTES.md for sources. */
object Metrics {
    const val DESIGN_W = 1280f
    const val DESIGN_H = 720f

    // HubSceneStandard: _PanelsContainer and _Twist.
    const val PANELS_Y = 181f
    const val PANELS_H = 400f
    const val TWIST_X = 153f
    const val TWIST_Y = 97f
    const val TWIST_H = 84f
    const val TWIST_GAP = 36f // 188 pitch minus the 152 item width
    const val TWIST_PT = 24f
    const val TWIST_DIM = 0.5f

    const val GUTTER = 4f
    const val SMALL_W = 174f
    const val SMALL_H = 130f

    // Template1HD: the online Home layout. Index 1 is the hero slot, 6 and 7 the wide ones.
    val HOME = Template(
        822f, 398f, listOf(
            Box(0f, 0f, 174f, 130f),
            Box(178f, 0f, 466f, 264f),
            Box(648f, 0f, 174f, 130f),
            Box(0f, 134f, 174f, 130f),
            Box(648f, 134f, 174f, 130f),
            Box(0f, 268f, 174f, 130f),
            Box(178f, 268f, 231f, 130f),
            Box(412f, 268f, 231f, 130f),
            Box(648f, 268f, 174f, 130f),
        ),
        hero = 1, wide = listOf(6, 7),
    )

    /**
     * Template1HD followed by [extraCols] more columns of small tiles, for zoomed-out landscape.
     * Slot indices 0-8 keep their Template1HD meaning; extras are appended in reading order.
     */
    fun landscapeHome(extraCols: Int): Template {
        if (extraCols <= 0) return HOME
        val extra = ArrayList<Box>()
        for (r in 0 until 3) for (c in 0 until extraCols) {
            extra += Box(HOME.w + GUTTER + c * (SMALL_W + GUTTER), r * (SMALL_H + GUTTER), SMALL_W, SMALL_H)
        }
        return HOME.copy(w = HOME.w + extraCols * (SMALL_W + GUTTER), slots = HOME.slots + extra)
    }

    fun gridW(cols: Int) = cols * SMALL_W + (cols - 1) * GUTTER
    fun gridH(rows: Int) = rows * SMALL_H + (rows - 1) * GUTTER

    /**
     * Portrait reflow of Template1HD (the dash is landscape-only): a full-width 16:9 hero over
     * [rows] x [cols] small tiles in reading order. The last two cells hold the wide shortcuts.
     */
    fun portraitHome(cols: Int, rows: Int): Template {
        val w = gridW(cols)
        val heroH = heroHeight(cols)
        val slots = arrayListOf(Box(0f, 0f, w, heroH))
        for (r in 0 until rows) for (c in 0 until cols) {
            slots += Box(c * (SMALL_W + GUTTER), heroH + GUTTER + r * (SMALL_H + GUTTER), SMALL_W, SMALL_H)
        }
        val n = slots.size
        return Template(w, heroH + GUTTER + gridH(rows), slots, hero = 0,
            wide = if (n >= 3) listOf(n - 2, n - 1) else emptyList(), compact = true)
    }

    /** 16:9 across the grid, but never taller than two tile rows so small sizes keep their tiles. */
    fun heroHeight(cols: Int) = min((gridW(cols) * 9f / 16f).roundToInt().toFloat(), gridH(2))

    /**
     * Uniform pages of small tiles (the dash's long lists use the same 174x130 slot). The landscape
     * horizon fills column by column; portrait reads row by row like a phone grid.
     */
    fun grid(cols: Int, rows: Int, rowMajor: Boolean = false): Template {
        val slots = ArrayList<Box>(cols * rows)
        val box = { c: Int, r: Int -> Box(c * (SMALL_W + GUTTER), r * (SMALL_H + GUTTER), SMALL_W, SMALL_H) }
        if (rowMajor) for (r in 0 until rows) for (c in 0 until cols) slots += box(c, r)
        else for (c in 0 until cols) for (r in 0 until rows) slots += box(c, r)
        return Template(gridW(cols), gridH(rows), slots)
    }

    const val GRID_COLS = 5
    const val GRID_ROWS = 3

    // skin.xui colours, read from the active theme. The Xbox Green theme holds the
    // original measured constants, so the default look is unchanged.
    val TILE_GREEN: Int get() = Themes.active.tile
    val TEXT: Int get() = Themes.active.text
    val TEXT_DIM: Int get() = Themes.active.textDim
    val SLOT_DARK: Int get() = Themes.active.slotDark

    // aura_background.jpg is a plain vertical ramp; aura_floor.png is #BFBFBF fading in.
    val AURA_STOPS: IntArray get() = Themes.active.auraStops()
    val FLOOR: Int get() = Themes.active.floor
    const val PAN_SCALE = 1.3f

    // HubSlotContainer Focus timeline (frames 19 -> 35 at 60 fps).
    const val FRAME_MS = 1000f / 60f
    const val FOCUS_FRAMES = 16f
    const val FOCUS_SCALE = 1.08f
    const val FLASH_PEAK = 0.08f
    const val FLASH_PEAK_AT = 7f / 16f
    const val CORNER_ALPHA = 0.44f
    const val SHADOW_ALPHA = 0.8f
    const val PRESS_DOWN_FRAMES = 8f
    const val PRESS_UP_FRAMES = 12f

    /** XUI point sizes are 72-dpi points against a 96-dpi design grid. */
    fun pt(points: Float) = points * 4f / 3f

    // Tile icons. App bitmaps and the code-drawn glyphs share one size per tile, so a grid of
    // mixed asset/glyph tiles sits evenly. Up to 0.4.0 every grid icon was a fixed 56 design
    // px -- about a third of the 174-wide tile -- which read as a small icon on a big button.
    const val ICON_FRACTION = 0.58f
    /** Height reserved under a grid tile's icon: the label line, plus the sub line if any. */
    const val LABEL_BLOCK = 34f
    const val LABEL_BLOCK_SUB = 50f

    /**
     * Grid-tile icon edge: [ICON_FRACTION] of the tile's width, capped by the height left
     * above the label block so the icon never crowds the text. The standard 174x130 tile
     * gets 96 (55% of its width); a two-line tile is height-capped at 80 (46%).
     */
    fun gridIconSize(tileW: Float, tileH: Float, hasSub: Boolean): Float {
        val block = if (hasSub) LABEL_BLOCK_SUB else LABEL_BLOCK
        return min(tileW * ICON_FRACTION, tileH - block)
    }

    /** Smallest relative size a fitted label may shrink to before it wraps instead. */
    const val LABEL_MIN_SCALE = 0.62f

    /** One extra label line's height, reserved when a long settings label wraps to two. */
    const val LABEL_LINE_H = 17f

    /**
     * Shrink-to-fit factor for one line of text: 1 when [fullWidth] already fits
     * [availWidth], otherwise the ratio that would fit, floored at [LABEL_MIN_SCALE] so
     * the text stays legible (callers wrap instead of shrinking past the floor).
     */
    fun labelScale(fullWidth: Float, availWidth: Float): Float =
        if (fullWidth <= 0f || fullWidth <= availWidth) 1f
        else (availWidth / fullWidth).coerceIn(LABEL_MIN_SCALE, 1f)

    /**
     * Splits a long label into two lines: after the ": " state separator when the label
     * has one ("xbox 360 games: grant folder access" -> "xbox 360 games:" / "grant folder
     * access"), else at the space nearest the middle. Null when there is no space to
     * split at (callers keep the one-line ellipsized form then).
     */
    fun splitLabel(text: String): Pair<String, String>? {
        val colon = text.indexOf(": ")
        if (colon in 1 until text.length - 2) {
            return text.substring(0, colon + 1) to text.substring(colon + 2)
        }
        val spaces = text.indices.filter { text[it] == ' ' }
        if (spaces.isEmpty()) return null
        val at = spaces.minByOrNull { kotlin.math.abs(it - text.length / 2) } ?: return null
        return text.substring(0, at) to text.substring(at + 1)
    }

    /** Height of the hero's label block: primary line, plus the secondary line if any. */
    const val HERO_BLOCK = 44f
    const val HERO_BLOCK_SUB = 64f

    /**
     * Hero icon edge: as large as the panel takes -- bounded by 44% of its width, 78% of its
     * height, and the room above the label block. The portrait phone hero (708x264, with a
     * sub) gets 194, up from 121 under the old 0.46 x min(w, h) rule; a literal doubling
     * would run into the hero's own label text.
     */
    fun heroIconSize(w: Float, h: Float, hasSub: Boolean): Float {
        val block = if (hasSub) HERO_BLOCK_SUB else HERO_BLOCK
        return min(w * 0.44f, min(h * 0.78f, h - block - 6f))
    }

    /** Height of the hero area above its label block; the hero icon centres in it. */
    fun heroIconAreaH(h: Float, hasSub: Boolean) = h - (if (hasSub) HERO_BLOCK_SUB else HERO_BLOCK)

    /** A window into a source bitmap, in source pixels. */
    data class CoverSrc(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right get() = left + width
        val bottom get() = top + height
    }

    /**
     * The centred source window that lets a [srcW]x[srcH] bitmap cover a [dstW]x[dstH]
     * tile (0.4.3's filled grid tiles): scale by the larger factor and crop the overflow
     * on the other axis, never stretch. A square icon into the standard 174x130 tile
     * keeps the middle 74.7% of its height; a same-aspect source is used whole.
     */
    fun coverSrc(srcW: Float, srcH: Float, dstW: Float, dstH: Float): CoverSrc {
        val scale = max(dstW / srcW, dstH / srcH)
        val w = dstW / scale
        val h = dstH / scale
        return CoverSrc((srcW - w) / 2f, (srcH - h) / 2f, w, h)
    }

    /** Edge of the box a drawn glyph occupies on a filled grid tile: large and centred. */
    const val GLYPH_FRACTION = 0.85f
    fun gridGlyphSize(tileW: Float, tileH: Float) = min(tileW, tileH) * GLYPH_FRACTION
}

data class Template(
    val w: Float,
    val h: Float,
    val slots: List<Box>,
    /** Slot for the big feature tile, or -1. */
    val hero: Int = -1,
    /** Slots for the "all apps" / "settings" shortcuts on home. */
    val wide: List<Int> = emptyList(),
    /** Uniform cells: shortcuts move up to follow the last app instead of leaving a gap. */
    val compact: Boolean = false,
)

/** User-chosen tile size; smaller sizes fit more tiles by enlarging the design space. */
enum class TileSize(val label: String, val portraitCols: Int, val landscapeZoom: Float) {
    LARGE("large", 2, 1f),
    MEDIUM("medium", 3, 1.25f),
    SMALL("small", 4, 1.5f);

    fun smaller() = entries[min(ordinal + 1, entries.size - 1)]
    fun larger() = entries[max(ordinal - 1, 0)]
    fun next() = entries[(ordinal + 1) % entries.size]
}

/** Design space and chrome positions for one orientation. Pages sit one [w] apart on the horizon. */
data class Layout(
    val w: Float,
    val h: Float,
    val portrait: Boolean,
    val panelsY: Float,
    val panelsH: Float,
    val twistX: Float,
    val twistY: Float,
    val clockX: Float,
    val clockY: Float,
    val home: Template,
    val grid: Template,
) {
    companion object {
        /** The dash's own 1280x720 placement (HubSceneStandard). */
        val LANDSCAPE = Layout(
            Metrics.DESIGN_W, Metrics.DESIGN_H, false,
            Metrics.PANELS_Y, Metrics.PANELS_H, Metrics.TWIST_X, Metrics.TWIST_Y,
            1182f, 72f, Metrics.HOME, Metrics.grid(Metrics.GRID_COLS, Metrics.GRID_ROWS),
        )

        private const val TWIST_TO_PANELS = 100f
        private const val PORTRAIT_MARGIN = 34f
        private const val PORTRAIT_TOP = 40f
        private const val PORTRAIT_BOTTOM = 30f

        /**
         * Phone portrait: the design space is just wide enough for [TileSize.portraitCols] tiles, so
         * fewer columns means bigger tiles. Rows fill the height; the twist + panels block is centred.
         */
        fun portrait(viewW: Int, viewH: Int, size: TileSize = TileSize.LARGE): Layout {
            val cols = size.portraitCols
            val w = Metrics.gridW(cols) + 2 * PORTRAIT_MARGIN
            val h = w * viewH / viewW
            val avail = h - PORTRAIT_TOP - TWIST_TO_PANELS - PORTRAIT_BOTTOM
            val heroH = Metrics.heroHeight(cols)
            val homeRows = max(1, floor((avail - heroH) / (Metrics.SMALL_H + Metrics.GUTTER)).toInt())
            val gridRows = max(1, floor((avail + Metrics.GUTTER) / (Metrics.SMALL_H + Metrics.GUTTER)).toInt())
            val home = Metrics.portraitHome(cols, homeRows)
            val grid = Metrics.grid(cols, gridRows, rowMajor = true)
            val panelsH = max(home.h, grid.h)
            val top = max(PORTRAIT_TOP, (h - TWIST_TO_PANELS - panelsH) / 2f)
            return Layout(
                w, h, true, top + TWIST_TO_PANELS, panelsH, PORTRAIT_MARGIN, top,
                w - PORTRAIT_MARGIN, 40f, home, grid,
            )
        }

        /**
         * Landscape keeps the dash's chrome positions; smaller sizes zoom the design space out and
         * widen it to the screen's aspect, so list pages gain columns and rows.
         */
        fun landscape(viewW: Int, viewH: Int, size: TileSize = TileSize.LARGE): Layout {
            if (size == TileSize.LARGE) return LANDSCAPE
            val h = Metrics.DESIGN_H * size.landscapeZoom
            val w = max(Metrics.DESIGN_W * size.landscapeZoom, h * viewW / viewH)
            val panelsH = h - Metrics.PANELS_Y - 80f
            val rows = max(3, floor((panelsH + Metrics.GUTTER) / (Metrics.SMALL_H + Metrics.GUTTER)).toInt())
            val usable = w - 2 * Metrics.TWIST_X
            val cols = max(5, floor((usable + Metrics.GUTTER) / (Metrics.SMALL_W + Metrics.GUTTER)).toInt())
            val homeExtra = floor((usable - Metrics.HOME.w) / (Metrics.SMALL_W + Metrics.GUTTER)).toInt()
            return Layout(
                w, h, false, Metrics.PANELS_Y, panelsH, Metrics.TWIST_X, Metrics.TWIST_Y,
                w - 98f, 72f, Metrics.landscapeHome(homeExtra), Metrics.grid(cols, rows),
            )
        }

        fun forView(viewW: Int, viewH: Int, size: TileSize = TileSize.LARGE) =
            if (viewH > viewW) portrait(viewW, viewH, size) else landscape(viewW, viewH, size)
    }
}
