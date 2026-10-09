package com.example.ui.screens.dealroom

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.local.entity.*
import com.example.domain.intelligence.engine.DeterministicFinancialEngine
import com.example.domain.intelligence.model.CanonicalProperty
import com.example.domain.intelligence.model.FinancingType
import com.example.domain.intelligence.model.InvestmentStrategy
import com.example.domain.intelligence.model.StrategyFinancialMetrics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * One stored intelligence value: the current, highest-confidence enrichment row for a single
 * [PropertyEnrichmentType]. Nothing is derived here - the UI renders exactly what a provider wrote
 * (or UNKNOWN when no provider wrote anything).
 */
data class IntelligenceValue(
    val enrichmentType: String,
    val provider: String,
    val numericValue: Double?,
    val textValue: String,
    val unit: String,
    /** Provider reported confidence (0.0 = the provider did not report one). */
    val confidence: Double,
    val effectiveAt: Long,
    val ingestedAt: Long
)

/**
 * Declares where one input of the underwriting model came from.
 *
 * `sourced = true` means the deterministic engine consumed a stored canonical fact. `sourced =
 * false` means it fell back to its own built-in assumption - the UI must label that case instead of
 * presenting the result as a fact.
 */
data class ModelInputBasis(
    val label: String,
    /** Defaults to false: an input is treated as not source-backed unless a stored fact proves otherwise. */
    val sourced: Boolean = false,
    /** Table + record class of the stored fact, when [sourced] is true. */
    val sourceDetail: String = "",
    /** Why the input is not source-backed, when [sourced] is false. */
    val notSourcedReason: String = ""
)

/** Which of the tracked Deal Room fields actually have a stored, source-backed value. */
data class FieldCoverage(
    val trackedFields: List<String> = emptyList(),
    val sourcedFields: List<String> = emptyList()
) {
    val trackedCount: Int get() = trackedFields.size
    val sourcedCount: Int get() = sourcedFields.size
    val missingFields: List<String> get() = trackedFields.filterNot { sourcedFields.contains(it) }
}

/** One timestamped event assembled from stored rows only (imports, enrichments, offer audit). */
data class DealRoomEvent(
    val timestamp: Long,
    val title: String,
    val detail: String
)

data class DealRoomUiState(
    val isLoading: Boolean = true,
    val property: PropertyEntity? = null,
    val images: List<PropertyImageEntity> = emptyList(),
    val provenance: List<PropertyProvenanceEntity> = emptyList(),
    val intelligence: List<IntelligenceValue> = emptyList(),
    val comps: List<PropertyCompEntity> = emptyList(),
    val marketData: MarketDataEntity? = null,
    val rentEstimate: RentEstimateEntity? = null,
    val taxRecord: TaxRecordEntity? = null,
    val financials: PropertyFinancialEntity? = null,
    val aiAnalysis: PropertyAiAnalysisEntity? = null,
    val offer: OfferEntity? = null,
    val offerDocuments: List<OfferDocumentEntity> = emptyList(),
    val offerAuditEvents: List<OfferAuditEventEntity> = emptyList(),
    val selectedStrategy: InvestmentStrategy = InvestmentStrategy.BUY_AND_HOLD,
    val selectedFinancing: FinancingType = FinancingType.CONVENTIONAL,
    val activeTab: String = "Overview",
    val dynamicFinancials: StrategyFinancialMetrics? = null,
    /** True only when a stored rent figure was handed to the engine (see [ModelInputBasis]). */
    val rentSourced: Boolean = false,
    val modelInputs: List<ModelInputBasis> = emptyList(),
    val coverage: FieldCoverage = FieldCoverage(),
    val timeline: List<DealRoomEvent> = emptyList(),
    val errorMessage: String? = null
) {
    fun intelligenceFor(enrichmentType: String): IntelligenceValue? =
        intelligence.firstOrNull { it.enrichmentType == enrichmentType }
}

private data class PropertyRecordFlows(
    val images: List<PropertyImageEntity>,
    val provenance: List<PropertyProvenanceEntity>,
    val intelligence: List<IntelligenceValue>
)

private data class SatelliteRecords(
    val marketData: MarketDataEntity?,
    val rentEstimate: RentEstimateEntity?,
    val taxRecord: TaxRecordEntity?,
    val comps: List<PropertyCompEntity>
)

private data class StoredRecords(
    val financials: PropertyFinancialEntity?,
    val aiAnalysis: PropertyAiAnalysisEntity?
)

private data class OfferRecords(
    val offer: OfferEntity?,
    val documents: List<OfferDocumentEntity>,
    val auditEvents: List<OfferAuditEventEntity>
)

