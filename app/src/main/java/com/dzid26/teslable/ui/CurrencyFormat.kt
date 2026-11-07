// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.runtime.compositionLocalOf
import com.dzid26.teslable.core.health.PriceChange
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** The ISO 4217 code chosen in Settings; null follows the phone's locale. Provided once per car screen. */
internal val LocalCurrencyCode = compositionLocalOf<String?> { null }

/** The owner's price per kWh, for the finished charge's cost on the history card; null shows the kWh alone. */
internal val LocalPricePerKwh = compositionLocalOf<Float?> { null }

/** Every price the owner set, for pricing a finished charge by the price in effect when it ended. */
internal val LocalPriceChanges = compositionLocalOf<List<PriceChange>> { emptyList() }

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

/**
 * A money formatter for [code]; blank or invalid follows the phone's currency, and USD when it has none.
 * A currency the phone's language has no symbol for (PLN on an English phone prints "PLN 1.50") is written
 * the way its own country does, "1,50 zł".
 */
internal fun currencyFormat(code: String?): NumberFormat {
    val chosen = Currency.getInstance(resolvedCurrencyCode(code))
    val locale = formatLocale(chosen)
    return NumberFormat.getCurrencyInstance(locale).apply {
        currency = chosen
        // Android's formatter takes the symbol from the phone's language, not the format's locale ("PLN" for "zł").
        (this as? DecimalFormat)?.let { format ->
            format.decimalFormatSymbols = format.decimalFormatSymbols.apply { currencySymbol = chosen.getSymbol(locale) }
        }
        // Pinned explicitly: not every platform formatter resets its digits when the currency changes.
        minimumFractionDigits = chosen.defaultFractionDigits
        maximumFractionDigits = chosen.defaultFractionDigits
    }
}

private fun formatLocale(currency: Currency): Locale {
    val phone = Locale.getDefault()
    if (currency.getSymbol(phone) != currency.currencyCode) return phone
    return Locale
        .getAvailableLocales()
        .filter { it.country.isNotEmpty() && localeCurrencyOf(it) == currency }
        // The country's own language first (pl_PL, not en_PL or ru_PL), then a stable order.
        .minWithOrNull(compareBy({ !it.language.equals(it.country, ignoreCase = true) }, { it.toString() })) ?: phone
}

private fun localeCurrencyOf(locale: Locale): Currency? = runCatching { Currency.getInstance(locale) }.getOrNull()

/** The currencies offered in Settings; the phone's own and any already-saved one are added when missing. */
private val COMMON_CURRENCY_CODES =
    (
        "USD EUR GBP CAD AUD NZD CHF SEK NOK DKK PLN CZK HUF RON BGN UAH TRY ILS AED SAR ZAR INR CNY HKD TWD JPY KRW SGD THB MYR IDR PHP MXN BRL ARS CLP COP"
    ).split(' ')

/**
 * The picker's choices: the common currencies, then the [selected] one (a saved or phone currency outside
 * the list) so it can still be shown, each once and in that order.
 */
internal fun currencyChoices(selected: Currency?): List<Currency> =
    (COMMON_CURRENCY_CODES.mapNotNull { currencyOrNull(it) } + listOfNotNull(selected)).distinct()

/** "PLN – Polish Zloty": the code and the name the phone's language gives it. */
internal fun currencyLabel(currency: Currency): String = "${currency.currencyCode} – ${currency.displayName}"
