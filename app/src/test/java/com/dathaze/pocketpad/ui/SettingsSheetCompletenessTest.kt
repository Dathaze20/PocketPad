package com.dathaze.pocketpad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing was dropped when the settings list became a sheet, or when it learned to fit.
 *
 * This is a source check, which is the weaker kind of test and is used here on purpose. The
 * sheet is a `Dialog` full of inflated views, so it cannot be constructed in a JVM test, and
 * the thing most worth guarding is not behaviour but *completeness*: a redesign that quietly
 * loses "Make phone discoverable" would look perfectly fine and leave a user unable to pair.
 *
 * Behavioural guarantees in this project are tested by running code — see `ReportQueueTest`
 * and `ControlGeometryTest`. The sizing that keeps the list on one screen is arithmetic and is
 * tested that way, in `SettingsColumnsTest`. This one answers "is every action still wired up,
 * and can the text still be read?".
 */
class SettingsSheetCompletenessTest {

    private fun read(relative: String) = TestSources.read(relative)

    private val sheet by lazy { read("java/com/dathaze/pocketpad/ui/SettingsSheet.kt") }
    private val activity by lazy { read("java/com/dathaze/pocketpad/MainActivity.kt") }
    private val strings by lazy { read("res/values/strings.xml") }
    private val rowLayout by lazy { read("res/layout/row_settings.xml") }

    /** Every action the AlertDialog list used to offer, and what it called. */
    private val originalActions = mapOf(
        "action_connect_paired" to "showPairedDevicePicker",
        "action_discoverable" to "makeDiscoverable",
        "action_skin" to "showSkinPicker",
        "action_edit_layout" to "startLayoutEdit",
        "action_background" to "showBackgroundMenu",
        "action_haptics" to "showHapticsPicker",
        "action_bt_settings" to "ACTION_BLUETOOTH_SETTINGS",
        "action_disconnect" to "disconnect",
        "action_check_updates" to "checkForUpdates",
        "action_help" to "showHelp",
    )

    @Test
    fun everyActionFromTheOldMenuIsStillOffered() {
        originalActions.keys.forEach { key ->
            assertTrue(
                "the sheet no longer offers R.string.$key",
                sheet.contains("R.string.$key") || key == "action_bt_settings"
            )
        }
    }

    @Test
    fun everyActionIsStillWiredToTheSameHandler() {
        originalActions.forEach { (key, handler) ->
            assertTrue(
                "$key is listed but nothing calls $handler any more",
                activity.contains(handler)
            )
        }
    }

    /**
     * The keyboard-keys row survived gaining a third choice.
     *
     * It was a switch while the only options were TV menu keys on and off. PC Keys makes three,
     * which a switch cannot express, so it became a value row with a picker — the same shape as
     * Style and Vibration. What must not happen is the row quietly disappearing, or TV menus
     * ceasing to be one of the things it can select.
     */
    @Test
    fun keyboardKeysSurvivedAsAModeChoice() {
        assertTrue("row missing", sheet.contains("R.string.sheet_keyboard_mode"))
        assertTrue("no value shown", sheet.contains("value = host.keyboardModeLabel()"))
        assertTrue("not wired", activity.contains("showKeyboardModePicker"))
        assertTrue("TV menus is no longer selectable", activity.contains("KeyboardMode.TV"))
        assertTrue("PC keys is not selectable", activity.contains("KeyboardMode.PC"))
    }

    @Test
    fun allFourGroupsArePresent() {
        listOf("connection", "controller", "appearance", "system").forEach {
            assertTrue("group $it missing", sheet.contains("R.string.sheet_group_$it"))
            assertTrue("string for group $it missing", strings.contains("sheet_group_$it"))
        }
    }

    @Test
    fun theHostExposesAHandlerForEveryRow() {
        val declared = Regex("""fun (on[A-Z]\w*)\(""").findAll(sheet).map { it.groupValues[1] }.toSet()
        val expected = setOf(
            "onConnectToPaired", "onMakeDiscoverable", "onDisconnect",
            "onChooseStyle", "onMoveButtons", "onChooseVibration", "onToggleTvMenuKeys",
            "onBackgroundPicture",
            "onCheckForUpdates", "onBluetoothSettings", "onHelp",
        )
        expected.forEach { assertTrue("Host is missing $it", it in declared) }
        expected.forEach {
            assertTrue("MainActivity does not implement $it", activity.contains("override fun $it"))
        }
    }

    /**
     * The sheet must report the live HID connection, never the bonded-device list — a
     * remembered pairing is not a connection, and the status line exists to be trusted.
     */
    @Test
    fun theConnectionStatusReadsTheLiveLink() {
        val body = activity.substringAfter("override fun connectedDeviceName()")
            .substringBefore("override fun controllerStyleLabel")
        assertTrue("must consult the live connection", body.contains("hidManager.isConnected"))
        assertTrue("must use the connected device", body.contains("hidManager.currentDevice"))
        assertTrue(
            "must not derive status from the pairing list",
            !body.contains("bondedDevices")
        )
    }

    @Test
    fun everyStringTheSheetReferencesExists() {
        Regex("""R\.string\.(\w+)""").findAll(sheet).map { it.groupValues[1] }.distinct()
            .forEach { assertTrue("missing string resource: $it", strings.contains("\"$it\"")) }
    }

