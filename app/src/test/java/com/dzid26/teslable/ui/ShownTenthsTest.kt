// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShownTenthsTest {
    @Test
    fun aFullCarOnTheChargerReadsOneHundredPointZero() {
        assertEquals(1000, shownTenths(999, level = 100, onCharger = true))
    }

    @Test
    fun aFullCarOffTheChargerKeepsTheFittedTenth() {
        assertEquals(999, shownTenths(999, level = 100, onCharger = false))
    }

    @Test
    fun belowOneHundredTheFittedTenthStaysOnTheCharger() {
        assertEquals(987, shownTenths(987, level = 99, onCharger = true))
    }

    @Test
    fun withoutAFitThereIsNoTenth() {
        assertNull(shownTenths(null, level = 100, onCharger = true))
    }
}
