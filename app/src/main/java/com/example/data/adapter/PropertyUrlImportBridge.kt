package com.example.data.adapter

import com.example.data.local.entity.ComparablePropertyEntity
import com.example.data.local.entity.MarketDataEntity
import com.example.data.local.entity.PropertyEntity
import com.example.data.local.entity.PropertyImageEntity
import com.example.data.local.entity.RentEstimateEntity
import com.example.data.local.entity.SalesHistoryEntity
import com.example.data.local.entity.TaxRecordEntity
import com.example.data.repository.PropertyRepository
import com.example.domain.propertyurl.model.CanonicalProperty
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.pipeline.ImportOutcome
import com.example.domain.propertyurl.pipeline.ImportRequest
import com.example.domain.propertyurl.pipeline.PropertyUrlIntelligence
import com.example.domain.propertyurl.pipeline.UrlInspection

/**
 * Adapter between the Property URL Intelligence layer and the app's existing persistence model.
 *
 * This is the *only* place that knows about both sides: the layer below stays Android-free and
 * testable on a plain JVM, the app keeps using [NormalizedPropertyBundle] exactly as before, and the
 * UI/financial code is untouched.
 *
 * Identity: the bundle uses the canonical id (`cp-…`, derived from the address) as the Room primary
 * key, so importing the same house from two portals updates one row instead of creating two
 * (the DAO inserts with `OnConflictStrategy.REPLACE`).
 *
 * Financial placeholders: [MarketDataEntity] and [RentEstimateEntity] are required by the existing
 * schema, but an import has no valuation data. They are written as zeros on purpose — the layer never
 * invents numbers; the app's own analysis fills them in later.
 */
class PropertyUrlImportBridge(
    private val intelligence: PropertyUrlIntelligence,
    /** When set, [importAndStore] persists successful imports through the existing repository. */
    private val repository: PropertyRepository? = null
) {

    /** Imports one pasted link/share text. Never throws for expected conditions. */
    suspend fun import(rawInput: String, request: ImportRequest = ImportRequest()): ImportOutcome =
        intelligence.import(rawInput, request)

    /**
     * Imports and, when a repository is wired and a canonical record was produced, stores it.
     * Partial successes are stored too — a listing with an address and a price is useful even when
     * the portal hid the square footage.
     */
    suspend fun importAndStore(rawInput: String, request: ImportRequest = ImportRequest()): ImportOutcome {
        val outcome = intelligence.import(rawInput, request)
        bundleFor(outcome)?.let { bundle -> repository?.insertBundle(bundle) }
        return outcome
    }

    /** Offline preview for the UI: validation, source detection and support questions, no network. */
    fun inspect(rawInput: String): UrlInspection = intelligence.inspect(rawInput)

    suspend fun recentImports(limit: Int = 25) = intelligence.recentJobs(limit)

    suspend fun cancel(jobId: String) = intelligence.cancel(jobId)

    /** Room-ready bundle for a finished import, or null when there is no canonical record yet. */
    fun bundleFor(outcome: ImportOutcome, importedAtEpochMillis: Long = System.currentTimeMillis()): NormalizedPropertyBundle? =
        outcome.propertyOrNull()?.let { property -> toBundle(property, importedAtEpochMillis) }

    fun toBundle(property: CanonicalProperty, importedAtEpochMillis: Long = System.currentTimeMillis()): NormalizedPropertyBundle {
        val id = property.canonicalId
        val images = property.imageUrls.take(MAX_IMAGES).mapIndexed { index, url ->
            PropertyImageEntity(
                propertyId = id,
                imageUrl = url,
                caption = if (index == 0) "Primary" else "Listing photo ${index + 1}",
                isPrimary = index == 0
            )
        }

        val entity = PropertyEntity(
            id = id,
            sourceType = sourceTypeFor(property),
            title = property.text(PropertyField.LISTING_TITLE)
                ?: property.formattedAddress()
                ?: property.sourceUrl,
            address = property.addressLine1.orEmpty(),
            city = property.city.orEmpty(),
            state = property.state.orEmpty(),
            zipCode = property.postalCode.orEmpty(),
            latitude = property.decimal(PropertyField.LATITUDE) ?: 0.0,
            longitude = property.decimal(PropertyField.LONGITUDE) ?: 0.0,
            price = property.price ?: 0.0,
            propertyType = property.text(PropertyField.PROPERTY_TYPE) ?: "Single Family",
            bedrooms = property.bedrooms ?: 0,
            bathrooms = property.bathrooms ?: 0.0,
            squareFeet = property.livingAreaSqFt ?: 0,
            yearBuilt = property.wholeNumber(PropertyField.YEAR_BUILT) ?: 0,
            lotSizeSqFt = property.wholeNumber(PropertyField.LOT_SIZE_SQFT) ?: 0,
            description = property.text(PropertyField.DESCRIPTION).orEmpty(),
            status = property.text(PropertyField.LISTING_STATUS) ?: "Active",
            primaryImageUrl = property.imageUrls.firstOrNull().orEmpty(),
            scannedAt = importedAtEpochMillis
        )

        return NormalizedPropertyBundle(
            property = entity,
            images = images,
            // Placeholders: no valuation is inferred from an imported listing (see the class KDoc).
            marketData = MarketDataEntity(
                propertyId = id,
                estimatedValue = 0.0,
                neighborhoodAppreciationRate = 0.0,
                medianAreaPrice = 0.0,
                averageDaysOnMarket = property.wholeNumber(PropertyField.DAYS_ON_MARKET) ?: 0,
                pricePerSqFt = property.decimal(PropertyField.PRICE_PER_SQFT) ?: 0.0,
                marketDemand = "Unknown"
            ),
            rentEstimate = RentEstimateEntity(
                propertyId = id,
                estimatedRent = 0.0,
                rentRangeLow = 0.0,
                rentRangeHigh = 0.0,
                rentConfidenceScore = 0.0,
                grossYield = 0.0
            ),
            taxRecord = TaxRecordEntity(
                propertyId = id,
                annualTaxAmount = property.decimal(PropertyField.ANNUAL_TAX_AMOUNT) ?: 0.0,
                assessmentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
                assessedValue = 0.0,
                taxDelinquent = false
            ),
            salesHistory = emptyList<SalesHistoryEntity>(),
            comps = emptyList<ComparablePropertyEntity>()
        )
    }

    /** Maps the layer's market classification onto the app's existing `sourceType` vocabulary. */
    private fun sourceTypeFor(property: CanonicalProperty): String {
        val status = property.text(PropertyField.LISTING_STATUS)?.lowercase().orEmpty()
        val sourceId = property.primarySourceId.lowercase()
        return when {
            sourceId.contains("wholesale") || status.contains("off-market") -> "OFF_MARKET"
            status.contains("foreclosure") || status.contains("reo") -> "FORECLOSURE"
            else -> "ON_MARKET"
        }
    }

    private companion object {
        const val MAX_IMAGES = 25
    }
}
