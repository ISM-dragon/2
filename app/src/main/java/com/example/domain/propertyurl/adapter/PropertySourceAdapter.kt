package com.example.domain.propertyurl.adapter

import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.RawSourceDocument
import com.example.domain.propertyurl.model.SourceFailure
import com.example.domain.propertyurl.model.SourceFailureClassifier
import com.example.domain.propertyurl.parse.ParseOutcome
import com.example.domain.propertyurl.parse.ParserChain
import com.example.domain.propertyurl.parse.ParserContext
import com.example.domain.propertyurl.parse.SourceDocumentParser
import com.example.domain.propertyurl.port.Clock
import com.example.domain.propertyurl.port.FetchOptions
import com.example.domain.propertyurl.port.HttpFetcher
import com.example.domain.propertyurl.port.HttpRequest
import com.example.domain.propertyurl.port.SourceCredentials
import com.example.domain.propertyurl.port.TelemetrySink
import com.example.domain.propertyurl.source.PropertySourceDefinition
import com.example.domain.propertyurl.source.SourceCapabilities
import com.example.domain.propertyurl.url.ResolvedPropertyUrl

/** Static description of an adapter, used by the registry and by diagnostics. */
data class AdapterDescriptor(
    val adapterId: String,
    val sourceId: String,
    val displayName: String,
    val version: String,
    val capabilities: SourceCapabilities
)

/** Why an adapter refused to fetch. */
enum class SkipReason {
    POLICY_DISALLOWED,
    SOURCE_NOT_SUPPORTED,
    SOURCE_DISABLED,
    NOT_MODIFIED,
    DUPLICATE_SUPPRESSED
}

sealed interface SourceFetchOutcome {

    data class Fetched(
        val document: RawSourceDocument,
        val warnings: List<ImportWarning> = emptyList()
    ) : SourceFetchOutcome

    data class Failed(val failure: SourceFailure) : SourceFetchOutcome

    data class Skipped(val reason: SkipReason, val failure: SourceFailure? = null) : SourceFetchOutcome
}

/**
 * Everything an adapter is allowed to use. Adapters have no access to persistence, no access to
 * credentials beyond the one pair resolved for their source, and never see the job store — that
 * keeps the adapter contract testable and prevents accidental coupling to the Android app.
 */
class AdapterContext(
    val source: PropertySourceDefinition,
    val adapterId: String,
    val adapterVersion: String,
    val requestId: String,
    val clock: Clock,
    val httpFetcher: HttpFetcher,
    val fetchOptions: FetchOptions,
    val telemetry: TelemetrySink,
    val failureClassifier: SourceFailureClassifier,
    val parserChain: ParserChain,
    val credentials: SourceCredentials? = null
) {

    fun provenanceFactory(document: RawSourceDocument) = com.example.domain.propertyurl.model.ProvenanceFactory(
        sourceId = source.sourceId,
        adapterId = adapterId,
        adapterVersion = adapterVersion,
        sourceUrl = document.finalUrl,
        now = { clock.nowEpochMillis() }
    )

    fun parserContext(document: RawSourceDocument) = ParserContext(
        source = source,
        provenance = provenanceFactory(document),
        document = document
    )
}

/**
 * Adapter contract: turn a resolved URL into a document, and a document into facts.
 *
 * Adding Zillow/Redfin/Realtor/Homes later means implementing this interface (usually by extending
 * [BasePropertySourceAdapter] and providing a parser chain) and registering it — nothing else in the
 * layer changes.
 */
interface PropertySourceAdapter {

    val descriptor: AdapterDescriptor

    /** True when this adapter accepts the resolved URL (usually by source id). */
    fun supports(resolved: ResolvedPropertyUrl): Boolean

    /** Builds the outbound request (URL rewriting, credential headers, site specific hints). */
    fun buildRequest(resolved: ResolvedPropertyUrl, context: AdapterContext): HttpRequest

    /** Performs the (possibly multi-step) retrieval. Must classify problems, never throw. */
    suspend fun fetch(request: HttpRequest, context: AdapterContext): SourceFetchOutcome

    /** Pure parsing step: fixtures in, facts out. */
    fun parse(document: RawSourceDocument, context: AdapterContext): ParseOutcome

    /** Optional site specific parsers, appended to the shared chain. */
    fun extraParsers(): List<SourceDocumentParser> = emptyList()
}
