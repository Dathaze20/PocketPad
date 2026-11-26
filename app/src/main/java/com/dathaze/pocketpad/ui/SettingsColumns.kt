package com.dathaze.pocketpad.ui

import kotlin.math.max
import kotlin.math.min

/**
 * Which column each group of the settings list goes in, and how far to shrink it if two columns
 * still are not enough. No Android types, so a JVM test can check the sizing without a device.
 *
 * The list is the same one it has always been — icon, label, subtitle, chevron — and in portrait
 * it stays a single column, where none of this does anything. Landscape is the case that needs
 * arithmetic: a phone on its side has roughly 400dp of height and 900dp of width, so one column
 * cannot show eleven rows but two easily can.
 *
 * One rule shapes [assignColumns]: **a group is never torn in half.** Its heading and its rows
 * go in the same column. A heading stranded at the foot of one column with its rows at the top
 * of the other is worse than an uneven split, so the only question is which column each whole
 * group goes in.
 *
 * Within that, it picks whatever makes the taller column shortest — including putting a later
 * group above an earlier one. That matters more than it sounds: the four groups here are 4, 5, 2
 * and 4 views, and the best *contiguous* cut leaves 9 views against 6, which overflows a phone
 * in landscape at a large font scale while a third of the right-hand column sits empty. Letting
 * Connection and System share a column against Controller and Appearance balances to within a
 * row or two, and every group still arrives under its own heading.
 */
object SettingsColumns {

    /**
     * How small the rows may be squeezed, as a fraction of their natural size.
     *
     * Only reached when the font scale is large enough that even two balanced columns overflow.
     * Below about two thirds the labels stop being readable, and a list that scrolls a little is
     * better than one that cannot be read.
     */
    const val MIN_SCALE = 0.7f

    /** Passes allowed to converge. Row height is near enough linear in the scale for two. */
    const val MAX_PASSES = 4

    /**
     * More groups than this and every arrangement is not worth enumerating, so it falls back to
     * a single contiguous cut. The menu has four; this is a guard, not a working path.
     */
    const val MAX_GROUPS_TO_BALANCE = 12

    /**
     * The column index, 0 or 1, for each group in [groupHeights].
     *
     * With [columns] of 1 everything is in column 0. With 2 it returns the arrangement whose
     * taller column is shortest; where several tie, the one that keeps the earliest groups on
     * the left, so the first group a user reads is the first group in the list.
     */
    fun assignColumns(groupHeights: List<Int>, columns: Int): IntArray {
        val n = groupHeights.size
        if (columns <= 1 || n <= 1) return IntArray(n)
        if (n > MAX_GROUPS_TO_BALANCE) return contiguousAssignment(groupHeights)

        var bestMask = 0
        var bestTallest = Int.MAX_VALUE
        // Bit i of the mask, counting from the most significant, is the column of group i. Going
        // upwards means the first mask achieving a given height is the one with the most groups
        // on the left and the earliest ones there, which is the tie-break we want.
        for (mask in 0 until (1 shl n)) {
            var left = 0
            var right = 0
            for (i in 0 until n) {
                if (columnOf(mask, i, n) == 0) left += groupHeights[i] else right += groupHeights[i]
            }
            val tallest = max(left, right)
            if (tallest < bestTallest) {
                bestTallest = tallest
                bestMask = mask
            }
        }
        return IntArray(n) { columnOf(bestMask, it, n) }
    }

    private fun columnOf(mask: Int, group: Int, count: Int): Int =
        (mask shr (count - 1 - group)) and 1

    /** The single best cut through the list, used only if there are too many groups to balance. */
    private fun contiguousAssignment(groupHeights: List<Int>): IntArray {
        val n = groupHeights.size
        var bestCut = 1
        var bestTallest = Int.MAX_VALUE
        for (cut in 1 until n) {
            val tallest = max(
                groupHeights.take(cut).sum(),
                groupHeights.drop(cut).sum(),
            )
            if (tallest < bestTallest) {
                bestTallest = tallest
                bestCut = cut
            }
        }
        return IntArray(n) { if (it < bestCut) 0 else 1 }
    }

    /** Height of the taller column under [assignment]. */
    fun tallestColumn(groupHeights: List<Int>, assignment: IntArray): Int {
        var left = 0
        var right = 0
        groupHeights.forEachIndexed { i, height ->
            if (assignment.getOrElse(i) { 0 } == 0) left += height else right += height
        }
        return max(left, right)
    }

    /** Width of one column once the gap between columns has been taken out. */
    fun columnWidth(innerWidth: Int, columns: Int, gap: Int): Int {
        require(columns >= 1) { "columns must be at least 1, was $columns" }
        return max(1, (innerWidth - gap * (columns - 1)) / columns)
    }

    /**
     * The scale to try on the next pass, or [current] unchanged when it already fits — which is
     * how the caller knows to stop. Never returns more than [current], so a pass cannot
     * oscillate, and never less than [minScale].
     */
    fun nextScale(
        current: Float,
        measured: Int,
        available: Int,
        minScale: Float = MIN_SCALE,
    ): Float {
        if (measured <= 0) return current
        if (available <= 0) return max(minScale, current)
        if (measured <= available) return current
        // A hair under the exact ratio, so rounding in the text layout cannot leave it a pixel
        // too tall and burn another pass.
        val needed = current * (available.toFloat() / measured.toFloat()) * 0.99f
        return min(current, max(minScale, needed))
    }
}
