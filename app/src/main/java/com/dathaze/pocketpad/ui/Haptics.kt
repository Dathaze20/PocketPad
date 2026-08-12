package com.dathaze.pocketpad.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Button-press haptics with three user-selectable intensities.
 * "Strong" uses a crisp click on every press so the on-screen pad
 * feels as physical as possible.
 */
class Haptics(context: Context) {

    enum class Level { OFF, LIGHT, STRONG }

    var level: Level = Level.STRONG

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** Fired on every button/stick press. */
    fun press() {
        val v = vibrator ?: return
        when (level) {
            Level.OFF -> return
            Level.LIGHT -> v.vibrate(predefinedOr(VibrationEffect.EFFECT_TICK, 10, 120))
            Level.STRONG -> v.vibrate(predefinedOr(VibrationEffect.EFFECT_CLICK, 25, 255))
        }
    }

    /** Fired when the d-pad direction changes while held. */
    fun tick() {
        val v = vibrator ?: return
        when (level) {
            Level.OFF -> return
            Level.LIGHT -> v.vibrate(VibrationEffect.createOneShot(8, 90))
            Level.STRONG -> v.vibrate(predefinedOr(VibrationEffect.EFFECT_TICK, 12, 180))
        }
    }

    private fun predefinedOr(effectId: Int, fallbackMs: Long, fallbackAmp: Int): VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(effectId)
        } else {
            VibrationEffect.createOneShot(fallbackMs, fallbackAmp)
        }
}
