package com.dathaze.pocketpad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The settings list fits on one screen, in both orientations, at a large font size.
 *
 * That is a promise about arithmetic rather than about drawing, which is why [SettingsColumns]
 * has no Android types in it. The model at the bottom of this file measures the list the same
 * way [SettingsListView] does, from the same dimensions, so a change that broke the fit on a
 * real phone breaks it here first.
 *
 * The dimensions are duplicated here rather than read out of `dimens.xml`: a test that reads the
 * same file the code reads would agree with any value, including a wrong one. These are the
 * numbers the layout is *supposed* to have, and `theModelMatchesTheShippedDimensions` checks the
 * resource files still say so.
 */
class SettingsColumnsTest {

    private val tolerance = 0.0001f

    // ---- which column each group goes in ----------------------------------------------------

    @Test
    fun onePortraitColumnPutsEverythingInColumnZero() {
        val assignment = SettingsColumns.assignColumns(listOf(100, 200, 50, 150), columns = 1)
        assertTrue("portrait must be a single column", assignment.all { it == 0 })
    }

    @Test
    fun aSingleGroupIsNeverSplit() {
        val assignment = SettingsColumns.assignColumns(listOf(400), columns = 2)
        assertEquals(1, assignment.size)
        assertEquals(0, assignment[0])
    }

    @Test
    fun twoColumnsBalanceRatherThanCutInTheMiddle() {
        // The real shape of this menu: Connection, Controller, Appearance, System.
        val groups = listOf(146, 188, 62, 146)
        val assignment = SettingsColumns.assignColumns(groups, columns = 2)
        val tallest = SettingsColumns.tallestColumn(groups, assignment)

        // The best single cut is {Connection, Controller} against {Appearance, System} — 334
        // against 208. Balancing should beat that comfortably.
        assertTrue("balancing did no better than a plain cut: $tallest", tallest < 320)
        assertEquals("Connection, System", 0, assignment[0])
        assertEquals("Controller goes opposite", 1, assignment[1])
        assertEquals(1, assignment[2])
        assertEquals(0, assignment[3])
    }

    @Test
    fun bothColumnsGetUsed() {
        val groups = listOf(146, 188, 62, 146)
        val assignment = SettingsColumns.assignColumns(groups, columns = 2)
        assertTrue("nothing was put in the second column", assignment.any { it == 1 })
        assertTrue("nothing was left in the first column", assignment.any { it == 0 })
    }

    @Test
    fun theEarliestGroupsStayOnTheLeftWhenArrangementsTie() {
        // Four equal groups: several arrangements give the same height. The first group should
        // be on the left, so the list starts where it is read first.
        val assignment = SettingsColumns.assignColumns(listOf(100, 100, 100, 100), columns = 2)
        assertEquals(0, assignment[0])
        assertEquals(2, assignment.count { it == 0 })
    }

    @Test
    fun theTallerColumnIsWhatIsReported() {
        val groups = listOf(100, 50, 25)
        val assignment = intArrayOf(0, 1, 1)
        assertEquals(100, SettingsColumns.tallestColumn(groups, assignment))
    }

    @Test
    fun anEmptyListHasNoHeight() {
        assertEquals(0, SettingsColumns.tallestColumn(emptyList(), IntArray(0)))
        assertEquals(0, SettingsColumns.assignColumns(emptyList(), columns = 2).size)
    }

    @Test
    fun columnsShareTheWidthLeftOverByTheGap() {
        assertEquals(145, SettingsColumns.columnWidth(300, 2, 10))
        assertEquals(300, SettingsColumns.columnWidth(300, 1, 10))
        assertTrue("never narrower than a pixel", SettingsColumns.columnWidth(10, 2, 40) >= 1)
    }

    // ---- the shrink loop --------------------------------------------------------------------

    @Test
    fun somethingThatAlreadyFitsIsLeftAlone() {
        assertEquals(1f, SettingsColumns.nextScale(1f, measured = 500, available = 900), tolerance)
    }

    @Test
    fun somethingTooTallIsShrunkTowardsFitting() {
        val next = SettingsColumns.nextScale(1f, measured = 1000, available = 800)
        assertTrue("should shrink, got $next", next < 1f)
        assertTrue("should not overshoot, got $next", next > 0.75f)
    }

