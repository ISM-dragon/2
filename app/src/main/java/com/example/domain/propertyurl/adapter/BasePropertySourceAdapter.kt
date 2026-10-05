package com.example.domain.propertyurl.adapter

import com.example.domain.propertyurl.model.AntiBotDetector
import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.RawSourceDocument
import com.example.domain.propertyurl.model.SourceFailure
import com.example.domain.propertyurl.model.SourceFailureKind
import com.example.domain.propertyurl.parse.ParseOutcome
import com.example.domain.propertyurl.parse.ParserChain
import com.example.domain.propertyurl.parse.SourceDocumentParser
import com.example.domain.propertyurl.port.HttpFetchResult
import com.example.domain.propertyurl.port.HttpRequest
import com.example.domain.propertyurl.url.ResolvedPropertyUrl
import com.example.domain.propertyurl.util.Redaction

/**
 * Default adapter implementation: HTTP GET through the injected [com.example.domain.propertyurl.port.HttpFetcher],
 * failure classification, then the shared parser chain (+ adapter specific parsers).
 *
 * Site specific adapters normally override:
 *  - [buildRequest] to add per-source hints (e.g. a required `Accept` or a locale cookie),
 *  - [extraParsers] to add a parser for a payload only that site ships,
 *  - [fetch] when the source needs a multi-step flow (token exchange, GraphQL POST, pagination).
 */
abstract class BasePropertySourceAdapter : PropertySourceAdapter {

    override fun supports(resolved: ResolvedPropertyUrl): Boolean {
        val detected = resolved.detection.definition ?: return false
        return detected.sourceId == descriptor.sourceId
    }

    override fun buildRequest(resolved: ResolvedPropertyUrl, context: AdapterContext): HttpRequest =
        HttpRequest(url = resolved.url.normalized, headers = requestHeaders(resolved, context))

