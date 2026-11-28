package com.dathaze.pocketpad.hid

/**
 * **PC Keys** — the experimental keyboard mapping for Windows.
 *
 * ### Why this exists
 *
 * PocketPad advertises itself over SDP with `SUBCLASS1_COMBO`, which is what makes Samsung's
 * Input Device Manager list it at all. Windows reads the same byte and installs the phone as a
 * keyboard/pointer, under "Mouse, keyboard, & pen" — it never creates a game controller. So on a
 * PC the gamepad collection (report id 1) is received and ignored, and the keyboard collection
 * (report id 2) is the only channel that actually reaches applications.
 *
 * TV Keys already uses that channel, but it maps six controls — four arrows, ✕ and ○ — because
 * it exists to walk a TV menu. That is not enough to play anything.
 *
 * PC Keys maps every control to a distinct key, so an emulator can be configured against it:
 *
 * | Control | Key | Control | Key |
 * |---|---|---|---|
 * | ✕ | `Z` + `Enter` | L1 | `Q` |
 * | ○ | `X` | R1 | `W` |
 * | □ | `A` | L2 | `E` |
 * | △ | `S` | R2 | `R` |
 * | SHARE | Right Shift | L3 | `C` |
 * | OPTIONS | `Enter` | R3 | `V` |
 * | PS | `Escape` | | |
 * | D-pad | Arrow keys | Left stick | Arrow keys |
 *
 * The letters are not special; every emulator rebinds. What matters is that each control sends
 * *something distinct* and that combinations survive, which is what [KeyboardReport] provides.
 *
 * ### Nothing here changes the Bluetooth identity
 *
 * No descriptor change, no report id change, no SDP change, and so no re-pairing. This is the
 * same report id 2 the TV has always received, with slots 2..6 finally carrying something.
 *
 * ### Diagonals
 *
 * TV Keys reduces the hat to a single arrow and sends `KEY_NONE` for the four diagonal values,
 * so pressing diagonally there stops input dead. Here a diagonal is simply the two arrows it is
 * made of, and the stick is read the same way, so both can be held at once.
 *
 * Pure functions over [GamepadState] — no Android types, so all of it is unit-testable.
 */
object PcKeyMap {

    // ------------------------------------------------------------------ keys

    // USB HID keyboard usage ids. All are at or below KeyboardReport.MAX_KEYCODE, so all are
    // valid in a key slot. Right Shift is a modifier and deliberately not here.
    const val KEY_A: Byte = 0x04
    const val KEY_C: Byte = 0x06
    const val KEY_E: Byte = 0x08
    const val KEY_Q: Byte = 0x14
    const val KEY_R: Byte = 0x15
    const val KEY_S: Byte = 0x16
    const val KEY_V: Byte = 0x19
    const val KEY_W: Byte = 0x1A
    const val KEY_X: Byte = 0x1B
    const val KEY_Z: Byte = 0x1D

    // --------------------------------------------------------------- reading

    /**
     * How far the left stick must leave centre (128) before it counts as a direction.
     *
     * Generous on purpose. An arrow key is binary, so a small deadzone turns an idle thumb into
     * a stream of key-down/key-up pairs; this is about 16% of full travel.
     */
    const val STICK_DEADZONE = 40

    /**
     * How hard an analog trigger must be pulled to count as pressed.
     *
     * The touch layer sets the L2/R2 button bit *and* the axis, so the bit alone would do for
     * on-screen presses. A plugged-in USB controller may report only the axis, and this keeps
     * those working too.
     */
    const val TRIGGER_THRESHOLD = 128

    /** Hat values, clockwise from up; [HidConstants.HAT_NEUTRAL] (8) means centred. */
    fun hatUp(hat: Int): Boolean = hat == 7 || hat == 0 || hat == 1
    fun hatRight(hat: Int): Boolean = hat in 1..3
    fun hatDown(hat: Int): Boolean = hat in 3..5
    fun hatLeft(hat: Int): Boolean = hat in 5..7