/** Canonical facts handed to the underwriting engine; never invented inside the UI layer. */
private data class ModelFacts(
    val rentMonthly: Double? = null,
    val rentSourceDetail: String = "",
    val propertyTaxAnnual: Double? = null,
    val propertyTaxSourceDetail: String = "",
    val hoaMonthly: Double? = null,
    val hoaSourceDetail: String = ""
)

class DealRoomViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RealEstateAiApp
    private val propertyRepo = app.propertyRepository
    private val intelligenceRepo = app.intelligenceRepository
    private val enrichmentRepo = app.propertyEnrichmentRepository

    private val _uiState = MutableStateFlow(DealRoomUiState())
    val uiState: StateFlow<DealRoomUiState> = _uiState.asStateFlow()

    /** Latest canonical input set, reused when only the strategy/financing selection changes. */
    private var canonicalProperty: CanonicalProperty? = null

    /** Keeps a single live subscription when a new property is opened in the same view model. */
    private var loadJob: Job? = null

    fun loadDealRoom(propertyId: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val prop = propertyRepo.getPropertyById(propertyId)
            if (prop == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Property not found.") }
                return@launch
            }

            val propertyRecords = combine(
                propertyRepo.getImages(propertyId),
                intelligenceRepo.getProvenanceForProperty(propertyId),
                enrichmentRepo.observeForProperty(propertyId)
            ) { images, provenance, enrichments ->
                PropertyRecordFlows(
                    images = images,
                    provenance = provenance,
                    intelligence = enrichments.toIntelligenceValues()
                )
            }

            val satellites = combine(
                propertyRepo.getMarketDataFlow(propertyId),
                propertyRepo.getRentEstimateFlow(propertyId),
                propertyRepo.getTaxRecordFlow(propertyId),
                intelligenceRepo.getCompsForProperty(propertyId)
            ) { marketData, rentEstimate, taxRecord, comps ->
                SatelliteRecords(marketData, rentEstimate, taxRecord, comps)
            }

            val storedRecords = combine(
                intelligenceRepo.getFinancialsFlow(propertyId),
                intelligenceRepo.getAiAnalysisFlow(propertyId)
            ) { financials, aiAnalysis ->
                StoredRecords(financials, aiAnalysis)
            }

            combine(
                propertyRecords,
                satellites,
                storedRecords,
                observeOfferRecords(propertyId)
            ) { records, satelliteRecords, stored, offers ->
                buildState(prop, records, satelliteRecords, stored, offers)
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    fun selectTab(tab: String) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun setStrategy(strategy: InvestmentStrategy) {
        _uiState.update { it.copy(selectedStrategy = strategy) }
        recalculateDynamic()
    }

    fun setFinancing(financing: FinancingType) {
        _uiState.update { it.copy(selectedFinancing = financing) }
        recalculateDynamic()
    }

    private fun recalculateDynamic() {
        val canonical = canonicalProperty ?: return
        val metrics = DeterministicFinancialEngine.calculate(
            canonical,
            _uiState.value.selectedStrategy,
            _uiState.value.selectedFinancing
        )
        _uiState.update { it.copy(dynamicFinancials = metrics) }
    }

    private fun buildState(
        property: PropertyEntity,
        records: PropertyRecordFlows,
        satellites: SatelliteRecords,
        stored: StoredRecords,
        offers: OfferRecords
    ): DealRoomUiState {
        val facts = modelFactsOf(property, satellites.rentEstimate, satellites.taxRecord, stored.financials)
        val canonical = canonicalPropertyOf(property, records.provenance, facts)
        canonicalProperty = canonical

        val current = _uiState.value
        val metrics = DeterministicFinancialEngine.calculate(
            canonical,
            current.selectedStrategy,
            current.selectedFinancing
        )

        return DealRoomUiState(
            isLoading = false,
            property = property,
            images = records.images,
            provenance = records.provenance,
            intelligence = records.intelligence,
            comps = satellites.comps,
            marketData = satellites.marketData,
            rentEstimate = satellites.rentEstimate,
            taxRecord = satellites.taxRecord,
            financials = stored.financials,
            aiAnalysis = stored.aiAnalysis,
            offer = offers.offer,
            offerDocuments = offers.documents,
            offerAuditEvents = offers.auditEvents,
            selectedStrategy = current.selectedStrategy,
            selectedFinancing = current.selectedFinancing,
            activeTab = current.activeTab,
            dynamicFinancials = metrics,
            rentSourced = facts.rentMonthly != null,
            modelInputs = modelInputsOf(facts, stored.financials),
            coverage = coverageOf(property, satellites, stored.financials, records.intelligence, facts),
            timeline = timelineOf(records, offers)
        )
    }

    /** Current, highest-confidence value per enrichment type; rows without a value are dropped. */
    private fun List<PropertyEnrichmentEntity>.toIntelligenceValues(): List<IntelligenceValue> =
        filter { it.isCurrent && (it.valueNumeric != null || it.valueText.isNotBlank()) }
            .groupBy { it.enrichmentType }
            .mapNotNull { (_, rows) -> rows.maxByOrNull { it.confidence } }
            .map { row ->
                IntelligenceValue(
                    enrichmentType = row.enrichmentType,
                    provider = row.provider,
                    numericValue = row.valueNumeric,
                    textValue = row.valueText,
                    unit = row.unit,
                    confidence = row.confidence,
                    effectiveAt = row.effectiveAt,
                    ingestedAt = row.ingestedAt
                )
            }
            .sortedBy { it.enrichmentType }

    /**
     * Reads the canonical rent / tax / HOA facts a stored row provides. `null` means "no stored
     * value", which is exactly what the UI must render as UNKNOWN instead of an estimate.
     */
    private fun modelFactsOf(
        property: PropertyEntity,
        rentEstimate: RentEstimateEntity?,
        taxRecord: TaxRecordEntity?,
        financials: PropertyFinancialEntity?
    ): ModelFacts {
        val storedRent = financials?.monthlyRentEstimate?.takeIf { it > 0.0 }
        val avmRent = rentEstimate?.estimatedRent?.takeIf { it > 0.0 }
        val rent = storedRent ?: avmRent
        val rentDetail = when {
            storedRent != null -> "property_financials (${financials?.dataSource ?: "record class unspecified"})"
            avmRent != null -> "rent_estimates (rent AVM)"
            else -> ""
        }

        val storedTax = financials?.annualPropertyTax?.takeIf { it > 0.0 }
        val countyTax = taxRecord?.annualTaxAmount?.takeIf { it > 0.0 }
        val tax = storedTax ?: countyTax
        val taxDetail = when {
            storedTax != null -> "property_financials (${financials?.dataSource ?: "record class unspecified"})"
            countyTax != null -> "tax_records (county record)"
            else -> ""
        }

        val storedHoa = financials?.hoaMonthly?.takeIf { it > 0.0 }
        val listingHoa = property.hoaMonthly.takeIf { it > 0.0 }
        val hoa = storedHoa ?: listingHoa
        val hoaDetail = when {
            storedHoa != null -> "property_financials"
            listingHoa != null -> "property listing"
            else -> ""
        }

        return ModelFacts(
            rentMonthly = rent,
            rentSourceDetail = rentDetail,
            propertyTaxAnnual = tax,
            propertyTaxSourceDetail = taxDetail,
            hoaMonthly = hoa,
            hoaSourceDetail = hoaDetail
        )
    }

    private fun modelInputsOf(
        facts: ModelFacts,
        financials: PropertyFinancialEntity?
    ): List<ModelInputBasis> = listOf(
        ModelInputBasis(
            label = "Rent",
            sourced = facts.rentMonthly != null,
            sourceDetail = facts.rentSourceDetail,
            notSourcedReason = "not stored · engine default assumption"
        ),
        ModelInputBasis(
            label = "Property tax",
            sourced = facts.propertyTaxAnnual != null,
            sourceDetail = facts.propertyTaxSourceDetail,
            notSourcedReason = "not stored · engine default assumption"
        ),
        ModelInputBasis(
            label = "HOA / ownership fee",
            sourced = facts.hoaMonthly != null,
            sourceDetail = facts.hoaSourceDetail,
            notSourcedReason = "not stored · model assumes none"
        ),
        ModelInputBasis(
            label = "Insurance",
            sourced = false,
            notSourcedReason = if ((financials?.annualInsurance ?: 0.0) > 0.0) {
                "stored fact not consumed by the engine · default assumption used"
            } else {
                "not stored · engine default assumption"
            }
        ),
        ModelInputBasis(
            label = "Vacancy",
            sourced = false,
            notSourcedReason = if ((financials?.vacancyRatePct ?: 0.0) > 0.0) {
                "stored fact not consumed by the engine · default assumption used"
            } else {
                "not stored · engine default assumption"
            }
        )
    )

    private fun coverageOf(
        property: PropertyEntity,
        satellites: SatelliteRecords,
        financials: PropertyFinancialEntity?,
        intelligence: List<IntelligenceValue>,
        facts: ModelFacts
    ): FieldCoverage {
        val byType = intelligence.associateBy { it.enrichmentType }
        val tracked = listOf(
            "price" to (property.price > 0.0),
            "rent estimate" to (facts.rentMonthly != null),
            "property tax" to (facts.propertyTaxAnnual != null),
            "insurance" to ((financials?.annualInsurance ?: 0.0) > 0.0),
            "flood zone" to byType.containsKey(PropertyEnrichmentType.FLOOD_RISK),
            "walk score" to byType.containsKey(PropertyEnrichmentType.WALK_SCORE),
            "school rating" to byType.containsKey(PropertyEnrichmentType.SCHOOL_RATING),
            "crime index" to byType.containsKey(PropertyEnrichmentType.CRIME_INDEX),
            "market trend" to (
                ((satellites.marketData?.neighborhoodAppreciationRate ?: 0.0) > 0.0) ||
                    byType.containsKey(PropertyEnrichmentType.MARKET_TREND)
                ),
            "comparable sales" to satellites.comps.isNotEmpty()
        )
        return FieldCoverage(
            trackedFields = tracked.map { it.first },
            sourcedFields = tracked.filter { it.second }.map { it.first }
        )
    }

    private fun canonicalPropertyOf(
        property: PropertyEntity,
        provenance: List<PropertyProvenanceEntity>,
        facts: ModelFacts
    ): CanonicalProperty {
        val primary = provenance.firstOrNull { it.isPrimaryForProperty } ?: provenance.firstOrNull()
        return CanonicalProperty(
            propertyId = property.id,
            sourceUrl = primary?.externalUrl.orEmpty(),
            source = primary?.sourceId ?: "No stored source record",
            address = property.normalizedAddress.ifBlank { property.address },
            city = property.city,
            state = property.state,
            zipCode = property.zipCode,
            county = property.county.ifBlank { null },
            listPrice = property.price,
            bedrooms = property.bedrooms,
            bathrooms = property.bathrooms,
            squareFeet = property.squareFeet,
            yearBuilt = property.yearBuilt.takeIf { it > 0 },
            apn = property.apn.ifBlank { null },
            propertyTax = facts.propertyTaxAnnual,
            hoa = facts.hoaMonthly != null,
            hoaFee = facts.hoaMonthly,
            estimatedRent = facts.rentMonthly,
            rentSource = facts.rentSourceDetail.ifBlank { null }
        )
    }

    private fun timelineOf(
        records: PropertyRecordFlows,
        offers: OfferRecords
    ): List<DealRoomEvent> {
        val events = mutableListOf<DealRoomEvent>()

        records.provenance.forEach { record ->
            if (record.fetchedAt > 0L) {
                events += DealRoomEvent(
                    timestamp = record.fetchedAt,
                    title = "Source record fetched",
                    detail = "${record.sourceId} · ${record.externalId} · ${record.ingestionMethod}"
                )
            }
            if (record.firstSeenAt > 0L) {
                events += DealRoomEvent(
                    timestamp = record.firstSeenAt,
                    title = "Property first seen by source",
                    detail = "${record.sourceId} · ${record.externalId}"
                )
            }
            if (record.lastSeenAt > 0L && record.lastSeenAt != record.firstSeenAt) {
                events += DealRoomEvent(
                    timestamp = record.lastSeenAt,
                    title = "Property last seen by source",
                    detail = record.sourceId
                )
            }
        }

        records.intelligence.forEach { value ->
            if (value.ingestedAt > 0L) {
                events += DealRoomEvent(
                    timestamp = value.ingestedAt,
                    title = "Intelligence value recorded",
                    detail = "${value.enrichmentType} · ${value.provider}"
                )
            }
        }

        offers.offer?.let { offer ->
            events += DealRoomEvent(
                timestamp = offer.createdAt,
                title = "Offer drafted",
                detail = "${offer.id} · ${offer.status}"
            )
            offer.sentAt?.let { sentAt ->
                if (sentAt > 0L) {
                    events += DealRoomEvent(sentAt, "Offer sent", offer.recipientEmail)
                }
            }
        }

        offers.auditEvents.forEach { event ->
            events += DealRoomEvent(
                timestamp = event.timestamp,
                title = event.eventType.replace("_", " "),
                detail = listOfNotNull(event.status, event.details).joinToString(" · ")
            )
        }

        return events
            .filter { it.timestamp > 0L }
            .sortedByDescending { it.timestamp }
            .take(MAX_TIMELINE_EVENTS)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeOfferRecords(propertyId: String): Flow<OfferRecords> =
        app.offerRepository.allOffers
            .map { offers -> offers.filter { it.propertyId == propertyId }.maxByOrNull { it.createdAt } }
            .flatMapLatest { offer ->
                if (offer == null) {
                    flowOf(OfferRecords(null, emptyList(), emptyList()))
                } else {
                    combine(
                        app.offerRepository.getDocumentsForOffer(offer.id),
                        app.offerRepository.getAuditTrailForOffer(offer.id)
                    ) { documents, auditEvents ->
                        OfferRecords(offer, documents, auditEvents)
                    }
                }
            }

    private companion object {
        const val MAX_TIMELINE_EVENTS = 25
    }
}
