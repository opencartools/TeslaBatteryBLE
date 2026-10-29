// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class LogReportTest {
    @Test
    fun `header comes first and lines carry their time and car once`() {
        val text =
            formatLogReport(
                header = listOf("Phone: X"),
                log =
                    listOf(
                        LogEntry(null, "Scan ended"),
                        LogEntry("S1C", "connected", timeMillis = 5),
                        LogEntry("S1C", "S1C: pairing", timeMillis = 6),
                    ),
                formatTime = { "t$it" },
            )
        assertEquals("Phone: X\n\nScan ended\nt5 S1C: connected\nt6 S1C: pairing\n", text.replace("\r\n", "\n"))
    }
}
