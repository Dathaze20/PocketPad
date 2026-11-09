package com.dathaze.pocketpad.update

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dathaze.pocketpad.R

/**
 * The quiet "there's a new version" strip, and the once-a-day check behind it.
 *
 * Same experience as My Music: a few seconds after the app opens it asks GitHub whether
 * anything newer is published, says nothing at all if not, and otherwise puts a small strip at
 * the bottom with the version, the download size, an Update Now button and a dismiss cross.
 * Dismissing hides that version for a day. The result is cached so the strip survives a
 * relaunch without another request, and so there is at most one request a day.
 *
 * What is deliberately *not* ported is the finding. My Music derives a version from a Git tag;
 * PocketPad assigns `versionCode` by hand and publishes `version.json` beside the APK, so the
 * number has to be read from there. [AppUpdater] already does that, with the checks that go
 * with it — official repository URL, trusted redirects, published SHA-256, size, package
 * identity, version, and Android's own install confirmation. This class calls it and renders
 * the answer; none of those safeguards are bypassed or re-implemented.
 *
 * Nothing downloads on its own. Update Now opens the existing [UpdateFlow] dialog, which is the
 * same release-notes-then-Download step the manual menu item has always shown.
 *
 * No notification permission is requested: this is a view inside the app, not a system
 * notification.
 */
