package com.example.domain.transaction

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * Calendar date without a time zone. This small value type keeps the domain safe on Android API 24
 * and 25 without requiring Java time desugaring. It is also deterministic across machine time zones.
 */
data class TransactionDate internal constructor(val epochDay: Long) : Comparable<TransactionDate> {
    fun plusDays(days: Long): TransactionDate = TransactionDate(Math.addExact(epochDay, days))

    fun isBefore(other: TransactionDate): Boolean = epochDay < other.epochDay

    fun isAfter(other: TransactionDate): Boolean = epochDay > other.epochDay

    override fun compareTo(other: TransactionDate): Int = epochDay.compareTo(other.epochDay)

    override fun toString(): String {
        val calendar = utcCalendar().apply { timeInMillis = epochDay * MILLIS_PER_DAY }
        return "%04d-%02d-%02d".format(
            Locale.US,
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L

        fun of(year: Int, month: Int, day: Int): TransactionDate {
            require(year >= 1) { "year must be positive" }
            require(month in 1..12) { "month must be between 1 and 12" }
            require(day in 1..31) { "day must be between 1 and 31" }
            val calendar = utcCalendar().apply {
                isLenient = false
                set(year, month - 1, day, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // Reading timeInMillis forces GregorianCalendar to reject dates such as February 30.
            return TransactionDate(Math.floorDiv(calendar.timeInMillis, MILLIS_PER_DAY))
        }

        fun fromEpochDay(epochDay: Long): TransactionDate = TransactionDate(epochDay)

        private fun utcCalendar(): GregorianCalendar =
            GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US)
    }
}
