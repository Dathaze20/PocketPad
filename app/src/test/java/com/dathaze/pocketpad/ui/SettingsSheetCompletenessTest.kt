package com.dathaze.pocketpad.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing was dropped when the settings list became a sheet.
 *
 * This is a source check, which is the weaker kind of test and is used here on purpose. The
 * sheet is a `Dialog` full of inflated views, so it cannot be constructed in a JVM test, and
 * the thing most worth guarding is not behaviour but *completeness*: a redesign that quietly
 * loses "Make phone discoverable" would look perfectly fine and leave a user unable to pair.
 *
 * Behavioural guarantees in this project are tested by running code — see `ReportQueueTest`
 * and `ControlGeometryTest`. This one only answers "is every action still wired up?".
 */
class SettingsSheetCompletenessTest {

    private fun read(relative: String): String {
        val candidates = listOf(
            "src/main/$relative",
            "app/src/main/$relative",
            "../app/src/main/$relative",
        )
        val found = candidates.map(::File).firstOrNull { it.isFile }
        requireNotNull(found) { "$relative not found from ${File(".").absolutePath}" }
        return found.readText()
    }

    private val sheet by lazy { read("java/com/dathaze/pocketpad/ui/SettingsSheet.kt") }
    private val activity by lazy { read("java/com/dathaze/pocketpad/MainActivity.kt") }
    private val strings by lazy { read("res/values/strings.xml") }

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

    @Test
    fun tvMenuKeysSurvivedAsAToggle() {
        assertTrue("row missing", sheet.contains("R.string.sheet_tv_menu_keys"))
        assertTrue("not wired", activity.contains("toggleTvNavigation"))
        assertTrue("not shown as a switch", sheet.contains("switchOn = host.tvMenuKeysOn()"))
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
}
