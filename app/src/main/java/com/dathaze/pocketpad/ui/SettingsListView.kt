package com.dathaze.pocketpad.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * The settings list: one column in portrait, two side by side in landscape, shrunk to fit if it
 * has to be.
 *
 * It is the same list of rows it has always been. The only thing this view adds is where they go
 * and how big they are:
 *
 *  - **In portrait** it behaves like the vertical `LinearLayout` it replaces — every row full
 *    width, in order.
 *  - **In landscape** it puts the groups into two columns. A phone on its side has roughly
 *    400dp of height and 900dp of width; one column of eleven rows cannot fit in 400dp, two
 *    two balanced columns can, and the width was going spare anyway. Which group goes where is
 *    [SettingsColumns.assignColumns] — never splitting one group across both.
 *  - **At any font scale** it measures what it got, and if that is taller than [maxHeight] it
 *    shrinks every row and heading together and measures again, down to
 *    [SettingsColumns.MIN_SCALE].
 *
 * The column count comes from `R.integer.settings_list_columns`, so it is chosen by resource
 * qualifiers rather than by code asking which way up the phone is. That only works if the sheet
 * is rebuilt when the phone is rotated — `MainActivity` has `configChanges` set for orientation,
 * so nothing is recreated automatically and `SettingsSheet.onConfigurationChanged` has to do it.
 */
class SettingsListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    var columns: Int = 1
        set(value) {
            val clamped = value.coerceAtLeast(1)
            if (field != clamped) {
                field = clamped
                requestLayout()
            }
        }

    /** Space between the two columns, in pixels. Ignored in one-column mode. */
    var gap: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /**
     * The tallest this list may be, in pixels; 0 means "no limit".
     *
     * Set by [SettingsSheet] once it knows how much room is left under the sheet's header.
     */
    var maxHeight: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /**
     * How many child views belong to each group, in order, counting the heading as one.
     *
     * The list is a flat sequence of views, so this is what tells the layout where one group
     * ends and the next begins, and therefore which whole groups may go in a second column.
     */
    private var groupSizes: IntArray = IntArray(0)

    private var childLeft = IntArray(0)
    private var childTop = IntArray(0)
    private var columnWidth = 0

    fun setGroupSizes(sizes: IntArray) {
        groupSizes = sizes.copyOf()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val verticalPadding = paddingTop + paddingBottom

        if (childCount == 0) {
            setMeasuredDimension(width, verticalPadding)
            return
        }

        val groups = resolvedGroups()
        // A single group has nowhere to split to, so it stays one column whatever the setting.
        val useColumns = if (groups.size > 1) columns else 1
        columnWidth = SettingsColumns.columnWidth(inner, useColumns, gap)

        var scale = 1f
        var contentHeight = 0
        for (pass in 0 until SettingsColumns.MAX_PASSES) {
            applyScale(scale)
            measureChildrenAtColumnWidth()

            val groupHeights = groups.map { range -> range.sumOf { getChildAt(it).measuredHeight } }
            val assignment = SettingsColumns.assignColumns(groupHeights, useColumns)
            contentHeight = SettingsColumns.tallestColumn(groupHeights, assignment)
            position(groups, assignment)

            val total = contentHeight + verticalPadding
            if (maxHeight <= 0 || total <= maxHeight) break

            val next = SettingsColumns.nextScale(scale, contentHeight, maxHeight - verticalPadding)
            if (next >= scale) break
            scale = next
        }

        setMeasuredDimension(width, contentHeight + verticalPadding)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val x = childLeft.getOrElse(i) { paddingLeft }
            val y = childTop.getOrElse(i) { paddingTop }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    /**
     * Work out where every child sits, given which groups go in which column.
     *
     * Done during measure rather than in [onLayout] because the positions fall out of the same
     * pass that chose the columns, and recomputing later would mean choosing them again.
     */
    private fun position(groups: List<IntRange>, assignment: IntArray) {
        if (childLeft.size != childCount) {
            childLeft = IntArray(childCount)
            childTop = IntArray(childCount)
        }
        val nextTop = intArrayOf(paddingTop, paddingTop)
        groups.forEachIndexed { groupIndex, range ->
            val column = assignment.getOrElse(groupIndex) { 0 }
            val x = paddingLeft + column * (columnWidth + gap)
            for (child in range) {
                childLeft[child] = x
                childTop[child] = nextTop[column]
                nextTop[column] += getChildAt(child).measuredHeight
            }
        }
    }

    private fun measureChildrenAtColumnWidth() {
        val widthSpec = MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (i in 0 until childCount) getChildAt(i).measure(widthSpec, heightSpec)
    }

    private fun applyScale(scale: Float) {
        for (i in 0 until childCount) {
            (getChildAt(i) as? SettingsScalable)?.applyScale(scale)
        }
    }

    /**
     * The child ranges each group covers.
     *
     * Falls back to treating the whole list as one group when the sizes do not add up, which
     * would otherwise drop rows off the end of the layout. One tall column is wrong but it is
     * visibly wrong, where a missing row is not.
     */
    private fun resolvedGroups(): List<IntRange> {
        if (groupSizes.isEmpty() || groupSizes.sum() != childCount) {
            return listOf(0 until childCount)
        }
        val ranges = ArrayList<IntRange>(groupSizes.size)
        var start = 0
        for (size in groupSizes) {
            if (size <= 0) continue
            ranges.add(start until start + size)
            start += size
        }
        return ranges
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    /** Rows sit inside a scroll view, so press feedback should be immediate, not delayed. */
    override fun shouldDelayChildPressedState(): Boolean = false
}
