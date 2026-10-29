package com.dathaze.pocketpad.hid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two runtime Bluetooth permissions gate different things.
 *
 * The regression being guarded: the app asked for `BLUETOOTH_CONNECT` and
 * `BLUETOOTH_ADVERTISE` together, then checked only the first before reporting itself ready.
 * Holding one without the other left it claiming to work while no new host could ever find
 * the phone, with nothing on screen to explain it.
 */
class BluetoothReadinessTest {

    private fun on12(connect: Boolean, advertise: Boolean) =
        BluetoothReadiness.of(sdkInt = 31, hasConnect = connect, hasAdvertise = advertise)

    @Test
    fun bothGrantedMeansEverythingWorks() {
        val r = on12(connect = true, advertise = true)
        assertTrue(r.canRunHid)
        assertTrue(r.canBecomeDiscoverable)
        assertFalse(r.isBlocked)
        assertFalse(r.isPartiallyBlocked)
    }

    /** The case the old single check could not see. */
    @Test
    fun connectWithoutAdvertiseRunsTheControllerButCannotBeFound() {
        val r = on12(connect = true, advertise = false)
        assertTrue("the controller itself still works", r.canRunHid)
        assertFalse("but no new host can discover the phone", r.canBecomeDiscoverable)
        assertFalse(r.isBlocked)
        assertTrue("and the app must say so", r.isPartiallyBlocked)
    }

    @Test
    fun advertiseWithoutConnectCannotRunTheControllerAtAll() {
        val r = on12(connect = false, advertise = true)
        assertFalse(r.canRunHid)
        assertTrue(r.isBlocked)
        // Not "partially" blocked — nothing works, so the stronger message is the right one.
        assertFalse(r.isPartiallyBlocked)
    }

    @Test
    fun neitherGrantedIsBlocked() {
        val r = on12(connect = false, advertise = false)
        assertFalse(r.canRunHid)
        assertFalse(r.canBecomeDiscoverable)
        assertTrue(r.isBlocked)
    }

    @Test
    fun discoverabilityIsNeverReportedAsAvailableWithoutTheAdvertiseGrant() {
        // The specific false "ready" the old code produced: whatever else is true, a missing
        // advertise grant must never read as "we can be discovered".
        for (connect in listOf(true, false)) {
            assertFalse(on12(connect = connect, advertise = false).canBecomeDiscoverable)
        }
    }

    @Test
    fun beforeAndroid12TheManifestGrantsAreEnough() {
        // API 30 and below: both are install-time, so a runtime check says nothing and must
        // not be allowed to block the app.
        for (sdk in listOf(28, 29, 30)) {
            val r = BluetoothReadiness.of(sdk, hasConnect = false, hasAdvertise = false)
            assertTrue("sdk $sdk must not be blocked", r.canRunHid)
            assertTrue("sdk $sdk must still be discoverable", r.canBecomeDiscoverable)
            assertFalse(r.isBlocked)
        }
    }

    @Test
    fun theRuntimeBoundaryIsAndroid12() {
        assertTrue(BluetoothReadiness.RUNTIME_PERMISSIONS_FROM == 31)
        // 30 ignores the flags, 31 honours them — the boundary itself is the thing that must
        // not drift, because moving it either blocks old phones or silently un-checks new ones.
        assertTrue(BluetoothReadiness.of(30, false, false).canRunHid)
        assertFalse(BluetoothReadiness.of(31, false, false).canRunHid)
    }
}