    /** Credentials (if any) are attached here and only here; they never reach logs or the job store. */
    protected open fun requestHeaders(resolved: ResolvedPropertyUrl, context: AdapterContext): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        context.credentials?.takeIf { !it.isEmpty }?.asHeaders()?.forEach { (key, value) -> headers[key] = value }
        return headers
    }

    override suspend fun fetch(request: HttpRequest, context: AdapterContext): SourceFetchOutcome {
        val result = try {
            context.httpFetcher.fetch(request, effectiveFetchOptions(context))
        } catch (e: InterruptedException) {
            return SourceFetchOutcome.Failed(
                context.failureClassifier.fromTransport(
                    kindName = "CANCELLED",
                    message = "fetch interrupted",
                    sourceId = descriptor.sourceId,
                    adapterId = descriptor.adapterId,
                    url = Redaction.url(request.url)
                )
            )
        }

        return when (result) {
            is HttpFetchResult.TransportError -> SourceFetchOutcome.Failed(
                context.failureClassifier.fromTransport(
                    kindName = result.kind.name,
                    message = result.message,
                    causeType = result.causeType,
                    sourceId = descriptor.sourceId,
                    adapterId = descriptor.adapterId,
                    url = Redaction.url(result.requestedUrl)
                )
            )

            is HttpFetchResult.Response -> handleResponse(request, result, context)
        }
    }

    protected open fun effectiveFetchOptions(context: AdapterContext) = context.fetchOptions

    private fun handleResponse(
        request: HttpRequest,
        response: HttpFetchResult.Response,
        context: AdapterContext
    ): SourceFetchOutcome {
        val redactedUrl = Redaction.url(response.finalUrl)

        if (!response.isSuccess) {
            val failure = context.failureClassifier.fromHttp(
                status = response.status,
                headers = response.headers,
                bodySnippet = response.body.orEmpty().take(8192),
                bodyLength = response.bodyBytes,
                sourceId = descriptor.sourceId,
                adapterId = descriptor.adapterId,
                url = redactedUrl
            )
            return SourceFetchOutcome.Failed(
                failure ?: context.failureClassifier.internal(
                    "non-success response without classification",
                    descriptor.sourceId,
                    descriptor.adapterId
                )
            )
        }

        val body = response.body
        if (body.isNullOrBlank()) {
            return SourceFetchOutcome.Failed(
                SourceFailure(
                    kind = SourceFailureKind.EMPTY_RESPONSE,
                    message = "empty body for ${response.status}",
                    httpStatus = response.status,
                    sourceId = descriptor.sourceId,
                    adapterId = descriptor.adapterId,
                    url = redactedUrl,
                    occurredAtEpochMillis = context.clock.nowEpochMillis()
                )
            )
        }

        if (response.truncated) {
            // Truncated payloads are still usable (structured data is near the top) but flagged.
        }

        val interstitial = AntiBotDetector.detect(
            status = response.status,
            headers = response.headers,
            bodySnippet = body.take(8192),
            isBodySuspiciouslySmall = response.bodyBytes < AntiBotDetector.SMALL_BODY_THRESHOLD_BYTES
        )
        if (interstitial != null) {
            return SourceFetchOutcome.Failed(
                SourceFailure(
                    kind = interstitial,
                    message = "interstitial page detected (${response.status}, ${response.bodyBytes} bytes)",
                    httpStatus = response.status,
                    sourceId = descriptor.sourceId,
                    adapterId = descriptor.adapterId,
                    url = redactedUrl,
                    occurredAtEpochMillis = context.clock.nowEpochMillis()
                )
            )
        }

        if (!isSupportedContentType(response)) {
            return SourceFetchOutcome.Failed(
                SourceFailure(
                    kind = SourceFailureKind.UNSUPPORTED_CONTENT_TYPE,
                    message = "unsupported content type: ${response.contentType ?: "unknown"}",
                    httpStatus = response.status,
                    sourceId = descriptor.sourceId,
                    adapterId = descriptor.adapterId,
                    url = redactedUrl,
                    occurredAtEpochMillis = context.clock.nowEpochMillis()
                )
            )
        }

        val document = RawSourceDocument(
            requestedUrl = request.url,
            finalUrl = response.finalUrl,
            httpStatus = response.status,
            contentType = response.contentType,
            headers = Redaction.headers(response.headers),
            body = body,
            bodyBytes = response.bodyBytes,
            retrievedAtEpochMillis = context.clock.nowEpochMillis(),
            elapsedMillis = response.elapsedMillis,
            truncated = response.truncated,
            redirects = response.redirects
        )

        val warnings = ArrayList<ImportWarning>()
        if (response.redirects.isNotEmpty()) {
            warnings.add(
                ImportWarning(
                    code = ImportWarning.WarningCode.STALE_CACHE_REUSED,
                    message = "URL redirected ${response.redirects.size} time(s)",
                    sourceId = descriptor.sourceId,
                    detail = Redaction.url(response.redirects.last())
                )
            )
        }
        if (response.truncated) {
            warnings.add(
                ImportWarning(
                    code = ImportWarning.WarningCode.PARTIAL_PARSE,
                    message = "response body was truncated at ${response.bodyBytes} bytes",
                    sourceId = descriptor.sourceId
                )
            )
        }
        return SourceFetchOutcome.Fetched(document, warnings)
    }

    protected open fun isSupportedContentType(response: HttpFetchResult.Response): Boolean {
        val contentType = response.contentType?.lowercase()
        if (contentType == null) return response.isHtml || response.isJson
        return contentType.contains("html") ||
            contentType.contains("json") ||
            contentType.contains("xml") ||
            contentType.contains("text/plain")
    }

    override fun parse(document: RawSourceDocument, context: AdapterContext): ParseOutcome {
        val extras = extraParsers()
        val chain = if (extras.isEmpty()) context.parserChain else ParserChain(context.parserChain.parsers + extras)
        return chain.parse(document, context.parserContext(document))
    }

    override fun extraParsers(): List<SourceDocumentParser> = emptyList()
}
