package com.dathaze.pocketpad.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Registers the phone as a Bluetooth HID gamepad (peripheral role) and
 * pushes input reports to the connected host (your TV).
 *
 * Flow:
 *  1. [start] — obtains the HID_DEVICE profile proxy.
 *  2. registerApp — announces the phone as a gamepad via SDP.
 *  3. The TV pairs/connects (or we call [connectTo] for an already-paired TV).
 *  4. [send] — 7-byte input reports on every state change.
 *
 * All permission-guarded calls are wrapped: on Android 12+ the caller must
 * hold BLUETOOTH_CONNECT before calling [start].
 */
@SuppressLint("MissingPermission")
class HidGamepadManager(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onHidStatus(message: String)
        fun onHidConnected(device: BluetoothDevice?)
        fun onHidDisconnected()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var adapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var registered = false
    private var pendingConnect: BluetoothDevice? = null
    private var started = false

    val isConnected: Boolean get() = connectedDevice != null
    val currentDevice: BluetoothDevice? get() = connectedDevice

    fun start() {
        if (started) return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager.adapter
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            post { listener.onHidStatus("Bluetooth is off — turn it on and reopen the app") }
            return
        }
        started = true
        bt.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    fun stop() {
        val proxy = hidDevice
        if (proxy != null) {
            try {
                connectedDevice?.let { proxy.disconnect(it) }
                if (registered) proxy.unregisterApp()
            } catch (_: SecurityException) {
            }
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
        }
        hidDevice = null
        connectedDevice = null
        registered = false
        started = false
    }

    /** Bonded (paired) devices, for the "connect to paired device" picker. */
    fun bondedDevices(): List<BluetoothDevice> =
        try {
            adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

    fun connectTo(device: BluetoothDevice) {
        val proxy = hidDevice
        if (proxy != null && registered) {
            post { listener.onHidStatus("Connecting to ${safeName(device)}…") }
            try {
                proxy.connect(device)
            } catch (_: SecurityException) {
                post { listener.onHidStatus("Bluetooth permission missing") }
            }
        } else {
            // Connect as soon as registration completes.
            pendingConnect = device
            if (!started) start()
        }
    }

    fun disconnect() {
        val proxy = hidDevice ?: return
        try {
            connectedDevice?.let { proxy.disconnect(it) }
        } catch (_: SecurityException) {
        }
    }

    /** Send the current gamepad state to the connected host. */
    fun send(state: GamepadState) {
        val proxy = hidDevice ?: return
        val device = connectedDevice ?: return
        try {
            proxy.sendReport(device, HidConstants.REPORT_ID, state.toReport())
        } catch (_: SecurityException) {
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = proxy as BluetoothHidDevice
            post { listener.onHidStatus("Registering gamepad…") }
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "PocketPad Gamepad",
                "Phone touch + USB controller bridge",
                "Dathaze20",
                SUBCLASS_GAMEPAD,
                HidConstants.REPORT_DESCRIPTOR
            )
            try {
                proxy.registerApp(sdp, null, null, executor, hidCallback)
            } catch (_: SecurityException) {
                post { listener.onHidStatus("Bluetooth permission missing") }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = null
            registered = false
            connectedDevice = null
            post { listener.onHidDisconnected() }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            if (isRegistered) {
                post { listener.onHidStatus("Gamepad ready — waiting for TV") }
                val pending = pendingConnect
                pendingConnect = null
                if (pending != null) {
                    connectTo(pending)
                } else if (pluggedDevice != null) {
                    connectTo(pluggedDevice)
                }
            } else {
                post { listener.onHidStatus("Gamepad unregistered") }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    post { listener.onHidConnected(device) }
                }
                BluetoothProfile.STATE_CONNECTING ->
                    post { listener.onHidStatus("Connecting to ${safeName(device)}…") }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedDevice == device) connectedDevice = null
                    post { listener.onHidDisconnected() }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            // Host polled us — reply with a neutral report so it never stalls.
            try {
                hidDevice?.replyReport(device, type, id, GamepadState().toReport())
            } catch (_: SecurityException) {
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            try {
                hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun safeName(device: BluetoothDevice): String =
        try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            device.address
        }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private companion object {
        // Bluetooth Class of Device minor class: gamepad.
        const val SUBCLASS_GAMEPAD: Byte = 0x08
    }
}
