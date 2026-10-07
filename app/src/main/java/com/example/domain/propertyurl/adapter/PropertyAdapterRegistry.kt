package com.example.domain.propertyurl.adapter

import com.example.domain.propertyurl.url.ResolvedPropertyUrl
import java.util.Locale

/**
 * Immutable adapter registry keyed by source id.
 *
 * Invariants enforced at construction (fail fast at composition time, never at import time):
 *  - adapter ids are unique,
 *  - at most one adapter per source id,
 *  - an adapter declares a source id that exists in the catalogue (checked by the factory).
 */
class PropertyAdapterRegistry private constructor(
    private val bySourceId: Map<String, PropertySourceAdapter>
) {

    val adapters: List<PropertySourceAdapter> get() = bySourceId.values.toList()

    val sourceIds: Set<String> get() = bySourceId.keys

    fun forSource(sourceId: String?): PropertySourceAdapter? =
        sourceId?.let { bySourceId[it.lowercase(Locale.US)] }

    /** Adapter for a resolved URL, honouring the adapter's own [PropertySourceAdapter.supports]. */
    fun forResolved(resolved: ResolvedPropertyUrl): PropertySourceAdapter? {
        val candidates = listOfNotNull(
            resolved.detection.definition?.let { bySourceId[it.sourceId.lowercase(Locale.US)] },
            bySourceId[GENERIC_SOURCE_ID]
        )
        return candidates.firstOrNull { it.supports(resolved) }
    }

    /** True when the resolved URL points at a source that has no adapter (yet). */
    fun isUnsupported(resolved: ResolvedPropertyUrl): Boolean = forResolved(resolved) == null

    fun plus(vararg newAdapters: PropertySourceAdapter): PropertyAdapterRegistry =
        build(adapters + newAdapters)

    companion object {
        const val GENERIC_SOURCE_ID = "generic_web"

        fun empty(): PropertyAdapterRegistry = PropertyAdapterRegistry(emptyMap())

        fun of(vararg adapters: PropertySourceAdapter): PropertyAdapterRegistry = build(adapters.toList())

        fun build(adapters: List<PropertySourceAdapter>): PropertyAdapterRegistry {
            val byId = LinkedHashMap<String, PropertySourceAdapter>()
            adapters.forEach { adapter ->
                val key = adapter.descriptor.sourceId.lowercase(Locale.US)
                require(!byId.containsKey(key)) {
                    "Duplicate adapter for source '${adapter.descriptor.sourceId}': " +
                        "${byId[key]?.descriptor?.adapterId} vs ${adapter.descriptor.adapterId}"
                }
                byId[key] = adapter
            }
            val duplicateIds = adapters.groupBy { it.descriptor.adapterId }.filterValues { it.size > 1 }
            require(duplicateIds.isEmpty()) { "Duplicate adapter ids: ${duplicateIds.keys}" }
            return PropertyAdapterRegistry(byId)
        }
    }
}
