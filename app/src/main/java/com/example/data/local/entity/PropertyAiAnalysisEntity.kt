package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "property_ai_analysis")
data class PropertyAiAnalysisEntity(
    @PrimaryKey
    val propertyId: String,
    val summary: String,
    val investmentThesis: String,
    val strengthsJson: String,
    val weaknessesJson: String,
    val risksJson: String,
    val redFlagsJson: String,
    val recommendedStrategy: String,
    val recommendedOfferRange: String,
    val questionsForSellerJson: String,
    val dueDiligenceJson: String,
    val confidence: Double,
    val evidenceJson: String,
    val analyzedAt: Long
)