    /** Left stick, with [STICK_DEADZONE] applied. Lower [GamepadState.ly] is up, as in the report. */
    fun stickUp(state: GamepadState): Boolean = state.ly < 128 - STICK_DEADZONE
    fun stickDown(state: GamepadState): Boolean = state.ly > 128 + STICK_DEADZONE
    fun stickLeft(state: GamepadState): Boolean = state.lx < 128 - STICK_DEADZONE
    fun stickRight(state: GamepadState): Boolean = state.lx > 128 + STICK_DEADZONE

    /** The d-pad and the left stick both drive the arrows; either one is enough. */
    fun up(state: GamepadState): Boolean = hatUp(state.hat) || stickUp(state)
    fun down(state: GamepadState): Boolean = hatDown(state.hat) || stickDown(state)
    fun left(state: GamepadState): Boolean = hatLeft(state.hat) || stickLeft(state)
    fun right(state: GamepadState): Boolean = hatRight(state.hat) || stickRight(state)

    private fun triggerHeld(state: GamepadState, button: Int, axis: Int): Boolean =
        state.isPressed(button) || axis >= TRIGGER_THRESHOLD

    // --------------------------------------------------------------- mapping

    /** Modifier bits for [state]. SHARE is Right Shift, which only byte 0 can carry. */
    fun modifiersFor(state: GamepadState): Int =
        if (state.isPressed(HidConstants.BTN_SHARE)) KeyboardReport.MOD_RIGHT_SHIFT else 0

    /**
     * Every key [state] is holding, **in priority order**.
     *
     * Order matters only when more than [KeyboardReport.MAX_KEYS] are held at once, since the
     * report has six slots and [KeyboardReport.build] keeps the first six. Directions come first
     * so that movement is the last thing to be lost, then the face buttons, then the shoulders,
     * then the rest. Holding seven controls costs you Escape, not your left arrow.
     */
    fun keysFor(state: GamepadState): List<Byte> {
        val keys = ArrayList<Byte>(KeyboardReport.MAX_KEYS)

        // Directions first — see the ordering note above.
        if (up(state)) keys += HidConstants.KEY_UP
        if (down(state)) keys += HidConstants.KEY_DOWN
        if (left(state)) keys += HidConstants.KEY_LEFT
        if (right(state)) keys += HidConstants.KEY_RIGHT

        // Face buttons.
        if (state.isPressed(HidConstants.BTN_CROSS)) keys += KEY_Z
        if (state.isPressed(HidConstants.BTN_CIRCLE)) keys += KEY_X
        if (state.isPressed(HidConstants.BTN_SQUARE)) keys += KEY_A
        if (state.isPressed(HidConstants.BTN_TRIANGLE)) keys += KEY_S

        // Shoulders and triggers.
        if (state.isPressed(HidConstants.BTN_L1)) keys += KEY_Q
        if (state.isPressed(HidConstants.BTN_R1)) keys += KEY_W
        if (triggerHeld(state, HidConstants.BTN_L2, state.lt)) keys += KEY_E
        if (triggerHeld(state, HidConstants.BTN_R2, state.rt)) keys += KEY_R

        // Everything else.
        //
        // Enter comes from OPTIONS *and* from ✕. A report has six slots, so ✕ can carry Z and
        // Enter at once: it stays the natural jump button to bind in a game, and it still
        // confirms in the emulator's own menus — which is the first thing anyone hits, and
        // which the first version of this mapping made impossible by moving Enter away from the
        // button every menu expects it on. The two sources collapse to one key because
        // [KeyboardReport.build] refuses a duplicate a slot.
        //
        // It sits here, near the end, on purpose: in a menu almost nothing else is held so it
        // always fits, while in a busy moment it is among the first to be dropped to rollover
        // rather than costing a direction or a face button.
        if (state.isPressed(HidConstants.BTN_OPTIONS) || state.isPressed(HidConstants.BTN_CROSS)) {
            keys += HidConstants.KEY_ENTER
        }
        if (state.isPressed(HidConstants.BTN_L3)) keys += KEY_C
        if (state.isPressed(HidConstants.BTN_R3)) keys += KEY_V
        if (state.isPressed(HidConstants.BTN_PS)) keys += HidConstants.KEY_ESCAPE

        return keys
    }

    /** The 8-byte keyboard report for [state]. */
    fun reportFor(state: GamepadState): ByteArray =
        KeyboardReport.build(modifiersFor(state), keysFor(state))
}
