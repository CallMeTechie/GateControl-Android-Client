package com.gatecontrol.android.familie.money

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class MoneyTest {

    private val de = Locale.GERMANY

    /** NumberFormat puts a non-breaking space before "€"; compare with plain spaces. */
    private fun plain(s: String) = s.replace(' ', ' ').replace(' ', ' ')

    @Test
    fun `amounts in cents are formatted like the mockup`() {
        assertEquals("23,50 €", plain(Money.format(2350, de)))
        assertEquals("+ 5,00 €", plain(Money.format(500, de, signed = true)))
        assertEquals("− 8,50 €", plain(Money.format(-850, de, signed = true)))
        assertEquals("− 2,00 €", plain(Money.format(-200, de)), "a negative balance always shows its sign")
        assertEquals("0,00 €", plain(Money.format(0, de, signed = true)))
        assertEquals("€23.50", plain(Money.format(2350, Locale.UK)))
        assertEquals("60 €", plain(Money.short(6000, de)))
        assertEquals("12,30 €", plain(Money.short(1230, de)))
    }

    @Test
    fun `typed amounts become cents`() {
        assertEquals(500, Money.parseCents("5"))
        assertEquals(550, Money.parseCents("5,5"))
        assertEquals(550, Money.parseCents("5.50"))
        assertEquals(1230, Money.parseCents(" 12,30 € "))
        assertEquals(99_999_999, Money.parseCents("999999,99"))
        listOf("", "abc", "5,555", "-5", "1.000,00", "1234567", ",5").forEach { assertNull(Money.parseCents(it), it) }
        assertEquals("5,50", Money.input(550, de))
        assertEquals("5.05", Money.input(505, Locale.UK))
    }

    @Test
    fun `category bar shares, largest first`() {
        val shares = Money.shares(mapOf("household" to 10300, "food" to 33400, "kids" to 11600, "leisure" to 8900, "other" to 0))
        assertEquals(listOf("food", "kids", "household", "leisure"), shares.map { it.first })
        assertEquals(1.0f, shares.sumOf { it.second.toDouble() }.toFloat(), 0.0001f)
        assertTrue(Money.shares(emptyMap()).isEmpty())
        assertEquals(listOf("food", "kids"), Money.shares(mapOf("kids" to 5, "food" to 5)).map { it.first }, "ties in category order")
    }

    @Test
    fun `stars convert in whole steps of the rate`() {
        val rate = StarRate(10, 100)
        assertEquals(listOf(10), Money.convertSteps(12, rate))
        assertEquals(listOf(10, 20, 30), Money.convertSteps(35, rate))
        assertTrue(Money.convertSteps(9, rate).isEmpty())
        assertTrue(Money.convertSteps(50, null).isEmpty())
        assertEquals(20, Money.convertSteps(1_000, rate).size, "at most 20 choices")
        assertEquals(200, Money.starsValue(25, rate))
    }
}
