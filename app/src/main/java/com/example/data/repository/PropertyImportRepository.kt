package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.adapter.NormalizedPropertyBundle
import com.example.data.local.AppDatabase
import com.example.data.local.dao.*
import com.example.data.local.entity.*
import com.example.domain.property.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/** Outcome of importing a single source record. */
enum class ImportOutcome { INSERTED, UPDATED, MERGED, SKIPPED }

data class ImportResult(
    val propertyId: String,
    val outcome: ImportOutcome,
    val strategy: DedupStrategy,
    val confidence: Double,
    val reason: String,
    /** The canonical row after the merge; callers should key every later step off this. */
    val property: PropertyEntity
)

data class ImportSummary(
    val jobId: String?,
    val sourceId: String,
    val status: String,
    val fetched: Int,
    val inserted: Int,
    val updated: Int,
    val merged: Int,
    val skipped: Int,
    val failed: Int
) {
    /** Records that matched an existing canonical property instead of creating a new one. */
    val deduplicated: Int get() = updated + merged
}

data class ReconcileSummary(
    val scanned: Int,
    val keyed: Int,
    val mergedDuplicates: Int,
    val reasons: List<String>
)

/**
 * Owns ingestion, provenance and deduplication for the property data layer.
 *
 * Every import runs inside a single Room transaction per record: the canonical row, its provenance
 * row, its satellites and the import job counters either all land or none of them do. Rows that
 * lose a dedup merge are re-pointed child by child before being deleted, so no history is lost
 * silently.
 */
