// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.runtime.compositionLocalOf
import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.SessionPrice
import com.dzid26.teslable.core.health.StoredChargePrice

/**
 * The prices `owner.db` keeps for charge sessions, and how the owner changes one: the stored prices and the call
 * that stores one. [onSet] takes the ISO 4217 code the price is in.
 */
internal class ChargePricing(
    val stored: List<StoredChargePrice>,
    val onSet: (ChargeSession, SessionPrice?, String, String?) -> Unit,
)

/** Null (the default) shows the automatic prices and offers no editing. */
internal val LocalChargePricing = compositionLocalOf<ChargePricing?> { null }
