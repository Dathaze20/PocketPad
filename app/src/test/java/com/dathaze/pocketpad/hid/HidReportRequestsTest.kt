package com.dathaze.pocketpad.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the real Get_Report / Set_Report decision path.
 *
 * [HidGamepadManager] contains no logic of its own for these callbacks: it
 * hands the host's arguments to [HidReportRequests] and turns the returned
 * [HidRequestOutcome] into the matching Bluetooth call. So exercising this
 * object exercises what actually runs on the phone, rather than a
 * reimplementation of it in the test.
 *
 * The platform's `BluetoothHidDevice.REPORT_TYPE_INPUT` (1) is passed in as an
 * argument by production code and so is passed in here too. [INPUT_UNUSUAL]
 * is deliberately *not* that value, and is used to prove the decision follows
 * the argument instead of a constant hidden inside the object.
 */
class HidReportRequestsTest {

    /** The value Android's BluetoothHidDevice.REPORT_TYPE_INPUT carries. */
    private val INPUT: Byte = 1
    private val OUTPUT: Byte = 2
    private val FEATURE: Byte = 3
    private val UNSPECIFIED: Byte = 0

    /** Any value that is not [INPUT]; see the class comment. */
    private val INPUT_UNUSUAL: Byte = 7

    private val gamepadId = HidConstants.REPORT_ID.toByte()
    private val keyboardId = HidConstants.REPORT_ID_KEYBOARD.toByte()

    /** The bytes of a Reply, or a failure naming what came back instead. */
    private fun replyBytes(outcome: HidRequestOutcome): ByteArray = when (outcome) {
        is HidRequestOutcome.Reply -> outcome.payload
        else -> throw AssertionError("expected a Reply, got ${outcome::class.simpleName}")
    }

    private fun get(
        id: Byte,
        type: Byte = INPUT,
        bufferSize: Int = 0,
        inputType: Byte = INPUT
    ) = HidReportRequests.getReport(type, id, bufferSize, inputType)

    // -------------------------------------------------------- report ids

    @Test
    fun theGamepadIdIsAnsweredWithTheGamepadReport() {
        val payload = replyBytes(get(gamepadId))
        assertEquals(HidConstants.GAMEPAD_REPORT_SIZE, payload.size)
        assertArrayEquals(HidConstants.neutralGamepadReport(), payload)
    }

    @Test
    fun theKeyboardIdIsAnsweredWithTheKeyboardReport() {
        val payload = replyBytes(get(keyboardId))
        assertEquals(HidConstants.KEYBOARD_REPORT_SIZE, payload.size)
        assertArrayEquals(HidConstants.neutralKeyboardReport(), payload)
    }

    /**
     * The defect this code exists for. A keyboard poll answered with gamepad
     * bytes is read as modifier, reserved, keycodes — and the neutral hat byte
     * is 0x08, which is keyboard usage "e". The keyboard reply must carry
     * neither the gamepad's length nor any keycode.
     */
    @Test
    fun aKeyboardPollNeverReceivesGamepadBytes() {
        val gamepad = replyBytes(get(gamepadId))
        assertEquals(
            "precondition: the gamepad's third byte really is keyboard usage 0x08",
            0x08, gamepad[2].toInt() and 0xFF
        )

        val keyboard = replyBytes(get(keyboardId))
        assertTrue(
            "the keyboard reply must not be the gamepad report",
            !keyboard.contentEquals(gamepad)
        )
        for (i in keyboard.indices) {
            assertEquals("keyboard byte $i must be clear", 0, keyboard[i].toInt() and 0xFF)
        }
    }

    @Test
    fun reportIdZeroFallsBackToTheGamepad() {
        // Zero means the host named no report. Answering with the gamepad is
        // what hosts that already work expect.
        assertArrayEquals(HidConstants.neutralGamepadReport(), replyBytes(get(0)))
    }

    @Test
    fun unknownReportIdsAreRefusedRatherThanGuessed() {
        for (id in listOf<Byte>(3, 4, 15, 99, 127, -128, -1)) {
            assertEquals(
                "report id $id is not declared and must be refused",
                HidRequestOutcome.UnknownReportId, get(id)
            )
        }
    }

    @Test
    fun aReportIdIsReadAsUnsignedSoHighIdsAreNotMistakenForLowOnes() {
        // 0x81 as a signed byte is -127. Read as signed it is not 1, but a
        // careless sign extension could turn 0x00-adjacent values into the
        // gamepad id. Every high id must land on "unknown", never on a report.
        for (raw in 0x80..0xFF) {
            assertEquals(
                "id 0x${raw.toString(16)} must not resolve to a declared report",
                HidRequestOutcome.UnknownReportId, get(raw.toByte())
            )
        }
    }

    // ------------------------------------------------------- report types

    @Test
    fun outputAndFeatureRequestsAreRefused() {
        for (type in listOf(OUTPUT, FEATURE)) {
            assertEquals(
                "type $type is not declared by this descriptor",
                HidRequestOutcome.UnsupportedType, get(gamepadId, type = type)
            )
            assertEquals(
                HidRequestOutcome.UnsupportedType,
                HidReportRequests.setReport(type, gamepadId, INPUT)
            )
        }
    }

    @Test
    fun anUnspecifiedTypeIsTreatedAsInput() {
        assertArrayEquals(
            HidConstants.neutralGamepadReport(),
            replyBytes(get(gamepadId, type = UNSPECIFIED))
        )
        assertEquals(
            HidRequestOutcome.Acknowledge,
            HidReportRequests.setReport(UNSPECIFIED, gamepadId, INPUT)
        )
    }

