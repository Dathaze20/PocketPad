package com.dathaze.pocketpad.ui

import com.dathaze.pocketpad.hid.HidConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Per-style control mapping coverage for all five controller styles.
 *
 * `ControllerView` is an Android `View`, so it cannot be instantiated in a
 * plain JVM test. These tests therefore read the real source file and assert
 * what it declares. That is deliberate: the regression being guarded against
 * is somebody editing `applySkin()` or a control declaration and silently
 * changing which HID button a labelled control sends. Reading the
 * implementation catches that; a test that re-declared the mapping in Kotlin
 * would only restate the assumption.
 *
 * What this does NOT prove: that the view renders, hit-tests or dispatches
 * touches correctly. That needs an instrumented or real-device test.
 */
class ControllerStyleMappingTest {

    private lateinit var source: String

    /** control name -> (default label, HID button constant name) */
    private lateinit var declarations: Map<String, Pair<String, String>>

    /** style -> control name -> label assigned by applySkin */
    private lateinit var styleLabels: Map<String, Map<String, String>>

    /** style -> controls listed in activeControls */
    private lateinit var styleActive: Map<String, List<String>>

    private val buttonIndex = mapOf(
        "BTN_CROSS" to HidConstants.BTN_CROSS,
        "BTN_CIRCLE" to HidConstants.BTN_CIRCLE,
        "BTN_SQUARE" to HidConstants.BTN_SQUARE,
        "BTN_TRIANGLE" to HidConstants.BTN_TRIANGLE,
        "BTN_L1" to HidConstants.BTN_L1,
        "BTN_R1" to HidConstants.BTN_R1,
        "BTN_L2" to HidConstants.BTN_L2,
        "BTN_R2" to HidConstants.BTN_R2,
        "BTN_SHARE" to HidConstants.BTN_SHARE,
        "BTN_OPTIONS" to HidConstants.BTN_OPTIONS,
        "BTN_L3" to HidConstants.BTN_L3,
        "BTN_R3" to HidConstants.BTN_R3,
        "BTN_PS" to HidConstants.BTN_PS
    )

    /** Controls that carry no label and send no button of their own. */
    private val unlabelled = setOf("dpad", "leftStick", "rightStick", "gear")

    private val allStyles = listOf("PS", "XBOX", "SNES", "NES", "GAMEBOY")

