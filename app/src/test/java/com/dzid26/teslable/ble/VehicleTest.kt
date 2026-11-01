// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleTest {
    private val vin = "5YJ3E1EA7KF000001"
    private val bleName = "S3acc31774a738ea0C"

    private fun vehicle(vin: String? = null) =
        Vehicle(
            bleName = bleName,
            address = "18:12:ED:33:33:80",
            gattName = "\uD83D\uDD11 My Tesla",
            vin = vin,
        )

    @Test
    fun acceptsTheVinThatMatchesTheAdvertisedName() {
        assertTrue(vehicle().acceptsVin(vin))
    }

    @Test
    fun hasSessionVinOnlyForAFullLengthVin() {
        assertFalse(vehicle().hasSessionVin)
        assertFalse(vehicle(vin.take(16)).hasSessionVin)
        assertTrue(vehicle(vin).hasSessionVin)
    }

    @Test
    fun vinVerdictSplitsEmptyPartialMatchAndMismatch() {
        val car = vehicle()
        assertEquals(VinVerdict.EMPTY, car.vinVerdict("  "))
        assertEquals(VinVerdict.PARTIAL, car.vinVerdict(vin.take(10)))
        assertEquals(VinVerdict.MATCH, car.vinVerdict("  ${vin.lowercase()}  "))
        assertEquals(VinVerdict.MISMATCH, car.vinVerdict("5YJ3E7EBXKF000000"))
    }

    @Test
    fun acceptsNormalisedInput() {
        assertTrue(vehicle().acceptsVin("  ${vin.lowercase()}  "))
        assertEquals(vin, Vehicle.normalizeVin("  ${vin.lowercase()}  "))
    }

    @Test
    fun rejectsAnotherVin() {
        assertFalse(vehicle().acceptsVin("5YJ3E7EBXKF000000"))
    }

    @Test
    fun rejectsShortInput() {
        assertFalse(vehicle().acceptsVin("5YJ3E7"))
    }

    @Test
    fun titlePrefersUserThenCarName() {
        assertEquals("\uD83D\uDD11 My Tesla", vehicle().title)
        assertEquals("Daily driver", vehicle().copy(displayName = "Daily driver").title)
    }

    @Test
    fun pairedOnlyWhenAKeySlotIsKnown() {
        assertFalse(vehicle().isPaired)
        assertTrue(vehicle().copy(keySlot = 3).isPaired)
    }
}
