package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "source_health")
data class SourceHealthEntity(
    @PrimaryKey
    val source: String,
    val successCount: Int,
    val failureCount: Int,
    val successRate: Double,
    val failureRate: Double,
    val averageLatencyMs: Long,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
    val parserVersion: String,
    val healthStatus: String,
    val lastErrorReason: String?
)
