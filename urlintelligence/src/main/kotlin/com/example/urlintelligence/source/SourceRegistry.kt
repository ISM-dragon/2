package com.example.urlintelligence.source

import com.example.urlintelligence.adapter.PropertySourceAdapter
import com.example.urlintelligence.url.NormalizedUrl

data class RegisteredSource(
    val descriptor: SourceDescriptor,
    val adapter: PropertySourceAdapter?
) {
    val isResolvable: Boolean
        get() = adapter != null && descriptor.enabledByDefault
}

/**
 * Single place where sources and their adapters are registered and looked up.
 *
 * The registry owns no I/O: it is a thread-safe catalog. That keeps detection,
 * capability checks and adapter dispatch trivially testable and lets hosts
 * (tests, Android app, backend worker) build their own registry instance with
 * only the sources they are licensed/allowed to use.
 */
class SourceRegistry(
    descriptors: List<SourceDescriptor> = KnownSources.all(),
    adapters: List<PropertySourceAdapter> = emptyList()
) {

    private val lock = Any()
    private val descriptorsById: LinkedHashMap<String, SourceDescriptor> = LinkedHashMap()
    private val adaptersById: LinkedHashMap<String, PropertySourceAdapter> = LinkedHashMap()

    init {
        descriptors.forEach { registerDescriptor(it) }
        adapters.forEach { registerAdapter(it) }
    }

    fun registerDescriptor(descriptor: SourceDescriptor): SourceRegistry = synchronized(lock) {
        descriptorsById[descriptor.id] = descriptor
        this
    }

    /** Registers an adapter and (re)binds its descriptor, so they can never drift apart. */
    fun registerAdapter(adapter: PropertySourceAdapter): SourceRegistry = synchronized(lock) {
        descriptorsById[adapter.descriptor.id] = adapter.descriptor
        adaptersById[adapter.descriptor.id] = adapter
        this
    }

    fun unregister(sourceId: String): SourceRegistry = synchronized(lock) {
        descriptorsById.remove(sourceId)
        adaptersById.remove(sourceId)
        this
    }

    fun descriptorFor(sourceId: String): SourceDescriptor? = synchronized(lock) {
        descriptorsById[sourceId]
    }

    fun adapterFor(sourceId: String): PropertySourceAdapter? = synchronized(lock) {
        adaptersById[sourceId]
    }

    fun registeredSources(): List<RegisteredSource> = synchronized(lock) {
        descriptorsById.values.map { descriptor -> RegisteredSource(descriptor, adaptersById[descriptor.id]) }
    }

    fun descriptors(): List<SourceDescriptor> = synchronized(lock) { descriptorsById.values.toList() }

    fun adapters(): List<PropertySourceAdapter> = synchronized(lock) { adaptersById.values.toList() }

    fun sourceIds(): List<String> = synchronized(lock) { descriptorsById.keys.toList() }

    fun resolvableSourceIds(): List<String> = registeredSources()
        .filter { it.isResolvable }
        .map { it.descriptor.id }

    fun contains(sourceId: String): Boolean = synchronized(lock) { descriptorsById.containsKey(sourceId) }

    fun size(): Int = synchronized(lock) { descriptorsById.size }

    fun isEmpty(): Boolean = size() == 0

    /** True when the source exists but no adapter is wired (announced but not implemented). */
    fun isAnnouncedOnly(sourceId: String): Boolean =
        contains(sourceId) && adapterFor(sourceId) == null

    fun detect(url: NormalizedUrl, body: String? = null): SourceDetection =
        SourceDetector(descriptors()).detect(url, body)

    companion object {
        /** Detection-only registry: every known provider, no adapters. */
        fun detectionOnly(): SourceRegistry = SourceRegistry(KnownSources.all())

        fun empty(): SourceRegistry = SourceRegistry(emptyList(), emptyList())
    }
}
