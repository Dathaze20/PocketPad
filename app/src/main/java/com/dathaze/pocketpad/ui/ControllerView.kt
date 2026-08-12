package com.dathaze.pocketpad.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.dathaze.pocketpad.hid.GamepadState
import com.dathaze.pocketpad.hid.HidConstants
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen touch gamepad. Draws and hit-tests every control itself so
 * multitouch works naturally (each finger is tracked to the control it
 * landed on). Layout adapts to portrait/landscape in [onSizeChanged].
 */
class ControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        /** Touch state changed — read [touchState] and send a report. */
        fun onTouchStateChanged()
        fun onOpenSettings()
    }

    var listener: Listener? = null

    /** Live state produced by the touch controls. */
    val touchState = GamepadState()

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

    /** Round momentary button bound to one HID button bit. */
    private inner class PadButton(
        val label: String,
        val buttonIndex: Int,
        val labelColor: Int = textColor,
        val labelScale: Float = 1f
    ) : Control() {
        override fun onDown(x: Float, y: Float) {
            touchState.setButton(buttonIndex, true)
            haptic()
            notifyChanged()
        }

        override fun onUp() {
            touchState.setButton(buttonIndex, false)
            notifyChanged()
        }

        override fun draw(canvas: Canvas) {
            fillPaint.color = if (pressed) controlPressedColor else controlColor
            canvas.drawCircle(cx, cy, radius, fillPaint)
            strokePaint.color = if (pressed) accentColor else outlineColor
            canvas.drawCircle(cx, cy, radius, strokePaint)
            textPaint.color = labelColor
            textPaint.textSize = radius * 0.72f * labelScale
            canvas.drawText(label, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        }
    }

    /** Eight-way d-pad driving the HID hat switch. */
    private inner class Dpad : Control() {
        override fun onDown(x: Float, y: Float) {
            update(x, y)
            haptic()
        }

        override fun onMove(x: Float, y: Float) = update(x, y)

        override fun onUp() {
            touchState.hat = HidConstants.HAT_NEUTRAL
            notifyChanged()
        }

        private fun update(x: Float, y: Float) {
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
                notifyChanged()
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
            arrowPaint.color = if (pressed) textColor else dimTextColor
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
            haptic()
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

    /** Settings gear — opens the connection menu instead of sending input. */
    private inner class GearButton : Control() {
        override fun onDown(x: Float, y: Float) {
            haptic()
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
    private val arrowPath = Path()

    private val backgroundColor = Color.parseColor("#101118")
    private val surfaceColor = Color.parseColor("#1C1E2A")
    private val outlineColor = Color.parseColor("#3A3D52")
    private val controlColor = Color.parseColor("#262939")
    private val controlPressedColor = Color.parseColor("#4A517A")
    private val textColor = Color.parseColor("#E8E9F0")
    private val dimTextColor = Color.parseColor("#8A8FA8")
    private val accentColor = Color.parseColor("#6C79FF")
    private val triangleColor = Color.parseColor("#2ECC71")
    private val circleColor = Color.parseColor("#E74C3C")
    private val crossColor = Color.parseColor("#3498DB")
    private val squareColor = Color.parseColor("#E78FB3")

    // ---------------------------------------------------------------- layout

    private val dpad = Dpad()
    private val leftStick = Stick(isLeft = true)
    private val rightStick = Stick(isLeft = false)
    private val btnTriangle = PadButton("△", HidConstants.BTN_TRIANGLE, triangleColor)
    private val btnCircle = PadButton("○", HidConstants.BTN_CIRCLE, circleColor)
    private val btnCross = PadButton("✕", HidConstants.BTN_CROSS, crossColor)
    private val btnSquare = PadButton("□", HidConstants.BTN_SQUARE, squareColor)
    private val btnL1 = PadButton("L1", HidConstants.BTN_L1)
    private val btnL2 = PadButton("L2", HidConstants.BTN_L2)
    private val btnR1 = PadButton("R1", HidConstants.BTN_R1)
    private val btnR2 = PadButton("R2", HidConstants.BTN_R2)
    private val btnL3 = PadButton("L3", HidConstants.BTN_L3, dimTextColor, 0.9f)
    private val btnR3 = PadButton("R3", HidConstants.BTN_R3, dimTextColor, 0.9f)
    private val btnShare = PadButton("SH", HidConstants.BTN_SHARE, dimTextColor, 0.8f)
    private val btnOptions = PadButton("OPT", HidConstants.BTN_OPTIONS, dimTextColor, 0.62f)
    private val btnPs = PadButton("PS", HidConstants.BTN_PS, accentColor, 0.8f)
    private val gear = GearButton()

    private val controls: List<Control> = listOf(
        dpad, leftStick, rightStick,
        btnTriangle, btnCircle, btnCross, btnSquare,
        btnL1, btnL2, btnR1, btnR2, btnL3, btnR3,
        btnShare, btnOptions, btnPs, gear
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val fw = w.toFloat()
        val fh = h.toFloat()
        if (h >= w) layoutPortrait(fw, fh) else layoutLandscape(fw, fh)
    }

    private fun place(c: Control, x: Float, y: Float, r: Float) {
        c.cx = x
        c.cy = y
        c.radius = r
    }

    private fun layoutPortrait(w: Float, h: Float) {
        val small = w * 0.075f
        val face = w * 0.085f
        // Shoulder row
        place(btnL1, w * 0.10f, h * 0.06f, small)
        place(btnL2, w * 0.26f, h * 0.06f, small)
        place(btnR1, w * 0.90f, h * 0.06f, small)
        place(btnR2, w * 0.74f, h * 0.06f, small)
        // D-pad and face cluster
        place(dpad, w * 0.26f, h * 0.24f, w * 0.19f)
        val fx = w * 0.74f
        val fy = h * 0.24f
        val off = w * 0.125f
        place(btnTriangle, fx, fy - off, face)
        place(btnCircle, fx + off, fy, face)
        place(btnCross, fx, fy + off, face)
        place(btnSquare, fx - off, fy, face)
        // Sticks
        place(leftStick, w * 0.28f, h * 0.52f, w * 0.155f)
        place(rightStick, w * 0.72f, h * 0.52f, w * 0.155f)
        place(btnL3, w * 0.5f, h * 0.455f, small * 0.8f)
        place(btnR3, w * 0.5f, h * 0.585f, small * 0.8f)
        // Bottom row
        place(btnShare, w * 0.30f, h * 0.72f, small)
        place(btnPs, w * 0.50f, h * 0.72f, small * 1.1f)
        place(btnOptions, w * 0.70f, h * 0.72f, small)
        place(gear, w * 0.10f, h * 0.72f, small * 0.9f)
    }

    private fun layoutLandscape(w: Float, h: Float) {
        val small = h * 0.075f
        val face = h * 0.10f
        // Shoulders in the top corners
        place(btnL2, w * 0.055f, h * 0.12f, small)
        place(btnL1, w * 0.145f, h * 0.12f, small)
        place(btnR1, w * 0.855f, h * 0.12f, small)
        place(btnR2, w * 0.945f, h * 0.12f, small)
        // D-pad and face cluster
        place(dpad, w * 0.135f, h * 0.52f, min(w, h) * 0.21f)
        val fx = w * 0.865f
        val fy = h * 0.52f
        val off = min(w, h) * 0.145f
        place(btnTriangle, fx, fy - off, face)
        place(btnCircle, fx + off, fy, face)
        place(btnCross, fx, fy + off, face)
        place(btnSquare, fx - off, fy, face)
        // Sticks toward the middle-bottom
        place(leftStick, w * 0.335f, h * 0.70f, h * 0.17f)
        place(rightStick, w * 0.665f, h * 0.70f, h * 0.17f)
        place(btnL3, w * 0.44f, h * 0.88f, small * 0.8f)
        place(btnR3, w * 0.56f, h * 0.88f, small * 0.8f)
        // Middle row
        place(btnShare, w * 0.42f, h * 0.14f, small)
        place(btnPs, w * 0.50f, h * 0.17f, small * 1.1f)
        place(btnOptions, w * 0.58f, h * 0.14f, small)
        place(gear, w * 0.045f, h * 0.88f, small * 0.9f)
    }

    // --------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backgroundColor)
        for (control in controls) control.draw(canvas)
    }

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
                val control = controls.firstOrNull { !it.pressed && it.contains(x, y) }
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
                    val control = controls.firstOrNull { it.pointerId == id } ?: continue
                    control.onMove(event.getX(i), event.getY(i))
                    moved = true
                }
                if (moved) invalidate()
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    for (control in controls) {
                        if (control.pressed) {
                            control.pointerId = -1
                            control.onUp()
                        }
                    }
                } else {
                    val id = event.getPointerId(event.actionIndex)
                    controls.firstOrNull { it.pointerId == id }?.let {
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

    private fun haptic() {
        performHapticFeedback(
            HapticFeedbackConstants.VIRTUAL_KEY,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }
}
