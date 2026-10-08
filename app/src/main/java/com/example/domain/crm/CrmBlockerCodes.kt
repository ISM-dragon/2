package com.example.domain.crm

/**
 * Stable codes for the deterministic reasons a lead cannot advance.
 *
 * Codes are part of the contract between the domain layer and everything that explains a refusal to
 * an operator (UI, automation logs, tests). Messages may be reworded freely; codes may not.
 */
object CrmBlockerCodes {

    // ── Qualification: hard disqualifiers (the deal/seller is not workable) ─────────────────────
    const val SELLER_DECLINED = "seller-declined"
    const val DO_NOT_CONTACT = "do-not-contact"
    const val OUTSIDE_SERVICE_AREA = "outside-service-area"
    const val SPREAD_BELOW_FLOOR = "spread-below-floor"

    // ── Qualification: evidence blockers (workable, but unproven) ───────────────────────────────
    const val MISSING_PROPERTY_LINK = "missing-property-link"
    const val MISSING_PROPERTY_ADDRESS = "missing-property-address"
    const val MISSING_PRICE_EVIDENCE = "missing-price-evidence"
    const val MISSING_VALUE_EVIDENCE = "missing-value-evidence"
    const val MISSING_REPAIR_ESTIMATE = "missing-repair-estimate"
    const val MISSING_MOTIVATION_EVIDENCE = "missing-motivation-evidence"
    const val MISSING_DOCUMENTED_MOTIVATION = "missing-documented-motivation"
    const val MISSING_CONTACT_CHANNEL = "missing-contact-channel"

    // ── Qualification overrides ────────────────────────────────────────────────────────────────
    const val MANUAL_OVERRIDE_QUALIFICATION = "manual-override-qualification"

    // ── Pipeline transition gate ───────────────────────────────────────────────────────────────
    const val NO_USABLE_CONTACT_CHANNEL = "no-usable-contact-channel"
    const val CONTACT_RESTRICTED = "contact-restricted"
    const val NO_SELLER_RESPONSE = "no-seller-response"
    const val NO_SELLER_CONVERSATION = "no-seller-conversation"
    const val QUALIFICATION_NOT_ASSESSED = "qualification-not-assessed"
    const val QUALIFICATION_STALE = "qualification-stale"
    const val QUALIFICATION_DISQUALIFIED = "qualification-disqualified"
    const val QUALIFICATION_INSUFFICIENT = "qualification-insufficient"
    const val MOTIVATION_NOT_EVIDENCED = "motivation-not-evidenced"
    const val MISSING_OFFER_REFERENCE = "missing-offer-reference"
    const val MISSING_CONTRACT = "missing-contract"
    const val MISSING_DUE_DILIGENCE_WINDOW = "missing-due-diligence-window"
}

/** Stable codes for pipeline/qualification audit reason classification. Not user visible. */
object LeadAuditActors {
    /** Entries written by an automated path rather than a person. */
    const val SYSTEM = "system"
}
