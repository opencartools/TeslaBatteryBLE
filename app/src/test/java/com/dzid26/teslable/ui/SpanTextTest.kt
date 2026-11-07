// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class SpanTextTest {
    private val defaultLocale: Locale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun timeSpanGivesTheChangeTheLengthAndTheRate() {
        val text = timeSpanText(0L to 80f, 3 * HOUR + 5 * MINUTE to 67.6f)
        assertEquals("−12.4% · 3h5m · −4.0%/h", text)
    }

    @Test
    fun timeSpanReadsLeftToRightWhicheverWayTheFingerWent() {
        assertEquals(timeSpanText(0L to 50f, 2 * HOUR to 60f), timeSpanText(2 * HOUR to 60f, 0L to 50f))
        assertEquals("+10.0% · 2h · +5.0%/h", timeSpanText(2 * HOUR to 60f, 0L to 50f))
    }

    @Test
    fun distanceSpanGivesTheRatePerUnit() {
        val text = distanceSpanText(2.0 to 70f, 14.4 to 63.9f, { "${it}mi" }, "mi")
        assertEquals("−6.1% · 12.4mi · −0.49%/mi", text)
    }

    @Test
    fun noChangeHasNoSign() {
        assertEquals("0.0% · 1h · 0.0%/h", timeSpanText(0L to 50f, HOUR to 50f))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
