package com.example.domain.crm

import java.util.Locale

/** How a seller (or their representative) relates to the property. */
enum class SellerRole {
    /** The owner of record, or the person entitled to sell. */
    OWNER,
    CO_OWNER,
    SPOUSE,
    /** Heir of an estate (probate sales). */
    HEIR,
    TRUSTEE,
    /** Attorney / probate representative who signs off but does not own. */
    ATTORNEY,
    /** Listing agent or broker involved in a listed property. */
    AGENT,
    PROPERTY_MANAGER,
    /** Anyone else in the decision path. */
    OTHER;

    /** Roles that can bind the seller to a contract without further approval. */
    val isDecisionMakerRole: Boolean
        get() = this == OWNER || this == CO_OWNER || this == SPOUSE || this == HEIR || this == TRUSTEE
}

/** Legal shape of the owner of record. Drives vesting paperwork, not scoring. */
enum class SellerEntityType {
    INDIVIDUAL,
    LLC,
    CORPORATION,
    PARTNERSHIP,
    TRUST,
    ESTATE,
    NON_PROFIT,
    UNKNOWN;

    /** Entity-owned property: the signer must be verified against an operating agreement. */
    val requiresSigningAuthorityCheck: Boolean
        get() = this == LLC || this == CORPORATION || this == PARTNERSHIP || this == TRUST
}

/** Occupancy as reported by the seller, the field visit, or public data. */
enum class OccupancyStatus {
    OWNER_OCCUPIED,
    ABSENTEE,
    TENANT_OCCUPIED,
    VACANT,
    UNKNOWN;

    /** Occupied by someone other than the owner: access and possession need explicit terms. */
    val isThirdPartyOccupied: Boolean
        get() = this == TENANT_OCCUPIED

    val isVacant: Boolean get() = this == VACANT
}

/** Channel a contact point reaches the seller through. */
enum class ContactChannel(val isOutboundCapable: Boolean) {
    PHONE(isOutboundCapable = true),
    SMS(isOutboundCapable = true),
    EMAIL(isOutboundCapable = true),
    MAILING_ADDRESS(isOutboundCapable = false),
    /** In-person only (door knock) — never used for automated outreach. */
    IN_PERSON(isOutboundCapable = false),
    /** No usable channel: the seller is only reachable through a third party. */
    NONE(isOutboundCapable = false);

    /** Maps a communication channel onto the contact path it consumes. */
    companion object {
        fun fromCommunicationChannel(channel: CommunicationChannel): ContactChannel = when (channel) {
            CommunicationChannel.CALL -> PHONE
            CommunicationChannel.SMS -> SMS
            CommunicationChannel.EMAIL -> EMAIL
            CommunicationChannel.LETTER -> MAILING_ADDRESS
            CommunicationChannel.IN_PERSON, CommunicationChannel.DRIVE_BY -> IN_PERSON
            CommunicationChannel.OTHER -> NONE
        }
    }
}

/**
 * A single way of reaching the seller.
 *
 * [value] is stored **normalized** (E.164 for phones, lower-cased for email, a single-line address
 * for mail) so that the same seller reached through two lead vendors de-duplicates by value. The
 * raw, human-entered form is never kept in the domain model; it belongs to the vendor payload, which
 * is provenance, not identity.
 */
