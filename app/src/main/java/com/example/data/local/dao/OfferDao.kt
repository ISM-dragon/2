package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.OfferDocumentEntity
import com.example.data.local.entity.OfferEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OfferDao {
    @Query("SELECT * FROM offers ORDER BY createdAt DESC")
    fun getAllOffers(): Flow<List<OfferEntity>>

    @Query("SELECT * FROM offers WHERE status = :status ORDER BY createdAt DESC")
    fun getOffersByStatus(status: String): Flow<List<OfferEntity>>

    @Query("SELECT * FROM offers WHERE id = :id LIMIT 1")
    fun getOfferByIdFlow(id: String): Flow<OfferEntity?>

    @Query("SELECT * FROM offers WHERE id = :id LIMIT 1")
    suspend fun getOfferById(id: String): OfferEntity?

    @Query("SELECT * FROM offers WHERE propertyId = :propertyId ORDER BY createdAt DESC")
    fun getOffersForProperty(propertyId: String): Flow<List<OfferEntity>>

    @Query("SELECT COUNT(*) FROM offers")
    fun getGeneratedOffersCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM offers WHERE status IN ('SENT', 'OPENED', 'SIGNED')")
    fun getSentOffersCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM offers")
    suspend fun getGeneratedOffersCount(): Int

    @Query("SELECT COUNT(*) FROM offers WHERE status IN ('SENT', 'OPENED', 'SIGNED')")
    suspend fun getSentOffersCount(): Int

    @Query("SELECT COUNT(*) FROM offers WHERE status = 'FAILED'")
    fun getFailedOffersCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM offers WHERE status = 'FAILED'")
    suspend fun getFailedOffersCount(): Int

    @Query("SELECT * FROM offers WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getOfferByPropertyId(propertyId: String): OfferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOffer(offer: OfferEntity)

    @Update
    suspend fun updateOffer(offer: OfferEntity)

    @Query("UPDATE offers SET status = :status, sentAt = :sentAt, lastError = :error WHERE id = :id")
    suspend fun updateOfferStatus(id: String, status: String, sentAt: Long?, error: String?)

    @Query("DELETE FROM offers WHERE id = :id")
    suspend fun deleteOffer(id: String)

    // Documents
    @Query("SELECT * FROM offer_documents WHERE offerId = :offerId")
    fun getDocumentsForOffer(offerId: String): Flow<List<OfferDocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOfferDocument(document: OfferDocumentEntity)

    @Query("SELECT * FROM offers")
    suspend fun getAllOffersList(): List<OfferEntity>

    @Query("SELECT * FROM offer_documents")
    suspend fun getAllDocumentsList(): List<OfferDocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOffers(offers: List<OfferEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOfferDocuments(docs: List<OfferDocumentEntity>)
}
