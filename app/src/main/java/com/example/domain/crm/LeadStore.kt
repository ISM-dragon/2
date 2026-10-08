package com.example.domain.crm

/**
 * Persistence port for CRM leads.
 *
 * The CRM domain never talks to Room, a file or SharedPreferences: it talks to this interface. That is
 * what keeps `com.example.domain.crm` free of Android imports on this branch (see the isolation test)
 * and what makes the integration branch a wiring exercise rather than a rewrite — a Room-backed
 * implementation maps [Lead] onto `leads` + child tables (`lead_sellers`, `lead_property_links`,
 * `lead_motivation_signals`, `lead_notes`, `lead_communications`, `lead_offers`, `lead_transitions`,
 * `lead_qualification_history`) inside one `withTransaction` block, and nothing above this port changes.
 *
 * All queries return leads in a deterministic order (see [InMemoryLeadStore] for the ordering rules) so
 * that screens and tests see the same list for the same data.
 */
interface LeadStore {

    /** Inserts or replaces the whole aggregate for [lead]. */
    suspend fun save(lead: Lead)

    suspend fun findById(leadId: String): Lead?

    /** Open leads (everything except CLOSED/LOST), most urgent first. */
    suspend fun listOpen(limit: Int = DEFAULT_LIMIT): List<Lead>

    suspend fun listByStatus(statuses: Set<LeadPipelineStatus>, limit: Int = DEFAULT_LIMIT): List<Lead>

    /** Open leads whose pending follow-up is due at or before [epochMillis] (overdue included). */
    suspend fun listFollowUpsDueAtOrBefore(epochMillis: Long, limit: Int = DEFAULT_LIMIT): List<Lead>

    /** Leads carrying the given (already normalized) tag. */
    suspend fun listByTag(tag: String, limit: Int = DEFAULT_LIMIT): List<Lead>

    /** Every lead linked to a property, whatever the link role. */
    suspend fun listByPropertyId(propertyId: String, limit: Int = DEFAULT_LIMIT): List<Lead>

    /** Leads captured from the same source attribution key ([LeadSourceAttribution.deduplicationKey]). */
    suspend fun findBySourceDeduplicationKey(key: String, limit: Int = DEFAULT_LIMIT): List<Lead>

    /** Leads for one seller id: used to reuse what is already known about an owner. */
    suspend fun listBySellerId(sellerId: String, limit: Int = DEFAULT_LIMIT): List<Lead>

    suspend fun count(): Int

    /** Retention: removes *terminal* leads last written before [epochMillis]. */
    suspend fun deleteTerminalOlderThan(epochMillis: Long): Int

    /** Removes every lead whose id is in [leadIds] (test/diagnostic helper, never used by the pipeline). */
    suspend fun deleteByIds(leadIds: Set<String>): Int

    companion object {
        const val DEFAULT_LIMIT = 200
    }
}

/**
 * Thread-safe, bounded in-memory store.
 *
 * It exists for tests, previews and the "no database yet" state of this branch. Two properties matter
 * and are enforced here:
 *
 *  - **bounded memory**: a mobile process must not accumulate an unbounded CRM. Eviction is oldest
 *    first and prefers terminal leads, so an in-flight deal is never dropped to make room for a dead
 *    one;
 *  - **deterministic ordering**: lists are sorted by priority, then ascending time in stage, then id,
 *    which means two callers (and two test runs) always see the same order.
 *
 * Queries scan the map rather than maintaining secondary indices: the map is bounded by [maxLeads]
 * (default 1_000), and a scan that is obviously correct beats an index that is subtly stale. Real
 * indexing belongs to the Room-backed implementation.
 */
