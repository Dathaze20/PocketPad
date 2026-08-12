package com.dathaze.pocketpad.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import com.dathaze.pocketpad.hid.GamepadState
import com.dathaze.pocketpad.hid.HidConstants
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen touch gamepad with switchable skins:
 *
 *  - [Skin.PS]      — full PS4/PS5-style pad: d-pad, △○✕□, dual sticks,
 *                     L1/L2/R1/R2, L3/R3, SHARE/OPTIONS and a central PS
 *                     home button (press-and-hold works like the real thing).
 *  - [Skin.SNES]    — Super Nintendo: d-pad, X/A/B/Y in SNES colors, L/R,
 *                     SELECT/START, home.
 *  - [Skin.NES]     — NES: d-pad, B/A, SELECT/START, home.
 *  - [Skin.GAMEBOY] — Game Boy: d-pad, B/A (diagonal), SELECT/START, home.
 *
 * Every skin drives the same HID gamepad report, positionally mapped, so a
 * host (TV, tablet, emulator) sees one consistent controller no matter the
 * skin: bottom face = button 1 (Cross/B), right face = button 2 (Circle/A),
 * left face = button 3 (Square/Y), top face = button 4 (Triangle/X).
 *
 * The view draws and hit-tests every control itself so multitouch works
 * naturally. Layout adapts to portrait/landscape in [onSizeChanged].
 */
class ControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Skin { PS, SNES, NES, GAMEBOY }

    /** Connection state shown by the halo around the PS/home button. */
    enum class LinkState { IDLE, DISCOVERABLE, CONNECTED }

    interface Listener {
        /** Touch state changed — read [touchState] and send a report. */
        fun onTouchStateChanged()
        fun onOpenSettings()
        /** PS/home button held for ~0.7 s while not connected. */
        fun onHomeLongPress()
    }

    var listener: Listener? = null

    /** Live state produced by the touch controls. */
    val touchState = GamepadState()

    /** Haptic feedback engine; intensity is user-configurable. */
    val haptics = Haptics(context)

    /** Drives the halo around the PS/home button. */
    var linkState: LinkState = LinkState.IDLE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var skin: Skin = Skin.PS
        set(value) {
            if (field == value) return
            field = value
            releaseAllPointers()
            applySkin()
            if (width > 0 && height > 0) layoutControls(width.toFloat(), height.toFloat())
            invalidate()
        }

    // ---------------------------------------------------------------- controls

    private abstract inner class Control {
        var cx = 0f
        var cy = 0f
        var radius = 0f
        var pointerId = -1
        val pressed: Boolean get() = pointerId != -1

        open fun contains(x: Float, y: Float): Boolean =
            hypot((x - cx).toDouble(), (y - cy).toDouble()) <= radius * 1.15

        open fun onDown(x: Float, y: Float) {}
        open fun onMove(x: Float, y: Float) {}
        open fun onUp() {}
        abstract fun draw(canvas: Canvas)
    }

    /**
     * Round momentary button bound to one HID button bit. Held as long as the
     * finger stays down — so a long press on PS/home behaves exactly like
     * holding the button on a physical controller.
     */
    private inner class PadButton(
        var label: String,
        val buttonIndex: Int,
        var labelColor: Int = textColor,
        var labelScale: Float = 1f,
        val rimmed: Boolean = false,
        val isHome: Boolean = false
    ) : Control() {
        private val longPressRunnable = Runnable {
            if (linkState != LinkState.CONNECTED) {
                haptics.press()
                listener?.onHomeLongPress()
            }
        }

        override fun onDown(x: Float, y: Float) {
            touchState.setButton(buttonIndex, true)
            haptics.press()
            if (isHome) postDelayed(longPressRunnable, 700)
            notifyChanged()
        }

        override fun onUp() {
            if (isHome) removeCallbacks(longPressRunnable)
            touchState.setButton(buttonIndex, false)
            notifyChanged()
        }

        override fun draw(canvas: Canvas) {
            // Colored rim buttons (face + home) glow in their own color.
            val tint = if (rimmed) labelColor else accentColor
            // Press animation: the cap sinks slightly and glows.
            val r = if (pressed) radius * 0.93f else radius
            if (isHome) drawHalo(canvas)
            if (pressed) {
                glowPaint.color = tint
                glowPaint.alpha = 80
                canvas.drawCircle(cx, cy, radius * 1.12f, glowPaint)
            }
            fillPaint.color = if (pressed) controlPressedColor else controlColor
            canvas.drawCircle(cx, cy, r, fillPaint)
            strokePaint.color = when {
                pressed -> tint
                rimmed -> withAlpha(labelColor, 150)
                else -> outlineColor
            }
            canvas.drawCircle(cx, cy, r, strokePaint)
            // Soft top light so buttons read as raised caps.
            if (!pressed) {
                highlightPaint.alpha = 26
                canvas.drawArc(
                    cx - r * 0.72f, cy - r * 0.72f, cx + r * 0.72f, cy + r * 0.72f,
                    200f, 140f, false, highlightPaint
                )
            }
            textPaint.color = labelColor
            textPaint.textSize = r * 0.72f * labelScale
            canvas.drawText(label, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        }

        /**
         * Neon status halo around the PS/home button, like a controller's
         * light ring: faint when idle, pulsing electric cyan while the phone
         * is discoverable, steady neon green once connected. A dark separator
         * ring underneath keeps it readable on any background.
         */
        private fun drawHalo(canvas: Canvas) {
            haloPaint.strokeWidth = radius * 0.09f
            haloPaint.color = Color.BLACK
            haloPaint.alpha = 150
            canvas.drawCircle(cx, cy, radius * 1.14f, haloPaint)
            when (linkState) {
                LinkState.CONNECTED -> neonRing(canvas, neonGreen, 1f)
                LinkState.DISCOVERABLE -> {
                    val t = (SystemClock.uptimeMillis() % 1400L) / 1400f
                    neonRing(canvas, neonCyan, 0.35f + 0.65f * abs(sin(t * PI)).toFloat())
                    postInvalidateOnAnimation()
                }
                LinkState.IDLE -> neonRing(canvas, Color.WHITE, 0.16f)
            }
        }

        /** Three concentric strokes with alpha falloff = a neon glow. */
        private fun neonRing(canvas: Canvas, color: Int, intensity: Float) {
            val r = radius * 1.30f
            haloPaint.color = color
            haloPaint.strokeWidth = radius * 0.34f      // wide outer bloom
            haloPaint.alpha = (34 * intensity).toInt()
            canvas.drawCircle(cx, cy, r, haloPaint)
            haloPaint.strokeWidth = radius * 0.18f      // mid glow
            haloPaint.alpha = (90 * intensity).toInt()
            canvas.drawCircle(cx, cy, r, haloPaint)
            haloPaint.strokeWidth = radius * 0.07f      // bright core
            haloPaint.alpha = (255 * intensity).toInt()
            canvas.drawCircle(cx, cy, r, haloPaint)
        }
    }

    /** Eight-way d-pad driving the HID hat switch. */
    private inner class Dpad : Control() {
        override fun onDown(x: Float, y: Float) {
            update(x, y, first = true)
        }

        override fun onMove(x: Float, y: Float) = update(x, y, first = false)

        override fun onUp() {
            touchState.hat = HidConstants.HAT_NEUTRAL
            notifyChanged()
        }

        private fun update(x: Float, y: Float, first: Boolean) {
            val dx = x - cx
            val dy = y - cy
            val dist = hypot(dx.toDouble(), dy.toDouble())
            val newHat = if (dist < radius * 0.18) {
                HidConstants.HAT_NEUTRAL
            } else {
                // Angle 0 = up, clockwise; snap to nearest of 8 directions.
                val deg = (Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())) + 360.0) % 360.0
                ((deg + 22.5) / 45.0).toInt() % 8
            }
            if (newHat != touchState.hat) {
                touchState.hat = newHat
                if (first) haptics.press() else haptics.tick()
                notifyChanged()
            } else if (first) {
                haptics.press()
            }
        }

        override fun draw(canvas: Canvas) {
            fillPaint.color = controlColor
            canvas.drawCircle(cx, cy, radius, fillPaint)
            strokePaint.color = if (pressed) accentColor else outlineColor
            canvas.drawCircle(cx, cy, radius, strokePaint)
            // Cross
            val arm = radius * 0.30f
            val len = radius * 0.80f
            fillPaint.color = if (pressed) controlPressedColor else surfaceColor
            canvas.drawRoundRect(cx - arm, cy - len, cx + arm, cy + len, arm * 0.5f, arm * 0.5f, fillPaint)
            canvas.drawRoundRect(cx - len, cy - arm, cx + len, cy + arm, arm * 0.5f, arm * 0.5f, fillPaint)
            // Direction arrows
            arrowPaint.color = if (pressed) textColor else dpadArrowColor
            drawArrow(canvas, cx, cy - len * 0.72f, radius * 0.16f, 0f)
            drawArrow(canvas, cx + len * 0.72f, cy, radius * 0.16f, 90f)
            drawArrow(canvas, cx, cy + len * 0.72f, radius * 0.16f, 180f)
            drawArrow(canvas, cx - len * 0.72f, cy, radius * 0.16f, 270f)
        }
    }

    /** Analog stick driving one axis pair. */
    private inner class Stick(val isLeft: Boolean) : Control() {
        private var knobX = 0f
        private var knobY = 0f

        override fun onDown(x: Float, y: Float) {
            update(x, y)
            haptics.press()
        }

        override fun onMove(x: Float, y: Float) = update(x, y)

        override fun onUp() {
            knobX = 0f
            knobY = 0f
            setAxes(128, 128)
        }

        private fun update(x: Float, y: Float) {
            var dx = x - cx
            var dy = y - cy
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val max = radius * 0.82f
            if (dist > max && dist > 0f) {
                dx *= max / dist
                dy *= max / dist
            }
            knobX = dx
            knobY = dy
            setAxes(
                (128 + (dx / max) * 127).roundToInt().coerceIn(0, 255),
                (128 + (dy / max) * 127).roundToInt().coerceIn(0, 255)
            )
        }

        private fun setAxes(ax: Int, ay: Int) {
            if (isLeft) {
                if (touchState.lx != ax || touchState.ly != ay) {
                    touchState.lx = ax
                    touchState.ly = ay
                    notifyChanged()
                }
            } else {
                if (touchState.rx != ax || touchState.ry != ay) {
                    touchState.rx = ax
                    touchState.ry = ay
                    notifyChanged()
                }
            }
        }

        override fun draw(canvas: Canvas) {
            fillPaint.color = surfaceColor
            canvas.drawCircle(cx, cy, radius, fillPaint)
            strokePaint.color = if (pressed) accentColor else outlineColor
            canvas.drawCircle(cx, cy, radius, strokePaint)
            fillPaint.color = if (pressed) controlPressedColor else controlColor
            canvas.drawCircle(cx + knobX, cy + knobY, radius * 0.52f, fillPaint)
            strokePaint.color = outlineColor
            canvas.drawCircle(cx + knobX, cy + knobY, radius * 0.52f, strokePaint)
        }
    }

    /** Settings gear — opens the connection/skin menu instead of sending input. */
    private inner class GearButton : Control() {
        override fun onDown(x: Float, y: Float) {
            haptics.press()
        }

        override fun onUp() {
            listener?.onOpenSettings()
        }

        override fun draw(canvas: Canvas) {
            fillPaint.color = if (pressed) controlPressedColor else surfaceColor
            canvas.drawCircle(cx, cy, radius, fillPaint)
            strokePaint.color = outlineColor
            canvas.drawCircle(cx, cy, radius, strokePaint)
            textPaint.color = textColor
            textPaint.textSize = radius
            canvas.drawText("⚙", cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        }
    }

    // ------------------------------------------------------------------ paints

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val neonCyan = Color.parseColor("#2BE4FF")
    private val neonGreen = Color.parseColor("#3BFFA8")
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val arrowPath = Path()

    // Background gradients: deep navy fading to indigo (Game Boy skin goes green).
    private val bgTop = Color.parseColor("#0C0D16")
    private val bgBottom = Color.parseColor("#1A1E33")
    private val gbBgTop = Color.parseColor("#131F18")
    private val gbBgBottom = Color.parseColor("#20301F")
    private val dpadArrowColor = Color.parseColor("#A9AEC6")
    private val surfaceColor = Color.parseColor("#1C1E2A")
    private val outlineColor = Color.parseColor("#3A3D52")
    private val controlColor = Color.parseColor("#262939")
    private val controlPressedColor = Color.parseColor("#4A517A")
    private val textColor = Color.parseColor("#E8E9F0")
    private val dimTextColor = Color.parseColor("#8A8FA8")
    private val accentColor = Color.parseColor("#6C79FF")

    // PS face colors
    private val psTriangleColor = Color.parseColor("#2ECC71")
    private val psCircleColor = Color.parseColor("#E74C3C")
    private val psCrossColor = Color.parseColor("#3498DB")
    private val psSquareColor = Color.parseColor("#E78FB3")

    // SNES (Super Famicom) face colors: X blue, A red, B yellow, Y green
    private val snesXColor = Color.parseColor("#3B6EE0")
    private val snesAColor = Color.parseColor("#D93025")
    private val snesBColor = Color.parseColor("#F4B400")
    private val snesYColor = Color.parseColor("#0F9D58")

    private val nesColor = Color.parseColor("#E4404A")
    private val gbColor = Color.parseColor("#B48CC8")

    // ---------------------------------------------------------------- layout

    // Positional face buttons: bottom/right/left/top — HID mapping is fixed,
    // labels and colors change with the skin.
    private val dpad = Dpad()
    private val leftStick = Stick(isLeft = true)
    private val rightStick = Stick(isLeft = false)
    private val faceBottom = PadButton("✕", HidConstants.BTN_CROSS, psCrossColor, rimmed = true)
    private val faceRight = PadButton("○", HidConstants.BTN_CIRCLE, psCircleColor, rimmed = true)
    private val faceLeft = PadButton("□", HidConstants.BTN_SQUARE, psSquareColor, rimmed = true)
    private val faceTop = PadButton("△", HidConstants.BTN_TRIANGLE, psTriangleColor, rimmed = true)
    private val btnL1 = PadButton("L1", HidConstants.BTN_L1)
    private val btnL2 = PadButton("L2", HidConstants.BTN_L2)
    private val btnR1 = PadButton("R1", HidConstants.BTN_R1)
    private val btnR2 = PadButton("R2", HidConstants.BTN_R2)
    private val btnL3 = PadButton("L3", HidConstants.BTN_L3, dimTextColor, 0.9f)
    private val btnR3 = PadButton("R3", HidConstants.BTN_R3, dimTextColor, 0.9f)
    private val btnShare = PadButton("SHARE", HidConstants.BTN_SHARE, dimTextColor, 0.38f)
    private val btnOptions = PadButton("OPTIONS", HidConstants.BTN_OPTIONS, dimTextColor, 0.3f)
    private val btnHome =
        PadButton("PS", HidConstants.BTN_PS, accentColor, 0.8f, rimmed = true, isHome = true)
    private val gear = GearButton()

    private var activeControls: List<Control> = emptyList()

    // Safe-area offsets so controls avoid the camera cutout and gesture bars.
    private var safeLeft = 0f
    private var safeTop = 0f
    private var safeRight = 0f
    private var safeBottom = 0f
    private var offX = 0f
    private var offY = 0f

    init {
        applySkin()
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            safeLeft = bars.left.toFloat()
            safeTop = bars.top.toFloat()
            safeRight = bars.right.toFloat()
            safeBottom = bars.bottom.toFloat()
            if (width > 0 && height > 0) {
                layoutControls(width.toFloat(), height.toFloat())
                invalidate()
            }
            insets
        }
    }

    private fun applySkin() {
        when (skin) {
            Skin.PS -> {
                faceBottom.label = "✕"; faceBottom.labelColor = psCrossColor; faceBottom.labelScale = 1f
                faceRight.label = "○"; faceRight.labelColor = psCircleColor; faceRight.labelScale = 1f
                faceLeft.label = "□"; faceLeft.labelColor = psSquareColor; faceLeft.labelScale = 1f
                faceTop.label = "△"; faceTop.labelColor = psTriangleColor; faceTop.labelScale = 1f
                btnL1.label = "L1"; btnR1.label = "R1"
                btnShare.label = "SHARE"; btnShare.labelScale = 0.38f
                btnOptions.label = "OPTIONS"; btnOptions.labelScale = 0.3f
                btnHome.label = "PS"
                activeControls = listOf(
                    dpad, leftStick, rightStick,
                    faceTop, faceRight, faceBottom, faceLeft,
                    btnL1, btnL2, btnR1, btnR2, btnL3, btnR3,
                    btnShare, btnOptions, btnHome, gear
                )
            }
            Skin.SNES -> {
                faceTop.label = "X"; faceTop.labelColor = snesXColor; faceTop.labelScale = 0.9f
                faceRight.label = "A"; faceRight.labelColor = snesAColor; faceRight.labelScale = 0.9f
                faceBottom.label = "B"; faceBottom.labelColor = snesBColor; faceBottom.labelScale = 0.9f
                faceLeft.label = "Y"; faceLeft.labelColor = snesYColor; faceLeft.labelScale = 0.9f
                btnL1.label = "L"; btnR1.label = "R"
                btnShare.label = "SELECT"; btnShare.labelScale = 0.34f
                btnOptions.label = "START"; btnOptions.labelScale = 0.34f
                btnHome.label = "⌂"
                activeControls = listOf(
                    dpad, faceTop, faceRight, faceBottom, faceLeft,
                    btnL1, btnR1, btnShare, btnOptions, btnHome, gear
                )
            }
            Skin.NES, Skin.GAMEBOY -> {
                val color = if (skin == Skin.NES) nesColor else gbColor
                faceRight.label = "A"; faceRight.labelColor = color; faceRight.labelScale = 0.9f
                faceBottom.label = "B"; faceBottom.labelColor = color; faceBottom.labelScale = 0.9f
                btnShare.label = "SELECT"; btnShare.labelScale = 0.34f
                btnOptions.label = "START"; btnOptions.labelScale = 0.34f
                btnHome.label = "⌂"
                activeControls = listOf(
                    dpad, faceRight, faceBottom, btnShare, btnOptions, btnHome, gear
                )
            }
        }
    }

    private fun releaseAllPointers() {
        for (control in activeControls) {
            if (control.pressed) {
                control.pointerId = -1
                control.onUp()
            }
        }
        touchState.reset()
        notifyChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        layoutControls(w.toFloat(), h.toFloat())
    }

    private fun layoutControls(fullW: Float, fullH: Float) {
        // Lay out inside the safe area (excludes cutout + gesture bars).
        offX = safeLeft
        offY = safeTop
        val w = (fullW - safeLeft - safeRight).coerceAtLeast(1f)
        val h = (fullH - safeTop - safeBottom).coerceAtLeast(1f)
        val portrait = h >= w
        when (skin) {
            Skin.PS -> if (portrait) layoutPsPortrait(w, h) else layoutPsLandscape(w, h)
            Skin.SNES -> if (portrait) layoutSnesPortrait(w, h) else layoutSnesLandscape(w, h)
            Skin.NES, Skin.GAMEBOY ->
                if (portrait) layoutRetroPortrait(w, h) else layoutRetroLandscape(w, h)
        }
    }

    private fun place(c: Control, x: Float, y: Float, r: Float) {
        c.cx = offX + x
        c.cy = offY + y
        c.radius = r
    }

    // ------------------------------------------------------------ PS layouts

    private fun layoutPsPortrait(w: Float, h: Float) {
        val small = w * 0.075f
        val face = w * 0.085f
        place(btnL1, w * 0.10f, h * 0.06f, small)
        place(btnL2, w * 0.26f, h * 0.06f, small)
        place(btnR1, w * 0.90f, h * 0.06f, small)
        place(btnR2, w * 0.74f, h * 0.06f, small)
        place(dpad, w * 0.26f, h * 0.24f, w * 0.19f)
        val fx = w * 0.74f
        val fy = h * 0.24f
        val off = w * 0.125f
        place(faceTop, fx, fy - off, face)
        place(faceRight, fx + off, fy, face)
        place(faceBottom, fx, fy + off, face)
        place(faceLeft, fx - off, fy, face)
        place(leftStick, w * 0.28f, h * 0.52f, w * 0.155f)
        place(rightStick, w * 0.72f, h * 0.52f, w * 0.155f)
        place(btnL3, w * 0.5f, h * 0.455f, small * 0.8f)
        place(btnR3, w * 0.5f, h * 0.585f, small * 0.8f)
        place(btnShare, w * 0.30f, h * 0.72f, small)
        place(btnHome, w * 0.50f, h * 0.72f, small * 1.2f)
        place(btnOptions, w * 0.70f, h * 0.72f, small)
        place(gear, w * 0.10f, h * 0.72f, small * 0.9f)
    }

    private fun layoutPsLandscape(w: Float, h: Float) {
        val small = h * 0.075f
        val face = h * 0.10f
        place(btnL2, w * 0.055f, h * 0.12f, small)
        place(btnL1, w * 0.145f, h * 0.12f, small)
        place(btnR1, w * 0.855f, h * 0.12f, small)
        place(btnR2, w * 0.945f, h * 0.12f, small)
        place(dpad, w * 0.135f, h * 0.52f, min(w, h) * 0.21f)
        val fx = w * 0.865f
        val fy = h * 0.52f
        val off = min(w, h) * 0.145f
        place(faceTop, fx, fy - off, face)
        place(faceRight, fx + off, fy, face)
        place(faceBottom, fx, fy + off, face)
        place(faceLeft, fx - off, fy, face)
        place(leftStick, w * 0.335f, h * 0.70f, h * 0.17f)
        place(rightStick, w * 0.665f, h * 0.70f, h * 0.17f)
        place(btnL3, w * 0.44f, h * 0.88f, small * 0.8f)
        place(btnR3, w * 0.56f, h * 0.88f, small * 0.8f)
        place(btnShare, w * 0.42f, h * 0.14f, small)
        place(btnHome, w * 0.50f, h * 0.18f, small * 1.2f)
        place(btnOptions, w * 0.58f, h * 0.14f, small)
        place(gear, w * 0.045f, h * 0.88f, small * 0.9f)
    }

    // ---------------------------------------------------------- SNES layouts

    private fun layoutSnesPortrait(w: Float, h: Float) {
        val small = w * 0.08f
        val face = w * 0.09f
        place(btnL1, w * 0.12f, h * 0.06f, small)
        place(btnR1, w * 0.88f, h * 0.06f, small)
        place(dpad, w * 0.26f, h * 0.28f, w * 0.20f)
        val fx = w * 0.74f
        val fy = h * 0.28f
        val off = w * 0.13f
        place(faceTop, fx, fy - off, face)
        place(faceRight, fx + off, fy, face)
        place(faceBottom, fx, fy + off, face)
        place(faceLeft, fx - off, fy, face)
        place(btnShare, w * 0.34f, h * 0.52f, small)
        place(btnOptions, w * 0.66f, h * 0.52f, small)
        place(btnHome, w * 0.50f, h * 0.64f, small)
        place(gear, w * 0.10f, h * 0.64f, small * 0.9f)
    }

    private fun layoutSnesLandscape(w: Float, h: Float) {
        val small = h * 0.08f
        val face = h * 0.105f
        place(btnL1, w * 0.09f, h * 0.12f, small)
        place(btnR1, w * 0.91f, h * 0.12f, small)
        place(dpad, w * 0.16f, h * 0.58f, min(w, h) * 0.22f)
        val fx = w * 0.84f
        val fy = h * 0.58f
        val off = min(w, h) * 0.15f
        place(faceTop, fx, fy - off, face)
        place(faceRight, fx + off, fy, face)
        place(faceBottom, fx, fy + off, face)
        place(faceLeft, fx - off, fy, face)
        place(btnShare, w * 0.42f, h * 0.16f, small)
        place(btnOptions, w * 0.58f, h * 0.16f, small)
        place(btnHome, w * 0.50f, h * 0.55f, small)
        place(gear, w * 0.05f, h * 0.88f, small * 0.9f)
    }

    // ------------------------------------------------- NES / Game Boy layouts

    private fun layoutRetroPortrait(w: Float, h: Float) {
        val small = w * 0.08f
        val face = w * 0.105f
        place(dpad, w * 0.26f, h * 0.30f, w * 0.20f)
        // A high on the right, B lower-left of it — Game Boy diagonal.
        place(faceRight, w * 0.82f, h * 0.25f, face)
        place(faceBottom, w * 0.62f, h * 0.35f, face)
        place(btnShare, w * 0.34f, h * 0.54f, small)
        place(btnOptions, w * 0.66f, h * 0.54f, small)
        place(btnHome, w * 0.50f, h * 0.66f, small)
        place(gear, w * 0.10f, h * 0.66f, small * 0.9f)
    }

    private fun layoutRetroLandscape(w: Float, h: Float) {
        val small = h * 0.08f
        val face = h * 0.115f
        place(dpad, w * 0.16f, h * 0.55f, min(w, h) * 0.22f)
        place(faceRight, w * 0.89f, h * 0.44f, face)
        place(faceBottom, w * 0.76f, h * 0.62f, face)
        place(btnShare, w * 0.42f, h * 0.16f, small)
        place(btnOptions, w * 0.58f, h * 0.16f, small)
        place(btnHome, w * 0.50f, h * 0.60f, small)
        place(gear, w * 0.05f, h * 0.88f, small * 0.9f)
    }

    // --------------------------------------------------------------- drawing

    private val bgPaint = Paint()
    private var bgShaderSkin: Skin? = null
    private var bgShaderHeight = 0

    private fun ensureBackground() {
        if (bgShaderSkin == skin && bgShaderHeight == height) return
        val top = if (skin == Skin.GAMEBOY) gbBgTop else bgTop
        val bottom = if (skin == Skin.GAMEBOY) gbBgBottom else bgBottom
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP
        )
        bgShaderSkin = skin
        bgShaderHeight = height
    }

    override fun onDraw(canvas: Canvas) {
        ensureBackground()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        for (control in activeControls) control.draw(canvas)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, size: Float, rotation: Float) {
        arrowPath.reset()
        arrowPath.moveTo(0f, -size)
        arrowPath.lineTo(size, size * 0.7f)
        arrowPath.lineTo(-size, size * 0.7f)
        arrowPath.close()
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotation)
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.restore()
    }

    // ----------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
                val control = activeControls.firstOrNull { !it.pressed && it.contains(x, y) }
                if (control != null) {
                    control.pointerId = id
                    control.onDown(x, y)
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                var moved = false
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val control = activeControls.firstOrNull { it.pointerId == id } ?: continue
                    control.onMove(event.getX(i), event.getY(i))
                    moved = true
                }
                if (moved) invalidate()
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    for (control in activeControls) {
                        if (control.pressed) {
                            control.pointerId = -1
                            control.onUp()
                        }
                    }
                } else {
                    val id = event.getPointerId(event.actionIndex)
                    activeControls.firstOrNull { it.pointerId == id }?.let {
                        it.pointerId = -1
                        it.onUp()
                    }
                }
                invalidate()
            }
        }
        return true
    }

    private fun notifyChanged() {
        listener?.onTouchStateChanged()
    }
}
