package com.dathaze.pocketpad.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dathaze.pocketpad.R

/**
 * The settings menu, as a sheet that rises from the bottom of the screen.
 *
 * It replaces `AlertDialog.setItems()` — eleven equal-weight strings with no icons, no grouping
 * and no indication of any current setting. Three things change the experience rather than just
 * the look:
 *
 *  - **The connection state is at the top.** It is the first thing anyone opening this menu
 *    wants to know and the old list did not show it at all. It is reported from the live HID
 *    connection, never from a remembered pairing, so "Connected" always means connected.
 *  - **Current values sit on their rows.** The controller style and the vibration level read
 *    out without opening anything, and TV menu keys is a switch rather than a sentence that
 *    changes.
 *  - **Four groups instead of one list.** Connection, Controller, Appearance, System.
 *
 * It sits at the bottom so it is reachable with the thumb already holding the phone, and it is
 * no taller than it needs to be, capped so landscape on a tall phone still fits.
 *
 * Every action the old menu had is still here and still does the same thing.
 */
class SettingsSheet(private val activity: Activity) {

    /** What the sheet needs to know, and what it does when a row is tapped. */
    interface Host {
        /** The connected device's name, or null when nothing is connected right now. */
        fun connectedDeviceName(): String?
        fun controllerStyleLabel(): String
        fun vibrationLabel(): String
        fun tvMenuKeysOn(): Boolean

        /** Version string of a waiting update, or null when there is nothing to offer. */
        fun pendingUpdateLabel(): String?

        fun onConnectToPaired()
        fun onMakeDiscoverable()
        fun onDisconnect()
        fun onChooseStyle()
        fun onMoveButtons()
        fun onChooseVibration()
        fun onToggleTvMenuKeys()
        fun onBackgroundPicture()
        fun onCheckForUpdates()
        fun onBluetoothSettings()
        fun onHelp()
    }

    private var dialog: Dialog? = null

    /** Tint for a row's glyph tile, so related actions read as a set. */
    private enum class Tint(val colorRes: Int) {
        NEUTRAL(R.color.pp_control),
        CONNECTION(R.color.pp_connected),
        CONTROLLER(R.color.pp_accent),
        APPEARANCE(R.color.pp_square),
        DANGER(R.color.pp_danger),
    }

    fun show(host: Host) {
        dismiss()
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_settings, null, false)
        val rows = view.findViewById<LinearLayout>(R.id.sheetRows)

        bindConnectionHeader(view, host)
        buildRows(rows, host)

