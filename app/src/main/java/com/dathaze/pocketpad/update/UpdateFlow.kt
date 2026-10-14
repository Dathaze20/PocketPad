package com.dathaze.pocketpad.update

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.dathaze.pocketpad.R

/**
 * The "Check for updates" dialog, and the thread the checking happens on.
 *
 * Started only when the user picks it from Settings. Nothing here runs on its own: an update
 * must never interrupt a game or drop the Bluetooth link, so there is no check on launch, no
 * background poll and no notification. The HID connection is left completely alone — the dialog
 * sits over the controller and the pad keeps working underneath it.
 *
 * Network work happens on a plain background thread, the same shape [com.dathaze.pocketpad.usb]
 * already uses for the USB reader, and results are posted back to the main thread. If the user
 * dismisses the dialog mid-download the work finishes anyway and reports with a toast, rather
 * than being abandoned halfway through writing a file.
 */
class UpdateFlow(private val activity: Activity) {

    private val updater = AppUpdater(activity)
    private val main = Handler(Looper.getMainLooper())

    private var dialog: AlertDialog? = null
    private var messageView: TextView? = null
    private var notesView: TextView? = null
    private var progressView: ProgressBar? = null

    private var state: UpdateState = UpdateState.Idle
    private var busy = false

    /**
     * The update that was being installed when Android asked for permission.
     *
     * Kept so that coming back from the settings screen can put the offer straight back in
     * front of the user. Without it, granting permission left the dialog stuck on "Android
     * needs permission" with no way forward but closing it and checking all over again.
     */
    private var offered: AvailableUpdate? = null

    /**
     * Opens the dialog, and starts a check unless one is already running.
     *
     * Reopening while a download is in flight shows that download's progress rather than
     * starting a second one — the dialog can be dismissed mid-download, and coming back to it
     * should be how you see where it got to.
     */
    fun start() {
        showDialog()
        if (!busy) check()
    }

    // ----------------------------------------------------------------- steps

    private fun check() {
        busy = true
        // A fresh check replaces whatever was offered before, so a stale one cannot be
        // resurrected by a later return from the settings screen.
        offered = null
        render(UpdateState.Checking)
        onBackgroundThread {
            val result = updater.check()
            main.post {
                busy = false
                render(result)
            }
        }
    }

    /**
     * Called from the activity's `onResume`.
     *
     * The one thing it does is notice that the user has come back from Android's
     * "install unknown apps" screen having granted permission, and put the offer back on
     * screen so they can carry on with one tap. It deliberately does **not** resume the
     * download itself: granting a permission is not the same as asking to install, and an
     * update that started on its own because the user walked through a settings screen would
     * be exactly the silent behaviour this updater is supposed to avoid.
     */
    fun onActivityResumed() {
        if (state !is UpdateState.NeedsInstallPermission) return
        val pending = offered ?: return
        if (!updater.canInstallPackages()) return
        render(UpdateState.Available(pending))
    }

    private fun download() {
        val available = (state as? UpdateState.Available)?.update ?: return
        if (busy) return
        offered = available
        busy = true
        render(UpdateState.Downloading(0))
        onBackgroundThread {
            val result = updater.downloadAndInstall(available) { percent ->
                main.post {
                    // Only while still downloading: a late progress report must not overwrite
                    // the final state.
                    if (state is UpdateState.Downloading) render(UpdateState.Downloading(percent))
                }
            }
            main.post {
                busy = false
                render(result)
            }
        }
    }

    private fun openInstallPermission() {
        if (!updater.openInstallPermissionSettings()) {
            render(
                UpdateState.Failed(
                    activity.getString(R.string.update_no_permission_screen)
                )
            )
        }
    }

