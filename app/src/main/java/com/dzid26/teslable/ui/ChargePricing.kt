// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.runtime.compositionLocalOf
import com.dzid26.teslable.core.health.ChargePriceOverride
import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.SessionPrice

/**
 * The prices the owner set for charge sessions, and how to change them: the overrides `owner.db` keeps and the
 * calls that store or clear one. [onSet] takes the ISO 4217 code the price is in.
 */
internal class ChargePricing(
    val overrides: List<ChargePriceOverride>,
    val onSet: (ChargeSession, SessionPrice, String) -> Unit,
    val onClear: (ChargeSession) -> Unit,
)

/** Null (the default) shows the automatic prices and offers no editing. */
internal val LocalChargePricing = compositionLocalOf<ChargePricing?> { null }
