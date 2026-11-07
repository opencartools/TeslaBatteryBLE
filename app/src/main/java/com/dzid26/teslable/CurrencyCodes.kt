// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable

import java.util.Currency
import java.util.Locale

// The owner's currency as an ISO 4217 code: shared by the screens, which format money, and the history, which
// stores a price's resolved code.

internal const val FALLBACK_CODE = "USD"

/**
 * The currency for an ISO 4217 [code] such as "eur" or " usd ", or null when it is blank, not a code, or
 * not real money: pseudo codes (XXX, XTS) and funds or metals (XAU) have no fraction digits (negative).
 */
internal fun currencyOrNull(code: String?): Currency? =
    runCatching { Currency.getInstance(code?.trim()?.uppercase()) }.getOrNull()?.takeIf { it.defaultFractionDigits >= 0 }

/** The phone's own currency, or null for a locale without a region (plain "en", root). */
internal fun localeCurrencyOrNull(): Currency? =
    runCatching { Currency.getInstance(Locale.getDefault()) }.getOrNull()?.takeIf { it.defaultFractionDigits >= 0 }

/** The ISO 4217 code [code] stands for, resolved the way [currencyFormat] does: the code to store with a price. */
internal fun resolvedCurrencyCode(code: String?): String =
    (currencyOrNull(code) ?: localeCurrencyOrNull() ?: Currency.getInstance(FALLBACK_CODE)).currencyCode
