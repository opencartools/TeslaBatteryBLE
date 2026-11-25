// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import com.dzid26.teslable.core.history.HistoryRange
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryZoomTest {
    @Test
    fun aLongRangeZoomsUntilAnHourFillsTheView() {
        // A day keeps the usual fiftieth (28.8 minutes); a week or a month may go down to one hour.
        assertEquals(MIN_VIEW_SPAN, HistoryRange.DAY.minViewSpan(), 1e-9)
        assertEquals(1.0 / (7 * 24), HistoryRange.WEEK.minViewSpan(), 1e-9)
        assertEquals(1.0 / (30 * 24), HistoryRange.MONTH.minViewSpan(), 1e-9)
        // All has no fixed range, so it keeps the default.
        assertEquals(MIN_VIEW_SPAN, HistoryRange.ALL.minViewSpan(), 1e-9)
    }
}