    /**
     * Scoped to `onOpenSettings` on purpose: the background submenu is still a plain list
     * dialog and should stay one. Only the top-level menu became a sheet.
     */
    @Test
    fun theTopLevelMenuIsNoLongerAPlainList() {
        val body = activity.substringAfter("override fun onOpenSettings()")
            .substringBefore("private val settingsSheet")
        assertTrue("onOpenSettings still builds a setItems list", !body.contains("setItems"))
        assertTrue("the sheet is not being shown", body.contains("settingsSheet.show"))
    }

    @Test
    fun theBackgroundSubmenuIsLeftAlone() {
        assertTrue(
            "showBackgroundMenu should still be its own small list dialog",
            activity.contains("fun showBackgroundMenu") && activity.contains("setItems(items)")
        )
    }

    // ---- fitting on one screen --------------------------------------------------------------

    /**
     * No word may ever be broken in half.
     *
     * A `TextView` splits a word when it is trying to wrap and the word is wider than the line,
     * which is how "Discoverable" became "Discovera / ble". One line cannot wrap, so it cannot
     * break; a label too long for its space loses its tail to an ellipsis instead. Every piece
     * of text in a row has to be pinned to one line for that to hold.
     */
    @Test
    fun noTextInARowCanWrapAndThereforeNoneCanBreakMidWord() {
        val blocks = Regex("""<TextView\b[^>]*>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(rowLayout)
            .map { it.value }
            .filter { it.contains("@+id/row") }
            .toList()
        assertTrue("no row text views found — has the layout moved?", blocks.size >= 4)

        blocks.forEach { block ->
            val id = Regex("""@\+id/(\w+)""").find(block)?.groupValues?.get(1) ?: "?"
            assertTrue("$id is not pinned to one line", block.contains("""maxLines="1""""))
        }
        listOf("rowLabel", "rowSubtitle").forEach { id ->
            val block = blocks.first { it.contains("@+id/$id") }
            assertTrue("$id must ellipsize rather than clip", block.contains("""ellipsize="end""""))
            assertTrue(
                "$id must not let the layout divide a word",
                block.contains("""hyphenationFrequency="none"""")
            )
        }
    }

    /**
     * A fixed row height cannot survive a large font scale — the text grows out of the box and
     * is clipped. It has to be a minimum that the text can push past.
     */
    @Test
    fun theRowHeightIsAMinimumNotAFixedSize() {
        val root = rowLayout.substringAfter("SettingsRowView").substringBefore(">")
        assertTrue("the row should wrap its content", root.contains("""layout_height="wrap_content""""))
        assertTrue("and have a minimum height", root.contains("""minHeight="@dimen/row_min_height""""))
    }

    /** No label should carry a trailing ellipsis of its own; truncation adds one if needed. */
    @Test
    fun noRowLabelEndsInAnEllipsis() {
        val labelled = Regex("""R\.string\.(\w+)""").findAll(sheet)
            .map { it.groupValues[1] }
            .filter { it.startsWith("action_") || it == "sheet_keyboard_mode" }
            .distinct()
        labelled.forEach { name ->
            val value = Regex("""<string name="$name">(.*?)</string>""")
                .find(strings)?.groupValues?.get(1)
            requireNotNull(value) { "string $name is missing" }
            assertTrue("$name still ends in an ellipsis: \"$value\"", !value.trimEnd().endsWith("…"))
        }
    }

    /**
     * The orientation must be answered by resource qualifiers, not by code — and, crucially, the
     * sheet has to be told to rebuild when the phone turns.
     *
     * `MainActivity` declares `configChanges` for orientation, so Android recreates nothing on
     * rotation and an open dialog keeps whatever layout it was inflated with. Without the
     * rebuild, the portrait list is simply stretched across a landscape window — the wrong
     * column count and the wrong row metrics, however correct the `-land` resources are.
     */
    @Test
    fun theSheetIsRebuiltWhenThePhoneIsTurned() {
        val manifest = read("AndroidManifest.xml")
        assertTrue(
            "this test only matters while the activity handles orientation itself",
            manifest.contains("android:configChanges") && manifest.contains("orientation")
        )
        assertTrue(
            "MainActivity must override onConfigurationChanged",
            activity.contains("override fun onConfigurationChanged")
        )
        assertTrue(
            "and tell the settings sheet about it",
            activity.contains("settingsSheet.onConfigurationChanged()")
        )
        assertTrue(
            "the sheet must actually rebuild rather than ignore it",
            sheet.contains("fun onConfigurationChanged")
        )
    }

    @Test
    fun theColumnCountComesFromResourcesRatherThanFromCode() {
        assertTrue(
            "the sheet should read R.integer.settings_list_columns",
            sheet.contains("R.integer.settings_list_columns")
        )
        assertTrue(
            "the sheet should not be branching on orientation itself",
            !sheet.contains("Configuration.ORIENTATION")
        )
        assertEquals("portrait should be one column", 1, columnsIn("res/values/integers.xml"))
        assertEquals("landscape should be two", 2, columnsIn("res/values-land/integers.xml"))
    }

    private fun columnsIn(path: String): Int {
        val match = Regex("""<integer name="settings_list_columns">(\d+)</integer>""")
            .find(read(path))
        requireNotNull(match) { "settings_list_columns not declared in $path" }
        return match.groupValues[1].toInt()
    }

    /** Offering "Disconnect" with nothing connected is an action that cannot do anything. */
    @Test
    fun disconnectIsOnlyOfferedWhenSomethingIsConnected() {
        val body = sheet.substringAfter("private fun buildRows").substringBefore("private fun group")
        val guard = body.substringBefore("R.string.action_disconnect")
        assertTrue(
            "the Disconnect row must be behind a live-connection check",
            guard.contains("host.connectedDeviceName() != null")
        )
    }
}
