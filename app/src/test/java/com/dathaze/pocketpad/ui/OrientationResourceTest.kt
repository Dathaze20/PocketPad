package com.dathaze.pocketpad.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Orientation-dependent resources are only trustworthy where something re-reads them.
 *
 * `MainActivity` declares `android:configChanges="orientation|screenSize|..."`, which is right
 * for a game pad — rotating mid-session should not tear the activity down — but it has a
 * consequence that has now caused three separate faults in this app:
 *
 * **Nothing is recreated on rotation, so nothing is inflated again.** A value a layout took from
 * `res/values` on a portrait launch stays at that value in landscape for the life of the
 * process. The `-land` variant is never consulted. It produced a settings sheet laid out for
 * portrait and stretched across a landscape window, and an editor panel that spanned the whole
 * screen with 8dp margins meant for portrait.
 *
 * There are only two safe patterns, and this test insists on one of them:
 *
 *  - **Re-inflate.** `sheet_settings`, `row_settings` and `row_settings_group` are built fresh
 *    every time the settings sheet is shown, and the sheet is rebuilt on rotation, so their
 *    `-land` values are always current.
 *  - **Re-read in code.** `activity_main` is inflated exactly once, by `setContentView`. Every
 *    orientation-dependent value in it must therefore also be read from `MainActivity` at the
 *    point of use, where `resources` reflects the configuration in force right now.
 *
 * A layout that does neither is the bug, and it is invisible until someone rotates a phone.
 */
class OrientationResourceTest {

    private fun read(relative: String) = TestSources.read(relative)

    private fun namesIn(path: String, kind: String): Set<String> {
        val file = listOf("app/src/main/$path", "src/main/$path", "../app/src/main/$path")
            .map(::File).firstOrNull { it.isFile } ?: return emptySet()
        return Regex("""<$kind name="([^"]+)"""").findAll(file.readText())
            .map { it.groupValues[1] }.toSet()
    }

    /** Everything that takes a different value in landscape. */
    private val orientationDependent: Map<String, Set<String>> by lazy {
        mapOf(
            "dimen" to namesIn("res/values-land/dimens.xml", "dimen"),
            "integer" to namesIn("res/values-land/integers.xml", "integer"),
            "bool" to namesIn("res/values-land/bools.xml", "bool"),
        )
    }

    /** What a layout asks for that changes with orientation. */
    private fun orientationDependentUsesIn(layout: String): Set<String> {
        val xml = read("res/layout/$layout")
        val used = mutableSetOf<String>()
        orientationDependent.forEach { (kind, names) ->
            Regex("""@$kind/(\w+)""").findAll(xml).forEach {
                if (it.groupValues[1] in names) used += it.groupValues[1]
            }
        }
        return used
    }

    @Test
    fun theActivityStillHandlesRotationItself() {
        val manifest = read("AndroidManifest.xml")
        assertTrue(
            "if the activity is recreated on rotation again, this whole test is moot — but so " +
                "is the code it guards, which should then be simplified rather than left",
            manifest.contains("android:configChanges") && manifest.contains("orientation")
        )
    }

    /**
     * The one layout that is never inflated twice. Anything in it that differs by orientation
     * has to be applied from code as well, or it is frozen at whatever the launch orientation
     * happened to be.
     */
    @Test
    fun everyOrientationDependentValueInTheMainLayoutIsAlsoReadFromCode() {
        val activity = read("java/com/dathaze/pocketpad/MainActivity.kt")
        val used = orientationDependentUsesIn("activity_main.xml")
        assertTrue("no orientation-dependent values found — has the layout moved?", used.isNotEmpty())
        used.forEach { name ->
            assertTrue(
                "activity_main.xml uses $name, which differs in landscape, but MainActivity " +
                    "never re-reads it — so it keeps the launch orientation's value for ever",
                activity.contains(name)
            )
        }
    }

    /**
     * The settings sheet's layouts are the other safe pattern: they are rebuilt, so they may use
     * orientation-dependent values freely. This checks the rebuild is actually wired up, because
     * that is the only thing making them safe.
     */
    @Test
    fun theSheetLayoutsAreRebuiltOnRotation() {
        val sheetLayouts = listOf("sheet_settings.xml", "row_settings.xml", "row_settings_group.xml")
        assertTrue(
            "these layouts are only safe because they are re-inflated; if they stopped using " +
                "orientation-dependent values this test should be re-examined, not deleted",
            sheetLayouts.any { orientationDependentUsesIn(it).isNotEmpty() }
        )
        val sheet = read("java/com/dathaze/pocketpad/ui/SettingsSheet.kt")
        val activity = read("java/com/dathaze/pocketpad/MainActivity.kt")
        assertTrue("the sheet must rebuild itself", sheet.contains("fun onConfigurationChanged"))
        assertTrue(
            "and the activity must tell it to",
            activity.contains("settingsSheet.onConfigurationChanged()")
        )
        assertTrue(
            "the rebuild must re-inflate, not just redraw",
            sheet.contains("R.layout.sheet_settings")
        )
    }

    /**
     * The panel's width must not be taken from a view's measured size.
     *
     * After a rotation the parent is re-laid-out asynchronously, so its width can still be the
     * previous orientation's when the panel is placed — which is exactly how the panel came up
     * at 55% of the portrait width while in landscape, about a quarter of the screen. The
     * configuration carries the new size before `onConfigurationChanged` is even called.
     */
    @Test
    fun thePanelSizesItselfFromTheConfigurationNotFromAMeasuredView() {
        val activity = read("java/com/dathaze/pocketpad/MainActivity.kt")
        val body = activity.substringAfter("private fun moveEditPanelAwayFrom")
            .substringBefore("private fun setUpEditPanelDrag")
        assertTrue(
            "the width should come from the configuration",
            body.contains("configuration.screenWidthDp")
        )
        assertTrue(
            "and not from the parent view, whose width lags a rotation",
            !body.contains("parent as? android.view.View)?.width") &&
                !body.contains("parentWidth")
        )
    }
}
