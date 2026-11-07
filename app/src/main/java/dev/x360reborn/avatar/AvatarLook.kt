package dev.x360reborn.avatar

/**
 * How the player's avatar looks: indices into the palettes and styles below. Stored as a short
 * comma-separated string in the hub prefs (so settings backups carry it).
 *
 * The avatar is TilePivot's own design, built from simple shapes in code ([AvatarModel]); no
 * console artwork is used.
 */
data class AvatarLook(
    val skin: Int = 2,
    val hair: Int = 0,
    val hairColor: Int = 1,
    val top: Int = 0,
    val bottoms: Int = 3,
    val shoes: Int = 0,
) {
    fun encode(): String = listOf(skin, hair, hairColor, top, bottoms, shoes).joinToString(",")

    fun skinRgb() = SKIN[skin.mod(SKIN.size)]
    fun hairRgb() = HAIR_COLORS[hairColor.mod(HAIR_COLORS.size)]
    fun topRgb() = CLOTHES[top.mod(CLOTHES.size)]
    fun bottomsRgb() = CLOTHES[bottoms.mod(CLOTHES.size)]
    fun shoesRgb() = SHOES[shoes.mod(SHOES.size)]
    fun hairStyle() = HairStyle.entries[hair.mod(HairStyle.entries.size)]

    companion object {
        val DEFAULT = AvatarLook()

        /** A stored look, or [DEFAULT] for anything that doesn't parse (missing, old, damaged). */
        fun decode(s: String?): AvatarLook {
            val v = s?.split(',')?.map { it.trim().toIntOrNull() ?: return DEFAULT } ?: return DEFAULT
            if (v.size != 6 || v.any { it < 0 }) return DEFAULT
            return AvatarLook(v[0], v[1], v[2], v[3], v[4], v[5])
        }

        val SKIN = intArrayOf(0xF6D5C3, 0xEBC2A0, 0xD9A57E, 0xB97F58, 0x8E5A3B, 0x5E3A26)
        val HAIR_COLORS = intArrayOf(0x1E1A18, 0x5A3A22, 0x8A5A30, 0xC9A060, 0xA33A20, 0x9A9A9A)
        val CLOTHES = intArrayOf(0x2F6FB7, 0x2E8B57, 0xC0392B, 0x333A44, 0xE6E2DA, 0xE0A030, 0x7D4FB0)
        val SHOES = intArrayOf(0xF2F2F2, 0x222222, 0x7A4A2A, 0x2F6FB7)
    }
}

enum class HairStyle(val label: String) { SHORT("short"), LONG("long"), SPIKY("spiky"), BUN("bun"), NONE("none") }
