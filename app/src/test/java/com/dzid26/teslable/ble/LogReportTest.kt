// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class LogReportTest {
    @Test
    fun `header comes first and car lines carry their car`() {
        val text =
            formatLogReport(
                header = listOf("Phone: X"),
                log = listOf(LogEntry(null, "Scan ended"), LogEntry("S1C", "connected")),
            )
        assertEquals("Phone: X\n\nScan ended\nS1C: connected\n", text.replace("\r\n", "\n"))
    }
}
