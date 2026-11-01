// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanFailuresTest {
    private val known =
        listOf(
            ScanFailures.ALREADY_STARTED,
            ScanFailures.APPLICATION_REGISTRATION_FAILED,
            ScanFailures.INTERNAL_ERROR,
            ScanFailures.FEATURE_UNSUPPORTED,
            ScanFailures.OUT_OF_HARDWARE_RESOURCES,
            ScanFailures.SCANNING_TOO_FREQUENTLY,
        )

    @Test
    fun `a refused, throttled or transitory start is retried`() {
        assertTrue(ScanFailures.isRetryable(ScanFailures.APPLICATION_REGISTRATION_FAILED))
        assertTrue(ScanFailures.isRetryable(ScanFailures.SCANNING_TOO_FREQUENTLY))
        assertTrue(ScanFailures.isRetryable(ScanFailures.INTERNAL_ERROR))
        assertTrue(ScanFailures.isRetryable(ScanFailures.OUT_OF_HARDWARE_RESOURCES))
    }

    @Test
    fun `a scan that is already running or unsupported is not retried`() {
        assertFalse(ScanFailures.isRetryable(ScanFailures.ALREADY_STARTED))
        assertFalse(ScanFailures.isRetryable(ScanFailures.FEATURE_UNSUPPORTED))
    }

    @Test
    fun `a throttle waits out the thirty second window`() {
        assertEquals(31_000L, ScanFailures.delayMs(ScanFailures.APPLICATION_REGISTRATION_FAILED, 1))
        assertEquals(31_000L, ScanFailures.delayMs(ScanFailures.SCANNING_TOO_FREQUENTLY, 3))
    }

    @Test
    fun `other failures back off from five seconds to twenty`() {
        val seconds = (1..4).map { ScanFailures.delayMs(ScanFailures.INTERNAL_ERROR, it) / 1000 }

        assertEquals(listOf(5L, 10L, 20L, 20L), seconds)
    }

    @Test
    fun `every known code has its own readable message`() {
        // An unknown code still logs, with the number, rather than saying nothing.
        assertTrue(ScanFailures.message(99).contains("99"))
        assertEquals(known.size, known.map { ScanFailures.message(it) }.toSet().size)
    }
}
