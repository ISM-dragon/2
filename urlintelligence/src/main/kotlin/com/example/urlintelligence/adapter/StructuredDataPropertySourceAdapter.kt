package com.example.urlintelligence.adapter

import com.example.urlintelligence.fetch.FetchLimits
import com.example.urlintelligence.fetch.ResponseGuard
import com.example.urlintelligence.fetch.ResponseGuardResult
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.failure.SourceFailureClassifier
import com.example.urlintelligence.html.Html
import com.example.urlintelligence.html.StructuredData
import com.example.urlintelligence.idempotency.Digests
import com.example.urlintelligence.model.PropertyDraft
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.normalization.AddressParser
import com.example.urlintelligence.parser.DriftLevel
import com.example.urlintelligence.parser.DriftPolicy
import com.example.urlintelligence.parser.ParserSpec
import com.example.urlintelligence.parser.SchemaDriftReport
import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.provenance.ExtractionMethod
import com.example.urlintelligence.retry.Clock
import com.example.urlintelligence.source.SourceDescriptor
import com.example.urlintelligence.url.PropertyUrlValidator
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
 * On top of extraction the base class owns everything that must happen for *every*
 * provider document, so subclasses cannot forget it:
 *
 *  1. **response guarding** — size, content type, redirects, empty bodies and anti-bot /
 *     login / consent interstitials are rejected before parsing ([ResponseGuard]),
 *  2. **parser versioning** — the adapter's [parserSpec] id/version is stamped onto every
 *     extracted field's provenance,
 *  3. **schema-drift detection** — the document is compared to the parser's versioned
 *     signature; drift is a warning ([DriftPolicy.WARN]) or a typed failure
 *     ([DriftPolicy.FAIL]) instead of silently importing less data,
 *  4. **verification bookkeeping** — fields extracted from a drifted document are downgraded
 *     to `UNVERIFIED`, and only a transport that reports a live fetch can produce
 *     live-verified fields.
 *
 * Subclasses declare a selector table plus a [parserSpec]; a fully data-driven provider can
 * use [DeclarativeSourceAdapter] instead of subclassing.
 */
