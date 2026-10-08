package com.example.domain.crm

/**
 * The one time convention for the CRM layer.
 *
 * Every timestamp in `com.example.domain.crm` is epoch milliseconds (UTC), every duration is
 * expressed in milliseconds, and **no domain code reads a clock**: callers pass `atEpochMillis`
 * explicitly, which is what makes the state machines, the priority ladder and the follow-up plan
 * reproducible in tests (and replayable from the audit trail).
 *
 * Calendar arithmetic (business days, "next Tuesday at 9am", timezone-aware reminders) is
 * deliberately absent: it depends on the device's timezone and would make otherwise deterministic
 * behaviour non-reproducible. Formatting an instant for display is a UI concern; the domain layer
 * only ever compares and adds durations.
 */
object CrmTime {

    const val MILLIS_PER_SECOND = 1_000L
    const val MILLIS_PER_MINUTE = 60L * MILLIS_PER_SECOND
    const val MILLIS_PER_HOUR = 60L * MILLIS_PER_MINUTE
    const val MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR

    /** Whole days between two instants, floored, so a negative result means "to is in the past". */
    fun daysBetween(fromEpochMillis: Long, toEpochMillis: Long): Long =
        Math.floorDiv(toEpochMillis - fromEpochMillis, MILLIS_PER_DAY)

    /** Whole hours between two instants, floored. */
    fun hoursBetween(fromEpochMillis: Long, toEpochMillis: Long): Long =
        Math.floorDiv(toEpochMillis - fromEpochMillis, MILLIS_PER_HOUR)

    /** Adds whole days. Negative values move backwards; the result is always a valid instant. */
    fun plusDays(fromEpochMillis: Long, days: Int): Long {
        require(fromEpochMillis > 0L) { "fromEpochMillis must be positive" }
        return Math.addExact(fromEpochMillis, days.toLong() * MILLIS_PER_DAY)
    }

    fun plusHours(fromEpochMillis: Long, hours: Int): Long {
        require(fromEpochMillis > 0L) { "fromEpochMillis must be positive" }
        return Math.addExact(fromEpochMillis, hours.toLong() * MILLIS_PER_HOUR)
    }

    /** UTC day index of an instant. Stable across timezones and used for dedup keys. */
    fun utcDayIndexOf(epochMillis: Long): Long = Math.floorDiv(epochMillis, MILLIS_PER_DAY)

    /**
     * True when both instants fall on the same UTC day.
     *
     * Used only where a *stable* definition matters (import dedup, "already attempted today"
     * guards). Anything a user would call "today" must be computed in the presentation layer with
     * the device timezone.
     */
    fun isSameUtcDay(firstEpochMillis: Long, secondEpochMillis: Long): Boolean =
        utcDayIndexOf(firstEpochMillis) == utcDayIndexOf(secondEpochMillis)
}
