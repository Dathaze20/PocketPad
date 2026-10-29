package com.dathaze.pocketpad

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dathaze.pocketpad.hid.ExternalInputTracker
import com.dathaze.pocketpad.hid.BluetoothReadiness
import com.dathaze.pocketpad.hid.GamepadState
import com.dathaze.pocketpad.hid.HidConstants
import com.dathaze.pocketpad.hid.HidGamepadManager
import com.dathaze.pocketpad.ui.BackgroundImage
import com.dathaze.pocketpad.ui.ControllerView
import com.dathaze.pocketpad.ui.Haptics
import com.dathaze.pocketpad.update.UpdateFlow
import com.dathaze.pocketpad.usb.Ds3UsbDriver
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(),
    ControllerView.Listener,
    HidGamepadManager.Listener,
    Ds3UsbDriver.Listener {

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var controllerView: ControllerView
    private lateinit var statusView: TextView
    private lateinit var editBar: android.widget.LinearLayout
    private lateinit var hidManager: HidGamepadManager
    private lateinit var ds3Driver: Ds3UsbDriver

    /**
     * The in-app updater, built the first time the user picks "Check for updates". An install
     * that never opens that menu never constructs it and never makes a request.
     */
    private var updateFlow: UpdateFlow? = null

    /**
     * When on, the pad ALSO sends keyboard keys so it can drive TV menus.
     * Off by default: while a game is running those keys are duplicate input,
     * and Escape in particular is read as "quit" by cloud gaming clients.
     */
    private var tvNavigation = false

    /**
     * Physical controller input, kept per device so one disconnecting drops
     * only its own contribution.
     */
    private val externalInputs = ExternalInputTracker()

    /** Every physical source folded together; merged with touch in [sendMerged]. */
    private val externalState = GamepadState()
    private val mergedState = GamepadState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        controllerView = findViewById(R.id.controller)
        statusView = findViewById(R.id.status)
        editBar = findViewById(R.id.editBar)
        controllerView.listener = this

        findViewById<android.widget.Button>(R.id.editSave).setOnClickListener {
            if (controllerView.isPanningBackground) {
                saveBackgroundPan()
            } else {
                controllerView.saveEditedLayout()
                finishLayoutEdit()
                Toast.makeText(this, R.string.edit_saved, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<android.widget.Button>(R.id.editReset).setOnClickListener {
            if (controllerView.isPanningBackground) {
                controllerView.centerBackground()
            } else {
                controllerView.resetLayoutToDefault()
                Toast.makeText(this, R.string.edit_defaults_restored, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<android.widget.Button>(R.id.editCancel).setOnClickListener {
            if (controllerView.isPanningBackground) {
                val prefs = getPreferences(MODE_PRIVATE)
                controllerView.setBackgroundPan(
                    prefs.getFloat(PREF_BG_X, 0.5f),
                    prefs.getFloat(PREF_BG_Y, 0.5f)
                )
                controllerView.finishBackgroundPan()
            } else {
                controllerView.cancelLayoutEdit()
            }
            finishLayoutEdit()
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        // Build the Bluetooth manager before restoring preferences: applying a
        // saved skin fires onTouchStateChanged, which sends through hidManager.
        hidManager = HidGamepadManager(this, this)
        ds3Driver = Ds3UsbDriver(this, this)

        restoreControllerPrefs()
        loadBackgroundImage()

        if (hasBluetoothPermissions()) {
            startHid()
        } else {
            requestBluetoothPermissions()
        }

        (getSystemService(INPUT_SERVICE) as? InputManager)
            ?.registerInputDeviceListener(inputDeviceListener, null)

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
        if (::hidManager.isInitialized && hasBluetoothPermissions()) hidManager.start()
        // A controller may have been unplugged while we were away.
        pruneDetachedControllers()
        // Coming back from Android's "install unknown apps" screen: put the update that was
        // waiting on that permission back in front of the user. Null unless they opened the
        // updater, so this costs nothing for everyone else.
        updateFlow?.onActivityResumed()
    }

    /**
     * A physical controller can vanish mid-press — unplugged, switched off, or
     * out of range. Its buttons and sticks would otherwise stay transmitted as
     * held, so drop that device's input and resend what is actually left.
     */
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            // The id is live again (possibly reused by different hardware),
            // so stop ignoring input from it.
            externalInputs.markAttached(deviceId)
        }
        override fun onInputDeviceChanged(deviceId: Int) = Unit
        override fun onInputDeviceRemoved(deviceId: Int) {
            if (externalInputs.remove(deviceId)) refreshExternalState()
        }
    }

    /** Catch disconnects that happened while the listener was not running. */
    private fun pruneDetachedControllers() {
        val attached = InputDevice.getDeviceIds().toHashSet()
        val stale = externalInputs.sourceIds().filter {
            it != ExternalInputTracker.SOURCE_DS3 && it !in attached
        }
        if (stale.isEmpty()) return
        stale.forEach { externalInputs.remove(it) }
        refreshExternalState()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Guarded so a failure during onCreate can't turn into a second crash.
        (getSystemService(INPUT_SERVICE) as? InputManager)
            ?.unregisterInputDeviceListener(inputDeviceListener)
        if (::ds3Driver.isInitialized) ds3Driver.release()
        if (::hidManager.isInitialized) hidManager.stop()
        // Guarded for the same reason as the two above: setContentView or findViewById failing
        // would leave this unset, and an unguarded read here would throw on the way out and
        // bury the exception that actually caused the problem.
        if (::controllerView.isInitialized) controllerView.setBackgroundImage(null, 0.5f, 0.5f)
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

    /**
     * What the two runtime Bluetooth permissions currently allow.
     *
     * Read fresh each time: the user can change either one in system settings while the app
     * is in the background.
     */
    private fun bluetoothReadiness(): BluetoothReadiness = BluetoothReadiness.of(
        sdkInt = Build.VERSION.SDK_INT,
        hasConnect = granted(Manifest.permission.BLUETOOTH_CONNECT),
        hasAdvertise = granted(Manifest.permission.BLUETOOTH_ADVERTISE)
    )

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Enough to register as a controller and send input. */
    private fun hasBluetoothPermissions(): Boolean = bluetoothReadiness().canRunHid

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
            val readiness = bluetoothReadiness()
            when {
                readiness.isBlocked -> statusView.text = getString(R.string.bt_permission_needed)
                // The controller works, but no new host can find the phone. Worth saying out
                // loud: the old code reported plain READY here and the user was left to work
                // out for themselves why pairing never succeeded.
                readiness.isPartiallyBlocked -> {
                    startHid()
                    Toast.makeText(this, R.string.bt_advertise_needed, Toast.LENGTH_LONG).show()
                }
                else -> startHid()
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
            "XBOX" -> ControllerView.Skin.XBOX
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
        tvNavigation = prefs.getBoolean(PREF_TV_NAV, false)
    }

    @SuppressLint("MissingPermission")
    override fun onOpenSettings() {
        val items = arrayOf(
            getString(R.string.action_connect_paired),
            getString(R.string.action_discoverable),
            getString(R.string.action_skin),
            getString(R.string.action_edit_layout),
            getString(R.string.action_background),
            getString(R.string.action_haptics),
            getString(
                if (tvNavigation) R.string.action_tv_nav_on else R.string.action_tv_nav_off
            ),
            getString(R.string.action_bt_settings),
            getString(R.string.action_disconnect),
            getString(R.string.action_check_updates),
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
                    4 -> showBackgroundMenu()
                    5 -> showHapticsPicker()
                    6 -> toggleTvNavigation()
                    7 -> startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                    8 -> hidManager.disconnect()
                    9 -> checkForUpdates()
                    10 -> showHelp()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Opens the in-app updater.
     *
     * Only ever reached from this menu. Nothing checks for updates on its own — a check that
     * fired on launch or in the background could interrupt a game or a Bluetooth session, so
     * the one that runs is the one the user asked for. The HID link is left untouched; the
     * dialog sits over the controller and the pad keeps working underneath it.
     */
    private fun checkForUpdates() {
        val flow = updateFlow ?: UpdateFlow(this).also { updateFlow = it }
        flow.start()
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

    // ---------------------------------------------------- background picture

    private fun backgroundFile() = java.io.File(filesDir, "controller_bg.jpg")

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        importBackgroundImage(uri)
    }

    /**
     * Copy the chosen picture into the app's own storage, then decode it — both off the main
     * thread.
     *
     * Neither step belongs on the main thread. The picker can hand back a photo that lives in
     * the cloud, and opening its stream is what downloads it, so the copy is a network transfer
     * of unknown length; decoding a full-resolution photo twice is not quick either. Doing that
     * where the UI runs is how a slow connection turns into "PocketPad isn't responding".
     *
     * The copy goes through a scratch file. Writing straight into the saved picture emptied it
     * first, so anything that failed partway destroyed the background the user already had —
     * see [BackgroundImage.replaceOnlyIfWritten].
     */
    private fun importBackgroundImage(uri: Uri) {
        Toast.makeText(this, R.string.bg_loading, Toast.LENGTH_SHORT).show()
        onBackgroundThread {
            val copied = BackgroundImage.replaceOnlyIfWritten(backgroundFile()) { output ->
                val input = contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("the picture could not be opened")
                input.use { it.copyTo(output) }
            }
            val bitmap = if (copied) decodeBackgroundImage() else null
            mainHandler.post {
                if (!acceptBackground(bitmap)) return@post
                getPreferences(MODE_PRIVATE).edit()
                    .putFloat(PREF_BG_X, 0.5f)
                    .putFloat(PREF_BG_Y, 0.5f)
                    .apply()
                controllerView.setBackgroundImage(bitmap, 0.5f, 0.5f)
                startBackgroundPan()
            }
        }
    }

    /** Decode and show the saved picture, if there is one. Returns immediately. */
    private fun loadBackgroundImage() {
        if (!backgroundFile().exists()) return
        onBackgroundThread {
            val bitmap = decodeBackgroundImage()
            mainHandler.post {
                if (!acceptBackground(bitmap)) return@post
                val prefs = getPreferences(MODE_PRIVATE)
                controllerView.setBackgroundImage(
                    bitmap,
                    prefs.getFloat(PREF_BG_X, 0.5f),
                    prefs.getFloat(PREF_BG_Y, 0.5f)
                )
            }
        }
    }

    /**
     * Main-thread gate for a decode that has just come back.
     *
     * A decode outlives the activity that asked for it, so by the time it lands the window may
     * be gone — handing the bitmap to a dead view would hold several megabytes alive with
     * nothing to draw it. Reports the failure to the user only while there is still someone
     * there to read it.
     */
    private fun acceptBackground(bitmap: Bitmap?): Boolean {
        if (isDestroyed || isFinishing) {
            bitmap?.recycle()
            return false
        }
        if (bitmap == null) {
            Toast.makeText(this, R.string.bg_load_failed, Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    /** Decode the saved picture at roughly screen resolution (avoids OOM). Blocks. */
    private fun decodeBackgroundImage(): Bitmap? {
        val file = backgroundFile()
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val targetLongSide = resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
        val sample = BackgroundImage.sampleSizeFor(bounds.outWidth, bounds.outHeight, targetLongSide)
        return BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    /** Same shape the updater and the USB reader already use: a plain daemon thread. */
    private fun onBackgroundThread(work: () -> Unit) {
        Thread(work, "pocketpad-background-image").apply { isDaemon = true }.start()
    }

    private fun startBackgroundPan() {
        controllerView.enterBackgroundPanMode()
        editBar.visibility = android.view.View.VISIBLE
        statusView.visibility = android.view.View.GONE
        Toast.makeText(this, R.string.bg_hint, Toast.LENGTH_LONG).show()
    }

    private fun saveBackgroundPan() {
        val (panX, panY) = controllerView.finishBackgroundPan()
        getPreferences(MODE_PRIVATE).edit()
            .putFloat(PREF_BG_X, panX)
            .putFloat(PREF_BG_Y, panY)
            .apply()
        finishLayoutEdit()
        Toast.makeText(this, R.string.bg_saved, Toast.LENGTH_SHORT).show()
    }

    private fun removeBackground() {
        backgroundFile().delete()
        controllerView.setBackgroundImage(null, 0.5f, 0.5f)
        Toast.makeText(this, R.string.bg_removed, Toast.LENGTH_SHORT).show()
    }

    private fun showBackgroundMenu() {
        val hasImage = controllerView.hasBackgroundImage
        val items = if (hasImage) {
            arrayOf(
                getString(R.string.bg_choose),
                getString(R.string.bg_adjust),
                getString(R.string.bg_remove)
            )
        } else {
            arrayOf(getString(R.string.bg_choose))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_background)
            .setItems(items) { _, which ->
                when {
                    which == 0 -> photoPicker.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                    which == 1 && hasImage -> startBackgroundPan()
                    which == 2 && hasImage -> removeBackground()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            getString(R.string.skin_xbox),
            getString(R.string.skin_snes),
            getString(R.string.skin_nes),
            getString(R.string.skin_gameboy)
        )
        val current = when (controllerView.skin) {
            ControllerView.Skin.PS -> 0
            ControllerView.Skin.XBOX -> 1
            ControllerView.Skin.SNES -> 2
            ControllerView.Skin.NES -> 3
            ControllerView.Skin.GAMEBOY -> 4
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_skin)
            .setSingleChoiceItems(skins, current) { dialog, which ->
                val (skin, key) = when (which) {
                    1 -> ControllerView.Skin.XBOX to "XBOX"
                    2 -> ControllerView.Skin.SNES to "SNES"
                    3 -> ControllerView.Skin.NES to "NES"
                    4 -> ControllerView.Skin.GAMEBOY to "GAMEBOY"
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
        // Discoverability needs BLUETOOTH_ADVERTISE, which is a separate grant from the
        // BLUETOOTH_CONNECT that the controller itself runs on. Ask for it here instead of
        // launching an intent the system will refuse.
        if (!bluetoothReadiness().canBecomeDiscoverable) {
            Toast.makeText(this, R.string.bt_advertise_needed, Toast.LENGTH_LONG).show()
            requestBluetoothPermissions()
            return
        }
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
            // The PC/tablet steps are appended rather than folded into help_text: the
            // phone-has-to-connect-outwards step is the one people miss, and it reads better
            // as its own block than buried in the TV instructions.
            .setMessage(
                getString(R.string.help_text) + "\n\n" + getString(R.string.help_windows)
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------- touch → TV

    override fun onTouchStateChanged() {
        sendMerged()
    }

    private fun sendMerged() {
        // The view can emit state before onCreate finishes wiring things up.
        if (!::hidManager.isInitialized) return
        GamepadState.merge(controllerView.touchState, externalState, mergedState)
        hidManager.send(mergedState)
        if (tvNavigation) hidManager.sendKey(navKeyFor(mergedState))
    }

    /**
     * Keyboard equivalent of the current state, so the pad can drive TV menus
     * when the user asks for it: d-pad → arrow keys, ✕/A → Enter, ○/B → Back.
     * This is deliberately opt-in — see [tvNavigation].
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
        if (isGamepadEvent(event) &&
            handleGamepadKey(event.deviceId, keyCode, pressed = true)
        ) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isGamepadEvent(event) &&
            handleGamepadKey(event.deviceId, keyCode, pressed = false)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun handleGamepadKey(deviceId: Int, keyCode: Int, pressed: Boolean): Boolean {
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
            // A disconnected controller's late events must not revive it.
            val source = externalInputs.sourceIfAttached(deviceId) ?: return false
            source.state.setButton(buttonIndex, pressed)
            // Controllers that report triggers as plain keys still need the
            // axis moved, or throttle input never reaches the host.
            when (buttonIndex) {
                HidConstants.BTN_L2 -> source.state.lt = if (pressed) 255 else 0
                HidConstants.BTN_R2 -> source.state.rt = if (pressed) 255 else 0
            }
            refreshExternalState()
            return true
        }
        val isDpad = keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        // Check before touching the tracker, so an unrelated key cannot create
        // an empty source that then looks like an attached controller.
        if (!isDpad) return false
        val source = externalInputs.sourceIfAttached(deviceId) ?: return false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> source.keyUp = pressed
            KeyEvent.KEYCODE_DPAD_DOWN -> source.keyDown = pressed
            KeyEvent.KEYCODE_DPAD_LEFT -> source.keyLeft = pressed
            KeyEvent.KEYCODE_DPAD_RIGHT -> source.keyRight = pressed
        }
        source.applyDpad()
        refreshExternalState()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoystick =
            event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (isJoystick && event.action == MotionEvent.ACTION_MOVE) {
            val source = externalInputs.sourceIfAttached(event.deviceId)
                ?: return super.onGenericMotionEvent(event)
            source.state.lx = axisToByte(event.getAxisValue(MotionEvent.AXIS_X))
            source.state.ly = axisToByte(event.getAxisValue(MotionEvent.AXIS_Y))
            source.state.rx = axisToByte(event.getAxisValue(MotionEvent.AXIS_Z))
            source.state.ry = axisToByte(event.getAxisValue(MotionEvent.AXIS_RZ))

            // Recomputed every event, including when the hat returns to centre
            // — otherwise the last direction stays latched. Key-reported
            // directions are tracked separately and are not disturbed.
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            source.axisLeft = hatX < -0.5f
            source.axisRight = hatX > 0.5f
            source.axisUp = hatY < -0.5f
            source.axisDown = hatY > 0.5f
            source.applyDpad()

            val lt = maxOf(
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE)
            )
            val rt = maxOf(
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS)
            )
            // Forward the real analog value, not just a pressed/not-pressed
            // bit — a throttle read as on/off is unusable in driving games.
            source.state.lt = triggerToByte(lt)
            source.state.rt = triggerToByte(rt)
            source.state.setButton(HidConstants.BTN_L2, lt > 0.12f)
            source.state.setButton(HidConstants.BTN_R2, rt > 0.12f)

            refreshExternalState()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /** Refold every attached controller and transmit the result. */
    private fun refreshExternalState() {
        externalInputs.combineInto(externalState)
        sendMerged()
    }

    private fun axisToByte(value: Float): Int =
        ((value.coerceIn(-1f, 1f) + 1f) / 2f * 255f).roundToInt().coerceIn(0, 255)

    /** Triggers report 0..1, unlike sticks which are -1..1. */
    private fun triggerToByte(value: Float): Int =
        (value.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)

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
        // Driver reports are posted to the main thread, so one sent just before
        // the pad was unplugged can still land after the disconnect. Dropping it
        // here is what stops that late report re-creating the DS3 mid-press.
        val source = externalInputs.sourceIfAttached(ExternalInputTracker.SOURCE_DS3) ?: return
        source.state.copyFrom(state)
        refreshExternalState()
    }

    override fun onDs3Status(connected: Boolean, message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        if (connected) {
            // Accept its input again after a previous disconnect.
            externalInputs.markAttached(ExternalInputTracker.SOURCE_DS3)
        } else {
            externalInputs.remove(ExternalInputTracker.SOURCE_DS3)
            refreshExternalState()
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
            HidGamepadManager.HidStatus.REGISTRATION_FAILED ->
                getString(R.string.status_registration_failed)
            HidGamepadManager.HidStatus.CONNECT_REFUSED ->
                getString(R.string.status_connect_refused, deviceName ?: "that device")
            HidGamepadManager.HidStatus.PROFILE_UNAVAILABLE ->
                getString(R.string.status_profile_unavailable)
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
        if (!tvNavigation) hidManager.sendKey(HidConstants.KEY_NONE)
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
        const val PREF_BG_X = "background_pan_x"
        const val PREF_BG_Y = "background_pan_y"
        const val PREF_LAST_DEVICE = "last_device_address"
        const val PREF_SKIN = "controller_skin"
        const val PREF_HAPTICS = "haptics_level"
        const val STATUS_CONNECTED = 0xFF35C98A.toInt()
        const val STATUS_DIM = 0xFF8A8FA8.toInt()
    }
}
