package com.example.domain.crm

/** Why a property is attached to a lead. */
enum class PropertyLinkRole {
    /** The property the seller wants to sell — the subject of the deal. */
    SUBJECT,
    /** Another property the same seller owns (portfolio play / package deal). */
    PORTFOLIO,
    /** A comparable used to justify an offer; never the subject. */
    COMPARABLE,
    /** A property the seller wants to buy after closing (1031-style swap). */
    REPLACEMENT,
    /** Disposition side: the buyer/end-buyer property this deal would be assigned to. */
    DISPOSITION;

    val isAcquisitionSide: Boolean
        get() = this == SUBJECT || this == PORTFOLIO || this == REPLACEMENT
}

/**
 * Soft link from a lead to a property row.
 *
 * `propertyId` is deliberately a *soft* reference (no foreign key, no Room relation): exactly like
 * `OfferEntity.propertyId`, the CRM must keep working when a listing row is merged or pruned by the
 * property identity deduplicator. A lead therefore stores the identifier it was linked to plus the
 * snapshot of what was known at link time, and never assumes the row still exists.
 */
data class LeadPropertyLink(
    val propertyId: String,
    val role: PropertyLinkRole = PropertyLinkRole.SUBJECT,
    /** Address snapshot at link time; survives deletion of the property row. */
    val address: PostalAddress? = null,
    /** Assessor parcel number, when known — the durable identity of a parcel. */
    val apn: String? = null,
    /** Asking price observed when the link was made (this is not the offer price). */
    val askingPriceUsd: Double? = null,
    /** After-repair value and repair estimate snapshotted for the qualification math. */
    val afterRepairValueUsd: Double? = null,
    val estimatedRepairCostUsd: Double? = null,
    val estimatedAsIsValueUsd: Double? = null,
    val estimatedRentUsdMonthly: Double? = null,
    val linkedAtEpochMillis: Long,
    val linkedBy: String,
    val notes: String? = null
) {
    init {
        require(propertyId.isNotBlank()) { "Property link must reference a property id" }
        require(linkedAtEpochMillis > 0L) { "Property link linkedAtEpochMillis must be positive" }
        require(linkedBy.isNotBlank()) { "Property link must record who linked it" }
        listOf(
            "askingPriceUsd" to askingPriceUsd,
            "afterRepairValueUsd" to afterRepairValueUsd,
            "estimatedRepairCostUsd" to estimatedRepairCostUsd,
            "estimatedAsIsValueUsd" to estimatedAsIsValueUsd,
            "estimatedRentUsdMonthly" to estimatedRentUsdMonthly
        ).forEach { (name, value) ->
            require(value == null || (value.isFinite() && value >= 0.0)) { "Property link $name must be finite and non-negative" }
        }
        require(apn == null || apn.isNotBlank()) { "Property link apn must not be blank when provided" }
        require(notes == null || notes.length <= MAX_NOTES_LENGTH) { "Property link notes must be at most $MAX_NOTES_LENGTH characters" }
    }

    /** Gross spread implied by the snapshot, or null when the snapshot cannot support the math. */
    fun impliedSpreadUsd(): Double? {
        val arv = afterRepairValueUsd ?: return null
        val asking = askingPriceUsd ?: return null
        val repairs = estimatedRepairCostUsd ?: 0.0
        return arv - repairs - asking
    }

    fun describe(): String = buildString {
        append(role.name).append(' ').append(propertyId)
        address?.let { append(" @ ").append(it.singleLine()) }
        if (impliedSpreadUsd() != null) append(" spread=\$").append(String.format(java.util.Locale.US, "%.0f", impliedSpreadUsd()))
    }

    companion object {
        const val MAX_NOTES_LENGTH = 500
    }
}
