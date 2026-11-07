package dev.x360reborn.avatar

import java.io.File

/**
 * Optional support for avatar packs the user supplies themselves. TilePivot ships no pack data
 * and, in its public build, no decoder either: [AvatarPacks.decoder] is null and the avatar
 * screen offers only TilePivot's own avatar. A local build may add a decoder implementation as
 * the class named [AvatarPacks.IMPL].
 */
interface AvatarPackDecoder {
    /** Opens a pack file the user picked; throws if it is not a pack this decoder reads. */
    fun open(file: File): AvatarPack
}

interface AvatarPack {
    /** Every wearable or body item; within a slot the preferred default comes first. */
    val items: List<PackItem>
    /** The item's renderable parts with colours applied ([skin] and [hair] are 0xRRGGBB). */
    fun load(item: PackItem, skin: Int, hair: Int): List<PackPart>
}

/**
 * [slot] is one of [PackItem.SLOTS] (or "other"); [male]/[female] say who it fits (both = unisex).
 * [hatVariant] is the [index] of this hairstyle's cut-down version for wearing under a hat, if any.
 * Decoders list the preferred default first within each slot.
 */
data class PackItem(
    val index: Int, val name: String, val slot: String, val male: Boolean, val female: Boolean,
    val hatVariant: Int? = null,
) {
    companion object {
        val SLOTS = listOf("body", "head", "hair", "top", "bottoms", "outfit", "shoes", "hat", "glasses")
    }
}

/**
 * One textured triangle mesh: [positions] xyz, [uvs] uv per vertex, [indices] triangles,
 * [texture] ARGB pixels [texW] x [texH] (null = plain [color]).
 */
class PackPart(
    val positions: FloatArray,
    val uvs: FloatArray,
    val indices: IntArray,
    val texture: IntArray?,
    val texW: Int,
    val texH: Int,
    val color: Int = 0xFFFFFF,
)

object AvatarPacks {
    const val IMPL = "dev.x360reborn.avatarpack.PackDecoder"

    /** The locally built decoder, or null in the public build. */
    val decoder: AvatarPackDecoder? by lazy {
        runCatching { Class.forName(IMPL).getDeclaredConstructor().newInstance() as AvatarPackDecoder }.getOrNull()
    }

    /** Where an imported pack is kept (app-private storage). */
    fun file(filesDir: File) = File(filesDir, "avatarpack.bin")
}
