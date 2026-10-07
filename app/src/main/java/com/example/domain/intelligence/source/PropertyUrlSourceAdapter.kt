package com.example.domain.intelligence.source

import com.example.domain.intelligence.model.CanonicalProperty
import com.example.domain.intelligence.model.FieldProvenance

object AdapterErrorCodes {
    const val SOURCE_UNSUPPORTED = "SOURCE_UNSUPPORTED"
    const val ACCESS_RESTRICTED = "ACCESS_RESTRICTED"
    const val PARSING_FAILED = "PARSING_FAILED"
    const val SOURCE_CHANGED = "SOURCE_CHANGED"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val NETWORK_ERROR = "NETWORK_ERROR"
}

data class PropertyExtractionResult(
    val success: Boolean,
    val canonicalProperty: CanonicalProperty? = null,
    val provenanceRecords: List<FieldProvenance> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val latencyMs: Long = 0L,
    val schemaChanged: Boolean = false
)

interface PropertyUrlSourceAdapter {
    val sourceName: String
    val supportedDomains: List<String>
    val parserVersion: String
    fun supports(url: String): Boolean
    suspend fun extract(url: String): PropertyExtractionResult
}

class SourceRegistry(
    private val adapters: MutableList<PropertyUrlSourceAdapter> = mutableListOf()
) {
    fun registerAdapter(adapter: PropertyUrlSourceAdapter) {
        adapters.add(adapter)
    }

    fun findAdapter(url: String): PropertyUrlSourceAdapter? {
        return adapters.firstOrNull { it.supports(url) }
    }

    fun getSupportedSources(): List<String> {
        return adapters.map { it.sourceName }
    }

    fun getAllAdapters(): List<PropertyUrlSourceAdapter> = adapters.toList()
}
