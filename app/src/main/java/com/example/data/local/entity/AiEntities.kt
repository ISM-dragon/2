package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI conversation. `propertyId` is a nullable soft relationship: conversations survive the deletion
 * of the property they were started from (the FK nulls the column instead of dropping the thread).
 */
@Entity(
    tableName = "ai_conversations",
    indices = [
        Index(value = ["propertyId"]),
        Index(value = ["updatedAt"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = PropertyEntity::class,
            parentColumns = ["id"],
            childColumns = ["propertyId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class AIConversationEntity(
    @PrimaryKey
    val id: String,
    val propertyId: String?,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "ai_messages",
    indices = [
        Index(value = ["conversationId", "timestamp"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = AIConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AIMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: String,
    val role: String, // "user", "model", "system"
    val content: String,
    val timestamp: Long
)
