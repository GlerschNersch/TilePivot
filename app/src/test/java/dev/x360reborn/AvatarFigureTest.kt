package dev.x360reborn

import dev.x360reborn.home.AvatarFigure
import dev.x360reborn.home.Box
import dev.x360reborn.home.Layout
import dev.x360reborn.home.Metrics
import dev.x360reborn.home.TileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Staging geometry of the home hero's code-drawn player figure (0.9.1). */
class AvatarFigureTest {

    /**
     * The canonical hero rects the figure must stage into: the portrait
     * layouts' shared hero at every tile size, the landscape Template1HD
     * hero, and the 708x352.7 portrait hero the 0.6.1 notes canonised.
     */
    private val heroes: List<Box> = listOf(
        Box(0f, 0f, 708f, 352.7f),
        Layout.portrait(1080, 2400, TileSize.SMALL).heroBox,
        Layout.portrait(1080, 2400, TileSize.MEDIUM).heroBox,
        Layout.portrait(1080, 2400, TileSize.LARGE).heroBox,
        Layout.LANDSCAPE.heroBox,
        Layout.landscape(2400, 1080, TileSize.SMALL).heroBox,
        Layout.landscape(2400, 1080, TileSize.MEDIUM).heroBox,
    )

    @Test fun figureStaysInsideTheHero() {
        for (hero in heroes) {
            val f = AvatarFigure.layout(hero.w, hero.h)
            assertTrue("bounds left in $hero", f.bounds.x >= 0f)
            assertTrue("bounds top in $hero", f.bounds.y >= 0f)
            assertTrue("bounds right in $hero", f.bounds.right <= hero.w + 0.01f)
            assertTrue("bounds bottom in $hero", f.bounds.bottom <= hero.h + 0.01f)
        }
    }

    @Test fun figureStandsClearOfTheLabelZone() {
        for (hero in heroes) {
            val f = AvatarFigure.layout(hero.w, hero.h)
            val label = AvatarFigure.labelRect(hero.w, hero.h)
            assertFalse("figure overlaps labels in $hero", AvatarFigure.overlaps(f.bounds, label))
            // The footprint ends exactly LABEL_GAP above the label zone.
            assertEquals(AvatarFigure.LABEL_GAP, label.y - f.bounds.bottom, 0.01f)
            // The label zone the figure avoids is the block the hero really
            // reserves for its name and sub (Metrics.HERO_BLOCK_SUB).
            assertTrue(AvatarFigure.LABEL_H >= Metrics.HERO_BLOCK_SUB)
        }
    }

    @Test fun feetRestOnTheFloorCentreLine() {
        for (hero in heroes) {
            val f = AvatarFigure.layout(hero.w, hero.h)
            assertEquals(f.body.bottom, f.shoeL.bottom, 0.01f)
            assertEquals(f.body.bottom, f.shoeR.bottom, 0.01f)
            assertEquals(f.body.bottom, f.floor.cy, 0.01f)
            assertEquals(f.floor.bottom, f.bounds.bottom, 0.01f)
        }
    }

    @Test fun partsStayWithinTheBody() {
        for (hero in heroes) {
            val f = AvatarFigure.layout(hero.w, hero.h)
            val parts = listOf(f.head, f.torso, f.chest, f.belt, f.armL, f.armR, f.legL, f.legR, f.shoeL, f.shoeR)
            for (p in parts) {
                assertTrue("part $p left of body in $hero", p.x >= f.body.x - 0.01f)
                assertTrue("part $p right of body in $hero", p.right <= f.body.right + 0.01f)
                assertTrue("part $p above body in $hero", p.y >= f.body.y - 0.01f)
                assertTrue("part $p below body in $hero", p.bottom <= f.body.bottom + 0.01f)
            }
        }
    }

    @Test fun headIsAPlainCircleCentredOverTheTorso() {
        val f = AvatarFigure.layout(708f, 352.7f)
        assertEquals(f.head.w, f.head.h, 0.01f)
        assertEquals(f.body.cx, f.head.cx, 0.01f)
        assertEquals(f.body.cx, f.torso.cx, 0.01f)
        assertTrue(f.head.bottom <= f.torso.bottom)
        // The figure is 55-65% of the hero's height, about a quarter of its width.
        assertTrue(f.body.h / 352.7f in 0.55f..0.65f)
        assertEquals(0.25f, f.body.w / 708f, 0.01f)
    }

    @Test fun proportionsAreScaleInvariant() {
        val a = AvatarFigure.layout(466f, 264f)
        val b = AvatarFigure.layout(932f, 528f)
        // Sizes and horizontal placement scale with the hero. Vertical
        // offsets are compared relative to the body's top: the figure is
        // anchored to the fixed-height label zone, so doubling the hero
        // does not double its distance from the top.
        fun check(name: String, x: Box, y: Box) {
            assertEquals("$name x", x.x * 2f, y.x, 0.05f)
            assertEquals("$name relY", (x.y - a.body.y) * 2f, y.y - b.body.y, 0.05f)
            assertEquals("$name w", x.w * 2f, y.w, 0.05f)
            assertEquals("$name h", x.h * 2f, y.h, 0.05f)
        }
        check("bounds", a.bounds, b.bounds)
        check("floor", a.floor, b.floor)
        check("head", a.head, b.head)
        check("torso", a.torso, b.torso)
        check("armL", a.armL, b.armL)
        check("legR", a.legR, b.legR)
        check("shoeR", a.shoeR, b.shoeR)
    }
}
