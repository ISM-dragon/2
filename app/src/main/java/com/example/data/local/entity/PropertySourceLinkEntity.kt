package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "property_source_links",
    indices = [
        Index(value = ["propertyId"]),
        Index(value = ["sourceUrl"])
    ]
)
data class PropertySourceLinkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val propertyId: String,
    val source: String,
    val sourceUrl: String,
    val listingId: String? = null,
    val isPrimary: Boolean = true,
    val lastSyncedAt: Long
)
