package dev.x360reborn.home

import kotlin.math.max

/**
 * Channel-entry tile cascade (0.5.1): when a channel becomes active, its landing page's
 * tiles pop in on a diagonal wave from the top-left -- each tile scales from [FROM_SCALE]
 * to 1 with a small easeOutBack overshoot and rises [RISE] design px into place. No alpha:
 * Metro tiles are opaque and stay solid. The math is pure so the timing and bounds are
 * unit-tested; HomeView only applies the resulting transform around each tile's centre.
 */
object Anim {
    /** Diagonal stagger per (row + column) step, in ms. */
    const val STAGGER_MS = 28f

    /** One tile's run, in ms. A full page settles in delay + this, roughly 600 ms worst case. */
    const val TILE_MS = 260f

    /** Scale a tile starts at before it pops. */
    const val FROM_SCALE = 0.85f

    /** How far below its slot a tile starts, in design px; it rises into place. */
    const val RISE = 16f

    /** easeOutBack overshoot constant (the standard cubic value). */
    const val OVERSHOOT = 1.70158f

    /** Wave delay for the tile at ([row], [col]): the diagonal from the top-left. */
    fun delayFor(row: Int, col: Int) = (row + col) * STAGGER_MS

    /** Linear progress for one tile: 0 until its delay has passed, 1 once its run finished. */
    fun progress(elapsedMs: Float, delayMs: Float) =
        ((elapsedMs - delayMs) / TILE_MS).coerceIn(0f, 1f)

    /**
     * easeOutBack: runs past 1 mid-flight (peaks at about 1.10, so the composed tile scale
     * peaks at about 1.015) and settles back to exactly 1 -- a landing, not jelly.
     * Both endpoints are exact.
     */
    fun easeOutBack(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        val u = c - 1f
        return 1f + (OVERSHOOT + 1f) * u * u * u + OVERSHOOT * u * u
    }

    /**
     * (scale, risePx) for one tile [elapsedMs] into the cascade. Before the tile's delay
     * it sits at the start pose; at progress 1 this is exactly (1, 0), so the settled
     * layout is pixel-identical to no animation at all.
     */
    fun entryAt(elapsedMs: Float, delayMs: Float): Pair<Float, Float> {
        val e = easeOutBack(progress(elapsedMs, delayMs))
        return Pair(FROM_SCALE + (1f - FROM_SCALE) * e, RISE * (1f - e))
    }

    /** Whole-cascade run time for a page whose last tile waits [maxDelayMs]. */
    fun totalMs(maxDelayMs: Float) = max(0f, maxDelayMs) + TILE_MS
}
