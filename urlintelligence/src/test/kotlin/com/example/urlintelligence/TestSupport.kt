package com.example.urlintelligence

import com.example.urlintelligence.adapter.PropertyHttpTransport
import com.example.urlintelligence.adapter.PropertySourceAdapter
import com.example.urlintelligence.adapter.SourceFetchRequest
import com.example.urlintelligence.adapter.SourceFetchResponse
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.model.PropertyDraft
import com.example.urlintelligence.retry.Clock
import com.example.urlintelligence.retry.Sleeper
import com.example.urlintelligence.source.SourceDescriptor
import java.io.ByteArrayOutputStream

/** Loads HTML fixtures from `src/test/resources/fixtures`. */
object Fixtures {

    fun load(name: String): String {
        val stream = Fixtures::class.java.classLoader.getResourceAsStream("fixtures/$name")
            ?: error("fixture not found: $name")
        val out = ByteArrayOutputStream()
        stream.use { input -> input.copyTo(out) }
        return out.toString("UTF-8")
    }

    const val ZILLOW_URL: String =
        "https://www.zillow.com/homedetails/2418-S-Congress-Ave-Austin-TX-78704/12345678_zpid/"
    const val REDFIN_URL: String =
        "https://www.redfin.com/TX/Austin/2418-S-Congress-Ave-Austin-TX-78704/home/98765432"
    const val REALTOR_URL: String =
        "https://www.realtor.com/realestateandhomes-detail/842-Piedmont-Ave-NE_Atlanta_GA-30308-AB12CD34"
    const val HOMES_URL: String =
        "https://www.homes.com/property/12405-memorial-dr-houston-tx-77024/5544332211/"
    const val GENERIC_URL: String =
        "https://example-brokerage.test/listings/4821-maple-ridge-dr"
}

/** Deterministic clock for tests. */
class TestClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun now(): Long = now
    fun advance(millis: Long) {
        now += millis
    }
}

/** Sleeper that records delays instead of waiting. */
class RecordingSleeper : Sleeper {
    val delays = mutableListOf<Long>()

    override suspend fun delay(millis: Long) {
        delays += millis
    }
}

/**
 * Scriptable transport.
 *
 * @param bodies document returned per call (rotates; the last one repeats)
 * @param statuses HTTP status per attempt
 * @param failures failure to return per attempt (wins over statuses/bodies)
 */
class FakeTransport(
    private val bodies: List<String> = listOf(""),
    private val statuses: List<Int> = listOf(200),
    private val failures: List<SourceFailure?> = listOf(null),
    private val finalUrls: List<String>? = null,
    private val headers: Map<String, String> = emptyMap()
) : PropertyHttpTransport {

    val requests: MutableList<SourceFetchRequest> = mutableListOf()

    override suspend fun execute(request: SourceFetchRequest): SourceFetchResponse {
        requests += request
        val index = (request.attempt - 1).coerceAtLeast(0)
        val failure = if (index < failures.size) failures[index] else failures.lastOrNull()
        if (failure != null) return SourceFetchResponse.Failure(failure)

        val status = if (index < statuses.size) statuses[index] else statuses.lastOrNull() ?: 200
        val callIndex = requests.size - 1
        val body = if (callIndex < bodies.size) bodies[callIndex] else bodies.lastOrNull() ?: ""
        val finalUrl = finalUrls?.let { if (index < it.size) it[index] else it.lastOrNull() }
            ?: request.requestUrl

        if (status != 200) {
            return SourceFetchResponse.Failure(
                SourceFailureClassifierForTests.fromStatus(status, headers)
            )
        }
        return SourceFetchResponse.Success(
            statusCode = status,
            body = body,
            contentType = "text/html",
            finalUrl = finalUrl,
            fetchedAtEpochMillis = 1_700_000_000_000L,
            headers = headers
        )
    }
}

/** Test-only re-export so FakeTransport does not depend on production naming. */
object SourceFailureClassifierForTests {
    fun fromStatus(statusCode: Int, headers: Map<String, String>): SourceFailure =
        com.example.urlintelligence.failure.SourceFailureClassifier.fromStatus(statusCode, headers)
}

/** Minimal adapter used to exercise registry wiring without touching the network. */
class FakeAdapter(
    override val descriptor: SourceDescriptor,
    private val draftProducer: (SourceFetchRequest) -> PropertyDraft = {
        PropertyDraft(descriptor.id, it.requestUrl).apply {
            sourcePropertyId = it.sourcePropertyId
        }
    }
) : PropertySourceAdapter {

    var fetchCalls: Int = 0
        private set

    override suspend fun fetch(request: SourceFetchRequest): SourceFetchResponse {
        fetchCalls++
        return SourceFetchResponse.Success(
            statusCode = 200,
            body = "<html></html>",
            contentType = "text/html",
            finalUrl = request.requestUrl,
            fetchedAtEpochMillis = 0L
        )
    }

    override fun parse(
        response: SourceFetchResponse.Success,
        request: SourceFetchRequest
    ): com.example.urlintelligence.adapter.PropertyParseResult =
        com.example.urlintelligence.adapter.PropertyParseResult.Success(draftProducer(request))
}