data class SellerContactPoint(
    val channel: ContactChannel,
    /** Normalized value, e.g. "+15125550123", "seller@example.com", "123 Main St, Austin, TX 78701". */
    val value: String,
    val label: String = "",
    val isPrimary: Boolean = false,
    /** Set once the number/address was confirmed reachable by a logged conversation or delivery. */
    val verifiedAtEpochMillis: Long? = null,
    /** Per-channel opt-out (a do-not-call request must not silence a different legitimate channel). */
    val doNotContact: Boolean = false,
    val addedAtEpochMillis: Long
) {
    init {
        require(value.isNotBlank()) { "Contact point value must not be blank" }
        require(addedAtEpochMillis > 0L) { "Contact point addedAtEpochMillis must be positive" }
        require(verifiedAtEpochMillis == null || verifiedAtEpochMillis >= addedAtEpochMillis) {
            "Contact point cannot be verified before it was added"
        }
        require(channel != ContactChannel.NONE) { "ContactChannel.NONE is not a contact point; leave the list empty instead" }
        if (channel == ContactChannel.EMAIL) {
            require(ContactNormalizer.looksLikeEmail(value)) { "Email contact point must look like an email address: $value" }
        }
        if (channel == ContactChannel.PHONE || channel == ContactChannel.SMS) {
            require(ContactNormalizer.looksLikePhone(value)) { "Phone contact point must be normalized E.164: $value" }
        }
    }

    /** Usable for outbound contact: an outbound-capable channel that has not opted out. */
    val isUsable: Boolean get() = channel.isOutboundCapable && !doNotContact

    val isVerified: Boolean get() = verifiedAtEpochMillis != null

    fun describe(): String = "${channel.name.lowercase(Locale.US)}:$value" +
        if (label.isNotBlank()) " ($label)" else ""
}

/**
 * Outbound restrictions recorded for a seller.
 *
 * These flags are compliance state, not preferences: once [doNotContactRequested] is set the
 * transition gate refuses to move a lead into any contacted status, and a [litigationHold] or
 * [bankruptcyStay] freezes outreach entirely. They are never cleared automatically — clearing them
 * is an explicit, audited operator action.
 */
data class SellerContactRestrictions(
    /** Blanket "stop contacting me" request. */
    val doNotContactRequested: Boolean = false,
    val doNotCall: Boolean = false,
    val doNotText: Boolean = false,
    val doNotEmail: Boolean = false,
    val doNotMail: Boolean = false,
    /** Active litigation with the seller (title dispute, foreclosure defence, ...). */
    val litigationHold: Boolean = false,
    /** Automatic stay in bankruptcy: no collection-style contact. */
    val bankruptcyStay: Boolean = false,
    /** Seller is deceased: only the estate representative may be approached. */
    val deceased: Boolean = false,
    val note: String? = null
) {
    /** True when no channel may be used at all. */
    val blocksAllOutboundContact: Boolean
        get() = doNotContactRequested || litigationHold || bankruptcyStay || deceased

    /** True when at least one channel is still permitted (assuming a usable contact point exists). */
    val allowsSomeChannel: Boolean get() = !blocksAllOutboundContact

    fun permits(channel: ContactChannel): Boolean = when {
        blocksAllOutboundContact -> false
        channel == ContactChannel.PHONE -> !doNotCall
        channel == ContactChannel.SMS -> !doNotText
        channel == ContactChannel.EMAIL -> !doNotEmail
        channel == ContactChannel.MAILING_ADDRESS -> !doNotMail
        else -> false
    }

    fun describe(): String = listOfNotNull(
        "doNotContactRequested".takeIf { doNotContactRequested },
        "doNotCall".takeIf { doNotCall },
        "doNotText".takeIf { doNotText },
        "doNotEmail".takeIf { doNotEmail },
        "doNotMail".takeIf { doNotMail },
        "litigationHold".takeIf { litigationHold },
        "bankruptcyStay".takeIf { bankruptcyStay },
        "deceased".takeIf { deceased }
    ).joinToString(",").ifEmpty { "none" }

    companion object {
        val NONE = SellerContactRestrictions()
    }
}

