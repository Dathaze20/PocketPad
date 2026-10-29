package com.dathaze.pocketpad.hid

/**
 * What PocketPad is allowed to do with the two runtime Bluetooth permissions it holds.
 *
 * `BLUETOOTH_CONNECT` and `BLUETOOTH_ADVERTISE` are **different capabilities**, and the app
 * used to treat them as one thing: it asked for both, then checked only `BLUETOOTH_CONNECT`
 * before reporting itself ready. Android grants them through a single "Nearby devices" prompt,
 * so in the common case both arrive together and the gap never shows — but they are separate
 * entries in the system settings, so a user can end up holding one and not the other. The app
 * would then say READY while being permanently unable to make the phone discoverable, and the
 * only symptom is that pairing never works and nothing explains why.
 *
 * They are modelled separately here so each answer is about the thing it actually gates:
 *
 *  - [canRunHid] — register as a controller and send input. Needs `BLUETOOTH_CONNECT`.
 *  - [canBecomeDiscoverable] — let a new host find the phone. Needs `BLUETOOTH_ADVERTISE`.
 *
 * Both are only runtime permissions from Android 12 (API 31). Below that the manifest grants
 * are enough and both are always true.
 */
data class BluetoothReadiness(
    val canRunHid: Boolean,
    val canBecomeDiscoverable: Boolean,
) {
    /** Nothing Bluetooth-related can work at all. */
    val isBlocked: Boolean get() = !canRunHid

    /** The controller works, but no *new* host can be paired with it. */
    val isPartiallyBlocked: Boolean get() = canRunHid && !canBecomeDiscoverable

    companion object {
        /** API level from which the two permissions became runtime permissions. */
        const val RUNTIME_PERMISSIONS_FROM = 31

        /**
         * Work out what is permitted.
         *
         * [hasConnect] and [hasAdvertise] are the results of checking the two permissions;
         * they are ignored below [RUNTIME_PERMISSIONS_FROM], where the manifest grants hold.
         */
        fun of(sdkInt: Int, hasConnect: Boolean, hasAdvertise: Boolean): BluetoothReadiness =
            if (sdkInt < RUNTIME_PERMISSIONS_FROM) {
                BluetoothReadiness(canRunHid = true, canBecomeDiscoverable = true)
            } else {
                BluetoothReadiness(canRunHid = hasConnect, canBecomeDiscoverable = hasAdvertise)
            }
    }
}
