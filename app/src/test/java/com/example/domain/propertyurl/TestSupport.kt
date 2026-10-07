package com.example.domain.propertyurl

import com.example.domain.propertyurl.adapter.AdapterContext
import com.example.domain.propertyurl.model.ExtractedFacts
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.ProvenanceFactory
import com.example.domain.propertyurl.model.RawSourceDocument
import com.example.domain.propertyurl.parse.ParserContext
import com.example.domain.propertyurl.source.SourceCatalog
import com.example.domain.propertyurl.source.PropertySourceDefinition
import com.example.domain.propertyurl.source.SourceRegistry
import com.example.domain.propertyurl.model.SourceFailure
import com.example.domain.propertyurl.model.SourceFailureKind
import com.example.domain.propertyurl.port.Clock
import com.example.domain.propertyurl.port.FetchOptions
import com.example.domain.propertyurl.port.HttpFetchResult
import com.example.domain.propertyurl.port.HttpFetcher
import com.example.domain.propertyurl.port.HttpRequest
import com.example.domain.propertyurl.port.IdGenerator
import com.example.domain.propertyurl.port.Sleeper
import com.example.domain.propertyurl.port.TelemetryEvent
import com.example.domain.propertyurl.port.TelemetrySink
import com.example.domain.propertyurl.port.TransportFailureKind
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

// --- Convenience accessors used by the parser tests ------------------------------------------------

/** Strongest text value for a field (or null). */
fun ExtractedFacts.textOf(field: PropertyField): String? = bestFact(field)?.value?.asText()

/** Strongest numeric value for a field (or null). */
fun ExtractedFacts.numberOf(field: PropertyField): Double? = bestFact(field)?.value?.asDecimal()

/** All text values for a field, in extraction order. */
fun ExtractedFacts.textsOf(field: PropertyField): List<String> =
    forField(field).flatMap { it.value.asTextList() }

private fun ExtractedFacts.bestFact(field: PropertyField) =
    forField(field).maxByOrNull { it.provenance.confidence }

/** [ParserContext] for fixture-driven tests. */
fun testParserContext(
    document: RawSourceDocument,
    sourceId: String = "zillow",
    adapterId: String = "test-adapter",
    registry: SourceRegistry = SourceRegistry.build(SourceCatalog.ALL),
    now: () -> Long = { 1_700_000_000_000L }
): ParserContext = ParserContext(
    source = registry.byId(sourceId) ?: SourceCatalog.GENERIC_WEB,
    provenance = ProvenanceFactory(
        sourceId = sourceId,
        adapterId = adapterId,
        adapterVersion = "1.0.0-test",
        sourceUrl = document.finalUrl,
        now = now
    ),
    document = document
)

fun definitionFor(sourceId: String): PropertySourceDefinition =
    SourceRegistry.build(SourceCatalog.ALL).byId(sourceId) ?: SourceCatalog.GENERIC_WEB

/** [AdapterContext] for adapter contract tests (canned transport, no real clock, no telemetry sink). */
fun testAdapterContext(
    sourceId: String,
    adapterId: String,
    fetcher: FakeHttpFetcher = FakeHttpFetcher(),
    clock: Clock = FixedClock(),
    telemetry: TelemetrySink = RecordingTelemetry(),
    registry: SourceRegistry = SourceRegistry.build(SourceCatalog.ALL),
    parserChain: com.example.domain.propertyurl.parse.ParserChain = com.example.domain.propertyurl.parse.ParserChain(
        listOf(
            com.example.domain.propertyurl.parse.SchemaOrgJsonLdParser(),
            com.example.domain.propertyurl.parse.EmbeddedJsonStateParser(),
            com.example.domain.propertyurl.parse.MetaAndTitleFactsParser(),
            com.example.domain.propertyurl.parse.VisibleTextFactsParser()
        )
    )
): AdapterContext = AdapterContext(
    source = registry.byId(sourceId) ?: SourceCatalog.GENERIC_WEB,
    adapterId = adapterId,
    adapterVersion = "1.0.0-test",
    requestId = "test-request",
    clock = clock,
    httpFetcher = fetcher,
    fetchOptions = com.example.domain.propertyurl.port.FetchOptions(),
    telemetry = telemetry,
    failureClassifier = com.example.domain.propertyurl.model.SourceFailureClassifier { clock.nowEpochMillis() },
    parserChain = parserChain
)

