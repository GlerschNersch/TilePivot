package dev.x360reborn

import dev.x360reborn.home.Glyph
import dev.x360reborn.home.Layout
import dev.x360reborn.home.Metrics
import dev.x360reborn.home.TileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {
    private val clockY = 72f

    @Test fun chipSitsLeftOfTheGearWithNameBesidePicture() {
        val gear = Metrics.settingsGearBox(1182f, clockY, 100f)
        val chip = Metrics.profileChip(gear.x, clockY, 150f, Metrics.TWIST_X)!!
        // Picture ends one gap short of the gear box and is centred on the clock text.
        assertEquals(gear.x - Metrics.PROFILE_GAP, chip.pic.right, 0.01f)
        assertEquals(Metrics.clockCenterY(clockY), chip.pic.cy, 0.01f)
        assertEquals(Metrics.PROFILE_PIC, chip.pic.w, 0f)
        // The whole name fits, right-aligned one gap left of the picture.
        assertEquals(150f, chip.nameMaxW, 0f)
        assertEquals(chip.pic.x - Metrics.PROFILE_GAP, chip.nameRight, 0.01f)
        // The hit box covers name and picture, stops at the gear, and is as tall as the gear's.
        assertEquals(gear.x, chip.hit.right, 0.01f)
        assertTrue(chip.hit.x <= chip.nameRight - chip.nameMaxW)
        assertEquals(Metrics.GEAR_BOX, chip.hit.h, 0f)
    }

    @Test fun longNamesAreCappedAndNoNameMeansPictureOnly() {
        val long = Metrics.profileChip(900f, clockY, 2000f, Metrics.TWIST_X)!!
        assertEquals(Metrics.PROFILE_NAME_MAX, long.nameMaxW, 0f)
        val none = Metrics.profileChip(900f, clockY, 0f, Metrics.TWIST_X)!!
        assertEquals(0f, none.nameMaxW, 0f)
        // Picture-only chips still get a gear-sized target (48 dp and up).
        assertEquals(Metrics.GEAR_BOX, none.hit.w, 0f)
        assertTrue(none.hit.w * 0.75f >= 48f)
    }

    @Test fun narrowPortraitDropsTheNameButKeepsThePicture() {
        // Large-tile portrait: the narrowest strip the app lays out.
        val l = Layout.portrait(1080, 2340, TileSize.LARGE)
        val gear = Metrics.settingsGearBox(l.clockX, l.clockY, 110f)
        val chip = Metrics.profileChip(gear.x, l.clockY, 180f, l.twistX)
        assertNotNull(chip)
        assertTrue(chip!!.pic.x >= l.twistX)
        // Too little room for a readable name: picture alone, never squeezed text.
        assertTrue(chip.nameMaxW == 0f || chip.nameMaxW >= Metrics.PROFILE_NAME_MIN)
    }

    @Test fun noRoomAtAllHidesTheChip() {
        assertNull(Metrics.profileChip(Metrics.TWIST_X + 40f, clockY, 0f, Metrics.TWIST_X))
    }

    @Test fun shortNamesFitEvenWhenRoomIsUnderTheMinimum() {
        // 60 px of room left of the picture: a 40 px name still shows, a 200 px one doesn't.
        val right = Metrics.TWIST_X + 60f + Metrics.PROFILE_GAP * 2 + Metrics.PROFILE_PIC
        assertEquals(40f, Metrics.profileChip(right, clockY, 40f, Metrics.TWIST_X)!!.nameMaxW, 0.01f)
        assertEquals(0f, Metrics.profileChip(right, clockY, 200f, Metrics.TWIST_X)!!.nameMaxW, 0f)
    }

    @Test fun namesAreTrimmedSingleLineAndCapped() {
        assertEquals("Matt V", Profile.clean("  Matt \n  V "))
        assertEquals("", Profile.clean("   "))
        val capped = Profile.clean("x".repeat(40))
        assertEquals(Profile.MAX_NAME, capped.length)
        assertFalse(Profile.clean("a" + " ".repeat(30) + "b").contains("  "))
    }

    @Test fun squareCropIsCentred() {
        assertEquals(Triple(40, 0, 100), Profile.squareCrop(180, 100))
        assertEquals(Triple(0, 25, 50), Profile.squareCrop(50, 100))
        assertEquals(Triple(0, 0, 64), Profile.squareCrop(64, 64))
    }

    @Test fun settingsTileShowsTheName() {
        val blank = Apps.profileEntry("")
        assertEquals("profile", blank.label)
        assertEquals(Apps.ACTION_PROFILE, blank.action)
        assertEquals(Glyph.PROFILE, blank.glyph)
        assertFalse(blank.external)
        assertEquals("profile: Matt", Apps.profileEntry("Matt").label)
    }
}
