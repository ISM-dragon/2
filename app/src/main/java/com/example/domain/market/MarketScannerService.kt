package com.example.domain.market

import com.example.data.adapter.NormalizedPropertyBundle
import com.example.data.local.entity.*
import java.util.UUID

data class MarketCity(
    val id: String,
    val name: String,
    val stateOrCountry: String,
    val medianPrice: Double,
    val averageRent: Double,
    val taxRatePct: Double,
    val appreciationRatePct: Double,
    val demandLevel: String,
    val description: String,
    val coordinates: Pair<Double, Double>
)

object MarketScannerService {

    val SUPPORTED_MARKETS = listOf(
        MarketCity(
            id = "austin_tx",
            name = "Austin",
            stateOrCountry = "TX",
            medianPrice = 465000.0,
            averageRent = 2500.0,
            taxRatePct = 1.8,
            appreciationRatePct = 5.4,
            demandLevel = "Very High",
            description = "Major tech hub with robust job growth and strong tenant demand in suburban corridors.",
            coordinates = 30.2672 to -97.7431
        ),
        MarketCity(
            id = "dallas_tx",
            name = "Dallas - Fort Worth",
            stateOrCountry = "TX",
            medianPrice = 385000.0,
            averageRent = 2250.0,
            taxRatePct = 2.0,
            appreciationRatePct = 6.1,
            demandLevel = "High",
            description = "High-performing cash flow market with corporate headquarters relocations.",
            coordinates = 32.7767 to -96.7970
        ),
        MarketCity(
            id = "miami_fl",
            name = "Miami",
            stateOrCountry = "FL",
            medianPrice = 540000.0,
            averageRent = 3200.0,
            taxRatePct = 1.1,
            appreciationRatePct = 7.2,
            demandLevel = "Booming",
            description = "Premium international destination with zero state income tax and high luxury/condo demand.",
            coordinates = 25.7617 to -80.1918
        ),
        MarketCity(
            id = "atlanta_ga",
            name = "Atlanta",
            stateOrCountry = "GA",
            medianPrice = 395000.0,
            averageRent = 2300.0,
            taxRatePct = 1.3,
            appreciationRatePct = 5.8,
            demandLevel = "High",
            description = "Southeast logistics and entertainment capital with favorable landlord laws.",
            coordinates = 33.7490 to -84.3880
        ),
        MarketCity(
            id = "phoenix_az",
            name = "Phoenix",
            stateOrCountry = "AZ",
            medianPrice = 430000.0,
            averageRent = 2200.0,
            taxRatePct = 0.65,
            appreciationRatePct = 5.5,
            demandLevel = "High",
            description = "Rapidly expanding semiconductor manufacturing corridor and sunbelt migration.",
            coordinates = 33.4484 to -112.0740
        ),
        MarketCity(
            id = "riyadh_sa",
            name = "Riyadh",
            stateOrCountry = "Saudi Arabia",
            medianPrice = 360000.0,
            averageRent = 2400.0,
            taxRatePct = 0.0,
            appreciationRatePct = 8.5,
            demandLevel = "Extraordinary",
            description = "Rapidly growing metropolis, Vision 2030 major mega-projects, zero property tax, high yields.",
            coordinates = 24.7136 to 46.6753
        ),
        MarketCity(
            id = "dubai_uae",
            name = "Dubai",
            stateOrCountry = "UAE",
            medianPrice = 420000.0,
            averageRent = 2900.0,
            taxRatePct = 0.0,
            appreciationRatePct = 7.8,
            demandLevel = "Global Top Tier",
            description = "Zero income/property tax, investor golden visa incentives, high gross rental yields (7-9%).",
            coordinates = 25.2048 to 55.2708
        )
    )

    private val STREET_NAMES = listOf(
        "Grand Avenue", "Oakwood Ridge", "Highland Boulevard", "Sunset Crest",
        "Palmetto Way", "Canyon Creek Drive", "Metropolitan Parkway", "Emerald Terrace",
        "Falcon Ridge Lane", "Harbor View Drive"
    )

    private val PROPERTY_IMAGES = listOf(
        "https://images.unsplash.com/photo-1568605117036-5fe5e7bab0b7?w=800",
        "https://images.unsplash.com/photo-1570129477492-45c003edd2be?w=800",
        "https://images.unsplash.com/photo-1600585154340-be6161a56a0c?w=800",
        "https://images.unsplash.com/photo-1600596542815-ffad4c1539a9?w=800",
        "https://images.unsplash.com/photo-1580587771525-78b9dba3b914?w=800",
        "https://images.unsplash.com/photo-1512917774080-9991f1c4c750?w=800"
    )