/** US-style postal address of a seller's mailing address or a linked property. */
data class PostalAddress(
    val line1: String,
    val line2: String? = null,
    val city: String,
    val state: String,
    val zipCode: String,
    val country: String = "US"
) {
    init {
        require(line1.isNotBlank()) { "Address line1 must not be blank" }
        require(city.isNotBlank()) { "Address city must not be blank" }
        require(state.trim().length == 2) { "Address state must be a two-letter code" }
        require(zipCode.trim().length in 5..10) { "Address zip code must be 5 to 10 characters" }
        require(country.trim().length == 2) { "Address country must be a two-letter code" }
    }

    val stateCode: String get() = state.trim().uppercase(Locale.US)

    /** Single-line, normalized form used when a mailing address is stored as a contact point. */
    fun singleLine(): String = listOfNotNull(
        line1.trim(),
        line2?.trim()?.takeIf { it.isNotEmpty() },
        "${city.trim()}, $stateCode ${zipCode.trim()}"
    ).joinToString(", ")

    companion object {
        /** Tolerant factory for vendor data: returns null instead of throwing on incomplete input. */
        fun orNull(
            line1: String?,
            line2: String?,
            city: String?,
            state: String?,
            zipCode: String?,
            country: String? = "US"
        ): PostalAddress? {
            val safeLine1 = line1?.trim().orEmpty()
            val safeCity = city?.trim().orEmpty()
            val safeState = state?.trim().orEmpty()
            val safeZip = zipCode?.trim().orEmpty()
            val safeCountry = country?.trim().takeUnless { it.isNullOrEmpty() } ?: "US"
            return runCatching {
                PostalAddress(safeLine1, line2?.trim()?.takeIf { it.isNotEmpty() }, safeCity, safeState, safeZip, safeCountry)
            }.getOrNull()
        }
    }
}

/**
 * Reference to a skip-trace/vendor lookup that produced (or corroborated) this seller's contacts.
 *
 * The CRM layer only stores *references*: who the provider was, which provider-side record id came
 * back, when it was fetched and how confident the match was. There is no skip-trace call, no
 * scraping and no address discovery on this branch — see [SkipTracePort]. This keeps the model
 * legally and technically integration-ready without shipping data-acquisition behaviour.
 */
data class SkipTraceReference(
    val provider: String,
    val providerRecordId: String,
    val fetchedAtEpochMillis: Long,
    /** Match confidence reported by the provider, 0.0..1.0. */
    val confidence: Double,
    val matchCount: Int = 1,
    /** Identifier of the stored raw payload (proof of what the provider returned). */
    val rawPayloadRef: String? = null
) {
    init {
        require(provider.isNotBlank()) { "Skip-trace provider must not be blank" }
        require(providerRecordId.isNotBlank()) { "Skip-trace provider record id must not be blank" }
        require(fetchedAtEpochMillis > 0L) { "Skip-trace fetchedAtEpochMillis must be positive" }
        require(confidence.isFinite() && confidence in 0.0..1.0) { "Skip-trace confidence must be finite and within 0.0..1.0" }
        require(matchCount >= 0) { "Skip-trace matchCount must not be negative" }
    }

    /** A single high-confidence match is the only result type safe to auto-fill without review. */
    val isAutoApplicable: Boolean get() = confidence >= 0.9 && matchCount <= 1
}

/**
 * Port for skip-trace providers.
 *
 * It exists so the rest of the layer can be written against an interface and tested with
 * [DisabledSkipTracePort]; implementing it against a real provider is a separate, deliberate
 * decision (credentials, contract, consent and jurisdiction all have to be settled first).
 * Nothing in this package implements it, and no code path in this branch performs a lookup.
 */
interface SkipTracePort {

    val providerName: String

    /** `true` only when a real provider is configured and permitted to be called. */
    val isEnabled: Boolean

    suspend fun lookup(request: SkipTraceRequest): SkipTraceResult
}

/** What a lookup would ask for. Only seller-supplied identifiers, never inferred personal data. */
data class SkipTraceRequest(
    val sellerId: String,
    val fullName: String?,
    val mailingAddress: PostalAddress?,
    val propertyAddress: PostalAddress?
) {
    init {
        require(sellerId.isNotBlank()) { "Skip-trace request requires a sellerId" }
        require(!fullName.isNullOrBlank() || mailingAddress != null || propertyAddress != null) {
            "Skip-trace request needs at least one searchable field"
        }
    }
}

sealed interface SkipTraceResult {

    /** Provider returned candidate contacts. */
    data class Matches(
        val provider: String,
        val providerRecordId: String,
        val fetchedAtEpochMillis: Long,
        val candidates: List<SkipTraceCandidate>
    ) : SkipTraceResult

    /** No provider is wired on this build. This is the only result this branch can produce. */
    data class NotConfigured(val requestedProvider: String?) : SkipTraceResult