    /**
     * The accepted type comes from the caller, so the platform constant is
     * never duplicated here and cannot drift. Proving that means showing the
     * decision moves when the argument moves.
     */
    @Test
    fun theAcceptedTypeFollowsTheArgumentRatherThanAHardCodedOne() {
        assertArrayEquals(
            "the caller's input type must be accepted",
            HidConstants.neutralGamepadReport(),
            replyBytes(get(gamepadId, type = INPUT_UNUSUAL, inputType = INPUT_UNUSUAL))
        )
        assertEquals(
            "a type that is not the caller's input type must be refused",
            HidRequestOutcome.UnsupportedType,
            get(gamepadId, type = INPUT, inputType = INPUT_UNUSUAL)
        )
    }

    @Test
    fun anUnsupportedTypeIsRefusedBeforeTheReportIdIsEvenConsidered() {
        // Otherwise a host probing an output report would be told the id was
        // wrong, and would retry the same unsupported request with other ids.
        assertEquals(
            HidRequestOutcome.UnsupportedType,
            get(0x42.toByte(), type = OUTPUT)
        )
    }

    // ------------------------------------------------------- buffer sizes

    @Test
    fun aShortHostBufferTruncatesRatherThanOverruns() {
        val full = HidConstants.GAMEPAD_REPORT_SIZE
        for (size in 1 until full) {
            val payload = replyBytes(get(gamepadId, bufferSize = size))
            assertEquals("a $size-byte buffer must get $size bytes", size, payload.size)
            assertArrayEquals(
                "the truncated reply must be the report's first $size bytes",
                HidConstants.neutralGamepadReport().copyOf(size), payload
            )
        }
    }

    @Test
    fun anExactOrLargerBufferGetsTheWholeReportAndNoPadding() {
        val full = HidConstants.GAMEPAD_REPORT_SIZE
        for (size in listOf(full, full + 1, 64, 1024)) {
            val payload = replyBytes(get(gamepadId, bufferSize = size))
            assertEquals(
                "a $size-byte buffer must still get exactly the report",
                full, payload.size
            )
            assertArrayEquals(HidConstants.neutralGamepadReport(), payload)
        }
    }

    @Test
    fun aMissingOrNonsenseBufferSizeMeansNoLimit() {
        for (size in listOf(0, -1, Int.MIN_VALUE)) {
            assertEquals(
                "buffer size $size must not shorten or empty the reply",
                HidConstants.GAMEPAD_REPORT_SIZE,
                replyBytes(get(gamepadId, bufferSize = size)).size
            )
        }
    }

    @Test
    fun theKeyboardReplyTruncatesAgainstItsOwnLengthNotTheGamepadsOne() {
        // 8 bytes is the whole keyboard report but only part of the gamepad's,
        // so a length taken from the wrong report would show up here.
        val payload = replyBytes(get(keyboardId, bufferSize = HidConstants.KEYBOARD_REPORT_SIZE))
        assertEquals(HidConstants.KEYBOARD_REPORT_SIZE, payload.size)

        val short = replyBytes(get(keyboardId, bufferSize = 3))
        assertEquals(3, short.size)
    }

    @Test
    fun eachReplyOwnsItsBytesSoAHostCannotCorruptTheNext() {
        val first = replyBytes(get(gamepadId))
        first[0] = 0x7F
        first[3] = 0x00
        val second = replyBytes(get(gamepadId))
        assertArrayEquals(
            "a later reply must be unaffected by an earlier one",
            HidConstants.neutralGamepadReport(), second
        )
    }

    // ------------------------------------------------------------ Set_Report

    @Test
    fun aWellFormedSetReportIsAcknowledged() {
        assertSame(HidRequestOutcome.Acknowledge, HidReportRequests.setReport(INPUT, gamepadId, INPUT))
        assertSame(HidRequestOutcome.Acknowledge, HidReportRequests.setReport(INPUT, keyboardId, INPUT))
        assertSame(HidRequestOutcome.Acknowledge, HidReportRequests.setReport(INPUT, 0, INPUT))
    }

    @Test
    fun aSetReportForAnUndeclaredIdIsRefused() {
        for (id in listOf<Byte>(3, 9, -1)) {
            assertEquals(
                HidRequestOutcome.UnknownReportId,
                HidReportRequests.setReport(INPUT, id, INPUT)
            )
        }
    }

    @Test
    fun setReportNeverAsksTheCallerToSendBytesBack() {
        // There is no Output or Feature report to read, so no Set_Report may
        // ever produce a payload for the host.
        for (type in listOf(UNSPECIFIED, INPUT, OUTPUT, FEATURE)) {
            for (id in listOf<Byte>(0, 1, 2, 3, -1)) {
                val outcome = HidReportRequests.setReport(type, id, INPUT)
                assertTrue(
                    "type $type id $id must not produce a reply payload",
                    outcome !is HidRequestOutcome.Reply
                )
            }
        }
    }

    // ------------------------------------------- agreement with the real code

    /**
     * Every outcome the decision can produce must be one [HidGamepadManager]
     * handles. If a new outcome is ever added without wiring it up there, the
     * host is left waiting on a request that is never answered.
     */
    @Test
    fun theOutcomesAreExactlyTheFourTheCallbackHandles() {
        val produced = mutableSetOf<String>()
        for (type in listOf(UNSPECIFIED, INPUT, OUTPUT, FEATURE)) {
            for (id in listOf<Byte>(0, 1, 2, 3, -1)) {
                for (buffer in listOf(-1, 0, 2, 9, 64)) {
                    produced += HidReportRequests
                        .getReport(type, id, buffer, INPUT)::class.simpleName!!
                    produced += HidReportRequests
                        .setReport(type, id, INPUT)::class.simpleName!!
                }
            }
        }
        assertEquals(
            setOf("Reply", "Acknowledge", "UnsupportedType", "UnknownReportId"),
            produced
        )
    }
}
