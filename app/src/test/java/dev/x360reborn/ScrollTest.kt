package dev.x360reborn

import dev.x360reborn.home.Scroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vertical scroll geometry (0.9.0): the pure math behind the channels' scrolling grids. */
class ScrollTest {

    @Test fun rowsForCeilsAndHandlesEmpty() {
        assertEquals(0, Scroll.rowsFor(0, 5))
        assertEquals(1, Scroll.rowsFor(1, 5))
        assertEquals(1, Scroll.rowsFor(5, 5))
        assertEquals(2, Scroll.rowsFor(6, 5))
        assertEquals(7, Scroll.rowsFor(31, 5))
        assertEquals(0, Scroll.rowsFor(10, 0))
    }

    @Test fun contentHeightStacksTilesAndGutters() {
        assertEquals(0f, Scroll.contentHeight(0, 130f, 4f), 0f)
        assertEquals(130f, Scroll.contentHeight(1, 130f, 4f), 0f)
        // 7 rows of 130 with 4px gutters: the apps channel's grid at the default size.
        assertEquals(934f, Scroll.contentHeight(7, 130f, 4f), 0f)
        // A boosted size: 6 rows of 185.1 with 5.7 gutters.
        assertEquals(6 * 185.1f + 5 * 5.7f, Scroll.contentHeight(6, 185.1f, 5.7f), 0.01f)
    }

    @Test fun maxOffsetIsTheOverflowOnly() {
        assertEquals(534f, Scroll.maxOffset(934f, 400f), 0f)
        assertEquals(0f, Scroll.maxOffset(400f, 400f), 0f)
        assertEquals(0f, Scroll.maxOffset(200f, 400f), 0f)
    }

    @Test fun clampOffsetStaysInRange() {
        assertEquals(0f, Scroll.clampOffset(-50f, 534f), 0f)
        assertEquals(200f, Scroll.clampOffset(200f, 534f), 0f)
        assertEquals(534f, Scroll.clampOffset(900f, 534f), 0f)
        assertEquals(0f, Scroll.clampOffset(10f, 0f), 0f)
    }

    @Test fun revealLeavesAVisibleItemAlone() {
        // Item fully inside the viewport at offset 100: no scroll.
        assertEquals(100f, Scroll.revealOffset(100f, 150f, 280f, 400f, 534f), 0f)
        // Exactly filling the visible band counts as visible too.
        assertEquals(100f, Scroll.revealOffset(100f, 100f, 500f, 400f, 534f), 0f)
    }

    @Test fun revealScrollsUpToAnItemAbove() {
        // Item above the viewport: align its top with the viewport top.
        assertEquals(40f, Scroll.revealOffset(300f, 40f, 170f, 400f, 534f), 0f)
        // Partially cut at the top: the smallest shift that shows it in full.
        assertEquals(90f, Scroll.revealOffset(100f, 90f, 220f, 400f, 534f), 0f)
    }

    @Test fun revealScrollsDownToAnItemBelow() {
        // Item below: the smallest shift that brings its bottom into view.
        assertEquals(270f, Scroll.revealOffset(100f, 540f, 670f, 400f, 534f), 0f)
        // Partially cut at the bottom: shift by just the hidden part.
        assertEquals(130f, Scroll.revealOffset(100f, 402f, 530f, 400f, 534f), 0f)
    }

    @Test fun revealAlignsTheTopOfAnOversizedItem() {
        assertEquals(120f, Scroll.revealOffset(0f, 120f, 620f, 400f, 534f), 0f)
    }

    @Test fun revealNeverPassesTheEnds() {
        // Below-case clamped to the max offset; above-case clamped to 0.
        assertEquals(534f, Scroll.revealOffset(0f, 900f, 1030f, 400f, 534f), 0f)
        assertEquals(0f, Scroll.revealOffset(200f, -50f, 80f, 400f, 534f), 0f)
    }

    @Test fun visibleRowsCoverTheViewport() {
        // 130px tiles, 4px gutters (pitch 134), 400px viewport, 7 rows.
        assertEquals(0..2, Scroll.visibleRows(0f, 400f, 130f, 4f, 7))
        // Scrolled one pitch: rows 1..3 (row 3 peeks in at the bottom edge).
        assertEquals(1..3, Scroll.visibleRows(134f, 400f, 130f, 4f, 7))
        // At the max offset the last row is fully in and the range ends at row 6;
        // row 3 ends at 532, just above the 534 offset, so it is not visible.
        assertEquals(4..6, Scroll.visibleRows(534f, 400f, 130f, 4f, 7))
    }

    @Test fun visibleRowsAreEmptyWithoutRowsOrViewport() {
        assertTrue(Scroll.visibleRows(0f, 400f, 130f, 4f, 0).isEmpty())
        assertTrue(Scroll.visibleRows(0f, 0f, 130f, 4f, 7).isEmpty())
    }
}
