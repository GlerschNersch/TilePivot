package dev.x360reborn

import dev.x360reborn.avatar.AvatarLook
import dev.x360reborn.avatar.AvatarMesh
import dev.x360reborn.avatar.AvatarModel
import dev.x360reborn.avatar.AvatarSnapshot
import dev.x360reborn.avatar.HairStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

class AvatarTest {
    @Test fun lookRoundTripsAndRejectsJunk() {
        val l = AvatarLook(3, 2, 4, 5, 1, 2)
        assertEquals(l, AvatarLook.decode(l.encode()))
        assertEquals(AvatarLook.DEFAULT, AvatarLook.decode(null))
        assertEquals(AvatarLook.DEFAULT, AvatarLook.decode("1,2,x,4,5,6"))
        assertEquals(AvatarLook.DEFAULT, AvatarLook.decode("1,2,3"))
        assertEquals(AvatarLook.DEFAULT, AvatarLook.decode("1,2,3,4,5,-1"))
        // Out-of-range indices wrap instead of crashing (e.g. a palette shrinks in a later build).
        assertEquals(AvatarLook.SKIN[1], AvatarLook(skin = AvatarLook.SKIN.size + 1).skinRgb())
    }

    @Test fun meshIsWellFormedForEveryHairStyle() {
        val counts = HashSet<Int>()
        for (h in HairStyle.entries.indices) {
            val m = AvatarModel.build(AvatarLook(hair = h))
            assertEquals(0, m.vertices.size % AvatarMesh.STRIDE)
            assertEquals(0, m.indices.size % 3)
            assertTrue(m.indices.all { it in 0 until m.vertexCount })
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            for (i in 0 until m.vertexCount) {
                val o = i * AvatarMesh.STRIDE
                minY = minOf(minY, m.vertices[o + 1]); maxY = maxOf(maxY, m.vertices[o + 1])
                minX = minOf(minX, m.vertices[o]); maxX = maxOf(maxX, m.vertices[o])
                val nl = sqrt(m.vertices[o + 3] * m.vertices[o + 3] + m.vertices[o + 4] * m.vertices[o + 4] + m.vertices[o + 5] * m.vertices[o + 5])
                assertEquals(1f, nl, 1e-3f)
                for (c in 6..8) assertTrue(m.vertices[o + c] in 0f..1f)
            }
            // Feet on the floor, about 1.6 tall (spiky hair adds a little), left/right symmetric.
            assertEquals(0f, minY, 0.01f)
            assertTrue("height $maxY", maxY in 1.45f..1.75f)
            assertEquals(maxX, -minX, 1e-4f)
            counts += m.vertexCount
        }
        assertEquals("each hair style changes the mesh", HairStyle.entries.size, counts.size)
    }

    @Test fun headshotFillsTheMiddleAndLeavesCornersClear() {
        val px = AvatarSnapshot.headshot(AvatarModel.build(AvatarLook.DEFAULT), 96)
        assertTrue(AvatarSnapshot.coverage(px) in 0.3f..0.9f)
        assertEquals(0, px[0] ushr 24)                 // top-left corner transparent
        assertNotEquals(0, px[48 * 96 + 48] ushr 24)   // face in the middle
        // Skin tone shows on the face: the centre pixel is warm (red > blue).
        val c = px[44 * 96 + 48]; assertTrue(((c shr 16) and 255) > (c and 255))
        dump("avatar_headshot_default", px, 96)
        for (h in HairStyle.entries.indices) dump("avatar_headshot_hair$h", AvatarSnapshot.headshot(AvatarModel.build(AvatarLook(hair = h, skin = h, top = h)), 96), 96)
        dump("avatar_full", AvatarSnapshot.headshot(AvatarModel.build(AvatarLook.DEFAULT), 160, cy = 0.8f, half = 0.85f), 160)
    }

    /** Writes a PPM next to the build outputs for eyeballing (ignored by the assertions). */
    private fun dump(name: String, px: IntArray, size: Int) {
        val dir = File("build/avatar-previews").apply { mkdirs() }
        File(dir, "$name.ppm").outputStream().use { o ->
            o.write("P6\n$size $size\n255\n".toByteArray())
            for (p in px) {
                val a = (p ushr 24) != 0
                o.write(if (a) (p shr 16) and 255 else 40); o.write(if (a) (p shr 8) and 255 else 44); o.write(if (a) p and 255 else 52)
            }
        }
    }

    @Suppress("unused") private fun close(a: Float, b: Float) = abs(a - b) < 1e-4f
}

class AnimatorTest {
    private fun handY(a: dev.x360reborn.avatar.Animator, rig: dev.x360reborn.avatar.Rig, name: String) = a.worldP[3 * rig.names.getValue(name) + 1]

    @Test fun ownAvatarSkinsToItsRestShapeAndWaveRaisesTheRightHand() {
        val part = AvatarModel.buildRigged(AvatarLook.DEFAULT); val rig = AvatarModel.rig
        val a = dev.x360reborn.avatar.Animator(rig)
        // With no motion (time 0 idle terms are ~0) the skinned mesh matches the rest mesh closely.
        a.pose(0f, 0f)
        val pos = FloatArray(part.positions.size); val nrm = FloatArray(part.positions.size)
        a.skin(part, pos, nrm)
        var maxD = 0f
        for (i in pos.indices) maxD = maxOf(maxD, kotlin.math.abs(pos[i] - part.positions[i]))
        assertTrue("rest drift $maxD", maxD < 0.02f)
        val restHand = handY(a, rig, "hand.R")
        a.play(dev.x360reborn.avatar.Animator.Emote.WAVE, 0f)
        a.pose(1.0f, 0.016f)
        assertTrue("hand up", handY(a, rig, "hand.R") > rig.joints[3 * rig.names.getValue("head") + 1])
        assertTrue(handY(a, rig, "hand.R") > restHand + 0.4f)
        assertTrue(pos.all { it.isFinite() } && nrm.all { it.isFinite() })
    }

    @Test fun lookAtTurnsGraduallyAndEmotesEnd() {
        val rig = AvatarModel.rig; val a = dev.x360reborn.avatar.Animator(rig)
        a.lookAt = 1f
        a.pose(0f, 0.016f)
        val headX0 = a.worldP[3 * rig.names.getValue("hand.L")]
        a.pose(0.5f, 0.5f)
        val headX1 = a.worldP[3 * rig.names.getValue("hand.L")]
        assertTrue("body turned", kotlin.math.abs(headX1 - headX0) > 0.02f)
        a.play(dev.x360reborn.avatar.Animator.Emote.CHEER, 0f)
        a.pose(5f, 0.016f)
        assertTrue(!a.busy)
    }
}
