package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.PropertyEntity

/**
 * Database-level identity guarantees for canonical properties.
 *
 * [insertNewProperty] never replaces: a conflicting id or canonical key aborts the statement, so a
 * row another writer just committed cannot be silently deleted (and its satellites cascaded away).
 *
 * The `repoint…` statements re-point every row that references a property, used when two property rows are merged into one.
 *
 * Each statement moves rows from [source] to [target]. Where a table has a uniqueness rule that
 * includes the property id, `UPDATE OR IGNORE` moves the rows that do not collide and leaves the
 * colliding ones on the source. The survivor's version of a colliding row wins, and the colliding
 * leftovers are removed when the source property is deleted (cascade) or by the explicit delete
 * below for tables without a foreign key.
 *
 * Nothing here deletes data that has no counterpart on the survivor. Tables without a unique rule
 * move as a whole.
 */
@Dao
interface PropertyIdentityDao {

    /**
     * Inserts a brand-new canonical row. Unlike the generic insert this aborts on a conflicting id or
     * canonical key, so the unique index is the final arbiter when two imports race.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNewProperty(property: PropertyEntity)

    // ── Canonical-property satellites ───────────────────────────────────────────────────────────

    @Query("UPDATE OR IGNORE property_provenance SET propertyId = :target WHERE propertyId = :source")
    suspend fun repointProvenance(source: String, target: String)

    @Query("UPDATE OR IGNORE property_enrichments SET propertyId = :target WHERE propertyId = :source")
    suspend fun repointEnrichments(source: String, target: String)

    /** Comps are unique per (target, address, sale date). */
    @Query("UPDATE OR IGNORE property_comps SET targetPropertyId = :target WHERE targetPropertyId = :source")
    suspend fun repointCompTargets(source: String, target: String)

    /** Comps where the source property was itself the comparable of another property. */
    @Query("UPDATE property_comps SET compPropertyId = :target WHERE compPropertyId = :source")
    suspend fun repointCompLinks(source: String, target: String)

    @Query("UPDATE OR IGNORE property_ai_analysis SET propertyId = :target WHERE propertyId = :source")
    suspend fun repointAiAnalysis(source: String, target: String)

    /** Analyses kept on the source because the target already had one; there is no foreign key. */
    @Query("DELETE FROM property_ai_analysis WHERE propertyId = :source")
    suspend fun deleteAiAnalysis(source: String)

    @Query("UPDATE property_source_links SET propertyId = :target WHERE propertyId = :source")
    suspend fun repointSourceLinks(source: String, target: String)

    // ── Financial work the user has done on the property ────────────────────────────────────────

    @Query("UPDATE financing_scenarios SET propertyId = :target WHERE propertyId = :source")
    suspend fun repointFinancingScenarios(source: String, target: String)

    @Query("UPDATE OR IGNORE saved_properties SET propertyId = :target WHERE propertyId = :source")
    suspend fun repointSavedProperties(source: String, target: String)

    @Query("UPDATE saved_deals SET propertyId = :target WHERE propertyId = :source")
    suspend fun repointSavedDeals(source: String, target: String)

    // ── Offers, conversations and automation: ids and history stay as they are ─────────────────

    @Query("UPDATE offers SET propertyId = :target WHERE propertyId = :source")
    suspend fun repointOffers(source: String, target: String)

    @Query("UPDATE ai_conversations SET propertyId = :target WHERE propertyId = :source")
    suspend fun repointConversations(source: String, target: String)

    /**
     * Only the property reference moves. The job's own key (`runId`) and its idempotency key are
     * left alone so an already-executed run cannot be replayed under a new key.
     */
    @Query("UPDATE automation_jobs SET propertyId = :target WHERE propertyId = :source")
    suspend fun repointAutomationJobs(source: String, target: String)
}
