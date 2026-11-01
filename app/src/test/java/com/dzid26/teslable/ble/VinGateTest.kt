// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The VIN gate: a car without a session-capable VIN gets a prompt for every action the owner asks
 * for, the prompt carries the car and the action, and saving the VIN replays exactly that action.
 */
class VinGateTest {
    private val vin = "5YJ3E1EA7KF000001"
    private val bleName = "S3acc31774a738ea0C"

    private fun vehicle(storedVin: String? = null) =
        Vehicle(
            bleName = bleName,
            address = "18:12:ED:33:33:80",
            gattName = "\uD83D\uDD11 My Tesla",
            vin = storedVin,
        )

    @Test
    fun everyUserIntentAsksForTheMissingVin() {
        val car = vehicle()
        listOf(VinIntent.Refresh, VinIntent.Wake).forEach { intent ->
            assertEquals(VinRequest(bleName, intent), vinRequestFor(car, intent))
        }
    }

    @Test
    fun theBannerAsksWithoutAnythingToReplay() {
        assertEquals(VinRequest(bleName, replay = null), vinRequestFor(vehicle(), null))
    }

    @Test
    fun aCarWithAVinIsNotAskedAgain() {
        val car = vehicle(vin)
        assertNull(vinRequestFor(car, VinIntent.Refresh))
        assertNull(vinRequestFor(car, VinIntent.Wake))
        assertNull(vinRequestFor(car, null))
    }

    @Test
    fun aPartialVinStillNeedsThePrompt() {
        assertEquals(VinRequest(bleName, VinIntent.Wake), vinRequestFor(vehicle(vin.take(16)), VinIntent.Wake))
    }

    @Test
    fun savingTheVinReplaysTheInterruptedAction() {
        val saved = vehicle(vin)
        assertEquals(VinIntent.Refresh, replayAfterVin(saved, VinRequest(bleName, VinIntent.Refresh)))
        assertEquals(VinIntent.Wake, replayAfterVin(saved, VinRequest(bleName, VinIntent.Wake)))
    }

    @Test
    fun savingFromTheBannerReplaysNothing() {
        assertNull(replayAfterVin(vehicle(vin), VinRequest(bleName)))
    }

    @Test
    fun cancellingLeavesTheCarUntouched() {
        val request = VinRequest(bleName, VinIntent.Refresh)
        // Cancel saved nothing, so the stored car still has no VIN and no action is replayed.
        assertNull(replayAfterVin(vehicle(), request))
        assertNull(replayAfterVin(null, request))
    }
}
