package com.dathaze.pocketpad.hid

/** How a host's Get_Report or Set_Report request should be answered. */
sealed class HidRequestOutcome {
    /** Reply with exactly these bytes. */
    class Reply(val payload: ByteArray) : HidRequestOutcome()

    /** Acknowledge the request; nothing to send back. */
    object Acknowledge : HidRequestOutcome()

    /** The report type is not one this descriptor declares. */
    object UnsupportedType : HidRequestOutcome()

    /** The report id is not one this descriptor declares. */
    object UnknownReportId : HidRequestOutcome()
}

/**
 * Decides how to answer a HID control request.
 *
 * This is the whole decision — type check, report-id lookup and buffer
 * truncation — kept free of `android.*` so it can be exercised directly in a
 * JVM test. [HidGamepadManager] does nothing with a request but hand it here
 * and translate the answer into the matching Bluetooth call, so testing this
 * tests the real path rather than a copy of it.
 *
 * The platform's own `REPORT_TYPE_INPUT` value is passed in rather than
 * redeclared here, so the two can never drift apart.
 */
object HidReportRequests {

    private const val TYPE_UNSPECIFIED: Byte = 0

    /**
     * Answer a Get_Report. [bufferSize] is how much room the host allocated;
     * a smaller buffer truncates rather than overruns, and a non-positive
     * value is treated as "no limit given".
     */
    fun getReport(
        type: Byte,
        reportId: Byte,
        bufferSize: Int,
        inputReportType: Byte
    ): HidRequestOutcome {
        if (!isInputType(type, inputReportType)) return HidRequestOutcome.UnsupportedType
        val report = HidConstants.neutralReportFor(reportId.toInt() and 0xFF)
            ?: return HidRequestOutcome.UnknownReportId
        val payload =
            if (bufferSize in 1 until report.size) report.copyOf(bufferSize) else report
        return HidRequestOutcome.Reply(payload)
    }

    /**
     * Answer a Set_Report. This descriptor declares no Output or Feature
     * report, so there is nothing a host can legitimately set; a well-formed
     * request is acknowledged and anything else is refused rather than
     * silently accepted.
     */
    fun setReport(type: Byte, reportId: Byte, inputReportType: Byte): HidRequestOutcome {
        if (!isInputType(type, inputReportType)) return HidRequestOutcome.UnsupportedType
        if (HidConstants.neutralReportFor(reportId.toInt() and 0xFF) == null) {
            return HidRequestOutcome.UnknownReportId
        }
        return HidRequestOutcome.Acknowledge
    }

    /** Type 0 means the host named no report type. */
    private fun isInputType(type: Byte, inputReportType: Byte): Boolean =
        type == TYPE_UNSPECIFIED || type == inputReportType
}