abstract class StructuredDataPropertySourceAdapter(
    private val transport: PropertyHttpTransport,
    override val descriptor: SourceDescriptor,
    private val clock: Clock = Clock.SYSTEM,
    private val limits: FetchLimits = FetchLimits(),
    private val validator: PropertyUrlValidator = PropertyUrlValidator(),
    /** Overridable drift policy; a spec may carry its own, this is the default when it does not. */
    private val driftPolicy: DriftPolicy = DriftPolicy.WARN
) : PropertySourceAdapter {

    protected val extractorName: String
        get() = this.javaClass.simpleName.ifBlank { descriptor.id }

    private val guard = ResponseGuard(limits, validator)

    /**
     * Versioned parser contract for this provider. Subclasses must declare one: it is what
     * makes drift observable and every field traceable to a parser version.
     */
    abstract val parserSpec: ParserSpec

    override suspend fun fetch(request: SourceFetchRequest): SourceFetchResponse = transport.execute(request)

    /** Exposed so hosts and tests can pre-validate without parsing. */
    fun inspectResponse(
        request: SourceFetchRequest,
        response: SourceFetchResponse.Success
    ): ResponseGuardResult = guard.inspect(request, response)

    final override fun parse(
        response: SourceFetchResponse.Success,
        request: SourceFetchRequest
    ): PropertyParseResult {
        val extractor = extractorName
        val body = response.body
        val warnings = ArrayList<String>()

        // 1. Transport-level safety net. Runs even for transports that already enforce limits.
        val guardResult = guard.inspect(request, response)
        val inspected = when (guardResult) {
            is ResponseGuardResult.Rejected -> return PropertyParseResult.Failure(guardResult.failure)
            is ResponseGuardResult.Usable -> guardResult.inspected
        }

        // Defense in depth: the document itself may carry an anti-bot/login wall even when the
        // transport reported a plain 200 (a transport that ignores the guard cannot bypass this).
        val softBlock = SourceFailureClassifier.detectSoftBlock(response.statusCode, body)
        if (softBlock != null) return PropertyParseResult.Failure(softBlock)

        val documentDigest = response.documentDigest ?: Digests.sha256Hex(body)

        val draft = PropertyDraft(
            sourceId = descriptor.id,
            requestUrl = request.requestUrl,
            resolvedUrl = inspected.finalUrl.ifBlank { request.requestUrl }
        )
        draft.parserId = parserSpec.parserId
        draft.parserVersion = parserSpec.version
        draft.documentDigest = documentDigest
        draft.origin = response.origin

        draft.sourcePropertyId = request.sourcePropertyId
            ?: descriptor.extractIdFromPath(UrlParts.pathOf(inspected.finalUrl))
            ?: descriptor.extractIdFromPath(UrlParts.pathOf(request.requestUrl))
            ?: descriptor.extractIdFromBody(body)

        if (inspected.redirected) {
            warnings.add(
                "followed ${inspected.redirectCount} redirect(s) to ${UrlParts.hostOf(inspected.finalUrl)}"
            )
        }

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

        val applied = structuredApplied + metaApplied + selectorApplied + hookApplied
        if (applied == 0) {
            return PropertyParseResult.Failure(
                SourceFailure.ParseError(
                    detail = "no extractor matched the ${descriptor.id} document",
                    extractor = extractor
                )
            )
        }

        // 2. Schema-drift detection against the parser's versioned contract.
        val drift = parserSpec.inspect(body, applied)
        draft.drift = drift
        if (drift.isDrift) {
            warnings.add(drift.summary())
            if (drift.level == DriftLevel.MAJOR && policyFor(drift) == DriftPolicy.FAIL) {
                return PropertyParseResult.Failure(driftFailure(drift))
            }
            if (parserSpec.downgradeUnverifiedOnDrift && drift.level == DriftLevel.MAJOR) {
                draft.downgradeVerificationToUnverified()
            }
            if (drift.level == DriftLevel.MAJOR) {
                warnings.add(
                    "parser ${drift.parserId}@${drift.parserVersion} no longer matches the page; " +
                        "fields are not parser-verified"
                )
            }
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

    private fun policyFor(drift: SchemaDriftReport): DriftPolicy = when {
        drift.level == DriftLevel.NONE -> DriftPolicy.OFF
        else -> driftPolicy
    }

    private fun driftFailure(drift: SchemaDriftReport): SourceFailure.SchemaDrift =
        SourceFailure.SchemaDrift(
            parserId = drift.parserId,
            parserVersion = drift.parserVersion,
            level = drift.level.name,
            detail = drift.detail,
            missingProbes = drift.missingProbes
        )

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

/**
 * A provider expressed purely as data: descriptor + versioned [ParserSpec] + selector table.
 *
 * This is the extension point for "the next provider": a new portal needs no new class and
 * no change to the pipeline — [SourceRegistry] binds the descriptor and the adapter, and the
 * resolver, job machine, retry, provenance and idempotency layers are untouched.
 */
class DeclarativeSourceAdapter(
    transport: PropertyHttpTransport,
    descriptor: SourceDescriptor,
    override val parserSpec: ParserSpec,
    private val selectorTable: List<FieldSelector>,
    clock: Clock = Clock.SYSTEM,
    limits: FetchLimits = FetchLimits(),
    validator: PropertyUrlValidator = PropertyUrlValidator(),
    driftPolicy: DriftPolicy = DriftPolicy.WARN
) : StructuredDataPropertySourceAdapter(transport, descriptor, clock, limits, validator, driftPolicy) {

    override fun selectors(): List<FieldSelector> = selectorTable

    companion object {
        /** Wraps an existing adapter's selector table for a provider that needs no custom code. */
        fun of(
            transport: PropertyHttpTransport,
            descriptor: SourceDescriptor,
            parserSpec: ParserSpec,
            selectors: List<FieldSelector>
        ): DeclarativeSourceAdapter =
            DeclarativeSourceAdapter(transport, descriptor, parserSpec, selectors)
    }
}
