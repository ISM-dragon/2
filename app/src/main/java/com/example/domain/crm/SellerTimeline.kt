package com.example.domain.crm

/**
 * How soon the seller wants to be done.
 *
 * Timeline is the single most predictive fact in a wholesale pipeline — more than condition or price
 * — because it decides whether the deal can close inside the assignment window at all. It is modeled
 * as a closed ladder (not a free-form date) so that urgency is comparable across leads and so that a
 * vague "someday" answer is a first-class answer ([NO_TIMELINE]) rather than missing data.
 */
enum class SellerTimeline(val horizonDays: Int?, val urgencyPoints: Int) {

    /** Nobody has asked yet. */
    UNKNOWN(horizonDays = null, urgencyPoints = 0),

    /** Days, not weeks: motivated by a deadline (auction date, closing on another house). */
    IMMEDIATE(horizonDays = 7, urgencyPoints = 100),

    WITHIN_30_DAYS(horizonDays = 30, urgencyPoints = 85),

    WITHIN_90_DAYS(horizonDays = 90, urgencyPoints = 60),

    WITHIN_6_MONTHS(horizonDays = 180, urgencyPoints = 35),

    WITHIN_12_MONTHS(horizonDays = 365, urgencyPoints = 15),

    /** Interested, but the horizon is beyond a year. */
    OVER_1_YEAR(horizonDays = null, urgencyPoints = 5),

    /** Explicitly open-ended: "when the right offer comes". */
    NO_TIMELINE(horizonDays = null, urgencyPoints = 0);

    /** The seller is under a deadline the pipeline can work with. */
    val isUrgent: Boolean get() = urgencyPoints >= 60

    /** The seller is unlikely to transact inside a standard assignment window. */
    val isLongHorizon: Boolean get() = this == OVER_1_YEAR || this == WITHIN_12_MONTHS

    val isKnown: Boolean get() = this != UNKNOWN

    companion object {
        /**
         * Deterministic mapping from a stated number of days to a bucket.
         * Negative values are treated as "now" ([IMMEDIATE]); null means nothing was stated.
         */
        fun fromDays(daysToClose: Int?): SellerTimeline = when {
            daysToClose == null -> UNKNOWN
            daysToClose <= IMMEDIATE.horizonDays!! -> IMMEDIATE
            daysToClose <= WITHIN_30_DAYS.horizonDays!! -> WITHIN_30_DAYS
            daysToClose <= WITHIN_90_DAYS.horizonDays!! -> WITHIN_90_DAYS
            daysToClose <= WITHIN_6_MONTHS.horizonDays!! -> WITHIN_6_MONTHS
            daysToClose <= WITHIN_12_MONTHS.horizonDays!! -> WITHIN_12_MONTHS
            else -> OVER_1_YEAR
        }

        /** Ordered from most to least urgent, for deterministic "most urgent wins" merges. */
        val MOST_URGENT_FIRST: List<SellerTimeline> = listOf(
            IMMEDIATE, WITHIN_30_DAYS, WITHIN_90_DAYS, WITHIN_6_MONTHS, WITHIN_12_MONTHS,
            OVER_1_YEAR, NO_TIMELINE, UNKNOWN
        )
    }
}

/** Who said it, and how much weight the claim carries. */
enum class TimelineSource(val isSellerStated: Boolean, val isDocumented: Boolean) {
    /** The seller said it during a conversation. */
    SELLER_STATED(isSellerStated = true, isDocumented = false),
    /** Backed by a document or a hard date (auction date, notice, relocation letter). */
    DOCUMENTED(isSellerStated = false, isDocumented = true),
    /** Inferred from public records or the listing history. */
    INFERRED(isSellerStated = false, isDocumented = false),
    /** Nothing recorded. */
    UNKNOWN(isSellerStated = false, isDocumented = false)
}

/**
 * A timeline claim with its provenance.
 *
 * [statedDaysToClose] is kept *next to* the bucket so a later review can re-bucket (policies change);
 * the bucket itself is derived by [SellerTimeline.fromDays] and validated against it, which makes an
 * inconsistent claim impossible to persist.
 */
data class SellerTimelineFact(
    val timeline: SellerTimeline = SellerTimeline.UNKNOWN,
    val statedDaysToClose: Int? = null,
    val source: TimelineSource = TimelineSource.UNKNOWN,
    val capturedAtEpochMillis: Long,
    /** Who captured it (operator id) — required unless nothing was captured yet. */
    val capturedBy: String = "system",
    val detail: String? = null
) {
    init {
        require(capturedAtEpochMillis > 0L) { "Timeline capturedAtEpochMillis must be positive" }
        require(capturedBy.isNotBlank()) { "Timeline must record who captured it" }
        require(statedDaysToClose == null || statedDaysToClose >= 0) { "statedDaysToClose must not be negative" }
        require(detail == null || detail.length <= MAX_DETAIL_LENGTH) { "Timeline detail must be at most $MAX_DETAIL_LENGTH characters" }
        if (statedDaysToClose != null) {
            require(SellerTimeline.fromDays(statedDaysToClose) == timeline) {
                "Timeline ${timeline.name} does not match statedDaysToClose=$statedDaysToClose"
            }
        }
        require(source != TimelineSource.UNKNOWN || timeline == SellerTimeline.UNKNOWN) {
            "A known timeline requires a source"
        }
        require(source != TimelineSource.SELLER_STATED || timeline != SellerTimeline.UNKNOWN) {
            "A seller-stated timeline cannot be UNKNOWN"
        }
        require(source != TimelineSource.DOCUMENTED || timeline != SellerTimeline.UNKNOWN) {
            "A documented timeline cannot be UNKNOWN"
        }
    }

    val isKnown: Boolean get() = timeline.isKnown

    /** Documented or seller-stated: strong enough to plan a closing around. */
    val isReliable: Boolean get() = source.isSellerStated || source.isDocumented

    /** Deterministic event factory: the standard way a seller-stated timeline is captured. */
    companion object {
        const val MAX_DETAIL_LENGTH = 300

        fun sellerStated(
            daysToClose: Int?,
            capturedAtEpochMillis: Long,
            capturedBy: String,
            detail: String? = null
        ): SellerTimelineFact = SellerTimelineFact(
            timeline = SellerTimeline.fromDays(daysToClose),
            statedDaysToClose = daysToClose,
            source = if (daysToClose == null) TimelineSource.UNKNOWN else TimelineSource.SELLER_STATED,
            capturedAtEpochMillis = capturedAtEpochMillis,
            capturedBy = capturedBy,
            detail = detail
        )

        fun unknown(capturedAtEpochMillis: Long): SellerTimelineFact = SellerTimelineFact(
            timeline = SellerTimeline.UNKNOWN,
            statedDaysToClose = null,
            source = TimelineSource.UNKNOWN,
            capturedAtEpochMillis = capturedAtEpochMillis
        )
    }
}
