package dev.x360reborn.home

/**
 * A hub colour theme. The Xbox Green values are the measured skin.xui constants the
 * launcher shipped with, so the default theme is pixel-identical to the pre-theme build.
 */
data class Theme(
    val id: String,
    val name: String,
    /** Live-tile fill (also the accent punched through glyph cut-outs). */
    val tile: Int,
    val bgTop: Int,
    val bgUpperMid: Int,
    val bgMid: Int,
    val bgLowerMid: Int,
    val bgBottom: Int,
    val floor: Int,
    val text: Int,
    val textDim: Int,
    val slotDark: Int,
) {
    /** The aura ramp as the five gradient stops the background shader consumes. */
    fun auraStops(): IntArray = intArrayOf(bgTop, bgUpperMid, bgMid, bgLowerMid, bgBottom)
}

object Themes {
    val XBOX = Theme(
        "xbox", "Xbox Green",
        tile = 0xFF008A00.toInt(),
        bgTop = 0xFF76777B.toInt(), bgUpperMid = 0xFF898A8C.toInt(), bgMid = 0xFFA3A3A5.toInt(),
        bgLowerMid = 0xFFBCBCBC.toInt(), bgBottom = 0xFFCDCDCD.toInt(),
        floor = 0xFFBFBFBF.toInt(), text = 0xFFEBEBEB.toInt(), textDim = 0xFF8C8C8C.toInt(),
        slotDark = 0xFF0F0F0F.toInt(),
    )
    val MIDNIGHT = Theme(
        "midnight", "Midnight",
        tile = 0xFF14467C.toInt(),
        bgTop = 0xFF232B3A.toInt(), bgUpperMid = 0xFF2E3A4F.toInt(), bgMid = 0xFF4A5A75.toInt(),
        bgLowerMid = 0xFF71859F.toInt(), bgBottom = 0xFF8FA3BF.toInt(),
        floor = 0xFF7787A0.toInt(), text = 0xFFEBEBEB.toInt(), textDim = 0xFF8C8C8C.toInt(),
        slotDark = 0xFF0A0F16.toInt(),
    )
    val CRIMSON = Theme(
        "crimson", "Crimson",
        tile = 0xFF8A1420.toInt(),
        bgTop = 0xFF3A2326.toInt(), bgUpperMid = 0xFF4F2E33.toInt(), bgMid = 0xFF75545A.toInt(),
        bgLowerMid = 0xFF9F7A7A.toInt(), bgBottom = 0xFFC0A3A3.toInt(),
        floor = 0xFFB08D8D.toInt(), text = 0xFFEBEBEB.toInt(), textDim = 0xFF9C8C8C.toInt(),
        slotDark = 0xFF160A0A.toInt(),
    )
    val VIOLET = Theme(
        "violet", "Violet",
        tile = 0xFF5B2D90.toInt(),
        bgTop = 0xFF2B2140.toInt(), bgUpperMid = 0xFF3A2D55.toInt(), bgMid = 0xFF5C4A80.toInt(),
        bgLowerMid = 0xFF8574A8.toInt(), bgBottom = 0xFFAC9CC9.toInt(),
        floor = 0xFF9A8DB5.toInt(), text = 0xFFEBEBEB.toInt(), textDim = 0xFF948C9C.toInt(),
        slotDark = 0xFF100A16.toInt(),
    )
    val CARBON = Theme(
        "carbon", "Carbon",
        tile = 0xFF3D4148.toInt(),
        bgTop = 0xFF232426.toInt(), bgUpperMid = 0xFF303133.toInt(), bgMid = 0xFF55565A.toInt(),
        bgLowerMid = 0xFF7A7B7F.toInt(), bgBottom = 0xFF9E9E9E.toInt(),
        floor = 0xFF828282.toInt(), text = 0xFFEBEBEB.toInt(), textDim = 0xFF8C8C8C.toInt(),
        slotDark = 0xFF0C0C0C.toInt(),
    )

    val all: List<Theme> = listOf(XBOX, MIDNIGHT, CRIMSON, VIOLET, CARBON)
    val default: Theme = XBOX

    /** The theme the hub draws with; set before the view is created and again on every change. */
    var active: Theme = default

    fun byId(id: String?): Theme = all.firstOrNull { it.id == id } ?: default
}

/** How the active theme's aura ramp is presented behind the hub. */
enum class BgStyle(val label: String) {
    GRADIENT("gradient"),
    SOLID("solid"),
    DIMMED("dimmed");

    fun next(): BgStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        fun byName(name: String?): BgStyle = entries.firstOrNull { it.name == name } ?: GRADIENT
    }
}
