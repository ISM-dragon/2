package com.example.data.adapter

import com.example.data.local.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Adapter providing realistic curated real estate seed data for MLS on-market testing
// Ready to be replaced or extended with a live MLS/IDX API endpoint
class OnMarketMlsAdapter : PropertySourceAdapter {
    override val sourceName: String = "MLS Feed (Demo Seed Dataset)"
    override val sourceType: String = "ON_MARKET"
    override val sourceId: String = PropertySourceDefaults.MLS_ID

    override suspend fun fetchProperties(
        query: String?,
        minPrice: Double?,
        maxPrice: Double?,
        limit: Int
    ): List<NormalizedPropertyBundle> = withContext(Dispatchers.IO) {
        val all = PropertySeedData.getSeedBundles().filter { it.property.sourceType == "ON_MARKET" }
        filterBundles(all, query, minPrice, maxPrice, limit)
    }
}

// Adapter providing realistic off-market & distressed wholesale seed data
// Ready to be replaced or extended with live Probate / County Recorder / ATTOM API endpoints
class OffMarketWholesaleAdapter : PropertySourceAdapter {
    override val sourceName: String = "Off-Market Wholesale (Demo Seed Dataset)"
    override val sourceType: String = "OFF_MARKET"
    override val sourceId: String = PropertySourceDefaults.WHOLESALE_ID

    override suspend fun fetchProperties(
        query: String?,
        minPrice: Double?,
        maxPrice: Double?,
        limit: Int
    ): List<NormalizedPropertyBundle> = withContext(Dispatchers.IO) {
        val all = PropertySeedData.getSeedBundles().filter { it.property.sourceType == "OFF_MARKET" }
        filterBundles(all, query, minPrice, maxPrice, limit)
    }
}

private fun filterBundles(
    bundles: List<NormalizedPropertyBundle>,
    query: String?,
    minPrice: Double?,
    maxPrice: Double?,
    limit: Int
): List<NormalizedPropertyBundle> {
    return bundles.filter { b ->
        val p = b.property
        val matchesQuery = query.isNullOrBlank() ||
                p.address.contains(query, ignoreCase = true) ||
                p.city.contains(query, ignoreCase = true) ||
                p.state.contains(query, ignoreCase = true) ||
                p.propertyType.contains(query, ignoreCase = true)

        val matchesMin = minPrice == null || p.price >= minPrice
        val matchesMax = maxPrice == null || p.price <= maxPrice

        matchesQuery && matchesMin && matchesMax
    }.take(limit)
}

