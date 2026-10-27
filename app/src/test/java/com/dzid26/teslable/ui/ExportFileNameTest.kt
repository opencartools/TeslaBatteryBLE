// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportFileNameTest {
    @Test
    fun namesTheZipByUtcTime() {
        assertEquals("teslabatteryble-logs-20261027-125138.zip", exportFileName(1_793_105_498_000L))
    }
}
