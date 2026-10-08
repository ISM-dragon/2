package com.example.urlintelligence.normalization

import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.idempotency.Digests
import com.example.urlintelligence.idempotency.IdempotencyKey
import com.example.urlintelligence.model.CanonicalAddress
import com.example.urlintelligence.model.CanonicalListingStatus
import com.example.urlintelligence.model.CanonicalProperty
import com.example.urlintelligence.model.CanonicalPropertyType
import com.example.urlintelligence.model.CompletenessReport
import com.example.urlintelligence.model.GeoPoint
import com.example.urlintelligence.model.PropertyDraft
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.provenance.ExtractionMethod
import com.example.urlintelligence.provenance.MergePolicy
import com.example.urlintelligence.provenance.ProvenanceMap
import com.example.urlintelligence.provenance.VerificationSummary
import com.example.urlintelligence.retry.Clock
import com.example.urlintelligence.url.PropertyUrlValidator
import com.example.urlintelligence.url.UrlParts
import java.util.Locale

sealed class NormalizationOutcome {
    data class Success(
        val property: CanonicalProperty,
        val warnings: List<String> = emptyList()
    ) : NormalizationOutcome()

    data class Failure(
        val failure: SourceFailure,
        val partialProperty: CanonicalProperty? = null,
        val warnings: List<String> = emptyList()
    ) : NormalizationOutcome()
}

/**
 * Turns a parser draft into an immutable [CanonicalProperty].
 *
 * Responsibilities:
 *  * type/format conversion (money strings, area strings, state names, dates),
 *  * range validation (a 3 sqft condo or a $12 price is a parsing bug, not data),
 *  * provenance capture for every surviving field,
 *  * completeness scoring used by the resolver for partial-success handling.
 *
 * It never performs I/O and never throws: bad values are dropped and recorded as
 * warnings so a single broken field cannot fail a whole import.
 */
