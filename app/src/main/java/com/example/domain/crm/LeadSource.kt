package com.example.domain.crm

import java.util.Locale

/**
 * How a wholesale lead entered the pipeline.
 *
 * The list is deliberately closed: acquisition reporting ("which channel produces contracts?") only
 * works if every capture path maps onto one of these values. Free-form context belongs in
 * [LeadSourceAttribution.detail], campaign or list name.
 */
enum class LeadSourceKind(val category: LeadSourceCategory, val isOutbound: Boolean, val isPaid: Boolean) {

    /** Drove for dollars / door knocked the property. */
    DRIVING_FOR_DOLLARS(LeadSourceCategory.FIELD, isOutbound = true, isPaid = false),

    /** Outbound cold call to a list. */
    COLD_CALL(LeadSourceCategory.OUTBOUND, isOutbound = true, isPaid = false),

    /** Direct mail piece returned by the seller. */
    DIRECT_MAIL(LeadSourceCategory.OUTBOUND, isOutbound = true, isPaid = true),

    /** Outbound SMS campaign. */
    SMS_CAMPAIGN(LeadSourceCategory.OUTBOUND, isOutbound = true, isPaid = true),

    /** Inbound pull: website, landing page, inbound call from a bandit sign / sign rider. */
    WEBSITE(LeadSourceCategory.INBOUND, isOutbound = false, isPaid = false),

    /** Paid search or social lead-gen form. */
    PAID_ADS(LeadSourceCategory.INBOUND, isOutbound = false, isPaid = true),

    /** Referral from a person (past client, agent, attorney, wholesaler). */
    REFERRAL(LeadSourceCategory.REFERRAL, isOutbound = false, isPaid = false),

    /** Purchased or partnered wholesale list (never an automated skip-trace call). */
    WHOLESALE_LIST(LeadSourceCategory.PARTNER, isOutbound = true, isPaid = true),

    /** MLS/IDX listing, typically an expired or withdrawn listing. */
    MLS(LeadSourceCategory.LISTING, isOutbound = true, isPaid = false),

    /** County assessor / recorder data: tax delinquency, probate, code violations, liens. */
    PUBLIC_RECORDS(LeadSourceCategory.PUBLIC_DATA, isOutbound = true, isPaid = false),

    /** Foreclosure / trustee auction feed. */
    AUCTION(LeadSourceCategory.PUBLIC_DATA, isOutbound = true, isPaid = false),

    /** Networking event, REIA meeting, meetup. */
    EVENT(LeadSourceCategory.EVENT, isOutbound = false, isPaid = false),

    /** Anything else; requires a [LeadSourceAttribution.detail] to be meaningful. */
    OTHER(LeadSourceCategory.OTHER, isOutbound = false, isPaid = false);

    /** A human captured this lead rather than an ingestion feed. */
    val isHumanCapture: Boolean
        get() = this == DRIVING_FOR_DOLLARS || this == COLD_CALL || this == REFERRAL ||
            this == EVENT || this == OTHER

    /** Distress-driven sources: the seller is reacting to a problem already. */
    val isDistressChannel: Boolean
        get() = this == PUBLIC_RECORDS || this == AUCTION || this == WHOLESALE_LIST ||
            this == DIRECT_MAIL || this == DRIVING_FOR_DOLLARS || this == MLS
}

/** Coarse bucket used for reporting and for outbound-compliance decisions. */
enum class LeadSourceCategory {
    FIELD,
    OUTBOUND,
    INBOUND,
    REFERRAL,
    PARTNER,
    LISTING,
    PUBLIC_DATA,
    EVENT,
    OTHER;

    /** Sources that produce outbound contact attempts and therefore need consent/DNC checks. */
    val isOutboundContact: Boolean
        get() = this == FIELD || this == OUTBOUND || this == PARTNER || this == LISTING || this == PUBLIC_DATA
}

/**
 * Where a lead came from, with the attribution needed to price a channel.
 *
 * [capturedAtEpochMillis] is mandatory: an attribution without a capture time cannot be attributed
 * to a campaign, and every downstream "cost per contract" number would be a guess.
 */
data class LeadSourceAttribution(
    val source: LeadSourceKind,
    /** Free-form context, e.g. "2450 Oak St door knock, absentee owner". */
    val detail: String = "",
    val campaign: String? = null,
    val listName: String? = null,
    val referrerName: String? = null,
    /** External identifier of the vendor record this lead came from (a reference, never a lookup). */
    val externalRecordId: String? = null,
    val capturedAtEpochMillis: Long,
    /** Acquisition cost attributed to this lead, when the channel is paid. */
    val costMicros: Long? = null
) {
    init {
        require(capturedAtEpochMillis > 0L) { "Lead source capturedAtEpochMillis must be positive" }
        require(detail.length <= MAX_DETAIL_LENGTH) { "Lead source detail must be at most $MAX_DETAIL_LENGTH characters" }
        require(costMicros == null || costMicros >= 0L) { "Lead source costMicros must not be negative" }
        // An attribution that says nothing is not an attribution: at least one context field must be
        // filled in. Channel-specific expectations (a referrer for REFERRAL, a list for DIRECT_MAIL)
        // are reported by `LeadValidator` as warnings because intake must never be blocked by them.
        require(detail.isNotBlank() || !campaign.isNullOrBlank() || !listName.isNullOrBlank() ||
            !referrerName.isNullOrBlank() || !externalRecordId.isNullOrBlank()
        ) { "Lead source attribution requires at least one of detail, campaign, listName, referrerName or externalRecordId" }
    }

    val isPaid: Boolean get() = source.isPaid
    val isOutbound: Boolean get() = source.isOutbound

    /** True when the source carries distress context the seller did not volunteer. */
    val isDistressDriven: Boolean get() = source.isDistressChannel

    /** Cost in whole dollars, or null when no cost was attributed. */
    val costUsd: Double? get() = costMicros?.let { it / 1_000_000.0 }

    /**
     * Deterministic de-duplication key for the capture event.
     *
     * Two leads captured from the same vendor record, the same list and the same campaign within the
     * same capture day are the same lead; the intake path can therefore suppress duplicates without
     * any fuzzy matching. Whitespace and case never leak into the key.
     */
    fun deduplicationKey(): String = listOf(
        source.name,
        normalizeKeyPart(externalRecordId),
        normalizeKeyPart(listName),
        normalizeKeyPart(campaign),
        normalizeKeyPart(detail),
        captureDay.toString()
    ).joinToString("|")

    /** UTC day index of the capture instant: keeps the key stable across the capture day. */
    val captureDay: Long get() = Math.floorDiv(capturedAtEpochMillis, MILLIS_PER_DAY)

    fun describe(): String = buildString {
        append(source.name)
        if (detail.isNotBlank()) append(" (").append(detail).append(')')
        listOfNotNull(
            listName?.let { "list=$it" },
            campaign?.let { "campaign=$it" },
            referrerName?.let { "referrer=$it" },
            externalRecordId?.let { "external=$it" }
        ).forEach { append(' ').append(it) }
    }

    private fun normalizeKeyPart(raw: String?): String =
        raw?.trim()?.lowercase(Locale.US)?.replace(Regex("[\\s_]+"), "-") ?: ""

    companion object {
        const val MAX_DETAIL_LENGTH = 240
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
