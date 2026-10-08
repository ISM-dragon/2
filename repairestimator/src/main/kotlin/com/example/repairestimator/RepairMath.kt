package com.example.repairestimator

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Exact decimal arithmetic and text formatting for the estimator.
 *
 * Doubles are converted with [BigDecimal.valueOf], which uses the shortest decimal that round-trips, so
 * 0.1 stays exactly 0.1 and binary artefacts never reach a total. Each line amount is rounded to cents
 * with HALF_UP once. Every total is a sum of those cent amounts, so totals reconcile exactly.
 */
internal object RepairMath {

    /** Exact decimal value of a finite double. Non-finite input is a programming error at this point. */
    fun dec(value: Double): BigDecimal {
        require(value.isFinite()) { "non-finite value reached arithmetic: $value" }
        return BigDecimal.valueOf(value)
    }

    /** Rounds to whole cents, HALF_UP. */
    fun cents(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)

    /** Cent-rounded value as a double for the public result types. */
    fun dollars(value: BigDecimal): Double = cents(value).toDouble()

    /**
     * A percentage expressed as a fraction: 10.0 becomes 0.10, exactly. [BigDecimal.movePointLeft] is used
     * because a division would round to the dividend's scale and could silently lose digits.
     */
    fun fraction(percent: Double): BigDecimal = dec(percent).movePointLeft(2)

    /** Sum of the non-null amounts of [lines], as exact cents. Unpriced lines contribute nothing. */
    fun sumAmounts(lines: List<RepairEstimateLine>): BigDecimal =
        lines.fold(BigDecimal.ZERO) { total, line -> line.amount?.let { total + dec(it) } ?: total }

    /** Money with thousands separators and exactly two decimals, for example "$11,700.00". */
    fun money(value: BigDecimal): String = dollarText(value, minScale = 2, maxScale = 2)

    /** Unit rates with two to six decimals, for example "$650.00" or "$0.0525". */
    fun rate(value: BigDecimal): String = dollarText(value, minScale = 2, maxScale = 6)

    /** A multiplier with at least two decimals, for example "0.90" or "1.25". */
    fun factor(value: BigDecimal): String = trimmed(value.setScale(4, RoundingMode.HALF_UP), minScale = 2)

    /** A percentage with two decimals and a percent sign, for example "10.00%". */
    fun percent(value: Double): String = dec(value).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%"

    /** A quantity without trailing zeros, for example "18", "1800.5" or "0.0125". */
    fun quantity(value: BigDecimal): String = trimmed(value.setScale(6, RoundingMode.HALF_UP), minScale = 0)

    private fun trimmed(value: BigDecimal, minScale: Int): String {
        val stripped = value.stripTrailingZeros()
        val padded = if (stripped.scale() < minScale) stripped.setScale(minScale, RoundingMode.HALF_UP) else stripped
        return padded.toPlainString()
    }

    private fun dollarText(value: BigDecimal, minScale: Int, maxScale: Int): String {
        val scaled = value.setScale(maxScale, RoundingMode.HALF_UP).stripTrailingZeros()
        val normal = if (scaled.scale() < minScale) scaled.setScale(minScale, RoundingMode.HALF_UP) else scaled
        val plain = normal.abs().toPlainString()
        val whole = plain.substringBefore('.')
        val fraction = if (plain.contains('.')) plain.substringAfter('.') else ""
        val sign = if (normal.signum() < 0) "-" else ""
        val grouped = StringBuilder()
        whole.forEachIndexed { index, digit ->
            if (index > 0 && (whole.length - index) % 3 == 0) grouped.append(',')
            grouped.append(digit)
        }
        return sign + "$" + grouped + (if (fraction.isEmpty()) "" else ".$fraction")
    }
}