object PropertySeedData {
    fun getSeedBundles(): List<NormalizedPropertyBundle> {
        val now = System.currentTimeMillis()

        return listOf(
            // Property 1: Austin Modern Duplex (On Market - Great Deal)
            createBundle(
                id = "prop-tx-001",
                sourceType = "ON_MARKET",
                title = "South Congress Cash Flow Duplex",
                address = "2418 S Congress Ave",
                city = "Austin",
                state = "TX",
                zipCode = "78704",
                lat = 30.2415,
                lng = -97.7551,
                price = 485000.0,
                type = "Multi-Family",
                beds = 4,
                baths = 3.0,
                sqft = 2250,
                year = 2017,
                lot = 6500,
                desc = "Turnkey duplex in prime high-demand rental corridor. Unit A fully rented at $2,100/mo, Unit B lease expires next month with $2,300/mo market potential.",
                img = "https://images.unsplash.com/photo-1568605117036-5fe5e7bab0b7?w=800",
                dealScore = 88,
                estRent = 4400.0,
                taxes = 5800.0,
                estValue = 530000.0,
                comps = listOf(
                    createComp("prop-tx-001", "2402 S Congress Ave", 515000.0, 4, 3.0, 2300, 0.1, "Sold Nov 2025"),
                    createComp("prop-tx-001", "310 Elizabeth St", 495000.0, 3, 2.5, 2100, 0.3, "Sold Jan 2026")
                )
            ),

            // Property 2: Phoenix Quadplex (Off Market - Wholesale High Cap Rate)
            createBundle(
                id = "prop-az-002",
                sourceType = "OFF_MARKET",
                title = "Central Phoenix Value-Add 4-Plex",
                address = "1820 E Thomas Rd",
                city = "Phoenix",
                state = "AZ",
                zipCode = "85016",
                lat = 33.4802,
                lng = -112.0425,
                price = 540000.0,
                type = "Multi-Family",
                beds = 8,
                baths = 4.0,
                sqft = 3600,
                year = 1985,
                lot = 9800,
                desc = "Direct from estate probate. 4 separate 2-bed/1-bath units. Rents currently under-market at $950/unit ($3,800/mo). Post cosmetic rehab market rent is $1,350/unit ($5,400/mo).",
                img = "https://images.unsplash.com/photo-1570129477492-45c003edd2be?w=800",
                dealScore = 92,
                estRent = 5400.0,
                taxes = 4200.0,
                estValue = 650000.0,
                comps = listOf(
                    createComp("prop-az-002", "1904 E Thomas Rd", 620000.0, 8, 4.0, 3550, 0.2, "Sold Dec 2025"),
                    createComp("prop-az-002", "1630 E Earll Dr", 590000.0, 6, 3.0, 3100, 0.4, "Sold Feb 2026")
                )
            ),

            // Property 3: Dallas Suburban Single Family (On Market)
            createBundle(
                id = "prop-tx-003",
                sourceType = "ON_MARKET",
                title = "Plano Executive Brick Ranch",
                address = "3712 Westwood Dr",
                city = "Dallas",
                state = "TX",
                zipCode = "75075",
                lat = 33.0198,
                lng = -96.7321,
                price = 375000.0,
                type = "Single Family",
                beds = 3,
                baths = 2.0,
                sqft = 1890,
                year = 2004,
                lot = 7200,
                desc = "Excellent school district, renovated kitchen with quartz counters, new roof (2024), long-term corporate tenant prospect.",
                img = "https://images.unsplash.com/photo-1600585154340-be6161a56a0c?w=800",
                dealScore = 79,
                estRent = 2850.0,
                taxes = 4600.0,
                estValue = 395000.0,
                comps = listOf(
                    createComp("prop-tx-003", "3688 Westwood Dr", 389000.0, 3, 2.0, 1920, 0.1, "Sold Jan 2026"),
                    createComp("prop-tx-003", "1410 Park Blvd", 395000.0, 3, 2.5, 2050, 0.5, "Sold Dec 2025")
                )
            ),

            // Property 4: Atlanta Brick Triplex (Off Market)
            createBundle(
                id = "prop-ga-004",
                sourceType = "OFF_MARKET",
                title = "Midtown Atlanta Historic Triplex",
                address = "842 Piedmont Ave NE",
                city = "Atlanta",
                state = "GA",
                zipCode = "30308",
                lat = 33.7782,
                lng = -84.3820,
                price = 590000.0,
                type = "Multi-Family",
                beds = 6,
                baths = 3.0,
                sqft = 3100,
                year = 1965,
                lot = 7000,
                desc = "Tired landlord retiring out of state. Minutes to Georgia Tech and Piedmont Park. Current gross rent $4,900/mo. Separate electrical meters.",
                img = "https://images.unsplash.com/photo-1580587771525-78b9dba3b914?w=800",
                dealScore = 85,
                estRent = 5200.0,
                taxes = 5100.0,
                estValue = 675000.0,
                comps = listOf(
                    createComp("prop-ga-004", "890 Piedmont Ave", 680000.0, 6, 3.5, 3200, 0.1, "Sold Oct 2025")
                )
            ),

            // Property 5: Houston Energy Corridor Townhouse (On Market)
            createBundle(
                id = "prop-tx-005",
                sourceType = "ON_MARKET",
                title = "Memorial Green Luxury Townhome",
                address = "12405 Memorial Dr",
                city = "Houston",
                state = "TX",
                zipCode = "77024",
                lat = 29.7712,
                lng = -95.5901,
                price = 320000.0,
                type = "Townhouse",
                beds = 3,
                baths = 2.5,
                sqft = 1750,
                year = 2012,
                lot = 2400,
                desc = "Gated community, attached 2-car garage, low HOA ($180/mo). Consistent occupancy with medical center professionals.",
                img = "https://images.unsplash.com/photo-1512917774080-9991f1c4c750?w=800",
                dealScore = 75,
                estRent = 2500.0,
                taxes = 3900.0,
                estValue = 340000.0,
                comps = listOf(
                    createComp("prop-tx-005", "12421 Memorial Dr", 335000.0, 3, 2.5, 1750, 0.1, "Sold Feb 2026")
                )
            ),

            // Property 6: Miami Edgewater Condo (On Market - Premium Yield)
            createBundle(
                id = "prop-fl-006",
                sourceType = "ON_MARKET",
                title = "Edgewater Bayview Residence",
                address = "1900 N Bayshore Dr #1402",
                city = "Miami",
                state = "FL",
                zipCode = "33132",
                lat = 25.7945,
                lng = -80.1884,
                price = 430000.0,
                type = "Condo",
                beds = 2,
                baths = 2.0,
                sqft = 1180,
                year = 2018,
                lot = 0,
                desc = "Panoramic bay views, floor-to-ceiling hurricane glass, luxury amenities. Flexible rental policy allowing 30-day minimum stays.",
                img = "https://images.unsplash.com/photo-1545324418-cc1a3fa10c00?w=800",
                dealScore = 81,
                estRent = 3600.0,
                taxes = 5400.0,
                estValue = 475000.0,
                comps = listOf(
                    createComp("prop-fl-006", "1900 N Bayshore Dr #1604", 455000.0, 2, 2.0, 1180, 0.0, "Sold Jan 2026")
                )
            )
        )
    }

