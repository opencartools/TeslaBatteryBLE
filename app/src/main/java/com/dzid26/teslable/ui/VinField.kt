// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import com.dzid26.teslable.ble.Vehicle
import com.dzid26.teslable.ble.VinVerdict

/**
 * The VIN field both dialogs show: the edit dialog and the prompt that asks for a missing VIN. They share
 * the verdict ([Vehicle.vinVerdict]), so a VIN one accepts is accepted by the other, and a typo is caught
 * against the car's advertised name before it is stored. An empty or partial VIN gets [VinLocationHint]
 * below the field, aligned with the paragraph above it (a supporting-text slot would indent it).
 */
@Composable
internal fun VinField(
    vehicle: Vehicle,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val verdict = vehicle.vinVerdict(value)
    val verdictHint: (@Composable () -> Unit)? =
        if (verdict == VinVerdict.EMPTY || verdict == VinVerdict.PARTIAL) {
            null
        } else {
            { VinVerdictText(verdict) }
        }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            label = { Text(stringResource(R.string.vin_label)) },
            isError = verdict == VinVerdict.MISMATCH,
            supportingText = verdictHint,
            modifier = Modifier.fillMaxWidth(),
        )
        if (verdictHint == null) VinLocationHint()
    }
}

/** The verdict line, kept off the location hint's row so the hint only appears while the VIN is incomplete. */
@Composable
private fun VinVerdictText(verdict: VinVerdict) {
    val text =
        when (verdict) {
            VinVerdict.MISMATCH -> R.string.vin_mismatch
            VinVerdict.MATCH -> R.string.vin_saved
            VinVerdict.EMPTY, VinVerdict.PARTIAL -> return
        }
    Text(stringResource(text))
}

/**
 * The VIN field's supporting text and the "VIN needed" card's body: how to get the VIN, led by a small
 * open-in-new icon. The whole row is the link to the Tesla app, so the glyph and the sentence start
 * flush with the paragraph above instead of an icon button's centered 48dp box. The VIN field in both
 * dialogs and the card render this one composable, so the copy cannot drift.
 */
@Composable
internal fun VinLocationHint(
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val context = LocalContext.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = VIN_LINK_TOUCH_TARGET)
                .clickable(onClickLabel = stringResource(R.string.vin_hint_open_app)) { openTeslaApp(context) }
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_open_in_new),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(VIN_LINK_ICON_SIZE),
        )
        Spacer(Modifier.width(VIN_LINK_GAP))
        Text(
            text = stringResource(R.string.vin_hint),
            style = style,
            color = color,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** The open-in-new glyph, leading the sentence; the whole row is the target. */
private val VIN_LINK_ICON_SIZE = 18.dp
private val VIN_LINK_GAP = 6.dp
private val VIN_LINK_TOUCH_TARGET = 48.dp