    private fun onBackgroundThread(work: () -> Unit) {
        Thread(work, "pocketpad-updater").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- dialog

    private fun showDialog() {
        if (activity.isFinishing || activity.isDestroyed) return
        // Already up: re-render rather than stacking a second dialog on top of the first.
        dialog?.let { render(state); return }
        val view = activity.layoutInflater.inflate(R.layout.dialog_update, null)
        messageView = view.findViewById(R.id.update_message)
        notesView = view.findViewById(R.id.update_notes)
        progressView = view.findViewById(R.id.update_progress)

        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setView(view)
            // Neither button is given an action here. Both are relabelled and rewired per
            // state in render(), which is also what keeps "Download and install" from
            // existing before there is anything to install.
            .setPositiveButton(R.string.update_action_download, null)
            .setNegativeButton(R.string.update_close, null)
            .create()
            .also { created ->
                created.setOnDismissListener {
                    dialog = null
                    messageView = null
                    notesView = null
                    progressView = null
                }
                created.show()
            }
        render(state)
    }

    /**
     * Writes [next] into the dialog.
     *
     * Also the only place the state is stored, so what is displayed and what the buttons do
     * cannot disagree. When the dialog has been dismissed the terminal states still have to
     * reach the user, which is what the toast is for — a download that finished after the
     * dialog was closed should not simply vanish.
     */
    private fun render(next: UpdateState) {
        state = next
        val open = dialog
        if (open == null) {
            toastIfTerminal(next)
            return
        }

        val message = messageView ?: return
        val notes = notesView ?: return
        val progress = progressView ?: return

        notes.visibility = View.GONE
        progress.visibility = View.GONE

        val positive = open.getButton(AlertDialog.BUTTON_POSITIVE)
        val negative = open.getButton(AlertDialog.BUTTON_NEGATIVE)
        positive?.visibility = View.GONE
        negative?.setText(R.string.update_close)
        open.setCancelable(true)

        when (next) {
            UpdateState.Idle -> {
                message.text = installedLine()
            }

            UpdateState.Checking -> {
                message.text = activity.getString(R.string.update_checking)
                progress.isIndeterminate = true
                progress.visibility = View.VISIBLE
                open.setCancelable(false)
            }

            UpdateState.UpToDate -> {
                message.text = activity.getString(
                    R.string.update_up_to_date, updater.installedVersionName
                )
            }

            is UpdateState.Available -> {
                val size = next.update.readableSize
                message.text = if (size.isEmpty()) {
                    activity.getString(
                        R.string.update_available, next.update.versionName,
                        updater.installedVersionName
                    )
                } else {
                    activity.getString(
                        R.string.update_available_sized, next.update.versionName,
                        updater.installedVersionName, size
                    )
                }
                if (next.update.notes.isNotEmpty()) {
                    notes.text = next.update.notes
                    notes.visibility = View.VISIBLE
                }
                positive?.apply {
                    setText(R.string.update_action_download)
                    visibility = View.VISIBLE
                    setOnClickListener { download() }
                }
            }

            is UpdateState.Downloading -> {
                message.text =
                    activity.getString(R.string.update_downloading, next.percent)
                progress.isIndeterminate = false
                progress.progress = next.percent
                progress.visibility = View.VISIBLE
                negative?.setText(R.string.update_hide)
            }

            UpdateState.ReadyToInstall -> {
                message.text = activity.getString(R.string.update_ready)
            }

            UpdateState.NeedsInstallPermission -> {
                message.text = activity.getString(R.string.update_needs_permission)
                positive?.apply {
                    setText(R.string.update_action_allow)
                    visibility = View.VISIBLE
                    setOnClickListener { openInstallPermission() }
                }
            }

            is UpdateState.Failed -> {
                message.text = next.message
            }
        }
    }

    /** The dialog is gone, so say how it ended without stealing focus from the controller. */
    private fun toastIfTerminal(next: UpdateState) {
        val text = when (next) {
            UpdateState.ReadyToInstall -> activity.getString(R.string.update_ready)
            is UpdateState.Failed -> next.message
            UpdateState.NeedsInstallPermission ->
                activity.getString(R.string.update_needs_permission)
            else -> return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }

    private fun installedLine(): String =
        activity.getString(R.string.update_installed, updater.installedVersionName)
}
