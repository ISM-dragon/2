package com.example.data.urlintelligence

import com.example.data.adapter.NormalizedPropertyBundle
import com.example.data.local.entity.ComparablePropertyEntity
import com.example.data.local.entity.MarketDataEntity
import com.example.data.local.entity.PropertyEntity
import com.example.data.local.entity.PropertyImageEntity
import com.example.data.local.entity.RentEstimateEntity
import com.example.data.local.entity.SalesHistoryEntity
import com.example.data.local.entity.TaxRecordEntity
import com.example.urlintelligence.model.CanonicalListingStatus
import com.example.urlintelligence.model.CanonicalProperty
import com.example.urlintelligence.model.CanonicalPropertyType
import com.example.urlintelligence.model.PropertyField
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Bridge between the platform-agnostic URL-intelligence module and the app's
 * Room-backed domain model.
 *
 * It lives in `:app` on purpose: the module never learns about Room, Android or
 * the app's table layout, and the app never has to know how a source parsed its
 * markup. Nothing here touches the UI or the financial engines.
 */
object CanonicalPropertyMapper {

    fun toBundle(
        property: CanonicalProperty,
        dealScore: Int = 0,
        isSaved: Boolean = false
    ): NormalizedPropertyBundle {
        val id = property.canonicalId

        val entity = PropertyEntity(
            id = id,
            sourceType = sourceTypeOf(property),
            title = titleOf(property),
            address = addressLineOf(property),
            city = property.address.city.orEmpty(),
            state = property.address.stateOrProvince.orEmpty(),
            zipCode = property.address.postalCode.orEmpty(),
            latitude = property.address.geo?.latitude ?: 0.0,
            longitude = property.address.geo?.longitude ?: 0.0,
            price = property.listPriceUsd ?: 0.0,
            propertyType = propertyTypeLabel(property.propertyType),
            bedrooms = (property.bedrooms ?: 0.0).roundToInt(),
            bathrooms = property.bathrooms ?: 0.0,
            squareFeet = property.livingAreaSqFt ?: 0,
            yearBuilt = property.yearBuilt ?: 0,
            lotSizeSqFt = property.lotSizeSqFt ?: 0,
            description = property.description.orEmpty(),
            status = statusLabel(property.listingStatus),
            primaryImageUrl = property.primaryImageUrl.orEmpty(),
            scannedAt = if (property.fetchedAtEpochMillis > 0L) {
                property.fetchedAtEpochMillis
            } else {
                System.currentTimeMillis()
            },
            isSaved = isSaved,
            isSavedDeal = false,
            dealScore = dealScore.coerceIn(0, 100)
        )

        val images = property.imageUrls.mapIndexed { index, url ->
            PropertyImageEntity(
                propertyId = id,
                imageUrl = url,
                caption = if (index == 0) "Primary" else "Photo ${index + 1}",
                isPrimary = index == 0
            )
        }

        val rent = property.estimatedMonthlyRentUsd
        val price = property.listPriceUsd
        val rentConfidence = property.provenance[PropertyField.ESTIMATED_MONTHLY_RENT]
            ?.confidence
            ?.times(100.0)
            ?: 0.0

        val marketData = MarketDataEntity(
            propertyId = id,
            estimatedValue = price ?: 0.0,
            neighborhoodAppreciationRate = 0.0,
            medianAreaPrice = price ?: 0.0,
            averageDaysOnMarket = property.daysOnMarket ?: 0,
            pricePerSqFt = property.pricePerSqFtUsd ?: 0.0,
            marketDemand = marketDemandLabel(property)
        )

        val rentEstimate = RentEstimateEntity(
            propertyId = id,
            estimatedRent = rent ?: 0.0,
            rentRangeLow = rent?.times(0.92) ?: 0.0,
            rentRangeHigh = rent?.times(1.10) ?: 0.0,
            rentConfidenceScore = rentConfidence,
            grossYield = if (rent != null && price != null && price > 0.0) {
                (rent * 12.0 / price) * 100.0
            } else {
                0.0
            }
        )

        val taxRecord = TaxRecordEntity(
            propertyId = id,
            annualTaxAmount = property.annualPropertyTaxUsd ?: 0.0,
            assessmentYear = Calendar.getInstance().get(Calendar.YEAR),
            assessedValue = (price ?: 0.0) * 0.9,
            taxDelinquent = false
        )

        return NormalizedPropertyBundle(
            property = entity,
            images = images,
            marketData = marketData,
            rentEstimate = rentEstimate,
            taxRecord = taxRecord,
            salesHistory = emptyList<SalesHistoryEntity>(),
            comps = emptyList<ComparablePropertyEntity>()
        )
    }

