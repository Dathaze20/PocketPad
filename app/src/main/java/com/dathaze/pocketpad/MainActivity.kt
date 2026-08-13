package com.dathaze.pocketpad

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dathaze.pocketpad.hid.GamepadState
import com.dathaze.pocketpad.hid.HidConstants
import com.dathaze.pocketpad.hid.HidGamepadManager
import com.dathaze.pocketpad.ui.ControllerView
import com.dathaze.pocketpad.ui.Haptics
import com.dathaze.pocketpad.usb.Ds3UsbDriver
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(),
    ControllerView.Listener,
    HidGamepadManager.Listener,
    Ds3UsbDriver.Listener {

    private lateinit var controllerView: ControllerView
    private lateinit var statusView: TextView
    private lateinit var editBar: android.widget.LinearLayout
    private lateinit var hidManager: HidGamepadManager
    private lateinit var ds3Driver: Ds3UsbDriver

    /** When on, the pad also sends arrow keys/Enter so it can drive TV menus. */
    private var tvNavigation = true

    /** State fed by a physical controller (native events or the raw DS3 driver). */
    private val externalState = GamepadState()
    private val mergedState = GamepadState()

    // D-pad key tracking for physical controllers that report keys, not a hat axis.
    private var extUp = false
    private var extDown = false
    private var extLeft = false
    private var extRight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        controllerView = findViewById(R.id.controller)
        statusView = findViewById(R.id.status)
        editBar = findViewById(R.id.editBar)
        controllerView.listener = this

        findViewById<android.widget.Button>(R.id.editSave).setOnClickListener {
            controllerView.saveEditedLayout()
            finishLayoutEdit()
            Toast.makeText(this, R.string.edit_saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<android.widget.Button>(R.id.editReset).setOnClickListener {
            controllerView.resetLayoutToDefault()
            Toast.makeText(this, R.string.edit_defaults_restored, Toast.LENGTH_SHORT).show()
        }
        findViewById<android.widget.Button>(R.id.editCancel).setOnClickListener {
            controllerView.cancelLayoutEdit()
            finishLayoutEdit()
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        restoreControllerPrefs()

        hidManager = HidGamepadManager(this, this)
        ds3Driver = Ds3UsbDriver(this, this)

        if (hasBluetoothPermissions()) {
            startHid()
        } else {
            requestBluetoothPermissions()
        }

        handleUsbIntent(intent)
        ds3Driver.findAttachedDs3()?.let { ds3Driver.openOrRequestPermission(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // If Bluetooth was off at launch, retry once the user comes back.
        if (hasBluetoothPermissions()) hidManager.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        ds3Driver.release()
        hidManager.stop()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, controllerView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Keep the status pill clear of the camera cutout.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(statusView) { view, insets ->
            val top = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            view.translationY = top.toFloat()
            insets
        }
    }

    // ------------------------------------------------------------ permissions

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ),
            REQUEST_BLUETOOTH
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH) {
            if (hasBluetoothPermissions()) {
                startHid()
            } else {
                statusView.text = getString(R.string.bt_permission_needed)
            }
        }
    }

    private fun startHid() {
        hidManager.start()
        reconnectLastDevice()
    }

    @SuppressLint("MissingPermission")
    private fun reconnectLastDevice() {
        val address = getPreferences(MODE_PRIVATE).getString(PREF_LAST_DEVICE, null) ?: return
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return
        val device = hidManager.bondedDevices().firstOrNull { it.address == address } ?: return
        hidManager.connectTo(device)
    }

    // -------------------------------------------------------------- settings

    private fun restoreControllerPrefs() {
        val prefs = getPreferences(MODE_PRIVATE)
        controllerView.skin = when (prefs.getString(PREF_SKIN, "PS")) {
            "SNES" -> ControllerView.Skin.SNES
            "NES" -> ControllerView.Skin.NES
            "GAMEBOY" -> ControllerView.Skin.GAMEBOY
            else -> ControllerView.Skin.PS
        }
        controllerView.haptics.level = when (prefs.getString(PREF_HAPTICS, "STRONG")) {
            "OFF" -> Haptics.Level.OFF
            "LIGHT" -> Haptics.Level.LIGHT
            else -> Haptics.Level.STRONG
        }
        tvNavigation = prefs.getBoolean(PREF_TV_NAV, true)
    }

    @SuppressLint("MissingPermission")
    override fun onOpenSettings() {
        val items = arrayOf(
            getString(R.string.action_connect_paired),
            getString(R.string.action_discoverable),
            getString(R.string.action_skin),
            getString(R.string.action_edit_layout),
            getString(R.string.action_haptics),
            getString(
                if (tvNavigation) R.string.action_tv_nav_on else R.string.action_tv_nav_off
            ),
            getString(R.string.action_bt_settings),
            getString(R.string.action_disconnect),
            getString(R.string.action_help)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showPairedDevicePicker()
                    1 -> makeDiscoverable()
                    2 -> showSkinPicker()
                    3 -> startLayoutEdit()
                    4 -> showHapticsPicker()
                    5 -> toggleTvNavigation()
                    6 -> startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                    7 -> hidManager.disconnect()
                    8 -> showHelp()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startLayoutEdit() {
        controllerView.enterLayoutEditMode()
        editBar.visibility = android.view.View.VISIBLE
        statusView.visibility = android.view.View.GONE
        Toast.makeText(this, R.string.edit_hint, Toast.LENGTH_LONG).show()
    }

    private fun finishLayoutEdit() {
        editBar.visibility = android.view.View.GONE
        statusView.visibility = android.view.View.VISIBLE
    }

    private fun toggleTvNavigation() {
        tvNavigation = !tvNavigation
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_TV_NAV, tvNavigation).apply()
        if (!tvNavigation) hidManager.sendKey(HidConstants.KEY_NONE)
        Toast.makeText(
            this,
            if (tvNavigation) R.string.tv_nav_enabled else R.string.tv_nav_disabled,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showSkinPicker() {
        val skins = arrayOf(
            getString(R.string.skin_ps),
            getString(R.string.skin_snes),
            getString(R.string.skin_nes),
            getString(R.string.skin_gameboy)
        )
        val current = when (controllerView.skin) {
            ControllerView.Skin.PS -> 0
            ControllerView.Skin.SNES -> 1
            ControllerView.Skin.NES -> 2
            ControllerView.Skin.GAMEBOY -> 3
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_skin)
            .setSingleChoiceItems(skins, current) { dialog, which ->
                val (skin, key) = when (which) {
                    1 -> ControllerView.Skin.SNES to "SNES"
                    2 -> ControllerView.Skin.NES to "NES"
                    3 -> ControllerView.Skin.GAMEBOY to "GAMEBOY"
                    else -> ControllerView.Skin.PS to "PS"
                }
                controllerView.skin = skin
                getPreferences(MODE_PRIVATE).edit().putString(PREF_SKIN, key).apply()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showHapticsPicker() {
        val levels = arrayOf(
            getString(R.string.haptics_strong),
            getString(R.string.haptics_light),
            getString(R.string.haptics_off)
        )
        val current = when (controllerView.haptics.level) {
            Haptics.Level.STRONG -> 0
            Haptics.Level.LIGHT -> 1
            Haptics.Level.OFF -> 2
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_haptics)
            .setSingleChoiceItems(levels, current) { dialog, which ->
                val (level, key) = when (which) {
                    1 -> Haptics.Level.LIGHT to "LIGHT"
                    2 -> Haptics.Level.OFF to "OFF"
                    else -> Haptics.Level.STRONG to "STRONG"
                }
                controllerView.haptics.level = level
                getPreferences(MODE_PRIVATE).edit().putString(PREF_HAPTICS, key).apply()
                controllerView.haptics.press()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun showPairedDevicePicker() {
        val devices = hidManager.bondedDevices()
        if (devices.isEmpty()) {
            Toast.makeText(this, R.string.no_paired_devices, Toast.LENGTH_LONG).show()
            return
        }
        val names = devices.map { device ->
            try {
                device.name ?: device.address
            } catch (_: SecurityException) {
                device.address
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.action_connect_paired)
            .setItems(names) { _, which -> connectAndRemember(devices[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun connectAndRemember(device: BluetoothDevice) {
        getPreferences(MODE_PRIVATE).edit()
            .putString(PREF_LAST_DEVICE, device.address)
            .apply()
        hidManager.connectTo(device)
    }

    override fun onHomeLongPress() {
        if (!hidManager.isConnected) makeDiscoverable()
    }

    private fun makeDiscoverable() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        try {
            // Only light the pairing halo once the system confirms the phone
            // really is discoverable — otherwise it lies when the user
            // dismisses the system prompt.
            discoverableLauncher.launch(intent)
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.bt_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    /** A granted request returns the discoverable duration; 0 = declined. */
    private val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode > 0) {
            controllerView.linkState = ControllerView.LinkState.DISCOVERABLE
            statusView.text = getString(R.string.status_discoverable)
            Toast.makeText(this, R.string.discoverable_hint, Toast.LENGTH_LONG).show()
        } else {
            controllerView.linkState = ControllerView.LinkState.IDLE
            Toast.makeText(this, R.string.discoverable_declined, Toast.LENGTH_LONG).show()
        }
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_help)
            .setMessage(R.string.help_text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------- touch → TV

    override fun onTouchStateChanged() {
        sendMerged()
    }

    private fun sendMerged() {
        GamepadState.merge(controllerView.touchState, externalState, mergedState)
        hidManager.send(mergedState)
        if (tvNavigation) hidManager.sendKey(navKeyFor(mergedState))
    }

    /**
     * Keyboard equivalent of the current state, so the pad also drives TV
     * menus: d-pad → arrow keys, ✕ → Enter, ○ → Back.
     */
    private fun navKeyFor(state: GamepadState): Byte = when {
        state.isPressed(HidConstants.BTN_CROSS) -> HidConstants.KEY_ENTER
        state.isPressed(HidConstants.BTN_CIRCLE) -> HidConstants.KEY_ESCAPE
        state.hat == 0 -> HidConstants.KEY_UP
        state.hat == 2 -> HidConstants.KEY_RIGHT
        state.hat == 4 -> HidConstants.KEY_DOWN
        state.hat == 6 -> HidConstants.KEY_LEFT
        else -> HidConstants.KEY_NONE
    }

    // ------------------------------------------------ physical controller input

    private fun isGamepadEvent(event: KeyEvent): Boolean {
        val source = event.source
        return source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            KeyEvent.isGamepadButton(event.keyCode)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isGamepadEvent(event) && handleGamepadKey(keyCode, pressed = true)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isGamepadEvent(event) && handleGamepadKey(keyCode, pressed = false)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun handleGamepadKey(keyCode: Int, pressed: Boolean): Boolean {
        val buttonIndex = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> HidConstants.BTN_CROSS
            KeyEvent.KEYCODE_BUTTON_B -> HidConstants.BTN_CIRCLE
            KeyEvent.KEYCODE_BUTTON_X -> HidConstants.BTN_SQUARE
            KeyEvent.KEYCODE_BUTTON_Y -> HidConstants.BTN_TRIANGLE
            KeyEvent.KEYCODE_BUTTON_L1 -> HidConstants.BTN_L1
            KeyEvent.KEYCODE_BUTTON_R1 -> HidConstants.BTN_R1
            KeyEvent.KEYCODE_BUTTON_L2 -> HidConstants.BTN_L2
            KeyEvent.KEYCODE_BUTTON_R2 -> HidConstants.BTN_R2
            KeyEvent.KEYCODE_BUTTON_THUMBL -> HidConstants.BTN_L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> HidConstants.BTN_R3
            KeyEvent.KEYCODE_BUTTON_SELECT -> HidConstants.BTN_SHARE
            KeyEvent.KEYCODE_BUTTON_START -> HidConstants.BTN_OPTIONS
            KeyEvent.KEYCODE_BUTTON_MODE -> HidConstants.BTN_PS
            else -> -1
        }
        if (buttonIndex >= 0) {
            externalState.setButton(buttonIndex, pressed)
            sendMerged()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> extUp = pressed
            KeyEvent.KEYCODE_DPAD_DOWN -> extDown = pressed
            KeyEvent.KEYCODE_DPAD_LEFT -> extLeft = pressed
            KeyEvent.KEYCODE_DPAD_RIGHT -> extRight = pressed
            else -> return false
        }
        updateExternalHat()
        sendMerged()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoystick =
            event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (isJoystick && event.action == MotionEvent.ACTION_MOVE) {
            externalState.lx = axisToByte(event.getAxisValue(MotionEvent.AXIS_X))
            externalState.ly = axisToByte(event.getAxisValue(MotionEvent.AXIS_Y))
            externalState.rx = axisToByte(event.getAxisValue(MotionEvent.AXIS_Z))
            externalState.ry = axisToByte(event.getAxisValue(MotionEvent.AXIS_RZ))

            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (abs(hatX) > 0.5f || abs(hatY) > 0.5f || (extUp || extDown || extLeft || extRight)) {
                if (abs(hatX) > 0.5f || abs(hatY) > 0.5f) {
                    extLeft = hatX < -0.5f
                    extRight = hatX > 0.5f
                    extUp = hatY < -0.5f
                    extDown = hatY > 0.5f
                }
                updateExternalHat()
            }

            val lt = maxOf(
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE)
            )
            val rt = maxOf(
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS)
            )
            externalState.setButton(HidConstants.BTN_L2, lt > 0.5f)
            externalState.setButton(HidConstants.BTN_R2, rt > 0.5f)

            sendMerged()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun updateExternalHat() {
        externalState.hat = when {
            extUp && extRight -> 1
            extRight && extDown -> 3
            extDown && extLeft -> 5
            extLeft && extUp -> 7
            extUp -> 0
            extRight -> 2
            extDown -> 4
            extLeft -> 6
            else -> HidConstants.HAT_NEUTRAL
        }
    }

    private fun axisToByte(value: Float): Int =
        ((value.coerceIn(-1f, 1f) + 1f) / 2f * 255f).roundToInt().coerceIn(0, 255)

    // ------------------------------------------------------------- USB (DS3)

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        device?.let { ds3Driver.openOrRequestPermission(it) }
    }

    override fun onDs3State(state: GamepadState) {
        externalState.copyFrom(state)
        sendMerged()
    }

    override fun onDs3Status(connected: Boolean, message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        if (!connected) {
            externalState.reset()
            extUp = false; extDown = false; extLeft = false; extRight = false
            sendMerged()
        }
    }

    // ------------------------------------------------------------ HID events

    override fun onHidStatus(status: HidGamepadManager.HidStatus, deviceName: String?) {
        statusView.text = when (status) {
            HidGamepadManager.HidStatus.BLUETOOTH_OFF -> getString(R.string.status_bt_off)
            HidGamepadManager.HidStatus.REGISTERING -> getString(R.string.status_registering)
            HidGamepadManager.HidStatus.READY -> getString(R.string.status_registered)
            HidGamepadManager.HidStatus.CONNECTING ->
                getString(R.string.status_connecting, deviceName ?: "…")
            HidGamepadManager.HidStatus.UNREGISTERED -> getString(R.string.status_unregistered)
            HidGamepadManager.HidStatus.PERMISSION_MISSING ->
                getString(R.string.bt_permission_needed)
        }
        statusView.setTextColor(STATUS_DIM)
    }

    @SuppressLint("MissingPermission")
    override fun onHidConnected(device: BluetoothDevice?) {
        val name = try {
            device?.name ?: device?.address ?: "TV"
        } catch (_: SecurityException) {
            device?.address ?: "TV"
        }
        statusView.text = getString(R.string.status_connected, name)
        statusView.setTextColor(STATUS_CONNECTED)
        controllerView.linkState = ControllerView.LinkState.CONNECTED
        device?.let {
            getPreferences(MODE_PRIVATE).edit()
                .putString(PREF_LAST_DEVICE, it.address)
                .apply()
        }
    }

    override fun onHidDisconnected() {
        statusView.text = getString(R.string.status_disconnected)
        statusView.setTextColor(STATUS_DIM)
        controllerView.linkState = ControllerView.LinkState.IDLE
    }

    private companion object {
        const val REQUEST_BLUETOOTH = 101
        const val PREF_TV_NAV = "tv_navigation"
        const val PREF_LAST_DEVICE = "last_device_address"
        const val PREF_SKIN = "controller_skin"
        const val PREF_HAPTICS = "haptics_level"
        const val STATUS_CONNECTED = 0xFF35C98A.toInt()
        const val STATUS_DIM = 0xFF8A8FA8.toInt()
    }
}
