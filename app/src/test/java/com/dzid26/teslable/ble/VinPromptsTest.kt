// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt state the Compose layer observes: raised for a car without a session VIN, kept per link and
 * across transient BLE link drops, cleared on cancel, and consumed with the action a saved VIN unblocks.
 */
class VinPromptsTest {
    private val vin = "5YJ3E1EA7KF000001"
    private val bleName = "S3acc31774a738ea0C"
    private val state = MutableStateFlow(BleUiState())
    private val logs = mutableListOf<String>()
    private val prompts = VinPrompts(state) { logs += it }

    private fun vehicle(storedVin: String? = null) =
        Vehicle(
            bleName = bleName,
            address = "18:12:ED:33:33:80",
            vin = storedVin,
        )

    @Test
    fun requestRaisesThePromptAndBlocksTheAction() {
        assertTrue(prompts.request(vehicle(), VinIntent.Wake))
        assertEquals(VinRequest(bleName, VinIntent.Wake), state.value.vinRequest)
        assertTrue(logs.single().contains("VIN needed"))
    }

    @Test
    fun aCarWithAVinIsNotPrompted() {
        assertFalse(prompts.request(vehicle(vin), VinIntent.Refresh))
        assertNull(state.value.vinRequest)
    }

    @Test
    fun theBannerAsksForNothingToReplay() {
        assertTrue(prompts.request(vehicle()))
        assertEquals(VinRequest(bleName, replay = null), state.value.vinRequest)
    }

    @Test
    fun aLinkCloseKeepsThePrompt() {
        prompts.request(vehicle(), VinIntent.Wake)
        // A periodic read or a dropped/reconnected link rewrites the connections; the dialog stays up.
        state.update { it.copy(connections = emptyMap(), selectedBleName = bleName) }
        assertEquals(VinRequest(bleName, VinIntent.Wake), state.value.vinRequest)
    }

    @Test
    fun savingConsumesThePromptAndReplaysTheAction() {
        prompts.request(vehicle(), VinIntent.Wake)
        assertEquals(VinIntent.Wake, prompts.saved(vehicle(vin)))
        assertNull(state.value.vinRequest)
    }

    @Test
    fun aSaveWithoutTheVinReplaysNothing() {
        prompts.request(vehicle(), VinIntent.Refresh)
        assertNull(prompts.saved(vehicle()))
        assertNull(state.value.vinRequest)
    }

    @Test
    fun dismissClearsThePrompt() {
        prompts.request(vehicle(), VinIntent.Refresh)
        prompts.dismiss()
        assertNull(state.value.vinRequest)
    }
}
