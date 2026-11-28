package com.dathaze.pocketpad.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dathaze.pocketpad.R

/**
 * The settings menu: a list of rows in a sheet that rises from the bottom of the screen.
 *
 * The list itself is the one it has always been — tinted glyph on the left, label with an
 * optional subtitle, current value or switch on the right, chevron at the end, grouped under
 * four headings. What changed is that it now fits on one screen:
 *
 *  - **Landscape puts the groups in two columns.** A phone on its side has roughly 400dp of
 *    height and 900dp of width. Eleven rows in one column cannot fit in 400dp; in two columns
 *    they fit easily, using width that was going spare. See [SettingsListView].
 *  - **Portrait is the same single column, tighter.** Shorter rows, less padding, smaller
 *    headings, and labels short enough not to need two lines.
 *  - **Nothing can break mid-word.** Every label and subtitle is one line that ellipsizes; a
 *    `TextView` only splits a word when it is trying to wrap, and these never wrap.
 *  - **It shrinks before it scrolls.** At font scales large enough to overflow even two
 *    columns, every row and heading is scaled down together rather than the list scrolling.
 *  - **Disconnect is only there when there is something to disconnect from.**
 *
 * The connection state is read from the live HID link, never from the bonded-device list, so
 * "Connected" always means connected rather than "paired at some point".
 *
 * Every action the original menu had is still here and still does the same thing.
 */
class SettingsSheet(private val activity: Activity) {

    /** What the sheet needs to know, and what it does when a row is tapped. */
    interface Host {
        /** The connected device's name, or null when nothing is connected right now. */
        fun connectedDeviceName(): String?
        fun controllerStyleLabel(): String
        fun vibrationLabel(): String
        fun keyboardModeLabel(): String

        /** Version string of a waiting update, or null when there is nothing to offer. */
        fun pendingUpdateLabel(): String?

        fun onConnectToPaired()
        fun onMakeDiscoverable()
        fun onDisconnect()
        fun onChooseStyle()
        fun onMoveButtons()
        fun onChooseVibration()
        fun onChooseKeyboardMode()
        fun onBackgroundPicture()
        fun onCheckForUpdates()
        fun onBluetoothSettings()
        fun onHelp()
    }

    private var dialog: Dialog? = null

    /** Kept so the sheet can rebuild itself when the phone is rotated. */
    private var host: Host? = null

    /** Tint for a row's glyph tile, so related actions read as a set. */
    private enum class Tint(val colorRes: Int) {
        NEUTRAL(R.color.pp_control),
        CONNECTION(R.color.pp_connected),
        CONTROLLER(R.color.pp_accent),
        APPEARANCE(R.color.pp_square),
        DANGER(R.color.pp_danger),
    }

    fun show(host: Host) {
        show(host, animate = true)
    }

    /**
     * Rebuild the sheet for the new orientation, if it is open.
     *
     * `MainActivity` declares `configChanges` for orientation and screen size, so Android does
     * not recreate anything when the phone is turned — which means an open dialog keeps the
     * layout it was inflated with. Without this, the portrait list is simply stretched across a
     * landscape window: the wrong number of columns, the wrong row metrics, and a list far too
     * tall for the screen it is now on.
     *
     * Rebuilt without the rise-from-the-bottom animation, because the sheet is already up.
     */
    fun onConfigurationChanged() {
        val current = host ?: return
        if (dialog?.isShowing != true) return
        show(current, animate = false)
    }

    private fun show(host: Host, animate: Boolean) {
        dismiss()
        this.host = host

        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_settings, null, false)
        val list = view.findViewById<SettingsListView>(R.id.sheetList)
        list.columns = activity.resources.getInteger(R.integer.settings_list_columns)
        list.gap = activity.resources.getDimensionPixelSize(R.dimen.list_gap)

        bindConnectionHeader(view, host)
        buildRows(list, host)