    @Before
    fun parseControllerViewSource() {
        source = readControllerView()
        declarations = parseDeclarations(source)
        val skinBlocks = parseSkinBlocks(source)
        styleLabels = skinBlocks.mapValues { (_, body) ->
            Regex("""(\w+)\.label\s*=\s*"([^"]*)"""")
                .findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        }
        styleActive = skinBlocks.mapValues { (_, body) -> parseActiveControls(body) }
    }

    private fun readControllerView(): String {
        val candidates = listOf(
            "src/main/java/com/dathaze/pocketpad/ui/ControllerView.kt",
            "app/src/main/java/com/dathaze/pocketpad/ui/ControllerView.kt",
            "../app/src/main/java/com/dathaze/pocketpad/ui/ControllerView.kt"
        )
        val found = candidates.map(::File).firstOrNull { it.isFile }
        requireNotNull(found) {
            "ControllerView.kt not found from ${File(".").absolutePath}; tried $candidates"
        }
        return found.readText()
    }

    private fun parseDeclarations(src: String): Map<String, Pair<String, String>> =
        Regex("""private val (\w+)\s*=\s*PadButton\(\s*"([^"]*)",\s*HidConstants\.(BTN_\w+)""")
            .findAll(src)
            .associate { m -> m.groupValues[1] to (m.groupValues[2] to m.groupValues[3]) }

    /** style name -> the body of its branch in applySkin(). */
    private fun parseSkinBlocks(src: String): Map<String, String> {
        val start = src.indexOf("private fun applySkin()")
        require(start >= 0) { "applySkin() not found" }
        val end = src.indexOf("\n    private fun releaseAllPointers", start)
        require(end > start) { "could not find the end of applySkin()" }
        val body = src.substring(start, end)

        val headers = Regex("""^\s{12}(Skin\.[A-Z_]+(?:,\s*Skin\.[A-Z_]+)*)\s*->\s*\{""",
            RegexOption.MULTILINE).findAll(body).toList()
        require(headers.isNotEmpty()) { "no Skin branches found in applySkin()" }

        val blocks = mutableMapOf<String, String>()
        headers.forEachIndexed { i, match ->
            val from = match.range.last + 1
            val to = if (i + 1 < headers.size) headers[i + 1].range.first else body.length
            val text = body.substring(from, to)
            Regex("""Skin\.([A-Z_]+)""").findAll(match.groupValues[1]).forEach { skin ->
                blocks[skin.groupValues[1]] = text
            }
        }
        return blocks
    }

    private fun parseActiveControls(body: String): List<String> {
        val idx = body.indexOf("activeControls = listOf(")
        require(idx >= 0) { "a style branch does not assign activeControls" }
        val open = body.indexOf('(', idx)
        var depth = 0
        var close = open
        while (close < body.length) {
            if (body[close] == '(') depth++
            if (body[close] == ')') { depth--; if (depth == 0) break }
            close++
        }
        return body.substring(open + 1, close)
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------------ structure

    @Test
    fun allFiveStylesAreDefined() {
        assertEquals(
            "applySkin must handle exactly the five supported styles",
            allStyles.toSet(), styleLabels.keys
        )
    }

    @Test
    fun everyDeclaredControlMapsToAKnownButton() {
        assertTrue("no PadButton declarations parsed", declarations.isNotEmpty())
        declarations.forEach { (control, decl) ->
            assertTrue(
                "$control uses unrecognised constant ${decl.second}",
                buttonIndex.containsKey(decl.second)
            )
        }
    }

    /**
     * Face buttons are bound by position, and that binding is a constructor
     * `val` that no style can reassign. This is the invariant the whole
     * five-style design rests on.
     */
    @Test
    fun faceButtonsAreBoundByPositionNotByLabel() {
        assertEquals("BTN_CROSS", declarations["faceBottom"]!!.second)
        assertEquals("BTN_CIRCLE", declarations["faceRight"]!!.second)
        assertEquals("BTN_SQUARE", declarations["faceLeft"]!!.second)
        assertEquals("BTN_TRIANGLE", declarations["faceTop"]!!.second)
    }

    @Test
    fun noStyleReassignsAButtonConstant() {
        parseSkinBlocks(source).forEach { (style, body) ->
            assertTrue(
                "$style must not reassign a button index; styles change labels only",
                !Regex("""\.buttonIndex\s*=""").containsMatchIn(body)
            )
        }
    }

    @Test
    fun noTwoControlsInAStyleShareAHidButton() {
        styleActive.forEach { (style, controls) ->
            val used = controls.filter { declarations.containsKey(it) }
                .groupBy { declarations[it]!!.second }
                .filterValues { it.size > 1 }
            assertTrue("$style maps two controls to the same button: $used", used.isEmpty())
        }
    }

    /**
     * Every button a style shows must be given a label by that style. Without
     * this, switching styles can leave a control wearing the previous style's
     * text — an Xbox "LT" still showing after switching to PlayStation.
     */
    @Test
    fun everyVisibleButtonIsLabelledByItsOwnStyle() {
        styleActive.forEach { (style, controls) ->
            val labels = styleLabels[style]!!
            controls.filter { it !in unlabelled }.forEach { control ->
                assertTrue(
                    "$style shows $control but never sets its label",
                    labels.containsKey(control)
                )
            }
        }
    }

    @Test
    fun everyStyleShowsADpadHomeButtonAndSettingsGear() {
        styleActive.forEach { (style, controls) ->
            assertTrue("$style has no d-pad", controls.contains("dpad"))
            assertTrue("$style has no home button", controls.contains("btnHome"))
            assertTrue("$style has no settings gear", controls.contains("gear"))
        }
    }

    // ------------------------------------------- per-style expected mappings

    /** Assert a style's labels and the HID button each one sends. */
    private fun assertStyle(style: String, expected: Map<String, Pair<String, Int>>) {
        val labels = styleLabels[style] ?: error("style $style not parsed")
        expected.forEach { (control, spec) ->
            val (label, hidButton) = spec
            assertEquals("$style: $control label", label, labels[control])
            val constant = declarations[control]!!.second
            assertEquals(
                "$style: $control (\"$label\") must send HID button $hidButton",
                hidButton, buttonIndex[constant]!! + 1
            )
        }
    }

    @Test
    fun playStationStyleMapping() {
        assertStyle("PS", mapOf(
            "faceBottom" to ("✕" to 1),
            "faceRight" to ("○" to 2),
            "faceLeft" to ("□" to 3),
            "faceTop" to ("△" to 4),
            "btnL1" to ("L1" to 5),
            "btnR1" to ("R1" to 6),
            "btnL2" to ("L2" to 7),
            "btnR2" to ("R2" to 8),
            "btnShare" to ("SHARE" to 9),
            "btnOptions" to ("OPTIONS" to 10),
            "btnL3" to ("L3" to 11),
            "btnR3" to ("R3" to 12),
            "btnHome" to ("PS" to 13)
        ))
        assertEquals("PlayStation shows the full pad", 17, styleActive["PS"]!!.size)
    }

    @Test
    fun xboxStyleMapping() {
        assertStyle("XBOX", mapOf(
            "faceBottom" to ("A" to 1),
            "faceRight" to ("B" to 2),
            "faceLeft" to ("X" to 3),
            "faceTop" to ("Y" to 4),
            "btnL1" to ("LB" to 5),
            "btnR1" to ("RB" to 6),
            "btnL2" to ("LT" to 7),
            "btnR2" to ("RT" to 8),
            "btnShare" to ("VIEW" to 9),
            "btnOptions" to ("MENU" to 10),
            "btnL3" to ("LS" to 11),
            "btnR3" to ("RS" to 12)
        ))
        assertEquals("Xbox shows the full pad", 17, styleActive["XBOX"]!!.size)
    }

    @Test
    fun snesStyleMapping() {
        assertStyle("SNES", mapOf(
            "faceBottom" to ("B" to 1),
            "faceRight" to ("A" to 2),
            "faceLeft" to ("Y" to 3),
            "faceTop" to ("X" to 4),
            "btnL1" to ("L" to 5),
            "btnR1" to ("R" to 6),
            "btnShare" to ("SELECT" to 9),
            "btnOptions" to ("START" to 10)
        ))
        val active = styleActive["SNES"]!!
        assertTrue("SNES has no analog sticks", !active.contains("leftStick"))
        assertTrue("SNES has no second shoulder row", !active.contains("btnL2"))
        assertTrue("SNES has no stick clicks", !active.contains("btnL3"))
    }

    @Test
    fun nesStyleMapping() {
        assertStyle("NES", mapOf(
            "faceBottom" to ("B" to 1),
            "faceRight" to ("A" to 2),
            "btnShare" to ("SELECT" to 9),
            "btnOptions" to ("START" to 10)
        ))
        val active = styleActive["NES"]!!
        assertTrue("NES has only two face buttons", !active.contains("faceTop"))
        assertTrue("NES has only two face buttons", !active.contains("faceLeft"))
        assertTrue("NES has no shoulders", !active.contains("btnL1"))
    }

    @Test
    fun gameBoyStyleMapping() {
        assertStyle("GAMEBOY", mapOf(
            "faceBottom" to ("B" to 1),
            "faceRight" to ("A" to 2),
            "btnShare" to ("SELECT" to 9),
            "btnOptions" to ("START" to 10)
        ))
        assertEquals(
            "Game Boy and NES share one control set",
            styleActive["NES"], styleActive["GAMEBOY"]
        )
    }

    /** The control a given style prints [label] on. */
    private fun controlLabelled(style: String, label: String): String {
        val matches = styleLabels[style]!!.filterValues { it == label }.keys
        assertEquals("$style should print \"$label\" on exactly one control", 1, matches.size)
        return matches.first()
    }

    /** The 1-based HID button a control sends. */
    private fun hidButtonOf(control: String): Int =
        buttonIndex[declarations[control]!!.second]!! + 1

    /**
     * Nintendo's A and B sit in mirrored positions to Xbox's: Nintendo A is on
     * the right where Xbox B is, and Nintendo B is on the bottom where Xbox A
     * is. Because the mapping is positional, a Nintendo style's "A" therefore
     * sends the button a host calls B, and its "B" sends the button a host
     * calls A.
     *
     * That is intended, not a defect, and it is pinned here so it cannot
     * change silently. Everything below is derived from the parsed source and
     * compared against expectations stated independently of it.
     */
    @Test
    fun nintendoAAndBAreMirroredRelativeToXboxByDesign() {
        val xboxA = controlLabelled("XBOX", "A")
        val xboxB = controlLabelled("XBOX", "B")
        assertEquals("faceBottom", xboxA)
        assertEquals("faceRight", xboxB)

        for (style in listOf("SNES", "NES", "GAMEBOY")) {
            val a = controlLabelled(style, "A")
            val b = controlLabelled(style, "B")

            assertNotEquals("$style A must not share Xbox A's position", xboxA, a)
            assertEquals("$style A sits where Xbox B does", xboxB, a)
            assertEquals("$style B sits where Xbox A does", xboxA, b)

            // The consequence a user actually feels.
            assertEquals("$style \"A\" transmits the button a host calls B", 2, hidButtonOf(a))
            assertEquals("$style \"B\" transmits the button a host calls A", 1, hidButtonOf(b))
        }

        // PlayStation is not mirrored: its primary button is on the bottom.
        assertEquals("faceBottom", controlLabelled("PS", "\u2715"))
        assertEquals(1, hidButtonOf(controlLabelled("PS", "\u2715")))
    }

    /**
     * Switching style must never change what a given position transmits —
     * that is what lets a host keep one mapping across all five styles.
     */
    @Test
    fun aGivenPositionSendsTheSameButtonInEveryStyle() {
        val positions = listOf("faceBottom", "faceRight", "faceLeft", "faceTop")
        positions.forEach { position ->
            val sending = allStyles
                .filter { styleActive[it]!!.contains(position) }
                .map { hidButtonOf(position) }
                .toSet()
            assertEquals(
                "$position must send one button across every style that shows it",
                1, sending.size
            )
        }
        assertEquals(1, hidButtonOf("faceBottom"))
        assertEquals(2, hidButtonOf("faceRight"))
        assertEquals(3, hidButtonOf("faceLeft"))
        assertEquals(4, hidButtonOf("faceTop"))
    }

    // ---------------------------------------------------------- layouts

    @Test
    fun everyStyleHasBothPortraitAndLandscapeLayouts() {
        val layoutFor = mapOf(
            "PS" to "Ps", "XBOX" to "Xbox", "SNES" to "Snes",
            "NES" to "Retro", "GAMEBOY" to "Retro"
        )
        layoutFor.forEach { (style, prefix) ->
            listOf("Portrait", "Landscape").forEach { orientation ->
                assertTrue(
                    "$style is missing layout$prefix$orientation",
                    source.contains("private fun layout$prefix$orientation(w: Float, h: Float)")
                )
            }
        }
    }

    @Test
    fun bothOrientationsPlaceEveryControlTheStyleShows() {
        val layoutFor = mapOf(
            "PS" to "Ps", "XBOX" to "Xbox", "SNES" to "Snes",
            "NES" to "Retro", "GAMEBOY" to "Retro"
        )
        layoutFor.forEach { (style, prefix) ->
            listOf("Portrait", "Landscape").forEach { orientation ->
                val fn = "layout$prefix$orientation"
                val start = source.indexOf("private fun $fn(w: Float, h: Float)")
                val end = source.indexOf("\n    }", start)
                val body = source.substring(start, end)
                val placed = Regex("""place\((\w+),""").findAll(body)
                    .map { it.groupValues[1] }.toSet()
                styleActive[style]!!.forEach { control ->
                    assertTrue("$fn never places $control (shown by $style)", control in placed)
                }
            }
        }
    }
}
