package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.AIConversationEntity
import com.example.data.local.entity.AIMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiChatDao {
    @Query("SELECT * FROM ai_conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<AIConversationEntity>>

    @Query("SELECT * FROM ai_conversations WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getConversationByPropertyId(propertyId: String): AIConversationEntity?

    @Query("SELECT * FROM ai_conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): AIConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conv: AIConversationEntity)

    @Query("UPDATE ai_conversations SET updatedAt = :timestamp WHERE id = :id")
    suspend fun updateTimestamp(id: String, timestamp: Long)

    @Query("SELECT * FROM ai_messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<AIMessageEntity>>

    @Insert
    suspend fun insertMessage(msg: AIMessageEntity)

    @Query("DELETE FROM ai_messages WHERE conversationId = :conversationId")
    suspend fun clearMessages(conversationId: String)
}