    /** A provider exists but refused the lookup (opt-out, missing consent, quota, jurisdiction). */
    data class Refused(val provider: String, val reason: String) : SkipTraceResult

    /** Transport/provider failure. Retrying is the caller's decision, never an automatic loop here. */
    data class Failed(val provider: String, val message: String) : SkipTraceResult
}

/** One candidate returned by a provider. Never written to a seller without operator review. */
data class SkipTraceCandidate(
    val fullName: String?,
    val phoneValues: List<String> = emptyList(),
    val emailValues: List<String> = emptyList(),
    val mailingAddress: PostalAddress? = null,
    val confidence: Double,
    val sourceRef: String? = null
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0) { "Candidate confidence must be finite and within 0.0..1.0" }
    }
}

/**
 * The implementation every build gets *by default*: it is disabled and returns
 * [SkipTraceResult.NotConfigured] without touching the network, a database or even the request
 * payload. Tests assert this behaviour, and the isolation guard asserts that the CRM package contains
 * no HTTP client at all.
 */
object DisabledSkipTracePort : SkipTracePort {
    override val providerName: String = "disabled"
    override val isEnabled: Boolean = false
    override suspend fun lookup(request: SkipTraceRequest): SkipTraceResult =
        SkipTraceResult.NotConfigured(requestedProvider = null)
}

/**
 * A seller/owner in the decision path of a deal.
 *
 * The seller is the *person*, not the deal: the same seller can own several leads, and a lead can
 * have several sellers (spouses, heirs, an LLC with a manager, an attorney who signs). Contact
 * history therefore lives on the lead ([Lead.communications]) while contact *reachability* lives
 * here, so a second lead for the same seller inherits what was already learned.
 */
data class Seller(
    val id: String,
    val fullName: String,
    val role: SellerRole = SellerRole.OWNER,
    val entityType: SellerEntityType = SellerEntityType.INDIVIDUAL,
    val contactPoints: List<SellerContactPoint> = emptyList(),
    /** Mailing address of the owner (may differ from the property address). */
    val mailingAddress: PostalAddress? = null,
    val occupancy: OccupancyStatus = OccupancyStatus.UNKNOWN,
    /** The person who actually says yes. Exactly one seller per lead should be flagged. */
    val isPrimaryContact: Boolean = false,
    val restrictions: SellerContactRestrictions = SellerContactRestrictions.NONE,
    val skipTrace: SkipTraceReference? = null,
    /** Free-form operator notes that are properties of the person, e.g. "prefers texts". */
    val notes: String? = null,
    val firstSeenAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
) {
    init {
        require(id.isNotBlank()) { "Seller id must not be blank" }
        require(fullName.isNotBlank()) { "Seller full name must not be blank" }
        require(firstSeenAtEpochMillis > 0L) { "Seller firstSeenAtEpochMillis must be positive" }
        require(updatedAtEpochMillis >= firstSeenAtEpochMillis) { "Seller updatedAtEpochMillis must not precede firstSeenAtEpochMillis" }
        require(notes == null || notes.length <= MAX_NOTES_LENGTH) { "Seller notes must be at most $MAX_NOTES_LENGTH characters" }
        val channelValues = contactPoints.map { it.channel to it.value }
        require(channelValues.size == channelValues.toSet().size) { "Seller contact points must be unique per channel and value" }
        require(contactPoints.count { it.isPrimary } <= 1) { "At most one primary contact point is allowed" }
        require(!isPrimaryContact || role != SellerRole.OTHER) { "The primary contact cannot have role OTHER" }
    }

    val isEntityOwned: Boolean get() = entityType != SellerEntityType.INDIVIDUAL && entityType != SellerEntityType.UNKNOWN

    /** Usable outbound channels: not opted out, not restricted, outbound capable. */
    val usableContactPoints: List<SellerContactPoint>
        get() = if (!restrictions.allowsSomeChannel) {
            emptyList()
        } else {
            contactPoints.filter { it.isUsable && restrictions.permits(it.channel) }
        }

    /** True when the app is allowed to attempt outbound contact with this seller at all. */
    val isContactable: Boolean get() = usableContactPoints.isNotEmpty()

    /** True when *no* channel may ever be used (DNC, litigation, stay, deceased). */
    val isFrozen: Boolean get() = restrictions.blocksAllOutboundContact

    /** Preferred channel: the primary usable point, else the first usable one. */
    val preferredContactPoint: SellerContactPoint?
        get() = usableContactPoints.firstOrNull { it.isPrimary } ?: usableContactPoints.firstOrNull()

    /** True when contacts came from a vendor lookup rather than from the seller or public records. */
    val hasVendorSkipTraceData: Boolean get() = skipTrace != null

    /** Contact path summary used in blocking messages, e.g. "phone:+1512... (primary)". */
    fun describeContactPath(): String = usableContactPoints
        .joinToString(", ") { it.describe() }
        .ifEmpty { "none" }

    fun withContactPoint(point: SellerContactPoint, atEpochMillis: Long): Seller {
        require(atEpochMillis >= updatedAtEpochMillis) { "Seller updates must not move backwards in time" }
        val replaced = if (point.isPrimary) contactPoints.map { it.copy(isPrimary = false) } else contactPoints
        return copy(
            contactPoints = replaced + point,
            updatedAtEpochMillis = atEpochMillis
        )
    }

    fun withRestrictions(restrictions: SellerContactRestrictions, atEpochMillis: Long): Seller {
        require(atEpochMillis >= updatedAtEpochMillis) { "Seller updates must not move backwards in time" }
        return copy(restrictions = restrictions, updatedAtEpochMillis = atEpochMillis)
    }

    fun withSkipTrace(reference: SkipTraceReference): Seller = copy(
        skipTrace = reference,
        updatedAtEpochMillis = maxOf(updatedAtEpochMillis, reference.fetchedAtEpochMillis)
    )

    fun describe(): String = buildString {
        append(fullName)
        append(" [").append(role.name).append(", ").append(entityType.name)
        if (isPrimaryContact) append(", primary")
        append("] contacts=").append(describeContactPath())
        if (isFrozen) append(" FROZEN(").append(restrictions.describe()).append(')')
    }

    companion object {
        const val MAX_NOTES_LENGTH = 1_000
    }
}