    fun generateScannedMarketBundle(
        city: MarketCity,
        propertyType: String = "Single Family",
        index: Int = 1
    ): NormalizedPropertyBundle {
        val uniqueId = "prop-${city.id}-${index}-${UUID.randomUUID().toString().take(6)}"
        val streetNum = (1000 + (index * 137) % 8900)
        val streetName = STREET_NAMES[index % STREET_NAMES.size]
        val address = "$streetNum $streetName"

        // Realistic variations based on city median
        val priceMultiplier = when (index % 4) {
            0 -> 0.88 // Distressed / Below market
            1 -> 1.02 // At median
            2 -> 0.94 // Good value deal
            else -> 1.15 // Turnkey renovated
        }
        val price = Math.round(city.medianPrice * priceMultiplier / 1000.0) * 1000.0
        val isOffMarket = (index % 3 == 0)

        val beds = when (propertyType) {
            "Condo / Apartment" -> 2
            "Multi-Family" -> 4
            else -> if (price > city.medianPrice) 4 else 3
        }
        val baths = if (beds >= 4) 3.0 else 2.0
        val sqft = when (propertyType) {
            "Condo / Apartment" -> 1100 + (index * 80) % 400
            "Multi-Family" -> 2800 + (index * 150) % 800
            else -> 1600 + (index * 120) % 900
        }

        // Rent estimate roughly aligned with local yields
        val yieldTarget = if (isOffMarket) 0.0085 else 0.0072
        val baseRent = Math.round(price * yieldTarget / 50.0) * 50.0

        val dealScore = when {
            isOffMarket -> 82 + (index % 12)
            priceMultiplier < 0.95 -> 74 + (index % 10)
            else -> 65 + (index % 15)
        }

        val latJitter = ((index * 37) % 100 - 50) * 0.0012
        val lngJitter = ((index * 53) % 100 - 50) * 0.0012

        val property = PropertyEntity(
            id = uniqueId,
            sourceType = if (isOffMarket) "OFF_MARKET" else "ON_MARKET",
            title = "$address, ${city.name}",
            address = address,
            city = city.name,
            state = city.stateOrCountry,
            zipCode = if (city.stateOrCountry.length == 2) "7${5000 + (index * 111) % 4000}" else "11564",
            latitude = city.coordinates.first + latJitter,
            longitude = city.coordinates.second + lngJitter,
            price = price,
            propertyType = propertyType,
            bedrooms = beds,
            bathrooms = baths,
            squareFeet = sqft,
            yearBuilt = 2012 + (index % 11),
            lotSizeSqFt = if (propertyType.contains("Condo")) 0 else 5500 + index * 400,
            description = "Opportunity located in ${city.name}. ${city.description} Estimated strong cash-flow & appreciation trajectory.",
            dealScore = dealScore,
            status = "Active",
            primaryImageUrl = PROPERTY_IMAGES[index % PROPERTY_IMAGES.size],
            scannedAt = System.currentTimeMillis()
        )

        val rentEstimate = RentEstimateEntity(
            propertyId = uniqueId,
            estimatedRent = baseRent,
            rentRangeLow = baseRent * 0.92,
            rentRangeHigh = baseRent * 1.10,
            rentConfidenceScore = 91.0,
            grossYield = (baseRent * 12.0 / price) * 100.0
        )

        val marketData = MarketDataEntity(
            propertyId = uniqueId,
            estimatedValue = price * 1.08,
            neighborhoodAppreciationRate = city.appreciationRatePct,
            medianAreaPrice = city.medianPrice,
            averageDaysOnMarket = if (isOffMarket) 8 else 24,
            pricePerSqFt = price / sqft.coerceAtLeast(1),
            marketDemand = city.demandLevel
        )

        val taxRecord = TaxRecordEntity(
            propertyId = uniqueId,
            annualTaxAmount = price * (city.taxRatePct / 100.0),
            assessmentYear = 2025,
            assessedValue = price * 0.85
        )

        val images = listOf(
            PropertyImageEntity(
                propertyId = uniqueId,
                imageUrl = PROPERTY_IMAGES[index % PROPERTY_IMAGES.size],
                caption = "Exterior Front View"
            ),
            PropertyImageEntity(
                propertyId = uniqueId,
                imageUrl = PROPERTY_IMAGES[(index + 1) % PROPERTY_IMAGES.size],
                caption = "Living & Interior"
            )
        )

        return NormalizedPropertyBundle(
            property = property,
            images = images,
            marketData = marketData,
            rentEstimate = rentEstimate,
            taxRecord = taxRecord,
            salesHistory = emptyList(),
            comps = emptyList(),
            sourceId = if (isOffMarket) "Off-Market Network" else "MLS Feed",
            externalId = uniqueId
        )
    }
}