class PropertyNormalizer(
    private val clock: Clock = Clock.SYSTEM,
    private val requiredFields: Set<PropertyField> = PropertyField.REQUIRED_FIELDS,
    private val strictRanges: Boolean = true
) {

    /** Stateless host screen reused for the image URLs this normalizer lets through. */
    private val imageHostPolicy = PropertyUrlValidator()

    fun normalize(
        draft: PropertyDraft,
        extraWarnings: List<String> = emptyList()
    ): NormalizationOutcome {
        val extractedAt = clock.now()
        val warnings = ArrayList(extraWarnings)

        if (draft.isEmpty()) {
            return NormalizationOutcome.Failure(
                failure = SourceFailure.ParseError(
                    detail = "source '${draft.sourceId}' produced no usable fields",
                    extractor = "PropertyNormalizer"
                ),
                warnings = warnings
            )
        }

        val (line1, unitFromAddress) = AddressParser.splitUnit(draft.string(PropertyField.ADDRESS_LINE1))
        val unit = draft.string(PropertyField.UNIT)?.trim() ?: unitFromAddress
        val city = draft.string(PropertyField.CITY)?.trim()?.takeIf { it.isNotBlank() }
        val state = StateNormalizer.normalize(draft.string(PropertyField.STATE))
        val postalCode = normalizePostalCode(draft.string(PropertyField.POSTAL_CODE))
        val country = draft.string(PropertyField.COUNTRY)
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf { it.length == 2 }
            ?: "US"

        val geo = buildGeoPoint(
            latitude = draft.double(PropertyField.LATITUDE),
            longitude = draft.double(PropertyField.LONGITUDE),
            warnings = warnings
        )

        val price = money(draft, PropertyField.LIST_PRICE, warnings, "list price") {
            MoneyParser.isValidPrice(it)
        }
        val rent = money(draft, PropertyField.ESTIMATED_MONTHLY_RENT, warnings, "rent estimate") {
            MoneyParser.isValidRent(it)
        }
        val tax = money(draft, PropertyField.ANNUAL_TAX_AMOUNT, warnings, "annual tax") {
            it in 0.0..1_000_000.0
        }
        val hoa = money(draft, PropertyField.HOA_MONTHLY_FEE, warnings, "HOA fee") {
            it in 0.0..10_000.0
        }

        val livingArea = area(draft, PropertyField.LIVING_AREA_SQFT, warnings, "living area") {
            AreaParser.isValidLivingArea(it)
        }
        val lotSize = area(draft, PropertyField.LOT_SIZE_SQFT, warnings, "lot size") {
            AreaParser.isValidLotSize(it)
        }
        val yearBuilt = integer(draft, PropertyField.YEAR_BUILT, warnings, "year built") {
            it in 1600..(currentYear() + 2)
        }
        val bedrooms = decimal(draft, PropertyField.BEDROOMS, warnings, "bedrooms") { it in 0.0..50.0 }
        val bathrooms = decimal(draft, PropertyField.BATHROOMS, warnings, "bathrooms") { it in 0.0..30.0 }
        val daysOnMarket = integer(draft, PropertyField.DAYS_ON_MARKET, warnings, "days on market") {
            it in 0..10_000
        }

        val images = normalizeImages(draft, warnings)
        val primaryImage = draft.string(PropertyField.PRIMARY_IMAGE_URL)
            ?.takeIf { isPublicImageUrl(it) && it !in images }
            ?: images.firstOrNull()

        val description = draft.string(PropertyField.DESCRIPTION)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(4_000)
            ?.takeIf { it.isNotBlank() }

        val propertyType = when (val raw = draft.raw(PropertyField.PROPERTY_TYPE)) {
            is CanonicalPropertyType -> raw
            is String -> PropertyTypeParser.parse(raw)
            else -> null
        } ?: CanonicalPropertyType.UNKNOWN

        val listingStatus = when (val raw = draft.raw(PropertyField.LISTING_STATUS)) {
            is CanonicalListingStatus -> raw
            is String -> ListingStatusParser.parse(raw)
            else -> null
        } ?: CanonicalListingStatus.UNKNOWN

        val listedAt = DateParser.epochMillis(draft.string(PropertyField.LISTED_AT_EPOCH_MILLIS))
            ?: draft.double(PropertyField.LISTED_AT_EPOCH_MILLIS)?.toLong()

        val address = CanonicalAddress(
            line1 = line1,
            unit = unit,
            city = city,
            stateOrProvince = state,
            postalCode = postalCode,
            countryCode = country,
            geo = geo
        )

        val present = linkedSetOf<PropertyField>()
        if (line1 != null) present.add(PropertyField.ADDRESS_LINE1)
        if (unit != null) present.add(PropertyField.UNIT)
        if (city != null) present.add(PropertyField.CITY)
        if (state != null) present.add(PropertyField.STATE)
        if (postalCode != null) present.add(PropertyField.POSTAL_CODE)
        if (country != "US") present.add(PropertyField.COUNTRY)
        if (geo != null) {
            present.add(PropertyField.LATITUDE)
            present.add(PropertyField.LONGITUDE)
        }
        if (price != null) present.add(PropertyField.LIST_PRICE)
        if (rent != null) present.add(PropertyField.ESTIMATED_MONTHLY_RENT)
        if (tax != null) present.add(PropertyField.ANNUAL_TAX_AMOUNT)
        if (hoa != null) present.add(PropertyField.HOA_MONTHLY_FEE)
        if (livingArea != null) present.add(PropertyField.LIVING_AREA_SQFT)
        if (lotSize != null) present.add(PropertyField.LOT_SIZE_SQFT)
        if (yearBuilt != null) present.add(PropertyField.YEAR_BUILT)
        if (bedrooms != null) present.add(PropertyField.BEDROOMS)
        if (bathrooms != null) present.add(PropertyField.BATHROOMS)
        if (daysOnMarket != null) present.add(PropertyField.DAYS_ON_MARKET)
        if (description != null) present.add(PropertyField.DESCRIPTION)
        if (primaryImage != null) present.add(PropertyField.PRIMARY_IMAGE_URL)
        if (images.isNotEmpty()) present.add(PropertyField.IMAGE_URLS)
        if (listedAt != null) present.add(PropertyField.LISTED_AT_EPOCH_MILLIS)
        if (propertyType != CanonicalPropertyType.UNKNOWN) present.add(PropertyField.PROPERTY_TYPE)
        if (listingStatus != CanonicalListingStatus.UNKNOWN) present.add(PropertyField.LISTING_STATUS)
        draft.string(PropertyField.MLS_ID)?.let { present.add(PropertyField.MLS_ID) }
        draft.string(PropertyField.PARCEL_ID)?.let { present.add(PropertyField.PARCEL_ID) }

        val canonicalId = IdempotencyKey.canonicalPropertyId(
            sourceId = draft.sourceId,
            sourcePropertyId = draft.sourcePropertyId,
            canonicalUrl = draft.resolvedUrl.ifBlank { draft.requestUrl }
        )

        // Provenance is built from the draft (which carries the parser id/version, the
        // document digest and the fetch origin), then audited: a field that survives
        // normalization without provenance is a bug, not an acceptable gap.
        val provenance = draft.provenance(extractedAt)
        val missingProvenance = present.filterNot { provenance.contains(it) }
        if (missingProvenance.isNotEmpty()) {
            warnings.add(
                "internal: ${missingProvenance.size} field(s) lost provenance: " +
                    missingProvenance.joinToString { it.stableName }
            )
        }
        val verification = VerificationSummary.from(provenance.entries(), draft.origin)
        val contentDigest = Digests.fieldsDigest(
            sourceId = draft.sourceId,
            values = draft.snapshot().mapValues { (_, value) -> value.value }
        )

        val property = CanonicalProperty(
            canonicalId = canonicalId,
            sourceId = draft.sourceId,
            sourcePropertyId = draft.sourcePropertyId,
            canonicalUrl = draft.requestUrl,
            resolvedUrl = draft.resolvedUrl.ifBlank { draft.requestUrl },
            address = address,
            propertyType = propertyType,
            listingStatus = listingStatus,
            listPriceUsd = price,
            bedrooms = bedrooms,
            bathrooms = bathrooms,
            livingAreaSqFt = livingArea,
            lotSizeSqFt = lotSize,
            yearBuilt = yearBuilt,
            description = description,
            primaryImageUrl = primaryImage,
            imageUrls = images,
            estimatedMonthlyRentUsd = rent,
            annualPropertyTaxUsd = tax,
            hoaMonthlyFeeUsd = hoa,
            daysOnMarket = daysOnMarket,
            listedAtEpochMillis = listedAt,
            mlsId = draft.string(PropertyField.MLS_ID)?.trim(),
            parcelId = draft.string(PropertyField.PARCEL_ID)?.trim(),
            fetchedAtEpochMillis = extractedAt,
            completeness = CompletenessReport.compute(present, requiredFields),
            provenance = provenance,
            warnings = warnings,
            verification = verification,
            contentDigest = contentDigest,
            parserVersions = verification.parsers
        )

        if (line1 == null && draft.sourcePropertyId == null) {
            return NormalizationOutcome.Failure(
                failure = SourceFailure.IncompleteData(
                    missingFields = listOf(
                        PropertyField.ADDRESS_LINE1.stableName,
                        "source_property_id"
                    )
                ),
                partialProperty = null,
                warnings = warnings
            )
        }

        return NormalizationOutcome.Success(property, warnings)
    }

    private fun money(
        draft: PropertyDraft,
        field: PropertyField,
        warnings: MutableList<String>,
        label: String,
        valid: (Double) -> Boolean
    ): Double? {
        val value = when (val raw = draft.raw(field)) {
            is Number -> raw.toDouble()
            is String -> MoneyParser.parse(raw)
            else -> null
        } ?: return null
        if (strictRanges && !valid(value)) {
            warnings.add("dropped out-of-range $label ($value)")
            return null
        }
        return value
    }

    private fun area(
        draft: PropertyDraft,
        field: PropertyField,
        warnings: MutableList<String>,
        label: String,
        valid: (Int) -> Boolean
    ): Int? {
        val value = when (val raw = draft.raw(field)) {
            is Number -> raw.toInt()
            is String -> AreaParser.sqFt(raw)
            else -> null
        } ?: return null
        if (strictRanges && !valid(value)) {
            warnings.add("dropped out-of-range $label ($value)")
            return null
        }
        return value
    }

    private fun integer(
        draft: PropertyDraft,
        field: PropertyField,
        warnings: MutableList<String>,
        label: String,
        valid: (Int) -> Boolean
    ): Int? {
        val value = draft.int(field) ?: return null
        if (strictRanges && !valid(value)) {
            warnings.add("dropped out-of-range $label ($value)")
            return null
        }
        return value
    }

    private fun decimal(
        draft: PropertyDraft,
        field: PropertyField,
        warnings: MutableList<String>,
        label: String,
        valid: (Double) -> Boolean
    ): Double? {
        val value = draft.double(field) ?: return null
        if (strictRanges && !valid(value)) {
            warnings.add("dropped out-of-range $label ($value)")
            return null
        }
        return value
    }

    private fun buildGeoPoint(
        latitude: Double?,
        longitude: Double?,
        warnings: MutableList<String>
    ): GeoPoint? {
        if (latitude == null || longitude == null) return null
        if (latitude == 0.0 && longitude == 0.0) return null
        return try {
            GeoPoint(latitude, longitude)
        } catch (t: IllegalArgumentException) {
            warnings.add("dropped invalid coordinates ($latitude, $longitude)")
            null
        }
    }

    private fun normalizePostalCode(raw: String?): String? {
        val match = raw?.let { Regex("([0-9]{5})(?:-[0-9]{4})?").find(it) } ?: return null
        return match.groupValues[1]
    }

    private fun normalizeImages(draft: PropertyDraft, warnings: MutableList<String>): List<String> {
        val all = ArrayList<String>()
        draft.string(PropertyField.PRIMARY_IMAGE_URL)?.let { if (isPublicImageUrl(it)) all.add(it) }
        draft.stringList(PropertyField.IMAGE_URLS).forEach { if (isPublicImageUrl(it)) all.add(it) }
        val deduped = all.distinct().take(50)
        if (all.size > deduped.size) warnings.add("dropped ${all.size - deduped.size} duplicate image url(s)")
        return deduped
    }

    /**
     * Image URLs come out of documents this module does not control, and callers render them with
     * their own image loader. They therefore get the same public-destination screening the fetch
     * boundary applies: absolute http(s), no embedded credentials, no IP literals and no
     * loopback/link-local/private/metadata hosts.
     */
    internal fun isPublicImageUrl(value: String): Boolean {
        if (value.length > MAX_IMAGE_URL_LENGTH) return false
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) return false
        val uri = try {
            java.net.URI(value)
        } catch (t: Exception) {
            return false
        }
        if (!uri.userInfo.isNullOrBlank()) return false
        val host = UrlParts.hostOf(value).trim('.')
        if (host.isBlank() || !host.contains('.')) return false
        if (host.startsWith("[") || host.contains(':')) return false // IPv6 literal
        if (IPV4_LITERAL.matches(host)) return false
        if (imageHostPolicy.isBlockedHost(host)) return false
        return UrlParts.pathOf(value).split('/').none { it == ".." }
    }

    private fun currentYear(): Int {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        return calendar.get(java.util.Calendar.YEAR)
    }

    private companion object {
        const val MAX_IMAGE_URL_LENGTH = 2048
        val IPV4_LITERAL = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    }
}