    @Test
    fun shrinkingNeverGrowsSomethingBack() {
        assertEquals(0.8f, SettingsColumns.nextScale(0.8f, 500, 900), tolerance)
    }

    @Test
    fun shrinkingStopsBeforeTheTextBecomesUnreadable() {
        val next = SettingsColumns.nextScale(1f, measured = 10_000, available = 100)
        assertEquals(SettingsColumns.MIN_SCALE, next, tolerance)
    }

    @Test
    fun theLoopConvergesWithinItsPassBudget() {
        var scale = 1f
        val natural = 1100
        val room = 900
        var passes = 0
        while (passes < SettingsColumns.MAX_PASSES) {
            val measured = (natural * scale).roundToInt()
            if (measured <= room) break
            val next = SettingsColumns.nextScale(scale, measured, room)
            if (next >= scale) break
            scale = next
            passes++
        }
        assertTrue("did not converge, ended at $scale after $passes passes", passes < 3)
        assertTrue("still too tall at $scale", (natural * scale).roundToInt() <= room)
    }

    // ---- does the real list fit? ------------------------------------------------------------

    /** A text line, as the layout measures it: the size times the user's font scale. */
    private fun line(sp: Float, fontScale: Float) = sp * fontScale * 1.3f

    /**
     * The four group heights of the real menu, in dp, at a given font scale.
     *
     * Connection is three rows when something is connected and two when not, because Disconnect
     * is only offered when there is a connection to end.
     */
    private fun groupHeights(
        fontScale: Float,
        connected: Boolean,
        icon: Int,
        minHeight: Int,
        padV: Int,
        labelSp: Float,
        subtitleSp: Float,
        headingSp: Float,
        headingPad: Int,
    ): List<Int> {
        val label = line(labelSp, fontScale)
        val subtitle = line(subtitleSp, fontScale)
        val plain = maxOf(minHeight.toFloat(), maxOf(icon.toFloat(), label) + 2 * padV)
        val withSubtitle =
            maxOf(minHeight.toFloat(), maxOf(icon.toFloat(), label + 1 + subtitle) + 2 * padV)
        val heading = line(headingSp, fontScale) + headingPad

        // Connection: connect and discoverable carry subtitles, disconnect does not.
        val connection = heading + withSubtitle * 2 + if (connected) plain else 0f
        // Controller: style, move and vibration are plain; TV menu keys carries a subtitle.
        val controller = heading + plain * 3 + withSubtitle
        val appearance = heading + plain
        val system = heading + plain * 3
        return listOf(connection, controller, appearance, system).map { it.roundToInt() }
    }

    /**
     * Portrait, on a Galaxy A17-sized screen: a single column, everything stacked.
     *
     * 891dp of height for the window, of which the sheet may use 92%, less about 71dp of handle,
     * header and padding. 1.4 is roughly the font scale the phone these screenshots came from is
     * running at, and it is the one that matters most.
     */
    @Test
    fun itFitsAPhoneInPortrait() {
        val budget = (891 * 0.92f).roundToInt() - 71
        listOf(1.0f, 1.15f, 1.3f, 1.4f).forEach { scale ->
            val groups = groupHeights(
                fontScale = scale, connected = true,
                icon = 32, minHeight = 48, padV = 5,
                labelSp = 14f, subtitleSp = 11f, headingSp = 10f, headingPad = 12,
            )
            val assignment = SettingsColumns.assignColumns(groups, columns = 1)
            val height = SettingsColumns.tallestColumn(groups, assignment)
            assertTrue(
                "portrait at font scale $scale needs ${height}dp but only has ${budget}dp",
                height <= budget
            )
        }
    }

    /**
     * Landscape, the case the single column could never handle: about 411dp of window height,
     * of which the sheet may use 92%, less around 58dp of chrome.
     */
    @Test
    fun itFitsAPhoneInLandscape() {
        val budget = (411 * 0.92f).roundToInt() - 58
        listOf(1.0f, 1.15f, 1.3f, 1.4f).forEach { scale ->
            val groups = groupHeights(
                fontScale = scale, connected = true,
                icon = 28, minHeight = 42, padV = 3,
                labelSp = 13f, subtitleSp = 10.5f, headingSp = 9.5f, headingPad = 8,
            )
            val assignment = SettingsColumns.assignColumns(groups, columns = 2)
            val height = SettingsColumns.tallestColumn(groups, assignment)
            assertTrue(
                "landscape at font scale $scale needs ${height}dp but only has ${budget}dp",
                height <= budget
            )
        }
    }