/** Deterministic clock; tests advance it explicitly. */
class FixedClock(private var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowEpochMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

/** Sequential ids so assertions can rely on stable names. */
class SequentialIdGenerator : IdGenerator {
    private val counter = AtomicInteger(0)
    override fun newId(prefix: String): String = "$prefix-${counter.incrementAndGet()}"
}

/** Records every requested sleep instead of waiting (retry loops stay instant). */
class RecordingSleeper : Sleeper {
    val sleeps = ArrayList<Long>()
    override suspend fun sleep(millis: Long) {
        if (millis > 0) sleeps.add(millis)
    }

    val totalMillis: Long get() = sleeps.sum()
}

class RecordingTelemetry : TelemetrySink {
    val events = ArrayList<TelemetryEvent>()
    override fun record(event: TelemetryEvent) {
        events.add(event)
    }

    fun names(): List<String> = events.map { it.name }

    fun has(name: String): Boolean = events.any { it.name == name }
}

/** Canned HTTP transport: routes match on a substring of the URL. */
class FakeHttpFetcher : HttpFetcher {

    data class Route(
        val match: String,
        val status: Int = 200,
        val body: String = "",
        val contentType: String = "text/html; charset=utf-8",
        val finalUrl: String? = null,
        val failuresBeforeSuccess: Int = 0,
        val transportError: TransportFailureKind? = null
    )

    private val routes = ArrayList<Route>()
    private val hits = LinkedHashMap<String, Int>()

    fun on(
        match: String,
        status: Int = 200,
        body: String = "",
        contentType: String = "text/html; charset=utf-8",
        finalUrl: String? = null,
        failuresBeforeSuccess: Int = 0,
        transportError: TransportFailureKind? = null
    ): FakeHttpFetcher {
        routes.add(Route(match, status, body, contentType, finalUrl, failuresBeforeSuccess, transportError))
        return this
    }

    fun hitsFor(match: String): Int = hits[match] ?: 0

    val totalHits: Int get() = hits.values.sum()

    override suspend fun fetch(request: HttpRequest, options: FetchOptions): HttpFetchResult {
        val route = routes.firstOrNull { request.url.contains(it.match) }
            ?: return HttpFetchResult.TransportError(
                kind = TransportFailureKind.DNS_FAILURE,
                message = "no route for ${request.url}",
                requestedUrl = request.url
            )

        val count = (hits[route.match] ?: 0) + 1
        hits[route.match] = count

        if (route.transportError != null) {
            return HttpFetchResult.TransportError(
                kind = route.transportError,
                message = "canned transport error",
                requestedUrl = request.url
            )
        }

        if (count <= route.failuresBeforeSuccess) {
            return response(route, request.url, 503, "<html><body>service unavailable</body></html>")
        }

        return response(route, request.url, route.status, route.body)
    }

    private fun response(route: Route, requestedUrl: String, status: Int, body: String): HttpFetchResult.Response =
        HttpFetchResult.Response(
            requestedUrl = requestedUrl,
            finalUrl = route.finalUrl ?: requestedUrl,
            status = status,
            headers = mapOf("content-type" to route.contentType),
            body = body,
            bodyBytes = body.toByteArray().size,
            contentType = route.contentType,
            elapsedMillis = 5
        )
}

/** Loads test fixtures from the classpath (Gradle) or the project tree (plain JVM runs). */
object Fixtures {

    private const val ROOT = "fixtures/property-url-intelligence"

    fun text(name: String): String {
        val resource = Thread.currentThread().contextClassLoader.getResourceAsStream("$ROOT/$name")
        if (resource != null) {
            return resource.use { it.readBytes().decodeToString() }
        }
        val candidates = listOf(
            File("app/src/test/resources/$ROOT/$name"),
            File("src/test/resources/$ROOT/$name"),
            File("../app/src/test/resources/$ROOT/$name")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw IllegalStateException("Fixture '$name' not found (looked at ${candidates.joinToString()})")
        return file.readText()
    }

    fun document(
        name: String,
        url: String,
        contentType: String = "text/html; charset=utf-8"
    ): RawSourceDocument {
        val body = text(name)
        return RawSourceDocument(
            requestedUrl = url,
            finalUrl = url,
            httpStatus = 200,
            contentType = contentType,
            headers = mapOf("content-type" to contentType),
            body = body,
            bodyBytes = body.toByteArray().size,
            retrievedAtEpochMillis = 1_700_000_000_000L
        )
    }

    fun failure(kind: SourceFailureKind = SourceFailureKind.PARSE_FAILED): SourceFailure = SourceFailure(
        kind = kind,
        message = "canned fixture failure",
        sourceId = "zillow",
        occurredAtEpochMillis = 1_700_000_000_000L
    )
}
