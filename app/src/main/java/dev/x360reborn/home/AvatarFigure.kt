package dev.x360reborn.home

import kotlin.math.max
import kotlin.math.min

/**
 * Geometry of the home hero's staged player figure (0.9.1): an original,
 * code-drawn standing figure in the app's flat style. The proportions are
 * deliberately chunky and abstract -- a plain featureless head, two-tone
 * geometric clothing, capsule limbs -- and share nothing with any shipped
 * avatar design; the re/out/avatar material is research reference only and
 * no part of it is reproduced here. Pure math in hero-local design px
 * (origin at the hero's top-left), so the staging is unit-tested;
 * HomeView only draws what this returns.
 */
object AvatarFigure {
    /** Height of the hero's label zone; matches Metrics.HERO_BLOCK_SUB, the tallest label block. */
    const val LABEL_H = 64f

    /** Clear space kept between the figure's floor and the label zone. */
    const val LABEL_GAP = 10f

    /** The figure's height as a fraction of the hero's height. */
    const val HEIGHT_FRAC = 0.58f

    /** The figure's staging width as a fraction of the hero's width. */
    const val WIDTH_FRAC = 0.25f

    /** Clear space between the staging box and the hero's right edge, as a fraction of its width. */
    const val RIGHT_FRAC = 0.04f

    /** How far the arms hang off vertical, in degrees. */
    const val ARM_TILT_DEG = 6f

    /** The hero's label zone: the bottom strip its name and sub are drawn into. */
    fun labelRect(heroW: Float, heroH: Float) = Box(0f, heroH - LABEL_H, heroW, LABEL_H)

    /** True when two boxes share any area (touching edges do not count). */
    fun overlaps(a: Box, b: Box) =
        a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom

    /** The figure's parts, all in hero-local design px. */
    data class Figure(
        /** Everything drawn, floor included: the staging's full footprint. */
        val bounds: Box,
        /** The figure proper: head top to shoe bottom, floor excluded. */
        val body: Box,
        /** The floor ellipse's box; the feet rest on its centre line. */
        val floor: Box,
        val head: Box,
        val torso: Box,
        val chest: Box,
        val belt: Box,
        val armL: Box,
        val armR: Box,
        val legL: Box,
        val legR: Box,
        val shoeL: Box,
        val shoeR: Box,
    )

    fun layout(heroW: Float, heroH: Float): Figure {
        val fh = heroH * HEIGHT_FRAC
        val fw = heroW * WIDTH_FRAC
        val floorH = fh * 0.09f
        // The shoes rest on the floor ellipse's centre line and the ellipse's
        // lower half sits under them, so the whole footprint -- floor
        // included -- ends LABEL_GAP above the label zone.
        val feetY = heroH - LABEL_H - LABEL_GAP - floorH / 2f
        val cx = heroW - heroW * RIGHT_FRAC - fw / 2f
        val top = feetY - fh
        val body = Box(cx - fw / 2f, top, fw, fh)
        val floor = Box(cx - fw * 0.62f, feetY - floorH / 2f, fw * 1.24f, floorH)
        val bounds = Box(
            min(body.x, floor.x), body.y,
            max(body.right, floor.right) - min(body.x, floor.x),
            floor.bottom - body.y,
        )
        // Parts as fractions of the figure height, chunky on purpose.
        fun part(dx: Float, dy: Float, w: Float, h: Float) =
            Box(cx + dx * fh, top + dy * fh, w * fh, h * fh)
        val head = part(-0.085f, 0.035f, 0.17f, 0.17f)
        val torso = part(-0.18f, 0.20f, 0.36f, 0.34f)
        val chest = Box(torso.x, torso.y + fh * 0.035f, torso.w, fh * 0.10f)
        val belt = Box(torso.x, torso.bottom - fh * 0.075f, torso.w, fh * 0.075f)
        val armL = part(-0.295f, 0.215f, 0.095f, 0.30f)
        val armR = part(0.20f, 0.215f, 0.095f, 0.30f)
        val legL = part(-0.1375f, 0.535f, 0.125f, 0.425f)
        val legR = part(0.0125f, 0.535f, 0.125f, 0.425f)
        val shoeL = part(-0.1625f, 0.95f, 0.175f, 0.05f)
        val shoeR = part(-0.0125f, 0.95f, 0.175f, 0.05f)
        return Figure(bounds, body, floor, head, torso, chest, belt, armL, armR, legL, legR, shoeL, shoeR)
    }
}
