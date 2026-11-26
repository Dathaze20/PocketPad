package com.dathaze.pocketpad.ui

/**
 * Reading and writing a saved controller layout, as plain text with no Android types.
 *
 * One entry per control, separated by `;`, each `key:x:y:scale` where x and y are fractions of
 * the safe area and scale is the user's size for that control. It lives here rather than inside
 * [ControllerView] for one reason: this is the code that can silently destroy work a user has
 * already done. A layout that fails to parse does not announce itself — the controls simply
 * appear at their defaults, as though the customisation had never been saved.
 *
 * **Entries written before sizing existed have three fields, not four.** Those must keep
 * loading, at the default size. The old reader skipped any entry whose field count was not
 * exactly three, so a reader that demanded four would throw away every layout saved by an
 * earlier version — every one, silently, on first launch after the update.
 */
object LayoutStore {

    /** Where one control sits and how big the user made it. */
    data class Placement(
        val key: String,
        val fx: Float,
        val fy: Float,
        val scale: Float = ControlScale.DEFAULT,
    )

    fun encode(placements: List<Placement>): String =
        placements.joinToString(ENTRY_SEPARATOR.toString()) {
            "${it.key}$FIELD_SEPARATOR${it.fx}$FIELD_SEPARATOR${it.fy}$FIELD_SEPARATOR${it.scale}"
        }

    /**
     * Parse a saved layout, skipping anything that does not make sense rather than failing the
     * whole string: one corrupt entry should cost one control's position, not all of them.
     *
     * Scales are clamped on the way in, so a value from a future version with wider bounds — or
     * a hand-edited preferences file — cannot produce a control too small to hit or large
     * enough to cover the screen.
     */
    fun decode(saved: String?): List<Placement> {
        if (saved.isNullOrBlank()) return emptyList()
        val out = ArrayList<Placement>()
        for (entry in saved.split(ENTRY_SEPARATOR)) {
            if (entry.isBlank()) continue
            val parts = entry.split(FIELD_SEPARATOR)
            // Three fields is the format from before controls could be resized; four is current.
            if (parts.size != 3 && parts.size != 4) continue
            val key = parts[0]
            if (key.isBlank()) continue
            val fx = parts[1].toFloatOrNull() ?: continue
            val fy = parts[2].toFloatOrNull() ?: continue
            if (!fx.isFinite() || !fy.isFinite()) continue
            val scale = if (parts.size == 4) {
                val parsed = parts[3].toFloatOrNull() ?: ControlScale.DEFAULT
                if (parsed.isFinite()) ControlScale.clamp(parsed) else ControlScale.DEFAULT
            } else {
                ControlScale.DEFAULT
            }
            out.add(Placement(key, fx, fy, scale))
        }
        return out
    }

    private const val ENTRY_SEPARATOR = ';'
    private const val FIELD_SEPARATOR = ':'
}