class UpdateNoticeController(
    private val activity: Activity,
    private val updater: AppUpdater,
    private val onOpenUpdateFlow: (AvailableUpdate) -> Unit,
) {

    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private var banner: View? = null
    private var checkStarted = false
    private var found: UpdateNotice.Remembered? = null

    /** The update the strip is currently offering, kept so Update Now can open it. */
    private var offer: AvailableUpdate? = null

    /** Version to show on the settings row's badge, or null when there is nothing waiting. */
    fun pendingUpdateLabel(): String? {
        val remembered = found ?: readRemembered() ?: return null
        if (!UpdateNotice.shouldMarkSettings(remembered, updater.installedVersionCode)) return null
        return activity.getString(R.string.sheet_badge_update)
    }

    /**
     * Start the automatic check, once per launch, a few seconds from now.
     *
     * Called from `onCreate`. The delay is the point: nothing competes with startup or with the
     * Bluetooth link coming up, and a game in progress is never interrupted by this.
     */
    fun scheduleAutomaticCheck() {
        if (checkStarted) return
        checkStarted = true
        main.postDelayed(::runAutomaticCheck, UpdateNotice.START_DELAY_MS)
    }

    /** Re-evaluate the strip, e.g. after returning to the app. */
    fun refresh() {
        found = readRemembered()
        renderBanner()
    }

    fun dispose() {
        main.removeCallbacksAndMessages(null)
        removeBanner()
    }

    // ------------------------------------------------------------------ check

    private fun runAutomaticCheck() {
        if (activity.isFinishing || activity.isDestroyed) return

        // Whatever was found last time still stands, so the strip works offline and on every
        // launch inside the window without asking again.
        found = readRemembered()
        renderBanner()

        val now = System.currentTimeMillis()
        if (!UpdateNotice.shouldCheckNetwork(prefs.getLong(KEY_CHECKED_AT, 0L), now)) return

        Thread({
            val state = try {
                updater.check()
            } catch (_: Exception) {
                // Offline, GitHub having a bad day, a rate limit. None of that is the user's
                // problem and none of it is worth a word on screen; try again next launch
                // rather than recording this as a check that happened.
                UpdateState.Failed("")
            }
            main.post { onCheckFinished(state) }
        }, "pocketpad-update-notice").apply { isDaemon = true }.start()
    }

    private fun onCheckFinished(state: UpdateState) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (UpdateNotice.isSettled(state)) {
            prefs.edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
        }

        offer = (state as? UpdateState.Available)?.update
        found = UpdateNotice.remember(state)
        writeRemembered(found)
        renderBanner()
    }

    // ----------------------------------------------------------------- banner

    private fun renderBanner() {
        val remembered = found
        val show = UpdateNotice.shouldShowBanner(
            found = remembered,
            installedVersionCode = updater.installedVersionCode,
            snoozedVersionCode = prefs.getInt(KEY_SNOOZED_CODE, 0),
            snoozedAt = prefs.getLong(KEY_SNOOZED_AT, 0L),
            now = System.currentTimeMillis(),
        )
        if (!show || remembered == null) {
            removeBanner()
            return
        }
        val existing = banner
        if (existing != null) {
            bind(existing, remembered)
            return
        }
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val view = LayoutInflater.from(activity).inflate(R.layout.view_update_banner, root, false)
        bind(view, remembered)

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = android.view.Gravity.BOTTOM }
        view.layoutParams = params

        // Keep it clear of the gesture bar and of a side cutout in landscape, and inset it from
        // the edges the way My Music's strip is.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            (v.layoutParams as? FrameLayout.LayoutParams)?.let {
                val edge = (EDGE_DP * activity.resources.displayMetrics.density).toInt()
                it.leftMargin = bars.left + edge
                it.rightMargin = bars.right + edge
                it.bottomMargin = bars.bottom + edge
                v.layoutParams = it
            }
            insets
        }
        root.addView(view)
        banner = view
        ViewCompat.requestApplyInsets(view)
    }

    private fun bind(view: View, remembered: UpdateNotice.Remembered) {
        val size = readableSize(remembered.apkBytes)
        view.findViewById<TextView>(R.id.bannerTitle).text =
            activity.getString(R.string.banner_title, remembered.versionName)
        view.findViewById<TextView>(R.id.bannerSubtitle).text = if (size.isEmpty()) {
            activity.getString(R.string.banner_subtitle_no_size, updater.installedVersionName)
        } else {
            activity.getString(R.string.banner_subtitle, updater.installedVersionName, size)
        }

        view.findViewById<Button>(R.id.bannerAction).setOnClickListener {
            removeBanner()
            // The same dialog the menu item has always opened: release notes, then a Download
            // the user presses. Nothing is downloaded or installed without that press.
            offer?.let(onOpenUpdateFlow) ?: onOpenUpdateFlowFallback()
        }
        view.findViewById<Button>(R.id.bannerDismiss).setOnClickListener {
            prefs.edit()
                .putInt(KEY_SNOOZED_CODE, remembered.versionCode)
                .putLong(KEY_SNOOZED_AT, System.currentTimeMillis())
                .apply()
            removeBanner()
        }
    }

    /**
     * Update Now after a relaunch, when the strip came from the cache and no live result is
     * held. Opening the flow makes it check for itself, which is the honest thing to do rather
     * than acting on a remembered URL.
     */
    private fun onOpenUpdateFlowFallback() {
        activity.findViewById<View>(android.R.id.content)?.post {
            UpdateFlow(activity).start()
        }
    }

    private fun removeBanner() {
        banner?.let { (it.parent as? ViewGroup)?.removeView(it) }
        banner = null
    }

    // ---------------------------------------------------------------- storage

    private fun readRemembered(): UpdateNotice.Remembered? {
        val code = prefs.getInt(KEY_FOUND_CODE, 0)
        if (code <= 0) return null
        return UpdateNotice.Remembered(
            versionCode = code,
            versionName = prefs.getString(KEY_FOUND_NAME, "").orEmpty(),
            apkBytes = prefs.getLong(KEY_FOUND_BYTES, 0L),
        )
    }

    private fun writeRemembered(value: UpdateNotice.Remembered?) {
        val editor = prefs.edit()
        if (value == null) {
            editor.remove(KEY_FOUND_CODE).remove(KEY_FOUND_NAME).remove(KEY_FOUND_BYTES)
        } else {
            editor.putInt(KEY_FOUND_CODE, value.versionCode)
                .putString(KEY_FOUND_NAME, value.versionName)
                .putLong(KEY_FOUND_BYTES, value.apkBytes)
        }
        editor.apply()
    }

    private fun readableSize(bytes: Long): String =
        if (bytes > 0) String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0) else ""

    private companion object {
        const val PREFS = "pocketpad_update_notice"
        const val KEY_CHECKED_AT = "checked_at"
        const val KEY_FOUND_CODE = "found_code"
        const val KEY_FOUND_NAME = "found_name"
        const val KEY_FOUND_BYTES = "found_bytes"
        const val KEY_SNOOZED_CODE = "snoozed_code"
        const val KEY_SNOOZED_AT = "snoozed_at"
        const val EDGE_DP = 12
    }
}
