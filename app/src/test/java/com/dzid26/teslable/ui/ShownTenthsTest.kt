// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShownTenthsTest {
    @Test
    fun aFullCarWhileChargingReadsOneHundredPointZero() {
        assertEquals(1000, shownTenths(999, level = 100, charging = true))
    }

    @Test
    fun aFullCarWhenNotChargingKeepsTheFittedTenth() {
        assertEquals(999, shownTenths(999, level = 100, charging = false))
    }

    @Test
    fun belowOneHundredTheFittedTenthStaysWhileCharging() {
        assertEquals(987, shownTenths(987, level = 99, charging = true))
    }

    @Test
    fun withoutAFitThereIsNoTenth() {
        assertNull(shownTenths(null, level = 100, charging = true))
    }
}