class PropertyImportRepository(
    private val database: AppDatabase,
    private val propertyDao: PropertyDao,
    private val sourceDao: PropertySourceDao,
    private val enrichmentDao: PropertyEnrichmentDao,
    private val financialDao: PropertyFinancialDao,
    /** Underwriting runs and financing scenarios that must follow a merged property. */
    private val analysisDao: FinancialDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { "imp-" + UUID.randomUUID().toString().take(12) }
) {

    fun observeSources(): Flow<List<PropertySourceEntity>> = sourceDao.observeSources()

    fun observeRecentJobs(limit: Int = 50): Flow<List<PropertyImportJobEntity>> =
        sourceDao.observeRecentJobs(limit)

    // ── Sources ─────────────────────────────────────────────────────────────────────────────────

    /** Idempotent: rows already present (including edited ones) are left untouched. */
    suspend fun seedDefaultSources() = withContext(Dispatchers.IO) {
        val now = clock()
        database.withTransaction {
            defaultSources(now).forEach { source -> sourceDao.insertSourceIfAbsent(source) }
        }
    }

    private fun defaultSources(now: Long): List<PropertySourceEntity> = listOf(
        PropertySourceEntity(
            id = PropertySourceDefaults.MLS_ID,
            name = "MLS Feed (demo seed dataset)",
            sourceKind = PropertySourceKind.MLS,
            adapterKey = "mls.demo.v1",
            description = "On-market listings delivered by the bundled MLS adapter.",
            priority = 10,
            requiresAttribution = true,
            attributionText = "Listing data courtesy of the MLS.",
            refreshIntervalMinutes = 15,
            createdAt = now,
            updatedAt = now
        ),
        PropertySourceEntity(
            id = PropertySourceDefaults.COUNTY_RECORDS_ID,
            name = "County Public Records",
            sourceKind = PropertySourceKind.PUBLIC_RECORDS,
            adapterKey = "county.records.v1",
            description = "Assessor / recorder facts (taxes, deeds, liens).",
            priority = 20,
            licenseNotes = "Public domain data.",
            refreshIntervalMinutes = 1440,
            createdAt = now,
            updatedAt = now
        ),
        PropertySourceEntity(
            id = PropertySourceDefaults.WHOLESALE_ID,
            name = "Off-Market Wholesale Feed",
            sourceKind = PropertySourceKind.WHOLESALER,
            adapterKey = "wholesale.demo.v1",
            description = "Off-market and distressed inventory from the wholesale adapter.",
            priority = 40,
            refreshIntervalMinutes = 60,
            createdAt = now,
            updatedAt = now
        ),
        PropertySourceEntity(
            id = PropertySourceDefaults.INTERNAL_ID,
            name = "Internal Seed Dataset",
            sourceKind = PropertySourceKind.INTERNAL,
            adapterKey = "internal.seed.v1",
            description = "Content shipped with the app for offline demos.",
            priority = 90,
            createdAt = now,
            updatedAt = now
        ),
        PropertySourceEntity(
            id = PropertySourceDefaults.MANUAL_ID,
            name = "Manual Entry",
            sourceKind = PropertySourceKind.USER_ENTERED,
            adapterKey = "manual.entry.v1",
            description = "Properties added by the investor from the Discover screen.",
            priority = 5,
            createdAt = now,
            updatedAt = now
        )
    )

    private suspend fun sourcePriority(sourceId: String?): Int =
        sourceId?.let { sourceDao.getSourceById(it)?.priority } ?: FALLBACK_PRIORITY

    /** Falls back to the internal source when a feed references an unknown source row. */
    private suspend fun resolveSourceId(requested: String?): String {
        val candidate = requested?.takeIf { it.isNotBlank() } ?: PropertySourceDefaults.INTERNAL_ID
        return if (sourceDao.getSourceById(candidate) != null) candidate else PropertySourceDefaults.INTERNAL_ID
    }

    // ── Import jobs ─────────────────────────────────────────────────────────────────────────────

    suspend fun startJob(
        sourceId: String,
        triggerKind: String,
        jobId: String = idFactory()
    ): PropertyImportJobEntity = withContext(Dispatchers.IO) {
        val now = clock()
        val job = PropertyImportJobEntity(
            id = jobId,
            sourceId = sourceId,
            status = PropertyImportStatus.RUNNING,
            triggerKind = triggerKind,
            startedAt = now,
            createdAt = now,
            updatedAt = now
        )
        database.withTransaction {
            sourceDao.upsertJob(job)
            sourceDao.updateSyncStatus(sourceId, now, PropertySourceSyncStatus.RUNNING, null)
        }
        job
    }

    suspend fun finishJob(
        job: PropertyImportJobEntity,
        status: String,
        fetched: Int,
        inserted: Int,
        updated: Int,
        merged: Int,
        skipped: Int,
        failed: Int,
        error: String? = null,
        cursor: String? = null
    ) = withContext(Dispatchers.IO) {
        val now = clock()
        database.withTransaction {
            sourceDao.finishJob(
                id = job.id,
                status = status,
                finishedAt = now,
                cursor = cursor,
                fetched = fetched,
                inserted = inserted,
                updated = updated,
                merged = merged,
                skipped = skipped,
                failed = failed,
                error = error
            )
            val sourceStatus = when {
                status == PropertyImportStatus.FAILED -> PropertySourceSyncStatus.FAILED
                status == PropertyImportStatus.PARTIAL -> PropertySourceSyncStatus.PARTIAL
                else -> PropertySourceSyncStatus.SUCCESS
            }
            sourceDao.updateSyncStatus(job.sourceId, now, sourceStatus, error)
        }
    }

    /**
     * Runs a full import cycle for one source: one job row, one import per record, aggregated
     * counters. Fetch failures mark the job FAILED without touching existing data.
     */
    suspend fun runImport(
        sourceId: String,
        triggerKind: String,
        fetch: suspend () -> List<NormalizedPropertyBundle>,
        cursor: String? = null
    ): ImportSummary = withContext(Dispatchers.IO) {
        val resolvedSource = resolveSourceId(sourceId)
        val job = startJob(resolvedSource, triggerKind)
        var fetched = 0
        var inserted = 0
        var updated = 0
        var merged = 0
        var skipped = 0
        var failed = 0
        var failure: String? = null
        try {
            val bundles = fetch()
            fetched = bundles.size
            for (bundle in bundles) {
                val result = importBundle(bundle.copy(sourceId = resolvedSource), job.id)
                when (result.outcome) {
                    ImportOutcome.INSERTED -> inserted++
                    ImportOutcome.UPDATED -> updated++
                    ImportOutcome.MERGED -> merged++
                    ImportOutcome.SKIPPED -> skipped++
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed++
            failure = "Property import failed."
        }
        val status = when {
            failure != null -> PropertyImportStatus.FAILED
            failed > 0 || skipped > 0 -> PropertyImportStatus.PARTIAL
            else -> PropertyImportStatus.COMPLETED
        }
        finishJob(job, status, fetched, inserted, updated, merged, skipped, failed, failure, cursor)
        ImportSummary(job.id, resolvedSource, status, fetched, inserted, updated, merged, skipped, failed)
    }

    // ── Single record import ────────────────────────────────────────────────────────────────────

    /**
     * Imports one normalized record. See [PropertyDeduplicator] for the matching ladder; this
     * function is the only place that writes canonical property rows.
     */
    suspend fun importBundle(
        bundle: NormalizedPropertyBundle,
        jobId: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val now = clock()
        val sourceId = resolveSourceId(bundle.sourceId)
        val incomingPriority = sourcePriority(sourceId)
        val incomingEntity = PropertyMapper.canonicalized(bundle.property)
        val externalId = bundle.externalId.ifBlank { incomingEntity.id }

        database.withTransaction {
            val provenance = sourceDao.findProvenance(sourceId, externalId)
            val canonicalIncoming = PropertyMapper.toCanonical(incomingEntity)

            if (provenance != null) {
                // Level 1: the exact source record was already imported.
                val existing = propertyDao.getPropertyById(provenance.propertyId)
                if (existing != null) {
                    val updated = mergeIntoExisting(existing, incomingEntity, canonicalIncoming, incomingPriority, now)
                    propertyDao.updateProperty(updated)
                    writeSatellites(updated.id, bundle)
                    touchProvenance(existing.id, sourceId, externalId, bundle, jobId, now, makePrimary = false)
                    return@withTransaction ImportResult(
                        propertyId = updated.id,
                        outcome = ImportOutcome.UPDATED,
                        strategy = DedupStrategy.EXACT_SOURCE_RECORD,
                        confidence = 1.0,
                        reason = "source record $sourceId/$externalId refreshed",
                        property = updated
                    )
                }
            }

            val decision = decideDedup(canonicalIncoming)
            if (decision.matched && decision.matchedPropertyId != null) {
                val existing = propertyDao.getPropertyById(decision.matchedPropertyId)
                if (existing != null) {
                    val updated = mergeIntoExisting(existing, incomingEntity, canonicalIncoming, incomingPriority, now)
                    propertyDao.updateProperty(updated)
                    writeSatellites(updated.id, bundle)
                    touchProvenance(
                        propertyId = updated.id,
                        sourceId = sourceId,
                        externalId = externalId,
                        bundle = bundle,
                        jobId = jobId,
                        now = now,
                        makePrimary = decision.strategy != DedupStrategy.FUZZY_ADDRESS &&
                            updated.primarySourceId != sourceId && incomingPriority < sourcePriority(updated.primarySourceId)
                    )
                    return@withTransaction ImportResult(
                        propertyId = updated.id,
                        outcome = if (decision.strategy == DedupStrategy.CANONICAL_KEY) {
                            ImportOutcome.UPDATED
                        } else {
                            ImportOutcome.MERGED
                        },
                        strategy = decision.strategy,
                        confidence = decision.confidence,
                        reason = decision.reason,
                        property = updated
                    )
                }
            }

            // New canonical property.
            val entity = incomingEntity.copy(
                primarySourceId = sourceId,
                listingStatusUpdatedAt = if (incomingEntity.listingStatusUpdatedAt == 0L) now else incomingEntity.listingStatusUpdatedAt,
                lastVerifiedAt = if (incomingEntity.lastVerifiedAt == 0L) now else incomingEntity.lastVerifiedAt
            )
            propertyDao.insertProperty(entity)
            writeSatellites(entity.id, bundle)
            touchProvenance(entity.id, sourceId, externalId, bundle, jobId, now, makePrimary = true)
            ImportResult(
                propertyId = entity.id,
                outcome = ImportOutcome.INSERTED,
                strategy = DedupStrategy.NONE,
                confidence = 0.0,
                reason = decision.reason,
                property = entity
            )
        }
    }

    /** Builds the candidate set and asks the pure deduplicator for a decision. */
    private suspend fun decideDedup(incoming: CanonicalProperty): DedupDecision {
        val candidates = LinkedHashMap<String, PropertyEntity>()
        incoming.canonicalKey?.takeIf { it.isNotBlank() }?.let { key ->
            propertyDao.getPropertyByCanonicalKey(key)?.let { candidates[it.id] = it }
        }
        if (incoming.apn.isNotBlank()) {
            propertyDao.findPropertiesByApn(incoming.apn).forEach { candidates.putIfAbsent(it.id, it) }
        }
        if (incoming.mlsNumber.isNotBlank()) {
            propertyDao.findPropertiesByMlsNumber(incoming.mlsNumber).forEach { candidates.putIfAbsent(it.id, it) }
        }
        propertyDao.getKeylessCandidates(
            zipCode = incoming.address.zip5,
            city = incoming.address.city,
            limit = KEYLESS_CANDIDATE_LIMIT
        ).forEach { candidates.putIfAbsent(it.id, it) }

        return PropertyDeduplicator.decide(
            incoming = PropertyMapper.toDedupInput(incoming),
            candidates = candidates.values.map { PropertyMapper.toDedupInput(it) }
        )
    }

    private suspend fun mergeIntoExisting(
        existing: PropertyEntity,
        incoming: PropertyEntity,
        canonicalIncoming: CanonicalProperty,
        incomingPriority: Int,
        now: Long
    ): PropertyEntity {
        val existingCanonical = PropertyMapper.toCanonical(existing)
        val existingPriority = sourcePriority(existing.primarySourceId)
        val incomingWins = incomingPriority <= existingPriority
        val merged = PropertyMergePolicy.merge(
            existing = existingCanonical,
            existingSourcePriority = existingPriority,
            incoming = canonicalIncoming,
            incomingSourcePriority = incomingPriority
        ).merged
        val applied = PropertyMapper.applyTo(existing, merged)
        return applied.copy(
            // UI owned columns are never rewritten by an import.
            isSaved = existing.isSaved,
            isSavedDeal = existing.isSavedDeal,
            dealScore = existing.dealScore,
            scannedAt = existing.scannedAt,
            title = existing.title.ifBlank { incoming.title },
            description = existing.description.ifBlank { incoming.description },
            primaryImageUrl = existing.primaryImageUrl.ifBlank { incoming.primaryImageUrl },
            sourceType = existing.sourceType,
            status = if (incomingWins && incoming.status.isNotBlank()) incoming.status else existing.status,
            propertyType = if (incomingWins && incoming.propertyType.isNotBlank()) {
                incoming.propertyType
            } else {
                existing.propertyType
            },
            canonicalKey = existing.canonicalKey ?: incoming.canonicalKey,
            // Promotion of the primary source is owned by touchProvenance so the property row and
            // the provenance flag can never disagree.
            primarySourceId = existing.primarySourceId ?: incoming.primarySourceId,
            lastVerifiedAt = maxOf(existing.lastVerifiedAt, now)
        )
    }

    private suspend fun writeSatellites(propertyId: String, bundle: NormalizedPropertyBundle) {
        if (bundle.images.isNotEmpty()) {
            propertyDao.deleteImagesByPropertyId(propertyId)
            propertyDao.insertImages(bundle.images.map { it.copy(id = 0, propertyId = propertyId) })
        }
        propertyDao.insertMarketData(bundle.marketData.copy(propertyId = propertyId))
        propertyDao.insertRentEstimate(bundle.rentEstimate.copy(propertyId = propertyId))
        propertyDao.insertTaxRecord(bundle.taxRecord.copy(propertyId = propertyId))
        if (bundle.salesHistory.isNotEmpty()) {
            propertyDao.deleteSalesHistoryByPropertyId(propertyId)
            propertyDao.insertSalesHistory(bundle.salesHistory.map { it.copy(id = 0, propertyId = propertyId) })
        }
        upsertComps(propertyId, bundle.comps)
        bundle.financials?.let { financialDao.upsert(it.copy(propertyId = propertyId)) }
        upsertEnrichments(propertyId, bundle.enrichments)
    }

    /**
     * Comps are deduplicated on (targetPropertyId, compAddress, saleDate) - the addresses are
     * canonicalized first so "2402 South Congress Avenue" and "2402 S Congress Ave" collapse.
     */
    private suspend fun upsertComps(propertyId: String, comps: List<PropertyCompEntity>) {
        for (comp in comps) {
            val label = UsPropertyNormalizer.canonicalStreetLabel(comp.compAddress).ifBlank { comp.compAddress }
            val existing = propertyDao.findComp(propertyId, label, comp.saleDate)
            val pricePerSqFt = if (comp.compSqFt > 0) comp.compPrice / comp.compSqFt else comp.pricePerSqFt
            val toWrite = comp.copy(
                id = existing?.id ?: 0,
                targetPropertyId = propertyId,
                compAddress = label,
                compCity = UsPropertyNormalizer.normalizeWhitespace(comp.compCity),
                compState = UsPropertyNormalizer.normalizeStateCode(comp.compState),
                compStatus = comp.compStatus.ifBlank { "SOLD" },
                pricePerSqFt = pricePerSqFt,
                adjustedPrice = if (comp.adjustedPrice != 0.0) comp.adjustedPrice else comp.compPrice + comp.adjustmentAmount
            )
            if (existing == null) propertyDao.insertComp(toWrite) else propertyDao.updateComp(toWrite)
        }
    }

    private suspend fun upsertEnrichments(propertyId: String, enrichments: List<PropertyEnrichmentEntity>) {
        for (enrichment in enrichments) {
            val existing = enrichmentDao.find(propertyId, enrichment.enrichmentType, enrichment.provider)
            if (existing == null) {
                enrichmentDao.upsert(enrichment.copy(id = 0, propertyId = propertyId, isCurrent = true))
            } else {
                enrichmentDao.refresh(
                    id = existing.id,
                    valueNumeric = enrichment.valueNumeric,
                    valueText = enrichment.valueText,
                    unit = enrichment.unit,
                    confidence = enrichment.confidence,
                    effectiveAt = enrichment.effectiveAt,
                    expiresAt = enrichment.expiresAt,
                    ingestedAt = enrichment.ingestedAt,
                    payloadHash = enrichment.payloadHash,
                    payloadRef = enrichment.rawPayloadRef,
                    provenanceId = enrichment.provenanceId,
                    notes = enrichment.notes
                )
            }
        }
    }

    private suspend fun touchProvenance(
        propertyId: String,
        sourceId: String,
        externalId: String,
        bundle: NormalizedPropertyBundle,
        jobId: String?,
        now: Long,
        makePrimary: Boolean
    ) {
        val existing = sourceDao.findProvenance(sourceId, externalId)
        if (existing == null) {
            val isFirstForProperty = sourceDao.countProvenanceForProperty(propertyId) == 0
            val insertedId = sourceDao.insertProvenance(
                PropertyProvenanceEntity(
                    propertyId = propertyId,
                    sourceId = sourceId,
                    externalId = externalId,
                    externalUrl = bundle.externalUrl,
                    ingestionMethod = bundle.ingestionMethod,
                    ingestJobId = jobId,
                    confidence = bundle.confidence,
                    isPrimaryForProperty = isFirstForProperty || makePrimary,
                    fetchedAt = now,
                    sourceUpdatedAt = bundle.sourceUpdatedAt,
                    firstSeenAt = now,
                    lastSeenAt = now,
                    rawPayloadHash = bundle.payloadHash,
                    rawPayloadRef = bundle.rawPayloadRef
                )
            )
            if (makePrimary && !isFirstForProperty && insertedId > 0) {
                sourceDao.clearPrimaryProvenance(propertyId)
                sourceDao.markPrimaryProvenance(insertedId)
                propertyDao.setPrimarySource(propertyId, sourceId)
            }
        } else {
            sourceDao.refreshProvenance(
                id = existing.id,
                seenAt = now,
                jobId = jobId,
                confidence = bundle.confidence,
                payloadHash = bundle.payloadHash,
                payloadRef = bundle.rawPayloadRef,
                sourceUpdatedAt = bundle.sourceUpdatedAt
            )
            if (makePrimary && !existing.isPrimaryForProperty) {
                sourceDao.clearPrimaryProvenance(propertyId)
                sourceDao.markPrimaryProvenance(existing.id)
                propertyDao.setPrimarySource(propertyId, sourceId)
            }
        }
    }

    // ── Reconcile pass for rows migrated without a canonical key ────────────────────────────────

    /**
     * Claims canonical keys for legacy rows and merges duplicates that the v2 schema allowed.
     * Idempotent and safe to run on every app start; the oldest row always survives so ids that
     * offers/conversations already point at stay valid.
     */
    suspend fun reconcileCanonicalKeys(limit: Int = RECONCILE_BATCH): ReconcileSummary =
        withContext(Dispatchers.IO) {
            var scanned = 0
            var keyed = 0
            var mergedDuplicates = 0
            val reasons = mutableListOf<String>()

            database.withTransaction {
                val pending = propertyDao.getPropertiesWithoutCanonicalKey(limit)
                for (entity in pending) {
                    scanned++
                    val canonical = PropertyMapper.toCanonical(entity)
                    val key = UsPropertyNormalizer.canonicalKey(canonical.address)

                    val owner = propertyDao.getPropertyByCanonicalKey(key)
                    if (owner != null && owner.id != entity.id) {
                        val (survivor, loser) = oldestFirst(entity, owner)
                        mergeDuplicate(loser, survivor)
                        mergedDuplicates++
                        reasons += "key already owned by ${survivor.id}"
                        continue
                    }

                    val siblings = propertyDao.getKeylessCandidates(
                        zipCode = canonical.address.zip5,
                        city = canonical.address.city,
                        limit = KEYLESS_CANDIDATE_LIMIT
                    ).filter { it.id != entity.id }
                    val decision = PropertyDeduplicator.decide(
                        incoming = PropertyMapper.toDedupInput(canonical),
                        candidates = siblings.map { PropertyMapper.toDedupInput(it) }
                    )
                    val matchedId = decision.matchedPropertyId
                    if (matchedId != null) {
                        val matched = siblings.first { it.id == matchedId }
                        val (survivor, loser) = oldestFirst(entity, matched)
                        mergeDuplicate(loser, survivor)
                        mergedDuplicates++
                        reasons += decision.reason
                        continue
                    }

                    propertyDao.updateCanonicalIdentity(
                        id = entity.id,
                        canonicalKey = key,
                        normalizedAddress = canonical.address.singleLine,
                        unitNumber = canonical.address.unit,
                        county = canonical.address.county,
                        countyFips = canonical.address.countyFips,
                        apn = canonical.apn,
                        propertySubType = canonical.subType.ifBlank { canonical.propertyType.name },
                        verifiedAt = if (canonical.lastVerifiedAt == 0L) entity.scannedAt else canonical.lastVerifiedAt
                    )
                    keyed++
                }
            }
            ReconcileSummary(scanned, keyed, mergedDuplicates, reasons)
        }

    /** Returns (survivor, loser): the row seen first wins so existing references stay valid. */
    private fun oldestFirst(a: PropertyEntity, b: PropertyEntity): Pair<PropertyEntity, PropertyEntity> =
        if (a.scannedAt <= b.scannedAt) Pair(a, b) else Pair(b, a)

    /**
     * Merges the losing duplicate into the surviving row, moving everything that would otherwise be
     * lost, then deletes the loser (cascades clean up the rest).
     */
    private suspend fun mergeDuplicate(loser: PropertyEntity, survivor: PropertyEntity) {
        if (loser.id == survivor.id) return

        sourceDao.repointProvenance(loser.id, survivor.id)

        if (propertyDao.getImagesListForProperty(survivor.id).isEmpty()) {
            propertyDao.repointImages(loser.id, survivor.id)
        }
        if (propertyDao.getSalesHistoryListForProperty(survivor.id).isEmpty()) {
            propertyDao.repointSalesHistory(loser.id, survivor.id)
        }
        if (propertyDao.getCompsListForProperty(survivor.id).isEmpty()) {
            propertyDao.repointComps(loser.id, survivor.id)
        } else {
            propertyDao.deleteCompsByPropertyId(loser.id)
        }
        if (propertyDao.getMarketData(survivor.id) == null) {
            propertyDao.getMarketData(loser.id)?.let { propertyDao.insertMarketData(it.copy(propertyId = survivor.id)) }
        }
        if (propertyDao.getRentEstimate(survivor.id) == null) {
            propertyDao.getRentEstimate(loser.id)?.let { propertyDao.insertRentEstimate(it.copy(propertyId = survivor.id)) }
        }
        if (propertyDao.getTaxRecord(survivor.id) == null) {
            propertyDao.getTaxRecord(loser.id)?.let { propertyDao.insertTaxRecord(it.copy(propertyId = survivor.id)) }
        }
        if (financialDao.getFinancials(survivor.id) == null) {
            financialDao.getFinancials(loser.id)?.let { financialDao.upsert(it.copy(propertyId = survivor.id)) }
        }
        if (analysisDao.getAnalysis(survivor.id) == null) {
            analysisDao.getAnalysis(loser.id)?.let { analysisDao.insertAnalysis(it.copy(propertyId = survivor.id)) }
        }
        if (analysisDao.getScenariosListForProperty(survivor.id).isEmpty()) {
            analysisDao.insertScenarios(
                analysisDao.getScenariosListForProperty(loser.id).map { it.copy(id = 0, propertyId = survivor.id) }
            )
        }
        if (enrichmentDao.countForProperty(survivor.id) == 0) {
            val moved = enrichmentDao.getForProperty(loser.id)
            if (moved.isNotEmpty()) {
                enrichmentDao.upsertAll(moved.map { it.copy(id = 0, propertyId = survivor.id) })
            }
        }

        propertyDao.updateProperty(
            survivor.copy(
                // The survivor inherits the identity if it had not claimed one yet; otherwise the
                // merged row would keep no canonical key at all after the loser is deleted.
                canonicalKey = survivor.canonicalKey ?: loser.canonicalKey,
                normalizedAddress = survivor.normalizedAddress.ifBlank { loser.normalizedAddress },
                isSaved = survivor.isSaved || loser.isSaved,
                isSavedDeal = survivor.isSavedDeal || loser.isSavedDeal,
                dealScore = maxOf(survivor.dealScore, loser.dealScore)
            )
        )
        propertyDao.deletePropertyById(loser.id)
    }

    /**
     * Re-keys the comps of one property to the canonical address label; rows that become duplicates
     * of an existing comp (same target, same label, same sale date) are dropped.
     */
    suspend fun rekeyComps(propertyId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            for (comp in propertyDao.getCompsListForProperty(propertyId)) {
                val label = UsPropertyNormalizer.canonicalStreetLabel(comp.compAddress).ifBlank { comp.compAddress }
                if (label == comp.compAddress) continue
                val duplicate = propertyDao.findComp(propertyId, label, comp.saleDate)
                if (duplicate != null && duplicate.id != comp.id) {
                    propertyDao.deleteComp(comp.id)
                } else {
                    propertyDao.updateComp(comp.copy(compAddress = label))
                }
            }
        }
    }

    suspend fun pruneJobHistory(keepSince: Long) = withContext(Dispatchers.IO) {
        sourceDao.pruneJobs(keepSince)
    }

    private companion object {
        const val FALLBACK_PRIORITY = 1000
        const val KEYLESS_CANDIDATE_LIMIT = 100
        const val RECONCILE_BATCH = 250
    }
}