        val sheet = Dialog(activity, R.style.Theme_PocketPad_Sheet).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(view)
            setCanceledOnTouchOutside(true)
        }
        sheet.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            if (animate) {
                attributes = attributes.apply {
                    windowAnimations = R.style.Animation_PocketPad_Sheet
                }
            }
            // Keep the game behind it visible rather than dimming the whole screen to black.
            setDimAmount(DIM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        fitToScreen(view)
        dialog = sheet
        sheet.setOnDismissListener { dialog = null }
        sheet.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    /**
     * Tell the list how much room it has, before the first frame is drawn.
     *
     * The height left for rows is whatever the handle and header do not take, and that is only
     * known once the view has been measured. So this runs on the first pre-draw, hands the
     * figure over, and returns false to skip that frame — the list re-measures and the sheet
     * appears at its final size rather than appearing too tall and visibly snapping smaller.
     *
     * The scroll view is capped to the same figure. Shrinking stops at
     * [SettingsColumns.MIN_SCALE] rather than let the text become unreadable, so at the largest
     * accessibility font scales a little may still not fit, and without the cap a
     * bottom-anchored dialog would lose its last rows off the bottom of the screen entirely,
     * with no way to reach them.
     */
    private fun fitToScreen(root: View) {
        val scroll = root.findViewById<ScrollView>(R.id.sheetScroll)
        val list = root.findViewById<SettingsListView>(R.id.sheetList)
        val budget = (activity.resources.displayMetrics.heightPixels * MAX_SCREEN_FRACTION).toInt()

        scroll.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                scroll.viewTreeObserver.removeOnPreDrawListener(this)
                val chrome = root.height - scroll.height
                val allowed = (budget - chrome).coerceAtLeast(MIN_LIST_PX)
                list.maxHeight = allowed
                if (scroll.height > allowed) {
                    scroll.layoutParams = scroll.layoutParams.apply { height = allowed }
                }
                scroll.requestLayout()
                return false
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
            // Portrait drops the device name: it is already spelled out on the status banner
            // above the sheet, and repeating it there pushed "PocketPad" into "Po...".
            text.text = if (activity.resources.getBoolean(R.bool.sheet_status_shows_device)) {
                activity.getString(R.string.sheet_status_connected, name)
            } else {
                activity.getString(R.string.sheet_status_connected_short)
            }
        } else {
            dot.backgroundTintList = colour(R.color.pp_idle)
            text.text = activity.getString(R.string.sheet_status_disconnected)
        }
    }

    /**
     * Build the list, counting the views in each group as it goes.
     *
     * [SettingsListView] needs those counts to know where it may cut for a second column. A
     * heading left at the foot of one column with its rows at the top of the other would be
     * worse than an uneven split, so the cut can only fall between groups.
     */
    private fun buildRows(list: SettingsListView, host: Host) {
        list.removeAllViews()
        val groupSizes = ArrayList<Int>(4)

        var connection = 3 // heading + connect + discoverable
        group(list, R.string.sheet_group_connection)
        row(list, R.drawable.ic_pp_connect, Tint.CONNECTION, R.string.action_connect_paired,
            subtitle = R.string.sheet_sub_connect) { host.onConnectToPaired() }
        row(list, R.drawable.ic_pp_discoverable, Tint.CONNECTION, R.string.action_discoverable,
            subtitle = R.string.sheet_sub_discoverable) { host.onMakeDiscoverable() }
        // Nothing to disconnect from means nothing to offer. Driven by the live HID link, so
        // the row appears and disappears with the connection rather than with a pairing.
        if (host.connectedDeviceName() != null) {
            row(list, R.drawable.ic_pp_disconnect, Tint.DANGER, R.string.action_disconnect,
                chevron = false) { host.onDisconnect() }
            connection++
        }
        groupSizes.add(connection)

        group(list, R.string.sheet_group_controller)
        row(list, R.drawable.ic_pp_style, Tint.CONTROLLER, R.string.action_skin,
            value = host.controllerStyleLabel()) { host.onChooseStyle() }
        row(list, R.drawable.ic_pp_move, Tint.CONTROLLER, R.string.action_edit_layout) { host.onMoveButtons() }
        row(list, R.drawable.ic_pp_vibration, Tint.CONTROLLER, R.string.action_haptics,
            value = host.vibrationLabel()) { host.onChooseVibration() }
        // Was a switch while there were only two choices. PC Keys makes three, so it is a value
        // row with a picker like Style and Vibration — same row, same icon, same group size.
        row(list, R.drawable.ic_pp_tvkeys, Tint.CONTROLLER, R.string.sheet_keyboard_mode,
            subtitle = R.string.sheet_sub_tv_menu_keys,
            value = host.keyboardModeLabel()) { host.onChooseKeyboardMode() }
        groupSizes.add(5)

        group(list, R.string.sheet_group_appearance)
        row(list, R.drawable.ic_pp_background, Tint.APPEARANCE, R.string.action_background) { host.onBackgroundPicture() }
        groupSizes.add(2)

        group(list, R.string.sheet_group_system)
        row(list, R.drawable.ic_pp_updates, Tint.NEUTRAL, R.string.action_check_updates,
            badge = host.pendingUpdateLabel()) { host.onCheckForUpdates() }
        row(list, R.drawable.ic_pp_bluetooth, Tint.NEUTRAL, R.string.action_bt_settings) { host.onBluetoothSettings() }
        row(list, R.drawable.ic_pp_help, Tint.NEUTRAL, R.string.action_help) { host.onHelp() }
        groupSizes.add(4)

        list.setGroupSizes(groupSizes.toIntArray())
    }

    private fun group(parent: SettingsListView, labelRes: Int) {
        val tv = LayoutInflater.from(activity)
            .inflate(R.layout.row_settings_group, parent, false) as SettingsHeadingView
        tv.setText(labelRes)
        parent.addView(tv)
    }

    private fun row(
        parent: SettingsListView,
        iconRes: Int,
        tint: Tint,
        labelRes: Int,
        subtitle: Int? = null,
        value: String? = null,
        badge: String? = null,
        switchOn: Boolean? = null,
        chevron: Boolean = true,
        onTap: () -> Unit,
    ) {
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.row_settings, parent, false) as SettingsRowView

        view.findViewById<ImageView>(R.id.rowIcon).apply {
            setImageResource(iconRes)
            backgroundTintList = colour(tint.colorRes)
            // The tinted tiles are strong colours; dark glyphs read better on them than light.
            // The drawables are drawn white, so the tint is what decides which way round it is.
            imageTintList =
                colour(if (tint == Tint.NEUTRAL) R.color.pp_text else R.color.pp_background)
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

        /**
         * The sheet never covers the whole screen. Leaving a strip of the game visible above it
         * is what makes it read as a panel over the controller rather than as a new screen.
         */
        const val MAX_SCREEN_FRACTION = 0.92f

        /** Floor for the list's allowance, so a bad measurement cannot collapse it to nothing. */
        const val MIN_LIST_PX = 200
    }
}
