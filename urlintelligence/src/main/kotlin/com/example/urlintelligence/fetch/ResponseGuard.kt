package com.example.urlintelligence.fetch

import com.example.urlintelligence.adapter.SourceFetchRequest
import com.example.urlintelligence.adapter.SourceFetchResponse
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.provenance.FetchOrigin
import com.example.urlintelligence.url.PropertyUrlValidator
import com.example.urlintelligence.url.UrlParts
import com.example.urlintelligence.url.UrlValidationResult

/**
 * Validates a fetched document before any parser sees it.
 *
 * Everything a hostile, broken or simply misconfigured provider can do to the pipeline is
 * handled here — one place, one taxonomy — so individual adapters never repeat (or forget)
 * these checks:
 *
 *  * oversized / truncated / empty bodies,
 *  * content types that cannot contain a listing (PDFs, images, octet streams),
 *  * redirects that leave the provider's host family (SSRF / unexpected walk-off),
 *  * redirect chains longer than the configured budget,
 *  * final URLs that fail the SSRF/credential validator (e.g. a redirect to `127.0.0.1`),
 *  * anti-bot / consent / login pages served with HTTP 200.
 *
 * It is pure: no allocation of network resources, no state, safe to call on every attempt.
 */
class ResponseGuard(
    private val limits: FetchLimits = FetchLimits(),
    private val validator: PropertyUrlValidator = PropertyUrlValidator()
) {

    /** Inspects a successful transport response and classifies it for the parsers. */
    fun inspect(
        request: SourceFetchRequest,
        response: SourceFetchResponse.Success
    ): ResponseGuardResult {
        val byteSize = response.byteSize
        if (byteSize > limits.maxResponseBytes) {
            return ResponseGuardResult.Rejected(
                SourceFailure.PayloadTooLarge(byteSize.toLong(), limits.maxResponseBytes.toLong())
            )
        }

        val body = response.body
        if (body.isBlank()) {
            return ResponseGuardResult.Rejected(
                SourceFailure.EmptyResponse(
                    detail = "source '${request.requestUrl}' returned an empty document",
                    statusCode = response.statusCode
                )
            )
        }
        if (byteSize < limits.minResponseBytes) {
            return ResponseGuardResult.Rejected(
                SourceFailure.EmptyResponse(
                    detail = "document of $byteSize bytes is below the usable minimum",
                    statusCode = response.statusCode
                )
            )
        }

        val declared = ContentTypes.parse(response.contentType)
        val kind = when (val headerKind = ContentTypes.kindOf(response.contentType)) {
            ContentKind.UNKNOWN -> ContentTypes.sniff(body)
            else -> headerKind
        }
        if (kind == ContentKind.BINARY) {
            return ResponseGuardResult.Rejected(
                SourceFailure.UnsupportedContentType(
                    contentType = declared ?: "unknown",
                    detail = "document is not a text/HTML/JSON listing payload"
                )
            )
        }
        if (declared != null && declared !in limits.allowedContentTypes && kind != ContentKind.HTML) {
            return ResponseGuardResult.Rejected(
                SourceFailure.UnsupportedContentType(
                    contentType = declared,
                    detail = "content type is not in the fetch allow-list"
                )
            )
        }

        val softBlock = SourceFailureClassifierBridge.detectSoftBlock(response.statusCode, body)
        if (softBlock != null) return ResponseGuardResult.Rejected(softBlock)

        val noindex = SourceFailureClassifierBridge.detectMetaNoindex(response.headers)
        if (noindex != null) return ResponseGuardResult.Rejected(noindex)

        if (response.redirectCount > limits.maxRedirects) {
            return ResponseGuardResult.Rejected(
                SourceFailure.TooManyRedirects(response.redirectCount, limits.maxRedirects)
            )
        }

        val finalUrl = response.finalUrl.ifBlank { request.requestUrl }
        val redirected = finalUrl != request.requestUrl
        val crossHost = redirected && !sameHostFamily(request.requestUrl, finalUrl)
        if (crossHost || redirected) {
            // Redirect targets are re-validated: a provider must not be able to point the
            // pipeline at credentials, private hosts or non-http schemes.
            val validation = validator.validate(finalUrl)
            if (validation is UrlValidationResult.Invalid) {
                return ResponseGuardResult.Rejected(
                    SourceFailure.RedirectNotAllowed(
                        detail = "redirect target rejected (${validation.error.code}): " +
                            validation.error.message
                    )
                )
            }
            if (crossHost && !limits.allowCrossHostRedirects) {
                return ResponseGuardResult.Rejected(
                    SourceFailure.RedirectNotAllowed(
                        detail = "redirect left the source host family " +
                            "(${UrlParts.hostOf(request.requestUrl)} -> ${UrlParts.hostOf(finalUrl)})"
                    )
                )
            }
        }

        return ResponseGuardResult.Usable(
            InspectedResponse(
                body = body,
                kind = kind,
                byteSize = byteSize,
                statusCode = response.statusCode,
                requestUrl = request.requestUrl,
                finalUrl = finalUrl,
                redirected = redirected,
                redirectCount = response.redirectCount,
                crossHostRedirect = crossHost,
                contentType = declared
            )
        )
    }

    /** Origin reported by the transport, used for verification levels. */
    fun originOf(response: SourceFetchResponse.Success): FetchOrigin = response.origin

    /**
     * True when both URLs share the same host family: equal hosts, or one is a subdomain of
     * the other's registrable suffix (`www.zillow.com` vs `zillow.com`, `m.zillow.com`).
     */
    fun sameHostFamily(first: String, second: String): Boolean {
        val a = UrlParts.hostOf(first).removePrefix("www.")
        val b = UrlParts.hostOf(second).removePrefix("www.")
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return a.endsWith(".$b") || b.endsWith(".$a")
    }
}

/**
 * Thin indirection so [ResponseGuard] (fetch layer) can reuse the failure taxonomy without
 * the fetch package depending on adapter internals. Kept internal to the module.
 */
internal object SourceFailureClassifierBridge {
    fun detectSoftBlock(statusCode: Int, body: String): SourceFailure? =
        com.example.urlintelligence.failure.SourceFailureClassifier.detectSoftBlock(statusCode, body)

    fun detectMetaNoindex(headers: Map<String, String>): SourceFailure? =
        com.example.urlintelligence.failure.SourceFailureClassifier.detectMetaNoindex(headers)
}