    /**
     * A single column could not do this, which is the whole reason for the second one. If this
     * ever stops being true, two columns are no longer earning their complexity.
     */
    @Test
    fun oneColumnWouldNotHaveFittedLandscape() {
        val budget = (411 * 0.92f).roundToInt() - 58
        val groups = groupHeights(
            fontScale = 1.0f, connected = true,
            icon = 28, minHeight = 42, padV = 3,
            labelSp = 13f, subtitleSp = 10.5f, headingSp = 9.5f, headingPad = 8,
        )
        val single = SettingsColumns.tallestColumn(groups, IntArray(groups.size))
        assertTrue("one column would have fitted after all: ${single}dp", single > budget)
    }

    /**
     * Past the scales the layout covers on its own, the shrinking has to finish the job without
     * bottoming out — if it bottomed out, the list would scroll after all.
     */
    @Test
    fun theShrinkingCoversTheLargestFontScales() {
        listOf(1.7f, 2.0f).forEach { scale ->
            val budget = (411 * 0.92f).roundToInt() - 58
            val groups = groupHeights(
                fontScale = scale, connected = true,
                icon = 28, minHeight = 42, padV = 3,
                labelSp = 13f, subtitleSp = 10.5f, headingSp = 9.5f, headingPad = 8,
            )
            val height = SettingsColumns.tallestColumn(
                groups, SettingsColumns.assignColumns(groups, columns = 2)
            )
            val shrunk = SettingsColumns.nextScale(1f, height, budget)
            assertTrue(
                "landscape at font scale $scale bottomed out at the shrink floor",
                shrunk > SettingsColumns.MIN_SCALE
            )
        }
    }

    /** Hiding Disconnect when nothing is connected can only ever make the list shorter. */
    @Test
    fun hidingDisconnectNeverMakesItTaller() {
        val metrics = { connected: Boolean ->
            groupHeights(
                fontScale = 1.4f, connected = connected,
                icon = 28, minHeight = 42, padV = 3,
                labelSp = 13f, subtitleSp = 10.5f, headingSp = 9.5f, headingPad = 8,
            )
        }
        val withRow = metrics(true)
        val without = metrics(false)
        val tall = { g: List<Int> ->
            SettingsColumns.tallestColumn(g, SettingsColumns.assignColumns(g, columns = 2))
        }
        assertTrue("hiding a row made the list taller", tall(without) <= tall(withRow))
    }

    /**
     * The model above is only worth anything if the shipped dimensions still match it. This
     * reads them back out of the resource files.
     */
    @Test
    fun theModelMatchesTheShippedDimensions() {
        val portrait = dimensions("res/values/dimens.xml")
        assertEquals("row_icon", 32f, portrait.getValue("row_icon"), tolerance)
        assertEquals("row_min_height", 48f, portrait.getValue("row_min_height"), tolerance)
        assertEquals("row_pad_v", 5f, portrait.getValue("row_pad_v"), tolerance)
        assertEquals("row_label_text", 14f, portrait.getValue("row_label_text"), tolerance)
        assertEquals("row_subtitle_text", 11f, portrait.getValue("row_subtitle_text"), tolerance)
        assertEquals("heading_text", 10f, portrait.getValue("heading_text"), tolerance)

        val landscape = dimensions("res/values-land/dimens.xml")
        assertEquals("row_icon", 28f, landscape.getValue("row_icon"), tolerance)
        assertEquals("row_min_height", 42f, landscape.getValue("row_min_height"), tolerance)
        assertEquals("row_pad_v", 3f, landscape.getValue("row_pad_v"), tolerance)
        assertEquals("row_label_text", 13f, landscape.getValue("row_label_text"), tolerance)
        assertEquals("row_subtitle_text", 10.5f, landscape.getValue("row_subtitle_text"), tolerance)
        assertEquals("heading_text", 9.5f, landscape.getValue("heading_text"), tolerance)
    }

    private fun dimensions(relative: String): Map<String, Float> {
        val xml = TestSources.read(relative)
        return Regex("""<dimen name="(\w+)">([\d.]+)(?:dp|sp)</dimen>""")
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].toFloat() }
    }
}
