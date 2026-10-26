// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dzid26.teslable.R
import com.dzid26.teslable.core.units.DistanceUnit
import com.dzid26.teslable.core.units.EfficiencyUnit

/** The unit distances are shown in; stored values stay in miles. Provided once per car screen. */
internal val LocalDistanceUnit = compositionLocalOf { DistanceUnit.MILES }

/** How energy per distance reads; provided once per car screen next to [LocalDistanceUnit]. */
internal val LocalEfficiencyUnit = compositionLocalOf { EfficiencyUnit.ENERGY_PER_DISTANCE }

/** Moves [LocalEfficiencyUnit] to the next form and remembers it; provided next to it, a no-op without a car screen. */
internal val LocalCycleEfficiencyUnit = compositionLocalOf<() -> Unit> { {} }

/** The form after this one, wrapping around. */
internal fun EfficiencyUnit.next(): EfficiencyUnit = EfficiencyUnit.entries[(ordinal + 1) % EfficiencyUnit.entries.size]

/** A tap on an efficiency figure cycles the unit everywhere; deliberately no ripple or underline, just a label for screen readers. */
@Composable
internal fun Modifier.cyclesEfficiencyUnit(): Modifier {
    val cycle = LocalCycleEfficiencyUnit.current
    val label = stringResource(R.string.drive_efficiency_cycle)
    return clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClickLabel = label,
        onClick = cycle,
    )
}