/**
 * Normalization for seller contact data.
 *
 * The same seller reaches the app as "+1 (512) 555-0123", "5125550123" and "512-555-0123"; without
 * one canonical form the CRM would show three phones for one person and the do-not-contact flag could
 * be recorded against a value that never matches again. Normalization is therefore total and
 * conservative: it only ever strips formatting, never invents digits.
 */
object ContactNormalizer {

    /** Normalizes a US phone number to "+1XXXXXXXXXX"; returns null when that is impossible. */
    fun normalizePhone(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        val national = when {
            digits.length == 10 -> digits
            digits.length == 11 && digits.startsWith("1") -> digits.substring(1)
            // International numbers keep their country code when explicitly provided.
            raw.trim().startsWith("+") && digits.length in 8..15 -> return "+$digits"
            else -> return null
        }
        return "+1$national"
    }

    /** Normalizes an email address to lower case, or null when it is not an email. */
    fun normalizeEmail(raw: String): String? {
        val trimmed = raw.trim().lowercase(Locale.US)
        return if (looksLikeEmail(trimmed)) trimmed else null
    }

    private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+\$")
    private val PHONE_REGEX = Regex("^\\+[1-9]\\d{7,14}\$")

    fun looksLikeEmail(value: String): Boolean = EMAIL_REGEX.matches(value.trim())

    fun looksLikePhone(value: String): Boolean = PHONE_REGEX.matches(value.trim())

    /** Last four digits of a normalized phone, or null. Used for display and de-dup hints. */
    fun phoneLastFour(normalizedPhone: String): String? =
        normalizedPhone.takeIf { looksLikePhone(it) }?.takeLast(4)

    /**
     * Normalizes a mailing address into a single contact-point value.
     * Returns null when the address is incomplete, instead of a partially useful string.
     */
    fun normalizeMailingAddress(address: PostalAddress): String = address.singleLine()
}
