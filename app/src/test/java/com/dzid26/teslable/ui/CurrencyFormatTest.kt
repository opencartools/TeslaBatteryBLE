// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import com.dzid26.teslable.currencyOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

class CurrencyFormatTest {
    private lateinit var savedLocale: Locale

    @Before
    fun pinLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `real codes are accepted whatever their case or padding`() {
        assertEquals("EUR", currencyOrNull("eur")?.currencyCode)
        assertEquals("USD", currencyOrNull(" usd ")?.currencyCode)
    }

    @Test
    fun `blank unknown and pseudo codes are rejected`() {
        assertNull(currencyOrNull(""))
        assertNull(currencyOrNull(null))
        assertNull(currencyOrNull("ABC"))
        assertNull(currencyOrNull("XXX"))
        assertNull(currencyOrNull("XAU"))
    }

    @Test
    fun `the formatter uses each currency's own fraction digits`() {
        assertEquals("€6.26", currencyFormat("EUR").format(6.26))
        assertEquals(0, currencyFormat("JPY").maximumFractionDigits)
        assertEquals(3, currencyFormat("KWD").maximumFractionDigits)
        assertNotNull(currencyFormat("KWD").format(1.5))
    }

    @Test
    fun `a blank or invalid code follows the locale currency`() {
        assertEquals("$6.26", currencyFormat(null).format(6.26))
        assertEquals("$6.26", currencyFormat("XXX").format(6.26))
    }

    @Test
    fun `a locale without a region falls back to dollars`() {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("$6.26", currencyFormat(null).format(6.26))
    }

    @Test
    fun `choices list the common currencies once and add an uncommon selected one last`() {
        val common = currencyChoices(null).map { it.currencyCode }
        assertEquals(common.distinct(), common)
        assertEquals("USD", common.first())
        assertEquals(common, currencyChoices(currencyOrNull("eur")).map { it.currencyCode })
        assertEquals("ISK", currencyChoices(currencyOrNull("ISK")).last().currencyCode)
    }

    @Test
    fun `a label is the code and its name`() {
        assertEquals("EUR – Euro", currencyLabel(currencyOrNull("EUR")!!))
        assertEquals("USD – US Dollar", currencyLabel(currencyOrNull("USD")!!))
    }

    @Test
    fun `a currency the phone has no symbol for is written the way its own country does`() {
        assertEquals("1,50 zł", currencyFormat("PLN").format(1.5))
        assertEquals("$1.50", currencyFormat("USD").format(1.5))
    }
}
