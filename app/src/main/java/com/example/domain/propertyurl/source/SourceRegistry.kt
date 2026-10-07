package com.example.domain.propertyurl.source

import com.example.domain.propertyurl.url.PropertyUrl
import java.util.Locale

/**
 * Immutable registry of known property sources.
 *
 * Thread-safety: instances are immutable and [plus] returns a new registry, so a registry can be
 * built once at composition time and extended (or replaced) in tests without locks.
 */
class SourceRegistry private constructor(
    private val byId: Map<String, PropertySourceDefinition>,
    private val domainIndex: Map<String, List<PropertySourceDefinition>>
) {

    val definitions: List<PropertySourceDefinition> get() = byId.values.toList()

    val ids: Set<String> get() = byId.keys

    fun byId(sourceId: String): PropertySourceDefinition? = byId[sourceId.lowercase(Locale.US)]

    fun supported(): List<PropertySourceDefinition> = definitions.filter { it.isSupported }

    fun planned(): List<PropertySourceDefinition> = definitions.filter { it.status == SourceStatus.PLANNED }

    fun domains(): Set<String> = domainIndex.keys

    /** The definition used for unknown hosts, when one is registered. */
    fun genericFallback(): PropertySourceDefinition? =
        definitions.firstOrNull { it.kind == PropertySourceKind.GENERIC_WEB }

    /** Adds or replaces definitions; throws on duplicate ids inside [newDefinitions]. */
    fun plus(vararg newDefinitions: PropertySourceDefinition): SourceRegistry =
        build(definitions.filter { existing -> newDefinitions.none { it.sourceId.equals(existing.sourceId, true) } } + newDefinitions)

    fun withStatus(sourceId: String, status: SourceStatus): SourceRegistry {
        val existing = byId(sourceId) ?: return this
        return plus(existing.copy(status = status))
    }

    /** Detects which source a URL belongs to (host → path → query evidence). */
    fun detect(url: PropertyUrl): SourceDetection = SourceDetector(this).detect(url)

    internal fun candidatesForHost(host: String): List<PropertySourceDefinition> {
        val lowered = host.lowercase(Locale.US).trim('.')
        val matches = LinkedHashMap<String, PropertySourceDefinition>()

        domainIndex[lowered]?.forEach { matches[it.sourceId] = it }

        // Sub-domain walk: www.zillow.com → zillow.com (also handles a.b.zillow.com).
        var remainder = lowered
        while (remainder.contains('.')) {
            remainder = remainder.substring(remainder.indexOf('.') + 1)
            domainIndex[remainder]?.forEach { matches[it.sourceId] = it }
        }
        return matches.values.toList()
    }

    /**
     * Alias matching for CDN/alternate hosts (e.g. `photos.zillowstatic.com`).
     *
     * Aliases are matched against the **registrable domain labels**, never as substrings of the whole
     * host: substring matching would let `www.zillow.com.evil-clone.net` masquerade as Zillow.
     */
    internal fun candidatesByAlias(host: String): List<PropertySourceDefinition> {
        val labels = com.example.domain.propertyurl.url.UrlHosts.registrableDomain(host)
            .lowercase(Locale.US)
            .split('.')
            .filter { it.isNotBlank() }
        if (labels.isEmpty()) return emptyList()

        return definitions.filter { definition ->
            definition.aliases.any { alias ->
                val normalized = alias.lowercase(Locale.US).trim().trim('.')
                normalized.isNotEmpty() && labels.any { label ->
                    label == normalized || label.startsWith("$normalized-") || label.endsWith("-$normalized")
                }
            }
        }
    }

    companion object {

        fun empty(): SourceRegistry = SourceRegistry(emptyMap(), emptyMap())

        fun of(vararg definitions: PropertySourceDefinition): SourceRegistry = build(definitions.toList())

        fun build(definitions: List<PropertySourceDefinition>): SourceRegistry {
            val byId = LinkedHashMap<String, PropertySourceDefinition>()
            for (definition in definitions) {
                val key = definition.sourceId.lowercase(Locale.US)
                require(!byId.containsKey(key)) { "Duplicate source definition: ${definition.sourceId}" }
                byId[key] = definition
            }
            val domainIndex = LinkedHashMap<String, MutableList<PropertySourceDefinition>>()
            byId.values.forEach { definition ->
                definition.domains.forEach { domain ->
                    val key = domain.lowercase(Locale.US)
                    val list = domainIndex.getOrPut(key) { mutableListOf() }
                    if (list.none { it.sourceId == definition.sourceId }) list.add(definition)
                }
            }
            return SourceRegistry(byId, domainIndex)
        }
    }
}
