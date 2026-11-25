package com.dathaze.pocketpad.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import com.dathaze.pocketpad.R
import kotlin.math.roundToInt

/**
 * Something in the settings list that can be shrunk to help the list fit on one screen.
 *
 * [SettingsListView] shrinks every row and heading by the same factor rather than letting the
 * list scroll. The factor always applies to the view's *natural* size, never to its current one:
 * scaling repeatedly from the current size compounds, and after three passes the rows would have
 * collapsed to nothing. So each implementation reads its natural sizes once, on inflation, and
 * every later call works from those.
 */
interface SettingsScalable {
    fun applyScale(scale: Float)
}

/**
 * One row of the settings list: tinted glyph on the left, label with an optional subtitle, and a
 * trailing value, badge, switch or chevron.
 *
 * Unchanged in shape from the list this has always been. What is new is that its height is no
 * longer fixed at 56dp — it is a minimum that the text can push past — and that it can be
 * scaled. A fixed height cannot survive a large font scale: the text simply grows out of it.
 */
class SettingsRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr), SettingsScalable {

    private lateinit var icon: TextView
    private lateinit var label: TextView
    private lateinit var subtitle: TextView
    private lateinit var value: TextView
    private lateinit var badge: TextView
    private lateinit var chevron: TextView

    private var naturalIcon = 0
    private var naturalIconText = 0f
    private var naturalLabelText = 0f
    private var naturalSubtitleText = 0f
    private var naturalValueText = 0f
    private var naturalBadgeText = 0f
    private var naturalChevronText = 0f
    private var naturalMinHeight = 0
    private var naturalPadTop = 0
    private var naturalPadBottom = 0

    private var appliedScale = Float.NaN

    override fun onFinishInflate() {
        super.onFinishInflate()
        icon = findViewById(R.id.rowIcon)
        label = findViewById(R.id.rowLabel)
        subtitle = findViewById(R.id.rowSubtitle)
        value = findViewById(R.id.rowValue)
        badge = findViewById(R.id.rowBadge)
        chevron = findViewById(R.id.rowChevron)

        naturalIcon = icon.layoutParams.height
        naturalIconText = icon.textSize
        naturalLabelText = label.textSize
        naturalSubtitleText = subtitle.textSize
        naturalValueText = value.textSize
        naturalBadgeText = badge.textSize
        naturalChevronText = chevron.textSize
        naturalMinHeight = minimumHeight
        naturalPadTop = paddingTop
        naturalPadBottom = paddingBottom
    }

    override fun applyScale(scale: Float) {
        if (scale == appliedScale) return
        appliedScale = scale

        val side = (naturalIcon * scale).roundToInt().coerceAtLeast(1)
        icon.layoutParams = icon.layoutParams.apply {
            width = side
            height = side
        }
        // Pixels, not scaled pixels: the natural sizes were read after inflation, so the user's
        // font scale is already in them and setting sp here would apply it a second time.
        icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, naturalIconText * scale)
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, naturalLabelText * scale)
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, naturalSubtitleText * scale)
        value.setTextSize(TypedValue.COMPLEX_UNIT_PX, naturalValueText * scale)
        badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, naturalBadgeText * scale)
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_PX, naturalChevronText * scale)
        minimumHeight = (naturalMinHeight * scale).roundToInt()
        setPadding(
            paddingLeft,
            (naturalPadTop * scale).roundToInt(),
            paddingRight,
            (naturalPadBottom * scale).roundToInt(),
        )
    }
}

/**
 * A section heading — Connection, Controller, Appearance, System — which shrinks with the rows
 * so the proportions of the list stay the same as it is squeezed.
 */
class SettingsHeadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr), SettingsScalable {

    private val naturalText = textSize
    private val naturalPadTop = paddingTop
    private val naturalPadBottom = paddingBottom
    private var appliedScale = Float.NaN

    override fun applyScale(scale: Float) {
        if (scale == appliedScale) return
        appliedScale = scale
        setTextSize(TypedValue.COMPLEX_UNIT_PX, naturalText * scale)
        setPadding(
            paddingLeft,
            (naturalPadTop * scale).roundToInt(),
            paddingRight,
            (naturalPadBottom * scale).roundToInt(),
        )
    }
}
