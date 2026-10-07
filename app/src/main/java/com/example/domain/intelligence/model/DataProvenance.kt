package com.example.domain.intelligence.model

enum class ProvenanceSourceTier(val priority: Int) {
    DIRECT_LISTING(1),
    GOVERNMENT_DATA(2),
    LICENSED_API(3),
    SECONDARY_ESTIMATE(4),
    AI_INFERENCE(5)
}

data class FieldProvenance(
    val field: String,
    val value: String,
    val source: String,
    val tier: ProvenanceSourceTier = ProvenanceSourceTier.DIRECT_LISTING,
    val retrievedAt: Long = System.currentTimeMillis(),
    val confidence: Double = 0.95 // 0.0 to 1.0
)

data class DataProvenanceManifest(
    val propertyId: String,
    val records: List<FieldProvenance> = emptyList()
) {
    fun getProvenanceFor(field: String): FieldProvenance? {
        return records.filter { it.field == field }
            .minByOrNull { it.tier.priority }
    }
}
