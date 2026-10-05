package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "properties")
data class PropertyEntity(
    @PrimaryKey
    val id: String,
    val sourceType: String, // "ON_MARKET", "OFF_MARKET", "WHOLESALE", "FORECLOSURE"
    val title: String,
    val address: String,
    val city: String,
    val state: String,
    val zipCode: String,
    val latitude: Double,
    val longitude: Double,
    val price: Double,
    val propertyType: String, // "Single Family", "Multi-Family", "Condo", "Townhouse", "Commercial"
    val bedrooms: Int,
    val bathrooms: Double,
    val squareFeet: Int,
    val yearBuilt: Int,
    val lotSizeSqFt: Int,
    val description: String,
    val status: String, // "Active", "Pending", "Off-Market", "Qualified"
    val primaryImageUrl: String,
    val scannedAt: Long,
    val isSaved: Boolean = false,
    val isSavedDeal: Boolean = false,
    val dealScore: Int = 0 // 0 to 100
)

@Entity(tableName = "property_images")
data class PropertyImageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val propertyId: String,
    val imageUrl: String,
    val caption: String = "",
    val isPrimary: Boolean = false
)

@Entity(tableName = "market_data")
data class MarketDataEntity(
    @PrimaryKey
    val propertyId: String,
    val estimatedValue: Double,
    val neighborhoodAppreciationRate: Double,
    val medianAreaPrice: Double,
    val averageDaysOnMarket: Int,
    val pricePerSqFt: Double,
    val marketDemand: String // "High", "Moderate", "Balanced", "Buyer's Market"
)

@Entity(tableName = "rent_estimates")
data class RentEstimateEntity(
    @PrimaryKey
    val propertyId: String,
    val estimatedRent: Double,
    val rentRangeLow: Double,
    val rentRangeHigh: Double,
    val rentConfidenceScore: Double,
    val grossYield: Double
)

@Entity(tableName = "tax_records")
data class TaxRecordEntity(
    @PrimaryKey
    val propertyId: String,
    val annualTaxAmount: Double,
    val assessmentYear: Int,
    val assessedValue: Double,
    val taxDelinquent: Boolean = false
)

@Entity(tableName = "sales_history")
data class SalesHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val propertyId: String,
    val date: String,
    val price: Double,
    val event: String
)

@Entity(tableName = "comparable_properties")
data class ComparablePropertyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val targetPropertyId: String,
    val compAddress: String,
    val compPrice: Double,
    val compBeds: Int,
    val compBaths: Double,
    val compSqFt: Int,
    val distanceMiles: Double,
    val saleDate: String,
    val adjustmentAmount: Double = 0.0
)