/**
 * Fills gaps in a property using another source, keeping provenance for whatever
 * was taken. Only fields missing (or less confident) in [primary] are taken from
 * [secondary]; provenance is merged under [policy].
 */
object CanonicalPropertyMerger {

    fun merge(
        primary: CanonicalProperty,
        secondary: CanonicalProperty,
        policy: MergePolicy = MergePolicy.HighestConfidence
    ): CanonicalProperty {
        val provenance = primary.provenance.merge(secondary.provenance, policy)
        val mergedOrigin = if (primary.verification.origin == com.example.urlintelligence.provenance.FetchOrigin.LIVE_NETWORK) {
            primary.verification.origin
        } else {
            secondary.verification.origin
        }
        val mergedVerification = VerificationSummary.from(provenance.entries(), mergedOrigin)
        val merged = primary.copy(
            sourcePropertyId = primary.sourcePropertyId ?: secondary.sourcePropertyId,
            propertyType = if (primary.propertyType == CanonicalPropertyType.UNKNOWN) secondary.propertyType else primary.propertyType,
            listingStatus = if (primary.listingStatus == CanonicalListingStatus.UNKNOWN) secondary.listingStatus else primary.listingStatus,
            listPriceUsd = primary.listPriceUsd ?: secondary.listPriceUsd,
            bedrooms = primary.bedrooms ?: secondary.bedrooms,
            bathrooms = primary.bathrooms ?: secondary.bathrooms,
            livingAreaSqFt = primary.livingAreaSqFt ?: secondary.livingAreaSqFt,
            lotSizeSqFt = primary.lotSizeSqFt ?: secondary.lotSizeSqFt,
            yearBuilt = primary.yearBuilt ?: secondary.yearBuilt,
            description = primary.description ?: secondary.description,
            primaryImageUrl = primary.primaryImageUrl ?: secondary.primaryImageUrl,
            imageUrls = (primary.imageUrls + secondary.imageUrls).distinct().take(50),
            estimatedMonthlyRentUsd = primary.estimatedMonthlyRentUsd ?: secondary.estimatedMonthlyRentUsd,
            annualPropertyTaxUsd = primary.annualPropertyTaxUsd ?: secondary.annualPropertyTaxUsd,
            hoaMonthlyFeeUsd = primary.hoaMonthlyFeeUsd ?: secondary.hoaMonthlyFeeUsd,
            daysOnMarket = primary.daysOnMarket ?: secondary.daysOnMarket,
            listedAtEpochMillis = primary.listedAtEpochMillis ?: secondary.listedAtEpochMillis,
            mlsId = primary.mlsId ?: secondary.mlsId,
            parcelId = primary.parcelId ?: secondary.parcelId,
            address = mergeAddress(primary.address, secondary.address),
            completeness = CompletenessReport.compute(
                primary.completeness.present + secondary.completeness.present
            ),
            provenance = provenance,
            warnings = primary.warnings + secondary.warnings,
            verification = mergedVerification,
            parserVersions = mergedVerification.parsers
        )
        return merged
    }