        val sheet = Dialog(activity, R.style.Theme_PocketPad_Sheet).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(view)
            setCanceledOnTouchOutside(true)
        }
        sheet.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            attributes = attributes.apply { windowAnimations = R.style.Animation_PocketPad_Sheet }
            // Keep the game behind it visible rather than dimming the whole screen to black.
            setDimAmount(DIM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        capHeight(view)
        dialog = sheet
        sheet.setOnDismissListener { dialog = null }
        sheet.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    /**
     * Stop the sheet growing past most of the screen.
     *
     * In landscape on a tall phone the full list is taller than the display, and a dialog that
     * runs off the bottom simply loses its last rows — there is nothing to scroll because the
     * window itself is oversized. Capping the scroll view instead keeps the handle and the
     * connection header on screen and lets the rows scroll under them.
     */
    private fun capHeight(root: View) {
        val scroll = root.findViewById<ScrollView>(R.id.sheetScroll)
        val maxSheet = (activity.resources.displayMetrics.heightPixels * MAX_SCREEN_FRACTION).toInt()
        scroll.viewTreeObserver.addOnPreDrawListener(object :
            android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                scroll.viewTreeObserver.removeOnPreDrawListener(this)
                val headerHeight = root.height - scroll.height
                val allowed = (maxSheet - headerHeight).coerceAtLeast(MIN_SCROLL_DP)
                if (scroll.height > allowed) {
                    scroll.layoutParams = scroll.layoutParams.apply { height = allowed }
                    scroll.requestLayout()
                }
                return true
            }
        })
    }

    private fun bindConnectionHeader(root: View, host: Host) {
        val name = host.connectedDeviceName()
        val dot = root.findViewById<View>(R.id.sheetStatusDot)
        val text = root.findViewById<TextView>(R.id.sheetStatusText)
        // Truthful by construction: this reads the live connection, not the pairing list.
        if (name != null) {
            dot.backgroundTintList = colour(R.color.pp_connected)
            text.text = activity.getString(R.string.sheet_status_connected, name)
        } else {
            dot.backgroundTintList = colour(R.color.pp_idle)
            text.text = activity.getString(R.string.sheet_status_disconnected)
        }
    }

    private fun buildRows(rows: LinearLayout, host: Host) {
        rows.removeAllViews()

        group(rows, R.string.sheet_group_connection)
        row(rows, "⇄", Tint.CONNECTION, R.string.action_connect_paired,
            subtitle = R.string.sheet_sub_connect) { host.onConnectToPaired() }
        row(rows, "◉", Tint.CONNECTION, R.string.action_discoverable,
            subtitle = R.string.sheet_sub_discoverable) { host.onMakeDiscoverable() }
        row(rows, "⏻", Tint.DANGER, R.string.action_disconnect,
            chevron = false) { host.onDisconnect() }

        group(rows, R.string.sheet_group_controller)
        row(rows, "⬡", Tint.CONTROLLER, R.string.action_skin,
            value = host.controllerStyleLabel()) { host.onChooseStyle() }
        row(rows, "✥", Tint.CONTROLLER, R.string.action_edit_layout) { host.onMoveButtons() }
        row(rows, "≈", Tint.CONTROLLER, R.string.action_haptics,
            value = host.vibrationLabel()) { host.onChooseVibration() }
        row(rows, "⌨", Tint.CONTROLLER, R.string.sheet_tv_menu_keys,
            subtitle = R.string.sheet_sub_tv_menu_keys,
            switchOn = host.tvMenuKeysOn(),
            chevron = false) { host.onToggleTvMenuKeys() }

        group(rows, R.string.sheet_group_appearance)
        row(rows, "▦", Tint.APPEARANCE, R.string.action_background) { host.onBackgroundPicture() }

        group(rows, R.string.sheet_group_system)
        row(rows, "⇪", Tint.NEUTRAL, R.string.action_check_updates,
            badge = host.pendingUpdateLabel()) { host.onCheckForUpdates() }
        row(rows, "⚙", Tint.NEUTRAL, R.string.action_bt_settings) { host.onBluetoothSettings() }
        row(rows, "?", Tint.NEUTRAL, R.string.action_help) { host.onHelp() }
    }

    private fun group(parent: LinearLayout, labelRes: Int) {
        val tv = LayoutInflater.from(activity)
            .inflate(R.layout.row_settings_group, parent, false) as TextView
        tv.setText(labelRes)
        parent.addView(tv)
    }

    private fun row(
        parent: LinearLayout,
        glyph: String,
        tint: Tint,
        labelRes: Int,
        subtitle: Int? = null,
        value: String? = null,
        badge: String? = null,
        switchOn: Boolean? = null,
        chevron: Boolean = true,
        onTap: () -> Unit,
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.row_settings, parent, false)

        view.findViewById<TextView>(R.id.rowIcon).apply {
            text = glyph
            backgroundTintList = colour(tint.colorRes)
            // The tinted tiles are strong colours; dark glyphs read better on them than light.
            setTextColor(
                ContextCompat.getColor(
                    activity,
                    if (tint == Tint.NEUTRAL) R.color.pp_text else R.color.pp_background
                )
            )
        }
        view.findViewById<TextView>(R.id.rowLabel).setText(labelRes)

        subtitle?.let {
            view.findViewById<TextView>(R.id.rowSubtitle).apply {
                setText(it)
                visibility = View.VISIBLE
            }
        }
        value?.let {
            view.findViewById<TextView>(R.id.rowValue).apply {
                text = it
                visibility = View.VISIBLE
            }
        }
        badge?.let {
            view.findViewById<TextView>(R.id.rowBadge).apply {
                text = it
                visibility = View.VISIBLE
            }
        }
        switchOn?.let {
            view.findViewById<Switch>(R.id.rowSwitch).apply {
                isChecked = it
                visibility = View.VISIBLE
            }
        }
        if (!chevron) view.findViewById<TextView>(R.id.rowChevron).visibility = View.GONE

        view.setOnClickListener {
            // The toggle stays open so its new state can be seen; everything else leads
            // somewhere, so the sheet gets out of the way.
            if (switchOn != null) {
                onTap()
                val sw = view.findViewById<Switch>(R.id.rowSwitch)
                sw.isChecked = !sw.isChecked
            } else {
                dismiss()
                onTap()
            }
        }
        parent.addView(view)
    }

    private fun colour(res: Int) = ColorStateList.valueOf(ContextCompat.getColor(activity, res))

    private companion object {
        const val DIM = 0.45f
        const val MAX_SCREEN_FRACTION = 0.88f
        const val MIN_SCROLL_DP = 200
    }
}
