package com.gatecontrol.android.familie.money

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Euro amounts in integer cents: formatting, input, the category bar. Pure, unit-tested. */
object Money {

    private val INPUT_RE = Regex("^\\d{1,6}([.,]\\d{1,2})?$")

    /** "23,50 €" / "€23.50"; [signed] adds "+ " / "− " (mockup "+ 5,00 €"). */
    fun format(cents: Int, locale: Locale, signed: Boolean = false): String {
        val nf = NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance("EUR")
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        val abs = nf.format(kotlin.math.abs(cents.toLong()) / 100.0)
        return when {
            signed && cents > 0 -> "+ $abs"
            cents < 0 -> "− $abs"
            else -> abs
        }
    }

    /** "ca. 60 €": whole euros when there are no cents. */
    fun short(cents: Int, locale: Locale): String =
        if (cents % 100 == 0) {
            val nf = NumberFormat.getCurrencyInstance(locale).apply {
                currency = Currency.getInstance("EUR")
                minimumFractionDigits = 0
                maximumFractionDigits = 0
            }
            (if (cents < 0) "− " else "") + nf.format(kotlin.math.abs(cents.toLong()) / 100)
        } else {
            format(cents, locale)
        }

    /** "5", "5,5", "5.50", " 12,30 € " → cents; anything else (or more than 999 999 €) → null. */
    fun parseCents(input: String): Int? {
        val t = input.trim().removeSuffix("€").trim().replace(" ", "")
        if (!INPUT_RE.matches(t)) return null
        val parts = t.replace(',', '.').split('.')
        val euros = parts[0].toLongOrNull() ?: return null
        val cents = parts.getOrNull(1)?.padEnd(2, '0')?.toLongOrNull() ?: 0L
        return (euros * 100 + cents).takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    /** Text for an input field: 550 → "5,50" (de) / "5.50". */
    fun input(cents: Int, locale: Locale): String {
        val s = String.format(Locale.ROOT, "%d.%02d", cents / 100, kotlin.math.abs(cents % 100))
        return if (locale.language == "de") s.replace('.', ',') else s
    }

    /**
     * Shares of the category bar (mockup AppExpenses): categories with an
     * amount, largest first, in [order] for ties; weights sum to 1.
     */
    fun shares(byCategory: Map<String, Int>, order: List<String> = Expense.CATEGORIES): List<Pair<String, Float>> {
        val positive = byCategory.filterValues { it > 0 }
        val total = positive.values.sumOf { it.toLong() }
        if (total <= 0) return emptyList()
        return positive.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { order.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } })
            .map { it.key to (it.value.toFloat() / total) }
    }

    /** Stars a child may convert now: whole multiples of the rate, at most [available]. */
    fun convertSteps(available: Int, rate: StarRate?): List<Int> {
        if (rate == null || rate.stars <= 0 || rate.cents <= 0 || available < rate.stars) return emptyList()
        return (1..(available / rate.stars).coerceAtMost(20)).map { it * rate.stars }
    }

    /** What [stars] are worth at [rate] (whole steps only). */
    fun starsValue(stars: Int, rate: StarRate): Int = if (rate.stars <= 0) 0 else (stars / rate.stars) * rate.cents
}