    private fun mergeAddress(primary: CanonicalAddress, secondary: CanonicalAddress): CanonicalAddress =
        CanonicalAddress(
            line1 = primary.line1 ?: secondary.line1,
            unit = primary.unit ?: secondary.unit,
            city = primary.city ?: secondary.city,
            stateOrProvince = primary.stateOrProvince ?: secondary.stateOrProvince,
            postalCode = primary.postalCode ?: secondary.postalCode,
            countryCode = if (primary.countryCode != "US") primary.countryCode else secondary.countryCode,
            geo = primary.geo ?: secondary.geo
        )

    /** Manual overrides (from the app's UI or an analyst) always win. */
    fun applyManualOverride(property: CanonicalProperty, overrides: Map<PropertyField, Any?>): CanonicalProperty {
        var provenance: ProvenanceMap = property.provenance
        overrides.forEach { (field, value) ->
            if (value != null) {
                provenance = provenance.with(
                    com.example.urlintelligence.provenance.FieldProvenance(
                        field = field,
                        sourceId = "manual",
                        sourceUrl = property.canonicalUrl,
                        extractor = "CanonicalPropertyMerger",
                        method = ExtractionMethod.MANUAL,
                        confidence = Confidence.EXACT,
                        rawValue = value.toString(),
                        extractedAtEpochMillis = property.fetchedAtEpochMillis,
                        notes = "manual override"
                    ),
                    MergePolicy.PreferIncoming
                )
            }
        }
        return property.copy(provenance = provenance)
    }
}