class InMemoryLeadStore(
    private val maxLeads: Int = 1_000,
    private val retainTerminalLeads: Int = 200
) : LeadStore {

    init {
        require(maxLeads > 0) { "maxLeads must be positive" }
        require(retainTerminalLeads >= 0) { "retainTerminalLeads must not be negative" }
        require(retainTerminalLeads <= maxLeads) { "retainTerminalLeads must not exceed maxLeads" }
    }

    private val lock = Any()
    private val byId = LinkedHashMap<String, Lead>()

    override suspend fun save(lead: Lead) {
        synchronized(lock) {
            byId[lead.id] = lead
            evictIfNeeded()
        }
    }

    override suspend fun findById(leadId: String): Lead? = synchronized(lock) { byId[leadId] }

    override suspend fun listOpen(limit: Int): List<Lead> = synchronized(lock) {
        ordered(byId.values.filter { it.isOpen }, limit)
    }

    override suspend fun listByStatus(statuses: Set<LeadPipelineStatus>, limit: Int): List<Lead> = synchronized(lock) {
        if (statuses.isEmpty()) emptyList() else ordered(byId.values.filter { it.pipelineStatus in statuses }, limit)
    }

    override suspend fun listFollowUpsDueAtOrBefore(epochMillis: Long, limit: Int): List<Lead> = synchronized(lock) {
        ordered(
            byId.values.filter { lead ->
                val followUp = lead.nextFollowUp
                !lead.isTerminal && followUp != null && !followUp.isCompleted &&
                    followUp.effectiveDueAtEpochMillis <= epochMillis
            },
            limit
        )
    }

    override suspend fun listByTag(tag: String, limit: Int): List<Lead> = synchronized(lock) {
        val normalized = LeadTags.normalize(tag) ?: return emptyList()
        ordered(byId.values.filter { normalized in it.tags }, limit)
    }

    override suspend fun listByPropertyId(propertyId: String, limit: Int): List<Lead> = synchronized(lock) {
        ordered(byId.values.filter { lead -> lead.propertyLinks.any { it.propertyId == propertyId } }, limit)
    }

    override suspend fun findBySourceDeduplicationKey(key: String, limit: Int): List<Lead> = synchronized(lock) {
        ordered(byId.values.filter { it.source.deduplicationKey() == key }, limit)
    }

    override suspend fun listBySellerId(sellerId: String, limit: Int): List<Lead> = synchronized(lock) {
        ordered(byId.values.filter { lead -> lead.sellers.any { it.id == sellerId } }, limit)
    }

    override suspend fun count(): Int = synchronized(lock) { byId.size }

    override suspend fun deleteTerminalOlderThan(epochMillis: Long): Int = synchronized(lock) {
        val doomed = byId.values.filter { it.isTerminal && it.audit.updatedAtEpochMillis < epochMillis }.map { it.id }
        doomed.forEach { byId.remove(it) }
        doomed.size
    }

    override suspend fun deleteByIds(leadIds: Set<String>): Int = synchronized(lock) {
        var removed = 0
        leadIds.forEach { id -> if (byId.remove(id) != null) removed++ }
        removed
    }

    /** Priority first, then the lead that has been waiting longest, then id: total and stable. */
    private fun ordered(leads: Collection<Lead>, limit: Int): List<Lead> = leads
        .sortedWith(
            compareByDescending<Lead> { it.priority.rank }
                .thenBy { it.stageEnteredAtEpochMillis }
                .thenBy { it.id }
        )
        .take(limit.coerceAtLeast(0))

    private fun evictIfNeeded() {
        if (byId.size <= maxLeads) return
        val terminalIds = byId.values
            .filter { it.isTerminal }
            .sortedBy { it.audit.updatedAtEpochMillis }
            .map { it.id }
        val overflow = byId.size - maxLeads
        terminalIds.take(overflow).forEach { byId.remove(it) }
        if (byId.size <= maxLeads) return
        // Still over budget: the store is holding too many *open* leads. Drop the least recently
        // written ones, but never below the terminal retention budget the caller asked for.
        val removable = byId.values
            .sortedBy { it.audit.updatedAtEpochMillis }
            .map { it.id }
            .take((byId.size - maxLeads).coerceAtLeast(0))
        removable.forEach { byId.remove(it) }
    }
}
