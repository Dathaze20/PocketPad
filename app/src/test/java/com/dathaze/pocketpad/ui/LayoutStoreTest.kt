package com.dathaze.pocketpad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saved layouts survive the arrival of per-control sizing.
 *
 * This is the part of the feature that can destroy work a user has already done, and do it
 * silently: a layout that fails to parse does not report an error, the controls simply come
 * back at their defaults as though the customisation had never happened. The old reader
 * required exactly three fields per entry, so a reader that demanded four would have thrown
 * away every layout saved by an earlier version, on the first launch after the update.
 */
class LayoutStoreTest {

    private val tolerance = 0.0001f

    @Test
    fun aLayoutSavedBeforeSizingExistedStillLoads() {
        // Exactly the format 2.6.1 and earlier wrote: key:x:y, no size.
        val old = "dpad:0.26:0.52;leftStick:0.28:0.56;btnL2:0.1:0.075"
        val read = LayoutStore.decode(old)
        assertEquals("every entry should survive", 3, read.size)
        assertEquals("dpad", read[0].key)
        assertEquals(0.26f, read[0].fx, tolerance)
        assertEquals(0.52f, read[0].fy, tolerance)
        read.forEach {
            assertEquals(
                "an old entry has no size, so it must load at the default",
                ControlScale.DEFAULT, it.scale, tolerance
            )
        }
    }

    @Test
    fun positionsAndSizesSurviveARoundTrip() {
        val written = listOf(
            LayoutStore.Placement("dpad", 0.25f, 0.5f, 1.4f),
            LayoutStore.Placement("btnL2", 0.1f, 0.08f, 0.75f),
            LayoutStore.Placement("gear", 0.5f, 0.2f),
        )
        val read = LayoutStore.decode(LayoutStore.encode(written))
        assertEquals(written.size, read.size)
        written.zip(read).forEach { (a, b) ->
            assertEquals(a.key, b.key)
            assertEquals(a.fx, b.fx, tolerance)
            assertEquals(a.fy, b.fy, tolerance)
            assertEquals(a.scale, b.scale, tolerance)
        }
    }

    @Test
    fun aMixtureOfOldAndNewEntriesLoads() {
        // What a part-upgraded preferences file would look like if one entry were rewritten.
        val read = LayoutStore.decode("dpad:0.26:0.52;btnL2:0.1:0.075:1.3")
        assertEquals(2, read.size)
        assertEquals(ControlScale.DEFAULT, read[0].scale, tolerance)
        assertEquals(1.3f, read[1].scale, tolerance)
    }

    /** One bad entry costs one control's position, not the whole layout. */
    @Test
    fun rubbishEntriesAreSkippedWithoutLosingTheGoodOnes() {
        val read = LayoutStore.decode(
            "dpad:0.26:0.52:1.0;;garbage;btnL2:notanumber:0.1;gear:0.5:0.2:1.1;onlykey"
        )
        assertEquals(listOf("dpad", "gear"), read.map { it.key })
    }

    @Test
    fun nothingSavedMeansNothingToApply() {
        assertTrue(LayoutStore.decode(null).isEmpty())
        assertTrue(LayoutStore.decode("").isEmpty())
        assertTrue(LayoutStore.decode("   ").isEmpty())
    }

    @Test
    fun anEmptyKeyIsNotAControl() {
        assertTrue(LayoutStore.decode(":0.5:0.5:1.0").isEmpty())
    }

    /**
     * A stored size out of range — a hand-edited preferences file, or a future version with
     * wider bounds — must not produce a control too small to hit or big enough to cover others.
     */
    @Test
    fun storedSizesAreClampedOnTheWayIn() {
        assertEquals(ControlScale.MAX, LayoutStore.decode("a:0:0:9.0")[0].scale, tolerance)
        assertEquals(ControlScale.MIN, LayoutStore.decode("a:0:0:0.01")[0].scale, tolerance)
        assertEquals(ControlScale.MIN, LayoutStore.decode("a:0:0:-3")[0].scale, tolerance)
    }

    @Test
    fun aNonsenseSizeFallsBackToTheDefaultRatherThanDroppingTheEntry() {
        val read = LayoutStore.decode("dpad:0.25:0.5:huge")
        assertEquals("the position is still worth keeping", 1, read.size)
        assertEquals(ControlScale.DEFAULT, read[0].scale, tolerance)
    }

    @Test
    fun infinitiesAndNaNsAreRejected() {
        assertTrue(LayoutStore.decode("a:NaN:0.5:1.0").isEmpty())
        assertTrue(LayoutStore.decode("a:Infinity:0.5:1.0").isEmpty())
        assertEquals(
            "a broken size should not take the position with it",
            ControlScale.DEFAULT, LayoutStore.decode("a:0.5:0.5:NaN")[0].scale, tolerance
        )
    }

    /** What the app writes must be what the app reads. */
    @Test
    fun theWrittenFormatIsTheOneTheReaderExpects() {
        val encoded = LayoutStore.encode(listOf(LayoutStore.Placement("dpad", 0.25f, 0.5f, 1.2f)))
        assertEquals(4, encoded.split(':').size)
        assertTrue("entries are separated by semicolons", !encoded.contains(';'))
        assertEquals(1, LayoutStore.decode(encoded).size)
    }
}
