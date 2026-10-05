package com.example.urlintelligence.adapter

import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.failure.SourceFailureClassifier
import com.example.urlintelligence.html.Html
import com.example.urlintelligence.html.StructuredData
import com.example.urlintelligence.model.PropertyDraft
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.normalization.AddressParser
import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.provenance.ExtractionMethod
import com.example.urlintelligence.retry.Clock
import com.example.urlintelligence.source.SourceDescriptor
import com.example.urlintelligence.url.UrlParts

/**
 * Shared implementation for HTML sources that publish schema.org data.
 *
 * Extraction is layered best-first — structured data, then meta tags, then the
 * adapter's own selector table — and [PropertyDraft.put] guarantees a weaker
 * signal can never overwrite a stronger one:
 *
 *   JSON-LD (EXACT)  >  meta tags (MEDIUM)  >  selector heuristics (LOW..MEDIUM)
 *
 * Subclasses only declare a selector table (and, if needed, override the hooks),
 * which is what makes adding Zillow/Redfin/Realtor/Homes (or the next provider)
 * a small, testable, additive change.
 */
abstract class StructuredDataPropertySourceAdapter(
    private val transport: PropertyHttpTransport,
    override val descriptor: SourceDescriptor,
    private val clock: Clock = Clock.SYSTEM
) : PropertySourceAdapter {

    protected val extractorName: String
        get() = this.javaClass.simpleName.ifBlank { descriptor.id }

    override suspend fun fetch(request: SourceFetchRequest): SourceFetchResponse = transport.execute(request)

    final override fun parse(
        response: SourceFetchResponse.Success,
        request: SourceFetchRequest
    ): PropertyParseResult {
        val extractor = extractorName
        val body = response.body
        val warnings = ArrayList<String>()

        if (body.isBlank()) {
            return PropertyParseResult.Failure(
                SourceFailure.ParseError("empty document returned by ${descriptor.id}", extractor)
            )
        }
        val softBlock = SourceFailureClassifier.detectSoftBlock(response.statusCode, body)
        if (softBlock != null) return PropertyParseResult.Failure(softBlock)

        val draft = PropertyDraft(
            sourceId = descriptor.id,
            requestUrl = request.requestUrl,
            resolvedUrl = response.finalUrl.ifBlank { request.requestUrl }
        )

        draft.sourcePropertyId = request.sourcePropertyId
            ?: descriptor.extractIdFromPath(UrlParts.pathOf(response.finalUrl))
            ?: descriptor.extractIdFromPath(UrlParts.pathOf(request.requestUrl))
            ?: descriptor.extractIdFromBody(body)

        val malformed = ArrayList<String>()
        val objects = StructuredData.objects(body, malformed)
        val primary = StructuredData.propertyLike(objects)
        val structuredApplied = if (primary != null) {
            SchemaOrgMapper.apply(draft, primary, extractor)
        } else {
            warnings.add("no schema.org property node found in ${descriptor.id} document")
            0
        }
        if (malformed.isNotEmpty()) {
            warnings.add("skipped ${malformed.size} malformed JSON-LD block(s)")
        }

        val metaApplied = applyMetaTags(draft, body, extractor)
        val selectorApplied = SelectorRunner.apply(draft, body, selectors(), extractor)
        val hookApplied = onDocumentParsed(draft, body, extractor)

        if (structuredApplied == 0 && metaApplied == 0 && selectorApplied == 0 && hookApplied == 0) {
            return PropertyParseResult.Failure(
                SourceFailure.ParseError(
                    detail = "no extractor matched the ${descriptor.id} document",
                    extractor = extractor
                )
            )
        }

        val missingCore = PropertyField.REQUIRED_FIELDS - draft.present()
        return when {
            missingCore.isEmpty() -> PropertyParseResult.Success(draft)
            draft.present().isEmpty() -> PropertyParseResult.Failure(
                SourceFailure.ParseError("document contained no property fields", extractor)
            )
            else -> PropertyParseResult.Partial(
                draft = draft,
                warnings = warnings + "missing core fields: " +
                    missingCore.joinToString { it.stableName }
            )
        }
    }

    /** Adapter-specific extraction rules, applied after structured data and meta tags. */
    protected abstract fun selectors(): List<FieldSelector>

    /** Optional hook for markup that cannot be expressed as a selector table. */
    protected open fun onDocumentParsed(draft: PropertyDraft, body: String, extractor: String): Int = 0

    /** og:* / twitter:* / <title> fallbacks, shared by every HTML source. */
    protected fun applyMetaTags(draft: PropertyDraft, body: String, extractor: String): Int {
        var applied = 0

        val image = Html.metaContent(body, "og:image")
            ?: Html.metaContent(body, "twitter:image")
            ?: Html.metaContent(body, "og:image:url")
        if (image != null && image.startsWith("http", ignoreCase = true)) {
            if (draft.put(
                    PropertyField.PRIMARY_IMAGE_URL,
                    image,
                    ExtractionMethod.META_TAG,
                    Confidence.MEDIUM,
                    extractor,
                    image
                )
            ) applied++
            if (draft.put(
                    PropertyField.IMAGE_URLS,
                    listOf(image),
                    ExtractionMethod.META_TAG,
                    Confidence.MEDIUM,
                    extractor,
                    image
                )
            ) applied++
        }

        val description = Html.metaContent(body, "og:description")
            ?: Html.metaContent(body, "description")
        if (description != null && draft.put(
                PropertyField.DESCRIPTION,
                description,
                ExtractionMethod.META_TAG,
                Confidence.MEDIUM,
                extractor,
                description
            )
        ) applied++

        val title = Html.metaContent(body, "og:title") ?: titleOf(body)
        if (title != null && looksLikeAddress(title)) {
            val parsed = AddressParser.parseOneLine(title)
            if (draft.put(
                    PropertyField.ADDRESS_LINE1,
                    parsed.line1,
                    ExtractionMethod.META_TAG,
                    Confidence.MEDIUM,
                    extractor,
                    title
                )
            ) applied++
            if (draft.put(PropertyField.UNIT, parsed.unit, ExtractionMethod.META_TAG, Confidence.LOW, extractor, title)) applied++
            if (draft.put(PropertyField.CITY, parsed.city, ExtractionMethod.META_TAG, Confidence.MEDIUM, extractor, title)) applied++
            if (draft.put(PropertyField.STATE, parsed.state, ExtractionMethod.META_TAG, Confidence.MEDIUM, extractor, title)) applied++
            if (draft.put(PropertyField.POSTAL_CODE, parsed.postalCode, ExtractionMethod.META_TAG, Confidence.MEDIUM, extractor, title)) applied++
        }

        return applied
    }

    protected fun titleOf(body: String): String? {
        val match = TITLE_RE.find(body) ?: return null
        return Html.decodeEntities(Html.stripTags(match.groupValues[1])).trim().takeIf { it.isNotEmpty() }
    }

    protected fun looksLikeAddress(candidate: String): Boolean {
        val value = candidate.trim()
        if (value.length !in 8..160) return false
        if (!value.first().isDigit()) return false
        return value.contains(',')
    }

    protected fun clockNow(): Long = clock.now()

    private companion object {
        val TITLE_RE = Regex(
            "<title[^>]*>(.*?)</title>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
    }
}