    /**
     * Human readable provenance summary, safe to show in a details screen or log.
     *
     * Each line names the source, the extractor, the parser version and how well the value is
     * verified — so "parser verified" is never displayed as "verified against the provider".
     */
    fun provenanceSummary(property: CanonicalProperty): String =
        property.provenance.entries().entries
            .sortedBy { it.key.name }
            .joinToString(separator = "\n") { (field, provenance) ->
                "${field.stableName}: ${provenance.sourceId} (${provenance.method}, " +
                    "${provenance.parserRef}, confidence=${"%.2f".format(provenance.confidence)}, " +
                    "verification=${provenance.verification})"
            }

    /** One-line, user-safe statement of what was actually verified for this property. */
    fun verificationSummary(property: CanonicalProperty): String = property.verification.describe()

    fun sourceTypeOf(property: CanonicalProperty): String = when (property.listingStatus) {
        CanonicalListingStatus.FOR_SALE,
        CanonicalListingStatus.FOR_RENT,
        CanonicalListingStatus.PENDING,
        CanonicalListingStatus.AUCTION -> "ON_MARKET"
        CanonicalListingStatus.SOLD,
        CanonicalListingStatus.OFF_MARKET,
        CanonicalListingStatus.UNKNOWN -> "OFF_MARKET"
    }

    fun statusLabel(status: CanonicalListingStatus): String = when (status) {
        CanonicalListingStatus.FOR_SALE -> "Active"
        CanonicalListingStatus.FOR_RENT -> "Active"
        CanonicalListingStatus.PENDING -> "Pending"
        CanonicalListingStatus.SOLD -> "Sold"
        CanonicalListingStatus.AUCTION -> "Auction"
        CanonicalListingStatus.OFF_MARKET -> "Off-Market"
        CanonicalListingStatus.UNKNOWN -> "Unknown"
    }

    fun propertyTypeLabel(type: CanonicalPropertyType): String = when (type) {
        CanonicalPropertyType.SINGLE_FAMILY -> "Single Family"
        CanonicalPropertyType.MULTI_FAMILY -> "Multi-Family"
        CanonicalPropertyType.APARTMENT -> "Multi-Family"
        CanonicalPropertyType.CONDO -> "Condo"
        CanonicalPropertyType.TOWNHOUSE -> "Townhouse"
        CanonicalPropertyType.LAND -> "Land"
        CanonicalPropertyType.MANUFACTURED -> "Manufactured"
        CanonicalPropertyType.COMMERCIAL -> "Commercial"
        CanonicalPropertyType.UNKNOWN -> "Other"
    }

    private fun titleOf(property: CanonicalProperty): String {
        val formatted = property.address.formatted
        if (formatted.isNotBlank()) return formatted
        val description = property.description?.take(60)?.trim()
        if (!description.isNullOrBlank()) return description
        return "Imported property"
    }

    private fun addressLineOf(property: CanonicalProperty): String {
        val line = property.address.line1.orEmpty()
        val unit = property.address.unit
        return if (unit.isNullOrBlank()) line else "$line Unit $unit"
    }

    private fun marketDemandLabel(property: CanonicalProperty): String {
        val days = property.daysOnMarket ?: return "Unknown"
        return when {
            days <= 15 -> "High"
            days <= 45 -> "Moderate"
            days <= 120 -> "Balanced"
            else -> "Buyer's Market"
        }
    }
}
