package com.dathaze.pocketpad.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
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

        // -------- molded-plastic rendering ------------------------------
        // Shaders depend on geometry, so they are rebuilt only when a control
        // moves or resizes — not on every frame.

        private var shaderX = Float.NaN
        private var shaderY = Float.NaN
        private var shaderR = Float.NaN
        private var capRaised: LinearGradient? = null
        private var capSunken: LinearGradient? = null

        private fun ensureShaders() {
            if (cx == shaderX && cy == shaderY && radius == shaderR) return
            shaderX = cx; shaderY = cy; shaderR = radius
            // Light falls from above: lit crown, shadowed skirt. Pressed caps
            // invert, so the top edge reads as the deepest part of the dish.
            capRaised = LinearGradient(
                cx, cy - radius, cx, cy + radius, capLit, capShade, Shader.TileMode.CLAMP
            )
            capSunken = LinearGradient(
                cx, cy - radius, cx, cy + radius, capPressedDeep, capPressedRise,
                Shader.TileMode.CLAMP
            )
        }

        /**
         * Draws a physical-looking button: a recessed socket, a drop shadow,
         * a gradient cap, and a bevel that is bright along the top edge and
         * dark along the bottom. Pressing sinks the cap into the socket, kills
         * the shadow and flips the bevel — the same cues a real controller
         * gives your eye.
         */
        fun drawMoldedCap(canvas: Canvas, r: Float, rimColor: Int) {
            ensureShaders()
            val sink = if (pressed) r * 0.07f else 0f
            val capY = cy + sink

            // Socket the cap sits in.
            moldPaint.shader = null
            moldPaint.color = socketColor
            canvas.drawCircle(cx, cy + r * 0.04f, r * 1.14f, moldPaint)
            bevelPaint.strokeWidth = r * 0.08f
            bevelPaint.color = withAlpha(Color.BLACK, 140)
            arcRect(cx, cy + r * 0.04f, r * 1.10f)
            canvas.drawArc(tmpRect, 190f, 160f, false, bevelPaint)
            bevelPaint.color = withAlpha(Color.WHITE, 22)
            canvas.drawArc(tmpRect, 10f, 160f, false, bevelPaint)

            // Cast shadow — only while the cap stands proud of the socket.
            if (!pressed) {
                moldPaint.color = withAlpha(Color.BLACK, 90)
                canvas.drawCircle(cx, capY + r * 0.10f, r * 0.98f, moldPaint)
            }

            // The cap itself.
            moldPaint.shader = if (pressed) capSunken else capRaised
            canvas.drawCircle(cx, capY, r, moldPaint)
            moldPaint.shader = null

            // Bevelled edge.
            arcRect(cx, capY, r * 0.955f)
            bevelPaint.strokeWidth = r * 0.10f
            bevelPaint.color = withAlpha(Color.WHITE, if (pressed) 18 else 66)
            canvas.drawArc(tmpRect, 186f, 150f, false, bevelPaint)
            bevelPaint.color = withAlpha(Color.BLACK, if (pressed) 130 else 85)
            canvas.drawArc(tmpRect, 6f, 150f, false, bevelPaint)

            // Coloured ring for face/home buttons.
            if (rimColor != 0) {
                strokePaint.color = withAlpha(rimColor, if (pressed) 255 else 165)
                canvas.drawCircle(cx, capY, r * 0.98f, strokePaint)
            }

            // Specular glint on the crown.
            if (!pressed) {
                arcRect(cx, capY - r * 0.10f, r * 0.62f)
                bevelPaint.strokeWidth = r * 0.13f
                bevelPaint.color = withAlpha(Color.WHITE, 30)
                canvas.drawArc(tmpRect, 205f, 105f, false, bevelPaint)
            }
        }

        /** Vertical offset the cap currently sits at (for labels). */
        fun capSink(r: Float): Float = if (pressed) r * 0.07f else 0f
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
            val tint = if (rimmed) labelColor else accentColor
            if (isHome) drawHalo(canvas)
            if (pressed) {
                glowPaint.color = tint
                glowPaint.alpha = 70
                canvas.drawCircle(cx, cy, radius * 1.20f, glowPaint)
            }
            drawMoldedCap(canvas, radius, if (rimmed) labelColor else 0)

            // Engraved label: a dark offset copy under the glyph makes it read
            // as stamped into the plastic rather than printed on top.
            val capY = cy + capSink(radius)
            textPaint.textSize = radius * 0.72f * labelScale
            val baseline = capY - (textPaint.ascent() + textPaint.descent()) / 2f
            textPaint.color = withAlpha(Color.BLACK, 120)
            canvas.drawText(label, cx, baseline + radius * 0.035f, textPaint)
            textPaint.color = if (pressed) brighten(labelColor) else labelColor
            canvas.drawText(label, cx, baseline, textPaint)
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

        /**
         * One moulded cross on a pivot, the way a Game Boy or DualShock pad
         * works: a single continuous piece with no seams cut through it, sunk
         * in a shallow dish. Pushing a direction rocks the whole cross that
         * way — the pressed side dips into shadow and the opposite side lifts
         * — so a thumb resting in the middle can roll between directions.
         */
        override fun draw(canvas: Canvas) {
            val arm = radius * 0.30f
            val len = radius * 0.84f
            val hat = touchState.hat
            val up = hat == 7 || hat == 0 || hat == 1
            val right = hat == 1 || hat == 2 || hat == 3
            val down = hat == 3 || hat == 4 || hat == 5
            val left = hat == 5 || hat == 6 || hat == 7

            ensureCross(arm, len)

            // Shallow housing dish.
            moldPaint.shader = null
            moldPaint.color = socketColor
            canvas.drawCircle(cx, cy + radius * 0.03f, radius, moldPaint)
            bevelPaint.strokeWidth = radius * 0.07f
            bevelPaint.color = withAlpha(Color.BLACK, 150)
            arcRect(cx, cy + radius * 0.03f, radius * 0.965f)
            canvas.drawArc(tmpRect, 190f, 160f, false, bevelPaint)
            bevelPaint.color = withAlpha(Color.WHITE, 26)
            canvas.drawArc(tmpRect, 10f, 160f, false, bevelPaint)

            // How far the cross tips, and toward where.
            val tilt = radius * 0.05f
            val tx = (if (right) 1f else 0f) - (if (left) 1f else 0f)
            val ty = (if (down) 1f else 0f) - (if (up) 1f else 0f)
            val norm = if (tx != 0f && ty != 0f) 0.707f else 1f

            canvas.save()
            canvas.translate(cx + tx * tilt * norm, cy + ty * tilt * norm)

            // Moulded edge: a dark copy underneath gives the piece thickness.
            moldPaint.color = withAlpha(Color.BLACK, 150)
            canvas.save()
            canvas.translate(0f, radius * 0.055f)
            canvas.drawPath(crossPath, moldPaint)
            canvas.restore()

            // The cross itself — one shape, one gradient, no seams.
            moldPaint.shader = crossShader(-len, len)
            canvas.drawPath(crossPath, moldPaint)
            moldPaint.shader = null

            // Bevel: lit along the top edge, shadowed along the bottom.
            bevelPaint.strokeWidth = radius * 0.055f
            canvas.save()
            canvas.clipRect(-len, -len, len, 0f)
            bevelPaint.color = withAlpha(Color.WHITE, 62)
            canvas.drawPath(crossPath, bevelPaint)
            canvas.restore()
            canvas.save()
            canvas.clipRect(-len, 0f, len, len)
            bevelPaint.color = withAlpha(Color.BLACK, 110)
            canvas.drawPath(crossPath, bevelPaint)
            canvas.restore()

            // Thumb dish in the middle — a soft depression, not a button.
            moldPaint.color = withAlpha(Color.BLACK, 46)
            canvas.drawCircle(0f, 0f, arm * 0.80f, moldPaint)
            moldPaint.color = withAlpha(Color.BLACK, 34)
            canvas.drawCircle(0f, 0f, arm * 0.55f, moldPaint)
            bevelPaint.strokeWidth = radius * 0.022f
            bevelPaint.color = withAlpha(Color.WHITE, 20)
            arcRect(0f, 0f, arm * 0.80f)
            canvas.drawArc(tmpRect, 15f, 150f, false, bevelPaint)

            // The side being pushed sits in shadow; soft, clipped to the piece.
            if (hat != HidConstants.HAT_NEUTRAL) {
                canvas.save()
                canvas.clipPath(crossPath)
                val px = tx * len * norm
                val py = ty * len * norm
                moldPaint.color = withAlpha(Color.BLACK, 52)
                canvas.drawCircle(px, py, len * 0.95f, moldPaint)
                canvas.drawCircle(px, py, len * 0.62f, moldPaint)
                canvas.restore()
            }

            // Arrows ride with the piece.
            drawDirArrow(canvas, 0f, -len * 0.70f, radius * 0.150f, 0f, up)
            drawDirArrow(canvas, len * 0.70f, 0f, radius * 0.150f, 90f, right)
            drawDirArrow(canvas, 0f, len * 0.70f, radius * 0.150f, 180f, down)
            drawDirArrow(canvas, -len * 0.70f, 0f, radius * 0.150f, 270f, left)

            canvas.restore()
        }

        /** Cross silhouette, centred on the origin, rebuilt only on resize. */
        private var crossArm = Float.NaN
        private var crossLen = Float.NaN
        private val crossPath = Path()

        private fun ensureCross(arm: Float, len: Float) {
            if (arm == crossArm && len == crossLen) return
            crossArm = arm
            crossLen = len
            val round = arm * 0.55f
            crossPath.reset()
            tmpRect.set(-arm, -len, arm, len)
            crossPath.addRoundRect(tmpRect, round, round, Path.Direction.CW)
            scratchPath.reset()
            tmpRect.set(-len, -arm, len, arm)
            scratchPath.addRoundRect(tmpRect, round, round, Path.Direction.CW)
            // Union so the piece has one continuous outline instead of two
            // overlapping bars with a seam where they cross.
            crossPath.op(scratchPath, Path.Op.UNION)
        }

        private fun drawDirArrow(
            canvas: Canvas, x: Float, y: Float, size: Float, rotation: Float, active: Boolean
        ) {
            arrowPaint.color = withAlpha(Color.BLACK, 130)
            drawArrow(canvas, x, y + size * 0.16f, size, rotation)
            arrowPaint.color = if (active) neonCyan else dpadArrowColor
            drawArrow(canvas, x, y, size, rotation)
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

        /**
         * A thumbstick sunk in a well: the housing is concave (dark at the
         * top rim, lit at the bottom), and the cap is a domed head with a
         * grippy lip and a dished, textured face that carries its own shadow
         * as you push it around.
         */
        override fun draw(canvas: Canvas) {
            val kx = cx + knobX
            val ky = cy + knobY
            val capR = radius * 0.56f

            // Well.
            moldPaint.shader = null
            moldPaint.color = socketColor
            canvas.drawCircle(cx, cy, radius, moldPaint)
            bevelPaint.strokeWidth = radius * 0.09f
            bevelPaint.color = withAlpha(Color.BLACK, 165)
            arcRect(cx, cy, radius * 0.955f)
            canvas.drawArc(tmpRect, 185f, 165f, false, bevelPaint)
            bevelPaint.color = withAlpha(Color.WHITE, 30)
            canvas.drawArc(tmpRect, 5f, 165f, false, bevelPaint)

            // Travel ring, brighter while the stick is in use.
            strokePaint.color = withAlpha(if (pressed) accentColor else outlineColor, if (pressed) 180 else 90)
            canvas.drawCircle(cx, cy, radius * 0.86f, strokePaint)

            // Cap shadow, thrown onto the well floor.
            moldPaint.color = withAlpha(Color.BLACK, 120)
            canvas.drawCircle(kx + knobX * 0.06f, ky + radius * 0.09f, capR, moldPaint)

            // Domed head.
            moldPaint.shader = LinearGradient(
                kx, ky - capR, kx, ky + capR, capLit, capShade, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(kx, ky, capR, moldPaint)
            moldPaint.shader = null

            // Grippy outer lip.
            bevelPaint.strokeWidth = capR * 0.16f
            bevelPaint.color = withAlpha(Color.WHITE, 52)
            arcRect(kx, ky, capR * 0.92f)
            canvas.drawArc(tmpRect, 190f, 150f, false, bevelPaint)
            bevelPaint.color = withAlpha(Color.BLACK, 105)
            canvas.drawArc(tmpRect, 10f, 150f, false, bevelPaint)

            // Dished face: darker in the middle, lit on the lower inner edge.
            moldPaint.color = withAlpha(Color.BLACK, 62)
            canvas.drawCircle(kx, ky, capR * 0.62f, moldPaint)
            bevelPaint.strokeWidth = capR * 0.07f
            bevelPaint.color = withAlpha(Color.WHITE, 26)
            arcRect(kx, ky, capR * 0.62f)
            canvas.drawArc(tmpRect, 15f, 150f, false, bevelPaint)
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
            drawMoldedCap(canvas, radius, 0)
            val capY = cy + capSink(radius)
            textPaint.textSize = radius
            val baseline = capY - (textPaint.ascent() + textPaint.descent()) / 2f
            textPaint.color = withAlpha(Color.BLACK, 120)
            canvas.drawText("⚙", cx, baseline + radius * 0.04f, textPaint)
            textPaint.color = textColor
            canvas.drawText("⚙", cx, baseline, textPaint)
        }
    }

    // ------------------------------------------------------------------ paints

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
    private val moldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tmpRect = RectF()

    // Molded-plastic palette: lit crown, shadowed skirt, near-black socket.
    private val capLit = Color.parseColor("#474D69")
    private val capShade = Color.parseColor("#202432")
    private val capPressedDeep = Color.parseColor("#171A24")
    private val capPressedRise = Color.parseColor("#343A52")
    private val socketColor = Color.parseColor("#0C0E15")

    private fun arcRect(x: Float, y: Float, r: Float) {
        tmpRect.set(x - r, y - r, x + r, y + r)
    }

    /** Vertical gradient reused for the d-pad cross. */
    private fun crossShader(top: Float, bottom: Float): LinearGradient =
        LinearGradient(0f, top, 0f, bottom, capLit, capShade, Shader.TileMode.CLAMP)

    /** Lift a color toward white — used for labels on a pressed cap. */
    private fun brighten(color: Int): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * 0.45f).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * 0.45f).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * 0.45f).toInt()
    )
    private val neonCyan = Color.parseColor("#2BE4FF")
    private val neonGreen = Color.parseColor("#3BFFA8")
    private val arrowPath = Path()
    private val scratchPath = Path()

    // Background gradients: deep navy fading to indigo (Game Boy skin goes green).
    private val bgTop = Color.parseColor("#0C0D16")
    private val bgBottom = Color.parseColor("#1A1E33")
    private val gbBgTop = Color.parseColor("#131F18")
    private val gbBgBottom = Color.parseColor("#20301F")
    private val dpadArrowColor = Color.parseColor("#A9AEC6")
    private val outlineColor = Color.parseColor("#3A3D52")
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

    // ---------------------------------------------------- custom layouts

    /** Stable keys for saving each control's position. */
    private val controlKeys: Map<String, Control> = mapOf(
        "dpad" to dpad, "lstick" to leftStick, "rstick" to rightStick,
        "ftop" to faceTop, "fright" to faceRight, "fbottom" to faceBottom,
        "fleft" to faceLeft, "l1" to btnL1, "l2" to btnL2, "r1" to btnR1,
        "r2" to btnR2, "l3" to btnL3, "r3" to btnR3, "share" to btnShare,
        "options" to btnOptions, "home" to btnHome, "gear" to gear
    )

    private val layoutPrefs =
        context.getSharedPreferences("pocketpad_layouts", Context.MODE_PRIVATE)

    /** True while the user is dragging buttons around. */
    var isEditingLayout = false
        private set

    // ------------------------------------------------- custom background

    private var bgBitmap: Bitmap? = null
    private var bgPanX = 0.5f
    private var bgPanY = 0.5f
    private val bgBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    // Tint between picture and controls so buttons always stay readable.
    private val scrimColor = Color.argb(110, 6, 7, 12)

    /** True while the user is dragging the background picture into place. */
    var isPanningBackground = false
        private set

    val hasBackgroundImage: Boolean get() = bgBitmap != null

    /** Set (or clear, with null) the custom background picture. */
    fun setBackgroundImage(bitmap: Bitmap?, panX: Float, panY: Float) {
        val previous = bgBitmap
        bgBitmap = bitmap
        // Free the picture being replaced instead of waiting on the collector;
        // full-screen bitmaps are several megabytes each.
        if (previous != null && previous !== bitmap && !previous.isRecycled) {
            previous.recycle()
        }
        bgPanX = panX.coerceIn(0f, 1f)
        bgPanY = panY.coerceIn(0f, 1f)
        invalidate()
    }

    fun enterBackgroundPanMode() {
        releaseAllPointers()
        isPanningBackground = true
        invalidate()
    }

    /** Exit pan mode, returning the chosen position to persist. */
    fun finishBackgroundPan(): Pair<Float, Float> {
        isPanningBackground = false
        invalidate()
        return bgPanX to bgPanY
    }

    fun setBackgroundPan(panX: Float, panY: Float) {
        bgPanX = panX.coerceIn(0f, 1f)
        bgPanY = panY.coerceIn(0f, 1f)
        invalidate()
    }

    fun centerBackground() = setBackgroundPan(0.5f, 0.5f)

    fun enterLayoutEditMode() {
        releaseAllPointers()
        isEditingLayout = true
        invalidate()
    }

    /** Persist the current positions for this skin + orientation and exit. */
    fun saveEditedLayout() {
        val w = safeWidth()
        val h = safeHeight()
        if (w > 0 && h > 0) {
            val serialized = controlKeys.entries.joinToString(";") { (key, c) ->
                "$key:${(c.cx - offX) / w}:${(c.cy - offY) / h}"
            }
            layoutPrefs.edit().putString(layoutPrefKey(), serialized).apply()
        }
        isEditingLayout = false
        invalidate()
    }

    /** Forget the saved layout for this skin + orientation; back to default. */
    fun resetLayoutToDefault() {
        layoutPrefs.edit().remove(layoutPrefKey()).apply()
        relayout()
        invalidate()
    }

    /** Exit without saving; reload whatever was stored (or the default). */
    fun cancelLayoutEdit() {
        isEditingLayout = false
        relayout()
        invalidate()
    }

    private fun layoutPrefKey(): String =
        "${skin.name}_${if (height >= width) "P" else "L"}"

    private fun safeWidth() = (width - safeLeft - safeRight).coerceAtLeast(1f)
    private fun safeHeight() = (height - safeTop - safeBottom).coerceAtLeast(1f)

    private fun relayout() {
        if (width > 0 && height > 0) layoutControls(width.toFloat(), height.toFloat())
    }

    /** Re-apply a user-saved layout on top of the defaults. */
    private fun applySavedLayout() {
        val saved = layoutPrefs.getString(layoutPrefKey(), null) ?: return
        val w = safeWidth()
        val h = safeHeight()
        for (entry in saved.split(';')) {
            val parts = entry.split(':')
            if (parts.size != 3) continue
            val control = controlKeys[parts[0]] ?: continue
            val fx = parts[1].toFloatOrNull() ?: continue
            val fy = parts[2].toFloatOrNull() ?: continue
            control.cx = offX + fx * w
            control.cy = offY + fy * h
        }
    }

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
        applySavedLayout()
    }

    private fun place(c: Control, x: Float, y: Float, r: Float) {
        c.cx = offX + x
        c.cy = offY + y
        c.radius = r
    }

    // ------------------------------------------------------------ PS layouts
    // The top ~8% of the safe area stays empty for the status pill, and no
    // control sits inside another's halo — spacing modeled on a real pad.

    private fun layoutPsPortrait(w: Float, h: Float) {
        val small = w * 0.072f
        val face = w * 0.078f
        place(btnL1, w * 0.10f, h * 0.10f, small)
        place(btnL2, w * 0.26f, h * 0.10f, small)
        place(btnR2, w * 0.74f, h * 0.10f, small)
        place(btnR1, w * 0.90f, h * 0.10f, small)
        place(dpad, w * 0.26f, h * 0.28f, w * 0.19f)
        val fx = w * 0.74f
        val fy = h * 0.28f
        val off = w * 0.138f
        place(faceTop, fx, fy - off, face)
        place(faceRight, fx + off, fy, face)
        place(faceBottom, fx, fy + off, face)
        place(faceLeft, fx - off, fy, face)
        place(leftStick, w * 0.28f, h * 0.56f, w * 0.155f)
        place(rightStick, w * 0.72f, h * 0.56f, w * 0.155f)
        place(btnL3, w * 0.5f, h * 0.495f, small * 0.8f)
        place(btnR3, w * 0.5f, h * 0.625f, small * 0.8f)
        place(btnShare, w * 0.24f, h * 0.79f, small)
        place(btnHome, w * 0.50f, h * 0.79f, small * 1.2f)
        place(btnOptions, w * 0.76f, h * 0.79f, small)
        place(gear, w * 0.08f, h * 0.92f, small * 0.9f)
    }

    private fun layoutPsLandscape(w: Float, h: Float) {
        val small = h * 0.072f
        val face = h * 0.092f
        place(btnL2, w * 0.055f, h * 0.14f, small)
        place(btnL1, w * 0.145f, h * 0.14f, small)
        place(btnR1, w * 0.855f, h * 0.14f, small)
        place(btnR2, w * 0.945f, h * 0.14f, small)
        place(dpad, w * 0.135f, h * 0.52f, min(w, h) * 0.21f)
        val fx = w * 0.865f
        val fy = h * 0.52f
        val off = min(w, h) * 0.168f
        place(faceTop, fx, fy - off, face)
        place(faceRight, fx + off, fy, face)
        place(faceBottom, fx, fy + off, face)
        place(faceLeft, fx - off, fy, face)
        place(leftStick, w * 0.335f, h * 0.68f, h * 0.155f)
        place(rightStick, w * 0.665f, h * 0.68f, h * 0.155f)
        place(btnL3, w * 0.445f, h * 0.94f, small * 0.78f)
        place(btnR3, w * 0.555f, h * 0.94f, small * 0.78f)
        place(btnShare, w * 0.38f, h * 0.22f, small)
        place(btnHome, w * 0.50f, h * 0.44f, small * 1.2f)
        place(btnOptions, w * 0.62f, h * 0.22f, small)
        place(gear, w * 0.045f, h * 0.90f, small * 0.9f)
    }

    // ---------------------------------------------------------- SNES layouts

    private fun layoutSnesPortrait(w: Float, h: Float) {
        val small = w * 0.076f
        val face = w * 0.082f
        place(btnL1, w * 0.12f, h * 0.10f, small)
        place(btnR1, w * 0.88f, h * 0.10f, small)
        place(dpad, w * 0.26f, h * 0.30f, w * 0.20f)
        val fx = w * 0.74f
        val fy = h * 0.30f
        val off = w * 0.145f
        place(faceTop, fx, fy - off, face)
        place(faceRight, fx + off, fy, face)
        place(faceBottom, fx, fy + off, face)
        place(faceLeft, fx - off, fy, face)
        place(btnShare, w * 0.28f, h * 0.56f, small)
        place(btnOptions, w * 0.72f, h * 0.56f, small)
        place(btnHome, w * 0.50f, h * 0.70f, small)
        place(gear, w * 0.08f, h * 0.92f, small * 0.9f)
    }

    private fun layoutSnesLandscape(w: Float, h: Float) {
        val small = h * 0.076f
        val face = h * 0.095f
        place(btnL1, w * 0.09f, h * 0.14f, small)
        place(btnR1, w * 0.91f, h * 0.14f, small)
        place(dpad, w * 0.16f, h * 0.58f, min(w, h) * 0.22f)
        val fx = w * 0.84f
        val fy = h * 0.58f
        val off = min(w, h) * 0.172f
        place(faceTop, fx, fy - off, face)
        place(faceRight, fx + off, fy, face)
        place(faceBottom, fx, fy + off, face)
        place(faceLeft, fx - off, fy, face)
        place(btnShare, w * 0.38f, h * 0.24f, small)
        place(btnOptions, w * 0.62f, h * 0.24f, small)
        place(btnHome, w * 0.50f, h * 0.52f, small)
        place(gear, w * 0.05f, h * 0.90f, small * 0.9f)
    }

    // ------------------------------------------------- NES / Game Boy layouts

    private fun layoutRetroPortrait(w: Float, h: Float) {
        val small = w * 0.08f
        val face = w * 0.105f
        place(dpad, w * 0.26f, h * 0.32f, w * 0.20f)
        // A high on the right, B lower-left of it — Game Boy diagonal.
        place(faceRight, w * 0.82f, h * 0.27f, face)
        place(faceBottom, w * 0.62f, h * 0.38f, face)
        place(btnShare, w * 0.28f, h * 0.58f, small)
        place(btnOptions, w * 0.72f, h * 0.58f, small)
        place(btnHome, w * 0.50f, h * 0.71f, small)
        place(gear, w * 0.08f, h * 0.92f, small * 0.9f)
    }

    private fun layoutRetroLandscape(w: Float, h: Float) {
        val small = h * 0.08f
        val face = h * 0.115f
        place(dpad, w * 0.16f, h * 0.55f, min(w, h) * 0.22f)
        place(faceRight, w * 0.89f, h * 0.44f, face)
        place(faceBottom, w * 0.76f, h * 0.63f, face)
        place(btnShare, w * 0.38f, h * 0.20f, small)
        place(btnOptions, w * 0.62f, h * 0.20f, small)
        place(btnHome, w * 0.50f, h * 0.48f, small)
        place(gear, w * 0.05f, h * 0.90f, small * 0.9f)
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
        drawBackgroundImage(canvas)
        for (control in activeControls) control.draw(canvas)
        if (isEditingLayout) {
            // Accent ring marks every control as grabbable.
            strokePaint.color = accentColor
            for (control in activeControls) {
                strokePaint.alpha = if (control.pressed) 255 else 120
                canvas.drawCircle(control.cx, control.cy, control.radius * 1.08f, strokePaint)
            }
            strokePaint.alpha = 255
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    /**
     * Center-crop the picture to fill the screen; [bgPanX]/[bgPanY] slide it
     * inside the overflow so the user can frame it however they like. The
     * scrim on top keeps the controls readable over any photo.
     */
    private fun drawBackgroundImage(canvas: Canvas) {
        val bmp = bgBitmap ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val scale = max(vw / bmp.width, vh / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val dx = -(dw - vw) * bgPanX
        val dy = -(dh - vh) * bgPanY
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, bgBitmapPaint)
        canvas.restore()
        canvas.drawColor(scrimColor)
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
        if (isPanningBackground) {
            handleBackgroundPanTouch(event)
            return true
        }
        if (isEditingLayout) {
            handleEditTouch(event)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                // Android reuses pointer ids. If a control is somehow still
                // holding this one, release it first — otherwise two controls
                // share an id, the lift only clears one, and the other stays
                // pressed forever.
                activeControls.firstOrNull { it.pointerId == id }?.let {
                    it.pointerId = -1
                    it.onUp()
                }
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
                // ACTION_UP is the last finger leaving, so nothing may stay
                // held; CANCEL means the gesture was taken away entirely.
                if (event.actionMasked != MotionEvent.ACTION_POINTER_UP) {
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

    private var panLastX = 0f
    private var panLastY = 0f

    /** In background-pan mode, a finger drags the picture, not the buttons. */
    private fun handleBackgroundPanTouch(event: MotionEvent) {
        val bmp = bgBitmap ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panLastX = event.x
                panLastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val vw = width.toFloat()
                val vh = height.toFloat()
                val scale = max(vw / bmp.width, vh / bmp.height)
                val overflowX = bmp.width * scale - vw
                val overflowY = bmp.height * scale - vh
                if (overflowX > 1f) {
                    bgPanX = (bgPanX - (event.x - panLastX) / overflowX).coerceIn(0f, 1f)
                }
                if (overflowY > 1f) {
                    bgPanY = (bgPanY - (event.y - panLastY) / overflowY).coerceIn(0f, 1f)
                }
                panLastX = event.x
                panLastY = event.y
                invalidate()
            }
        }
    }

    /** In edit mode, fingers drag controls instead of pressing them. */
    private fun handleEditTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                val control = activeControls.firstOrNull { !it.pressed && it.contains(x, y) }
                if (control != null) {
                    control.pointerId = event.getPointerId(index)
                    haptics.tick()
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val control = activeControls.firstOrNull { it.pointerId == id } ?: continue
                    control.cx = event.getX(i)
                        .coerceIn(safeLeft + control.radius, width - safeRight - control.radius)
                    control.cy = event.getY(i)
                        .coerceIn(safeTop + control.radius, height - safeBottom - control.radius)
                }
                invalidate()
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    for (control in activeControls) control.pointerId = -1
                } else {
                    val id = event.getPointerId(event.actionIndex)
                    activeControls.firstOrNull { it.pointerId == id }?.pointerId = -1
                }
                invalidate()
            }
        }
    }

    /**
     * If the window loses focus mid-press — notification shade, incoming call
     * — the matching ACTION_UP may never arrive. Releasing here stops a held
     * button or a deflected stick from being transmitted indefinitely.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) releaseAllPointers()
    }

    private fun notifyChanged() {
        listener?.onTouchStateChanged()
    }
}
