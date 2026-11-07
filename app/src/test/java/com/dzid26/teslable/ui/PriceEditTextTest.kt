// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriceEditTextTest {
    @Test
    fun `parses a dot or a comma and trims spaces`() {
        assertEquals(0.25f, parseEdit("0.25"))
        assertEquals(0.25f, parseEdit(" 0,25 "))
        assertEquals(0f, parseEdit("0"))
    }

    @Test
    fun `rejects blank, negative and non-numbers`() {
        assertNull(parseEdit(""))
        assertNull(parseEdit("-1"))
        assertNull(parseEdit("abc"))
        assertNull(parseEdit("NaN"))
        assertNull(parseEdit("Infinity"))
    }

    @Test
    fun `writes up to four decimals without trailing zeros`() {
        assertEquals("0.3", editText(0.3f))
        assertEquals("18.4", editText(18.4f))
        assertEquals("0.1235", editText(0.123456f))
        assertEquals("12", editText(12f))
        assertEquals("", editText(null))
        assertEquals("", editText(Float.NaN))
    }

    @Test
    fun `what it writes parses back`() {
        listOf(0.3f, 18.4f, 0.1235f, 12f).forEach { assertEquals(it, parseEdit(editText(it))) }
    }
}
