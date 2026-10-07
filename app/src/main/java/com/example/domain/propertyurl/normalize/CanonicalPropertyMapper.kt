package com.example.domain.propertyurl.normalize

import com.example.domain.propertyurl.model.CanonicalIds
import com.example.domain.propertyurl.model.CanonicalProperty
import com.example.domain.propertyurl.model.CompletenessReport
import com.example.domain.propertyurl.model.ExtractedFacts
import com.example.domain.propertyurl.model.FieldConflict
import com.example.domain.propertyurl.model.FieldValue
import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.Provenance
import com.example.domain.propertyurl.model.ProvenanceMethod
import com.example.domain.propertyurl.model.ProvenancePolicy
import com.example.domain.propertyurl.model.SourcedField
import com.example.domain.propertyurl.source.SourceRegistry
import com.example.domain.propertyurl.url.ResolvedPropertyUrl
import java.util.Calendar
import java.util.Locale

data class MapperOptions(
    /** Fields that must be present for the record to be considered usable at all. */
    val requiredFields: Set<PropertyField> = setOf(
        PropertyField.ADDRESS_LINE_1,
        PropertyField.CITY,
        PropertyField.STATE,
        PropertyField.PRICE_AMOUNT
    ),
    /** Adapters may forbid deriving address parts from the listing title (wholesale lists). */
    val deriveAddressFromTitle: Boolean = true,
    val deriveFields: Boolean = true,
    val defaultCountryCodeForUsStates: String = "US",
    val maxImages: Int = 25,
    val maxDescriptionChars: Int = 4000
)

/** Result of the canonicalization step, including everything that was degraded on the way. */
data class CanonicalMapping(
    val property: CanonicalProperty,
    val conflicts: List<FieldConflict>,
    val warnings: List<ImportWarning>
) {
    val isUsable: Boolean get() = property.completeness.isUsable

    val missingRequired: Set<PropertyField>
        get() = property.completeness.missing.filter { it in setOf(PropertyField.ADDRESS_LINE_1, PropertyField.CITY, PropertyField.STATE, PropertyField.POSTAL_CODE, PropertyField.PRICE_AMOUNT) }.toSet()
}

/**
 * Turns raw [ExtractedFacts] into a [CanonicalProperty]:
 *
 *  1. merge candidates per field through [ProvenancePolicy] (trust rank → method → confidence → freshness),
 *  2. coerce + guard every value (money strings, sqft, state names, zips, images…),
 *  3. derive what is safely derivable (address parts from the title, price per sqft, country code),
 *  4. attach provenance to every single field,
 *  5. compute completeness so partial imports are explicit rather than silent.
 */
