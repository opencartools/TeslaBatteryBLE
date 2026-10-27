// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTest {
    private fun drive(
        start: Long,
        end: Long,
    ) = DriveSession(start, end, "car", startOdometer = 0, endOdometer = 100)

    @Test
    fun `the stored sessions before the newest one stay and the tail is cut live`() {
        val stored = listOf(drive(0, 10), drive(20, 30), drive(40, 45))
        var askedFrom: Long? = null
        val joined =
            withLiveTail(stored) { from ->
                askedFrom = from
                listOf(drive(20, 30), drive(40, 50), drive(60, 70))
            }
        assertEquals(40L - SESSION_CUT_LEAD_MILLIS, askedFrom)
        assertEquals(listOf(drive(0, 10), drive(20, 30), drive(40, 50), drive(60, 70)), joined)
    }

    @Test
    fun `with nothing stored the live cut is everything`() {
        var askedFrom: Long? = null
        val joined =
            withLiveTail(emptyList<DriveSession>()) { from ->
                askedFrom = from
                listOf(drive(0, 10))
            }
        assertEquals(listOf(drive(0, 10)), joined)
        assertEquals(Long.MIN_VALUE, askedFrom)
    }

    @Test
    fun `fromTime finds the first item at or after a time`() {
        val times = listOf(1L, 3, 3, 5, 9)
        assertEquals(listOf(3L, 3, 5, 9), times.fromTime(3) { it })
        assertEquals(listOf(9L), times.fromTime(6) { it })
        assertEquals(emptyList(), times.fromTime(10) { it })
    }
}
