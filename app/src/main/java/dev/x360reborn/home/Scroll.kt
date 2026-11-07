package dev.x360reborn.home

import kotlin.math.max

/**
 * Vertical scroll geometry for a channel's tile grid (0.9.0): the grid lives in a
 * content space of rows under a fixed hero, and the panel region below the hero is
 * a viewport onto it. Pure math in design px, so the hub's scroll behaviour is
 * unit-tested; HomeView keeps the offsets and applies them.
 */
object Scroll {
    /** Rows needed to hold [count] tiles at [cols] per row (no tiles, no rows). */
    fun rowsFor(count: Int, cols: Int): Int =
        if (count <= 0 || cols <= 0) 0 else (count + cols - 1) / cols

    /** Height of [rows] rows of tiles: the tiles plus the gutters between them. */
    fun contentHeight(rows: Int, tileH: Float, gutter: Float): Float =
        if (rows <= 0) 0f else rows * tileH + (rows - 1) * gutter

    /** How far content may scroll: the overflow past the viewport, never negative. */
    fun maxOffset(contentH: Float, viewportH: Float): Float = max(0f, contentH - viewportH)

    /** An offset kept inside [0, maxOff]. */
    fun clampOffset(offset: Float, maxOff: Float): Float = offset.coerceIn(0f, max(0f, maxOff))

    /**
     * The offset nearest [current] that shows the item ([itemTop], [itemBottom], in
     * content coordinates) in full: no move when it is already fully visible, the
     * smallest shift otherwise. An item taller than the viewport aligns its top.
     * The result is always a legal offset.
     */
    fun revealOffset(current: Float, itemTop: Float, itemBottom: Float, viewportH: Float, maxOff: Float): Float {
        val target = when {
            itemBottom - itemTop >= viewportH -> itemTop
            itemTop < current -> itemTop
            itemBottom > current + viewportH -> itemBottom - viewportH
            else -> current
        }
        return clampOffset(target, maxOff)
    }

    /**
     * Rows intersecting the viewport at [offset]: from the row holding the
     * viewport's top edge to the row holding its last visible pixel. Empty when
     * there are no rows or no viewport.
     */
    fun visibleRows(offset: Float, viewportH: Float, tileH: Float, gutter: Float, rows: Int): IntRange {
        if (rows <= 0 || viewportH <= 0f || tileH <= 0f) return IntRange.EMPTY
        val pitch = tileH + gutter
        // First row whose bottom edge is below the offset (an offset can land in
        // the gutter under a row that is no longer visible at all).
        val first = (if (offset < tileH) 0 else ((offset - tileH) / pitch).toInt() + 1)
            .coerceIn(0, rows - 1)
        val last = ((offset + viewportH - 1f) / pitch).toInt().coerceIn(first, rows - 1)
        return first..last
    }
}