class CanonicalPropertyMapper(
    private val registry: SourceRegistry,
    private val options: MapperOptions = MapperOptions(),
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private val policy = ProvenancePolicy(
        sourceTrustRanks = registry.definitions.associate { it.sourceId to it.trustRank }
    )

    fun map(
        facts: ExtractedFacts,
        resolved: ResolvedPropertyUrl,
        sourceId: String
    ): CanonicalMapping {
        val warnings = ArrayList<ImportWarning>()
        val conflicts = ArrayList<FieldConflict>()

        val merged = facts.bestPerField(policy, conflicts)

        val fields = LinkedHashMap<PropertyField, SourcedField>()
        merged.forEach { (field, sourced) ->
            normalizeField(field, sourced, warnings)?.let { fields[field] = it }
        }

        if (options.deriveAddressFromTitle) {
            deriveAddress(fields, warnings)
        }
        if (options.deriveFields) {
            deriveComputed(fields, resolved, sourceId, warnings)
        }

        val completeness = CompletenessReport.of(fields)
        completeness.missingCritical.forEach { missing ->
            warnings.add(
                ImportWarning(
                    code = ImportWarning.WarningCode.MISSING_CRITICAL_FIELD,
                    message = "Missing critical field ${missing.name.lowercase(Locale.US)}",
                    field = missing,
                    sourceId = sourceId
                )
            )
        }
        completeness.missingRecommended.forEach { missing ->
            warnings.add(
                ImportWarning(
                    code = ImportWarning.WarningCode.MISSING_RECOMMENDED_FIELD,
                    message = "Missing recommended field ${missing.name.lowercase(Locale.US)}",
                    field = missing,
                    sourceId = sourceId
                )
            )
        }
        conflicts.forEach { conflict ->
            warnings.add(
                ImportWarning(
                    code = ImportWarning.WarningCode.FIELD_CONFLICT,
                    message = "Conflicting ${conflict.field.name.lowercase(Locale.US)} values",
                    field = conflict.field,
                    sourceId = conflict.kept.provenance.sourceId,
                    detail = conflict.reason
                )
            )
        }

        val addressLine1 = fields[PropertyField.ADDRESS_LINE_1]?.value?.asText()
        val city = fields[PropertyField.CITY]?.value?.asText()
        val state = fields[PropertyField.STATE]?.value?.asText()
        val postalCode = fields[PropertyField.POSTAL_CODE]?.value?.asText()
        val hasAddress = !addressLine1.isNullOrBlank() || (!city.isNullOrBlank() && !state.isNullOrBlank())

        val canonicalId = if (hasAddress) {
            CanonicalIds.canonicalId(addressLine1, city, state, postalCode)
        } else {
            CanonicalIds.canonicalIdForUrl(sourceId, resolved.url.identitySeed)
        }

        val property = CanonicalProperty(
            canonicalId = canonicalId,
            primarySourceId = sourceId,
            sourceListingId = fields[PropertyField.SOURCE_LISTING_ID]?.value?.asText(),
            sourceUrl = resolved.url.normalized,
            fields = fields,
            completeness = completeness,
            warnings = warnings.toList(),
            builtAtEpochMillis = now()
        )

        return CanonicalMapping(property = property, conflicts = conflicts, warnings = warnings.toList())
    }

    // --- Value normalization ---------------------------------------------------------------

    private fun normalizeField(
        field: PropertyField,
        sourced: SourcedField,
        warnings: MutableList<ImportWarning>
    ): SourcedField? {
        val value = sourced.value
        val provenance = sourced.provenance

        fun coerced(newValue: FieldValue, note: String): SourcedField {
            if (newValue != value) {
                // Report coercions once per field, at debug level: they are expected, not errors.
                if (warnings.none { it.code == ImportWarning.WarningCode.VALUE_COERCED && it.field == field }) {
                    warnings.add(
                        ImportWarning(
                            code = ImportWarning.WarningCode.VALUE_COERCED,
                            message = "Normalized ${field.name.lowercase(Locale.US)}: $note",
                            field = field,
                            sourceId = provenance.sourceId
                        )
                    )
                }
            }
            val effectiveProvenance = if (newValue != value) {
                provenance.copy(
                    method = provenance.method,
                    note = listOfNotNull(provenance.note, note).joinToString("; ")
                )
            } else {
                provenance
            }
            return SourcedField(newValue, effectiveProvenance)
        }

        fun reject(reason: String): SourcedField? {
            warnings.add(
                ImportWarning(
                    code = ImportWarning.WarningCode.VALUE_REJECTED_BY_GUARD,
                    message = "Dropped ${field.name.lowercase(Locale.US)}: $reason",
                    field = field,
                    sourceId = provenance.sourceId
                )
            )
            return null
        }

        return when (field) {
            PropertyField.PRICE_AMOUNT -> {
                val price = value.asDecimal() ?: ValueParsing.parseMoney(value.asText())
                val guard = ValueGuards.price(price)
                if (!guard.accepted) reject(guard.reason ?: "invalid price")
                else coerced(FieldValue.Decimal(price!!), "amount parsed and range-checked")
            }
            PropertyField.BEDROOMS -> {
                val beds = value.asDecimal() ?: ValueParsing.parseBedrooms(value.asText())
                val guard = ValueGuards.bedrooms(beds)
                if (!guard.accepted) reject(guard.reason ?: "invalid bedrooms")
                else coerced(FieldValue.Decimal(beds!!), "bedrooms parsed")
            }
            PropertyField.BATHROOMS -> {
                val baths = value.asDecimal() ?: ValueParsing.parseBathrooms(value.asText())
                val guard = ValueGuards.bathrooms(baths)
                if (!guard.accepted) reject(guard.reason ?: "invalid bathrooms")
                else coerced(FieldValue.Decimal(baths!!), "bathrooms parsed")
            }
            PropertyField.LIVING_AREA_SQFT -> {
                val sqft = value.asDecimal() ?: ValueParsing.parseAreaSqFt(value.asText())
                val guard = ValueGuards.areaSqFt(sqft)
                if (!guard.accepted) reject(guard.reason ?: "invalid living area")
                else coerced(FieldValue.WholeNumber(sqft!!.toInt()), "area converted to square feet")
            }
            PropertyField.LOT_SIZE_SQFT -> {
                val sqft = value.asDecimal() ?: ValueParsing.parseAreaSqFt(value.asText())
                val guard = ValueGuards.lotSqFt(sqft)
                if (!guard.accepted) reject(guard.reason ?: "invalid lot size")
                else coerced(FieldValue.WholeNumber(sqft!!.toInt()), "lot size converted to square feet")
            }
            PropertyField.YEAR_BUILT -> {
                val year = value.asWholeNumber() ?: ValueParsing.parseYearBuilt(value.asText())
                val guard = ValueGuards.yearBuilt(year, currentYear())
                if (!guard.accepted) reject(guard.reason ?: "invalid year built")
                else coerced(FieldValue.WholeNumber(year!!), "year validated")
            }
            PropertyField.DAYS_ON_MARKET -> {
                val days = value.asWholeNumber() ?: ValueParsing.parseDaysOnMarket(value.asText())
                val guard = ValueGuards.daysOnMarket(days)
                if (!guard.accepted) reject(guard.reason ?: "invalid days on market")
                else coerced(FieldValue.WholeNumber(days!!), "days on market parsed")
            }
            PropertyField.STATE -> {
                val state = ValueParsing.parseUsState(value.asText())
                if (state == null) reject("unrecognised state") else coerced(FieldValue.Text(state), "state normalized to code")
            }
            PropertyField.POSTAL_CODE -> {
                val postal = ValueParsing.parsePostalCode(value.asText())
                val guard = ValueGuards.postalCode(postal, null)
                if (postal == null || !guard.accepted) reject(guard.reason ?: "invalid postal code")
                else coerced(FieldValue.Text(postal), "postal code normalized")
            }
            PropertyField.PROPERTY_TYPE -> {
                val type = ValueParsing.normalizePropertyType(value.asText())
                if (type == null) reject("unrecognised property type")
                else coerced(FieldValue.Text(type), "property type mapped to the canonical vocabulary")
            }
            PropertyField.LISTING_STATUS -> {
                val status = ValueParsing.normalizeListingStatus(value.asText())
                if (status == null) reject("unrecognised listing status")
                else coerced(FieldValue.Text(status), "listing status normalized")
            }
            PropertyField.COUNTRY_CODE -> {
                val code = value.asText()?.take(2)?.uppercase(Locale.US)
                if (code == null || code.length != 2) reject("invalid country code")
                else coerced(FieldValue.Text(code), "country code normalized")
            }
            PropertyField.IMAGE_URLS -> {
                val urls = value.asTextList()
                    .map { it.trim() }
                    .filter { ValueGuards.imageUrl(it).accepted }
                    .distinct()
                    .take(options.maxImages)
                if (urls.isEmpty()) reject("no usable image urls") else coerced(FieldValue.TextList(urls), "image urls filtered")
            }
            PropertyField.DESCRIPTION -> {
                val text = ValueParsing.normalizeWhitespace(value.asText())?.take(options.maxDescriptionChars)
                if (text.isNullOrBlank()) null else coerced(FieldValue.Text(text), "description normalized")
            }
            PropertyField.LATITUDE -> {
                val latitude = value.asDecimal()
                val guard = ValueGuards.latitude(latitude)
                if (!guard.accepted) reject(guard.reason ?: "invalid latitude")
                else coerced(FieldValue.Decimal(latitude!!), "latitude validated")
            }
            PropertyField.LONGITUDE -> {
                val longitude = value.asDecimal()
                val guard = ValueGuards.longitude(longitude)
                if (!guard.accepted) reject(guard.reason ?: "invalid longitude")
                else coerced(FieldValue.Decimal(longitude!!), "longitude validated")
            }
            PropertyField.HOA_FEE_MONTHLY, PropertyField.ANNUAL_TAX_AMOUNT -> {
                val amount = value.asDecimal() ?: ValueParsing.parseMoney(value.asText())
                if (amount == null || amount < 0 || amount > 1_000_000) reject("amount out of range")
                else coerced(FieldValue.Decimal(amount), "amount parsed")
            }
            else -> {
                val text = ValueParsing.normalizeWhitespace(value.asText())
                if (text.isNullOrBlank()) null else SourcedField(FieldValue.Text(text), provenance)
            }
        }
    }

    // --- Derivations ------------------------------------------------------------------------

    private fun deriveAddress(fields: MutableMap<PropertyField, SourcedField>, warnings: MutableList<ImportWarning>) {
        val hasStreet = fields.containsKey(PropertyField.ADDRESS_LINE_1)
        val hasCityState = fields.containsKey(PropertyField.CITY) && fields.containsKey(PropertyField.STATE)
        if (hasStreet && hasCityState) return

        val candidates = listOfNotNull(
            fields[PropertyField.LISTING_TITLE]?.value?.asText(),
            fields[PropertyField.DESCRIPTION]?.value?.asText()?.substringBefore('.')
        )
        val baseProvenance = fields[PropertyField.LISTING_TITLE]?.provenance
            ?: fields[PropertyField.DESCRIPTION]?.provenance
            ?: Provenance(
                sourceId = "unknown",
                method = ProvenanceMethod.DERIVED,
                confidence = 0.4,
                extractedAtEpochMillis = now()
            )

        candidates.forEach { candidate ->
            val parts = ValueParsing.splitAddressLine(candidate)
            val anchored = parts.postalCode != null || parts.state != null
            if (!anchored) return@forEach

            val provenance = baseProvenance.copy(
                method = ProvenanceMethod.DERIVED,
                confidence = 0.55,
                rawPath = "title-address",
                note = "address derived from listing title",
                extractedAtEpochMillis = now()
            )

            fun putIfAbsent(field: PropertyField, value: String?) {
                if (value == null || fields.containsKey(field)) return
                val coerced = when (field) {
                    PropertyField.STATE -> ValueParsing.parseUsState(value)
                    PropertyField.POSTAL_CODE -> ValueParsing.parsePostalCode(value)
                    PropertyField.CITY -> value.takeIf { it.length in 2..48 && !it.any { c -> c.isDigit() } }
                    PropertyField.ADDRESS_LINE_1 -> value.takeIf { ValueGuards.addressLine(it).accepted }
                    else -> value
                } ?: return
                fields[field] = SourcedField(FieldValue.Text(coerced), provenance)
                warnings.add(
                    ImportWarning(
                        code = ImportWarning.WarningCode.DERIVED_FIELD,
                        message = "Derived ${field.name.lowercase(Locale.US)} from the listing title",
                        field = field,
                        sourceId = provenance.sourceId
                    )
                )
            }

            putIfAbsent(PropertyField.ADDRESS_LINE_1, parts.streetAddress)
            putIfAbsent(PropertyField.UNIT_NUMBER, parts.unit)
            putIfAbsent(PropertyField.CITY, parts.city)
            putIfAbsent(PropertyField.STATE, parts.state)
            putIfAbsent(PropertyField.POSTAL_CODE, parts.postalCode)
        }
    }

    private fun deriveComputed(
        fields: MutableMap<PropertyField, SourcedField>,
        resolved: ResolvedPropertyUrl,
        sourceId: String,
        warnings: MutableList<ImportWarning>
    ) {
        val derivedProvenance = Provenance(
            sourceId = sourceId,
            method = ProvenanceMethod.DERIVED,
            confidence = 0.85,
            extractedAtEpochMillis = now(),
            adapterId = null,
            sourceUrl = resolved.url.normalized,
            rawPath = null,
            note = "computed by the canonical mapper"
        )

        val price = fields[PropertyField.PRICE_AMOUNT]?.value?.asDecimal()
        val sqft = fields[PropertyField.LIVING_AREA_SQFT]?.value?.asWholeNumber()
        if (!fields.containsKey(PropertyField.PRICE_PER_SQFT) && price != null && sqft != null && sqft > 0) {
            val perSqFt = price / sqft
            if (perSqFt.isFinite() && perSqFt in 1.0..100_000.0) {
                fields[PropertyField.PRICE_PER_SQFT] = SourcedField(
                    FieldValue.Decimal(Math.round(perSqFt * 100.0) / 100.0),
                    derivedProvenance.copy(confidence = 0.9, rawPath = "price/livingArea")
                )
            }
        }

        if (!fields.containsKey(PropertyField.SOURCE_LISTING_ID) && !resolved.externalListingId.isNullOrBlank()) {
            fields[PropertyField.SOURCE_LISTING_ID] = SourcedField(
                FieldValue.Text(resolved.externalListingId!!),
                derivedProvenance.copy(
                    confidence = 0.8,
                    rawPath = "url",
                    note = "listing id taken from the URL (${resolved.externalIdOrigin ?: "heuristic"})"
                )
            )
        }

        val state = fields[PropertyField.STATE]?.value?.asText()
        if (!fields.containsKey(PropertyField.COUNTRY_CODE) && state != null && UsStates.isCode(state)) {
            val canadian = state in setOf("AB", "BC", "MB", "NB", "NL", "NS", "ON", "PE", "QC", "SK")
            fields[PropertyField.COUNTRY_CODE] = SourcedField(
                FieldValue.Text(if (canadian) "CA" else options.defaultCountryCodeForUsStates),
                derivedProvenance.copy(confidence = 0.9, rawPath = "state", note = "country implied by the state code")
            )
        }

        if (!fields.containsKey(PropertyField.LISTING_STATUS) && resolved.detection.definition != null) {
            val implied = when (resolved.detection.definition?.marketType) {
                com.example.domain.propertyurl.source.MarketType.ON_MARKET -> "Active"
                com.example.domain.propertyurl.source.MarketType.OFF_MARKET,
                com.example.domain.propertyurl.source.MarketType.WHOLESALE -> "Off-Market"
                com.example.domain.propertyurl.source.MarketType.FORECLOSURE -> "Foreclosure"
                else -> null
            }
            if (implied != null) {
                fields[PropertyField.LISTING_STATUS] = SourcedField(
                    FieldValue.Text(implied),
                    derivedProvenance.copy(confidence = 0.6, rawPath = "source-market-type", note = "implied by the source")
                )
                warnings.add(
                    ImportWarning(
                        code = ImportWarning.WarningCode.DERIVED_FIELD,
                        message = "Listing status implied by the source ($implied)",
                        field = PropertyField.LISTING_STATUS,
                        sourceId = sourceId
                    )
                )
            }
        }
    }

    private fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)
}
