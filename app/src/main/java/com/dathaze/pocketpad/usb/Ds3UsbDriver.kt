package com.dathaze.pocketpad.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.dathaze.pocketpad.hid.GamepadState
import com.dathaze.pocketpad.hid.HidConstants

/**
 * USB host driver for the Sony DualShock 3 (PS3 controller) plugged in via a
 * USB-C OTG adapter.
 *
 * The DS3 does not stream input reports over USB until it receives a special
 * "enable operational mode" command (HID SET_REPORT, feature report 0xF4,
 * payload 42 0C 00 00). After that it delivers ~49-byte input reports on the
 * interrupt IN endpoint, which we parse and hand to the app as a
 * [GamepadState].
 *
 * Note: on many Android builds the kernel's hid-sony driver claims the DS3
 * and exposes it as a normal input device — in that case the app receives its
 * input through regular KeyEvents/MotionEvents instead and this raw driver is
 * simply never opened. Both paths are supported.
 */
class Ds3UsbDriver(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onDs3State(state: GamepadState)
        fun onDs3Status(connected: Boolean, message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var readerThread: Thread? = null
    @Volatile private var running = false
    private var permissionReceiverRegistered = false
    private var detachReceiverRegistered = false

    val isActive: Boolean get() = running

    fun isDs3(device: UsbDevice): Boolean =
        device.vendorId == VENDOR_SONY && device.productId == PRODUCT_DS3

    /** Look for an already-attached DS3 (e.g. plugged in before app launch). */
    fun findAttachedDs3(): UsbDevice? = usbManager.deviceList.values.firstOrNull { isDs3(it) }

    /** Open the pad if we have USB permission, otherwise ask the user for it. */
    fun openOrRequestPermission(device: UsbDevice) {
        if (!isDs3(device)) return
        if (usbManager.hasPermission(device)) {
            open(device)
        } else {
            registerPermissionReceiver()
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(
                device,
                PendingIntent.getBroadcast(context, 0, intent, flags)
            )
        }
    }

    fun open(device: UsbDevice) {
        close()
        val iface = findHidInterface(device) ?: run {
            status(false, "PS3 pad: no HID interface found")
            return
        }
        val conn = usbManager.openDevice(device) ?: run {
            status(false, "PS3 pad: could not open USB device")
            return
        }
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            status(false, "PS3 pad: could not claim interface")
            return
        }
        val endpoint = findInterruptIn(iface) ?: run {
            conn.releaseInterface(iface)
            conn.close()
            status(false, "PS3 pad: no input endpoint")
            return
        }

        // Wake the DS3: SET_REPORT (0x09), feature report 0xF4.
        val enable = byteArrayOf(0x42, 0x0C, 0x00, 0x00)
        conn.controlTransfer(
            0x21,           // host-to-device | class | interface
            0x09,           // SET_REPORT
            0x03F4,         // (feature << 8) | report id 0xF4
            iface.id,
            enable, enable.size, 1000
        )

        connection = conn
        claimedInterface = iface
        running = true
        registerDetachReceiver()
        status(true, "PS3 controller connected")

        readerThread = Thread({ readLoop(conn, endpoint) }, "ds3-reader").also { it.start() }
    }

    fun close() {
        running = false
        readerThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
            }
        }
        readerThread = null
        val conn = connection
        val iface = claimedInterface
        connection = null
        claimedInterface = null
        if (conn != null) {
            if (iface != null) conn.releaseInterface(iface)
            conn.close()
        }
    }

    fun release() {
        close()
        if (permissionReceiverRegistered) {
            try {
                context.unregisterReceiver(permissionReceiver)
            } catch (_: IllegalArgumentException) {
            }
            permissionReceiverRegistered = false
        }
        if (detachReceiverRegistered) {
            try {
                context.unregisterReceiver(detachReceiver)
            } catch (_: IllegalArgumentException) {
            }
            detachReceiverRegistered = false
        }
    }

    private fun registerDetachReceiver() {
        if (detachReceiverRegistered) return
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(detachReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(detachReceiver, filter)
        }
        detachReceiverRegistered = true
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device: UsbDevice? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (device != null && isDs3(device) && running) {
                close()
                status(false, "PS3 controller disconnected")
            }
        }
    }

    private fun readLoop(conn: UsbDeviceConnection, endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        val state = GamepadState()
        var failures = 0
        while (running) {
            val read = conn.bulkTransfer(endpoint, buffer, buffer.size, 250)
            if (read > 0) {
                failures = 0
                if (parseReport(buffer, read, state)) {
                    val snapshot = GamepadState().apply { copyFrom(state) }
                    mainHandler.post { listener.onDs3State(snapshot) }
                }
            } else if (read < 0) {
                // Timeouts return -1 too; only give up after a sustained run of
                // failures once the device disappears from the device list.
                failures++
                if (failures > 40 && findAttachedDs3() == null) break
            }
        }
        if (running) {
            running = false
            status(false, "PS3 controller disconnected")
        }
    }

    /**
     * DS3 USB input report layout (report id 0x01 at byte 0):
     *  byte 2: select 0x01, L3 0x02, R3 0x04, start 0x08,
     *          dpad up 0x10, right 0x20, down 0x40, left 0x80
     *  byte 3: L2 0x01, R2 0x02, L1 0x04, R1 0x08,
     *          triangle 0x10, circle 0x20, cross 0x40, square 0x80
     *  byte 4: PS button 0x01
     *  bytes 6..9: left X, left Y, right X, right Y (0..255)
     *  bytes 18-19: analog pressure for L2 and R2 (0..255)
     */
    private fun parseReport(data: ByteArray, length: Int, out: GamepadState): Boolean {
        if (length < 10 || data[0] != 0x01.toByte()) return false
        val b1 = data[2].toInt() and 0xFF
        val b2 = data[3].toInt() and 0xFF
        val b3 = data[4].toInt() and 0xFF

        out.buttons = 0
        out.setButton(HidConstants.BTN_SHARE, b1 and 0x01 != 0)
        out.setButton(HidConstants.BTN_L3, b1 and 0x02 != 0)
        out.setButton(HidConstants.BTN_R3, b1 and 0x04 != 0)
        out.setButton(HidConstants.BTN_OPTIONS, b1 and 0x08 != 0)
        out.setButton(HidConstants.BTN_L2, b2 and 0x01 != 0)
        out.setButton(HidConstants.BTN_R2, b2 and 0x02 != 0)
        out.setButton(HidConstants.BTN_L1, b2 and 0x04 != 0)
        out.setButton(HidConstants.BTN_R1, b2 and 0x08 != 0)
        out.setButton(HidConstants.BTN_TRIANGLE, b2 and 0x10 != 0)
        out.setButton(HidConstants.BTN_CIRCLE, b2 and 0x20 != 0)
        out.setButton(HidConstants.BTN_CROSS, b2 and 0x40 != 0)
        out.setButton(HidConstants.BTN_SQUARE, b2 and 0x80 != 0)
        out.setButton(HidConstants.BTN_PS, b3 and 0x01 != 0)

        val up = b1 and 0x10 != 0
        val right = b1 and 0x20 != 0
        val down = b1 and 0x40 != 0
        val left = b1 and 0x80 != 0
        out.hat = hatFrom(up, right, down, left)

        out.lx = data[6].toInt() and 0xFF
        out.ly = data[7].toInt() and 0xFF
        out.rx = data[8].toInt() and 0xFF
        out.ry = data[9].toInt() and 0xFF

        // The DS3 sends real pressure for L2/R2 at bytes 18-19; pass it
        // through so the triggers are analog rather than on/off.
        if (length >= 20) {
            out.lt = data[18].toInt() and 0xFF
            out.rt = data[19].toInt() and 0xFF
        } else {
            out.lt = if (b2 and 0x01 != 0) 255 else 0
            out.rt = if (b2 and 0x02 != 0) 255 else 0
        }
        return true
    }

    private fun hatFrom(up: Boolean, right: Boolean, down: Boolean, left: Boolean): Int = when {
        up && right -> 1
        right && down -> 3
        down && left -> 5
        left && up -> 7
        up -> 0
        right -> 2
        down -> 4
        left -> 6
        else -> HidConstants.HAT_NEUTRAL
    }

    private fun findHidInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_HID) return iface
        }
        return if (device.interfaceCount > 0) device.getInterface(0) else null
    }

    private fun findInterruptIn(iface: UsbInterface): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN &&
                ep.type == UsbConstants.USB_ENDPOINT_XFER_INT
            ) return ep
        }
        return null
    }

    private fun registerPermissionReceiver() {
        if (permissionReceiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(permissionReceiver, filter)
        }
        permissionReceiverRegistered = true
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null && isDs3(device)) {
                open(device)
            } else {
                status(false, "PS3 pad: USB permission denied")
            }
        }
    }

    private fun status(connected: Boolean, message: String) {
        mainHandler.post { listener.onDs3Status(connected, message) }
    }

    private companion object {
        const val VENDOR_SONY = 0x054C
        const val PRODUCT_DS3 = 0x0268
        const val ACTION_USB_PERMISSION = "com.dathaze.pocketpad.USB_PERMISSION"
    }
}