    private fun createBundle(
        id: String,
        sourceType: String,
        title: String,
        address: String,
        city: String,
        state: String,
        zipCode: String,
        lat: Double,
        lng: Double,
        price: Double,
        type: String,
        beds: Int,
        baths: Double,
        sqft: Int,
        year: Int,
        lot: Int,
        desc: String,
        img: String,
        dealScore: Int,
        estRent: Double,
        taxes: Double,
        estValue: Double,
        comps: List<ComparablePropertyEntity>
    ): NormalizedPropertyBundle {
        val now = System.currentTimeMillis()
        val prop = PropertyEntity(
            id = id,
            sourceType = sourceType,
            title = title,
            address = address,
            city = city,
            state = state,
            zipCode = zipCode,
            latitude = lat,
            longitude = lng,
            price = price,
            propertyType = type,
            bedrooms = beds,
            bathrooms = baths,
            squareFeet = sqft,
            yearBuilt = year,
            lotSizeSqFt = lot,
            description = desc,
            status = if (dealScore >= 80) "Qualified" else "Active",
            primaryImageUrl = img,
            scannedAt = now,
            isSaved = false,
            isSavedDeal = dealScore >= 80,
            dealScore = dealScore
        )

        val imageEntities = listOf(
            PropertyImageEntity(propertyId = id, imageUrl = img, caption = "Front Exterior", isPrimary = true),
            PropertyImageEntity(propertyId = id, imageUrl = "https://images.unsplash.com/photo-1502672260266-1c1ef2d93688?w=800", caption = "Living Area", isPrimary = false),
            PropertyImageEntity(propertyId = id, imageUrl = "https://images.unsplash.com/photo-1556911220-e15b29be8c8f?w=800", caption = "Kitchen", isPrimary = false)
        )

        val market = MarketDataEntity(
            propertyId = id,
            estimatedValue = estValue,
            neighborhoodAppreciationRate = 5.8,
            medianAreaPrice = price * 1.05,
            averageDaysOnMarket = 24,
            pricePerSqFt = price / sqft,
            marketDemand = "High"
        )

        val rent = RentEstimateEntity(
            propertyId = id,
            estimatedRent = estRent,
            rentRangeLow = estRent * 0.92,
            rentRangeHigh = estRent * 1.10,
            rentConfidenceScore = 91.0,
            grossYield = (estRent * 12.0 / price) * 100.0
        )

        val tax = TaxRecordEntity(
            propertyId = id,
            annualTaxAmount = taxes,
            assessmentYear = 2025,
            assessedValue = price * 0.88,
            taxDelinquent = false
        )

        val history = listOf(
            SalesHistoryEntity(propertyId = id, date = "2021-04-15", price = price * 0.76, event = "Sold"),
            SalesHistoryEntity(propertyId = id, date = "2024-08-10", price = price * 1.04, event = "Price Change"),
            SalesHistoryEntity(propertyId = id, date = "2026-01-20", price = price, event = "Listed")
        )

        return NormalizedPropertyBundle(
            property = prop,
            images = imageEntities,
            marketData = market,
            rentEstimate = rent,
            taxRecord = tax,
            salesHistory = history,
            comps = comps
        )
    }

    private fun createComp(
        targetId: String,
        addr: String,
        price: Double,
        beds: Int,
        baths: Double,
        sqft: Int,
        dist: Double,
        date: String
    ): ComparablePropertyEntity {
        return ComparablePropertyEntity(
            targetPropertyId = targetId,
            compAddress = addr,
            compPrice = price,
            compBeds = beds,
            compBaths = baths,
            compSqFt = sqft,
            distanceMiles = dist,
            saleDate = date,
            adjustmentAmount = 0.0
        )
    }
}
