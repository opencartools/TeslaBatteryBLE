// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.dzid26.teslable.ble.DemoMode
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The VIN prompt in light and dark: a partial VIN (the location hint and its Tesla link visible, Save
 * disabled), the VIN that hashes to the demo car's advertised name (Save enabled), and a full-length
 * mismatch (error text, Save still disabled). Paparazzi does not render real Dialog windows, so
 * [VinPromptContent] — the dialog's own title, body and actions — is rendered inside the screen frame the
 * full-screen snapshots use.
 */
class VinPromptSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    private val defaultLocale: Locale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun promptPartial() = snap("vin-prompt_partial", DemoMode.DEMO_VIN.take(10))

    @Test
    fun promptValid() = snap("vin-prompt_valid", DemoMode.DEMO_VIN)

    @Test
    fun promptMismatch() = snap("vin-prompt_mismatch", "5YJ3E1EA7KF000002")

    private fun snap(
        name: String,
        vin: String,
    ) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = screenPhone,
            renderingMode = SessionParams.RenderingMode.NORMAL,
        )
        paparazzi.snapshot(name + "_light") { frame(darkTheme = false, vin = vin) }
        paparazzi.snapshot(name + "_dark") { frame(darkTheme = true, vin = vin) }
    }

    @Composable
    private fun frame(
        darkTheme: Boolean,
        vin: String,
    ) {
        ScreenFrame(darkTheme = darkTheme) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                VinPromptContent(
                    vehicle = DemoScreenFixtures.vehicle,
                    vinInput = vin,
                    onVinChange = {},
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
    }
}
