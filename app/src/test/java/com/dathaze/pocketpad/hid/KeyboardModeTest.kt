package com.dathaze.pocketpad.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing between the three keyboard mappings, and not losing anyone's existing choice.
 *
 * The mode used to be one boolean. Builds in the field have written it, so an upgrade has to
 * read it — and, just as importantly, must never land a user in PC Keys by accident.
 */
class KeyboardModeTest {

    @Test
    fun offIsTheDefaultForAFreshInstall() {
        assertEquals(KeyboardMode.OFF, KeyboardMode.fromPrefs(null, legacyTvOn = false))
    }

    @Test
    fun anUpgradeWithTvKeysOnStaysOnTvKeys() {
        assertEquals(KeyboardMode.TV, KeyboardMode.fromPrefs(null, legacyTvOn = true))
    }

    @Test
    fun anUpgradeWithTvKeysOffStaysOff() {
        assertEquals(KeyboardMode.OFF, KeyboardMode.fromPrefs(null, legacyTvOn = false))
    }

    @Test
    fun nobodyIsEverMigratedIntoPcKeys() {
        // PC Keys is experimental and opt-in. No combination of the old preference can select it.
        listOf(true, false).forEach { legacy ->
            assertNotEquals(
                KeyboardMode.PC,
                KeyboardMode.fromPrefs(null, legacyTvOn = legacy),
            )
        }
    }

    @Test
    fun anExplicitChoiceWinsOverTheLegacyBoolean() {
        assertEquals(
            KeyboardMode.PC,
            KeyboardMode.fromPrefs(KeyboardMode.PC.storedValue, legacyTvOn = true),
        )
        assertEquals(
            KeyboardMode.OFF,
            KeyboardMode.fromPrefs(KeyboardMode.OFF.storedValue, legacyTvOn = true),
        )
    }

    @Test
    fun everyModeSurvivesARoundTrip() {
        KeyboardMode.entries.forEach { mode ->
            assertEquals(mode, KeyboardMode.fromPrefs(mode.storedValue, legacyTvOn = false))
        }
    }

    @Test
    fun anUnreadableStoredValueFallsBackRatherThanCrashing() {
        // A downgrade could leave something this build does not know.
        assertEquals(KeyboardMode.OFF, KeyboardMode.fromPrefs("SOMETHING_ELSE", false))
        assertEquals(KeyboardMode.TV, KeyboardMode.fromPrefs("SOMETHING_ELSE", true))
    }

    @Test
    fun theLegacyPreferenceKeyIsTheOneTheOldBuildWrote() {
        assertEquals("tv_navigation", KeyboardMode.LEGACY_TV_PREF)
    }

    @Test
    fun switchingBetweenPcAndTvGoesThroughDistinctMappings() {
        // The two modes are different mappings, not settings of one. TV Keys can only ever put
        // one key on ✕, and that key is Enter. PC Keys sends Enter *and* Z, so ✕ still confirms
        // in a menu while also being bindable as a game button — something the single-key TV
        // path cannot express at all.
        val cross = GamepadState().apply { setButton(HidConstants.BTN_CROSS, true) }
        val sent = PcKeyMap.keysFor(cross)
        assertEquals(2, sent.size)
        assertTrue("PC Keys adds a game key TV Keys never sends", sent.contains(PcKeyMap.KEY_Z))
        assertTrue("and keeps the confirm key", sent.contains(HidConstants.KEY_ENTER))
    }
}
