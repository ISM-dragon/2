package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_conversations")
data class AIConversationEntity(
    @PrimaryKey
    val id: String,
    val propertyId: String?,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "ai_messages")
data class AIMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: String,
    val role: String, // "user", "model", "system"
    val content: String,
    val timestamp: Long
)
