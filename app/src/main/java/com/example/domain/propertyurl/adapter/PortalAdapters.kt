package com.example.domain.propertyurl.adapter

import com.example.domain.propertyurl.json.JsonParser
import com.example.domain.propertyurl.json.JsonValue
import com.example.domain.propertyurl.model.ExtractedFacts
import com.example.domain.propertyurl.model.ExtractedFactsBuilder
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.ProvenanceMethod
import com.example.domain.propertyurl.model.RawSourceDocument
import com.example.domain.propertyurl.normalize.ValueGuards
import com.example.domain.propertyurl.normalize.ValueParsing
import com.example.domain.propertyurl.parse.HtmlScanner
import com.example.domain.propertyurl.parse.ParserContext
import com.example.domain.propertyurl.parse.ParserResult
import com.example.domain.propertyurl.parse.SourceDocumentParser
import com.example.domain.propertyurl.port.HttpRequest
import com.example.domain.propertyurl.source.PropertySourceDefinition
import com.example.domain.propertyurl.source.SourceCatalog
import com.example.domain.propertyurl.url.ResolvedPropertyUrl

/** Builds a portal adapter descriptor from its source definition. */
private fun portalDescriptor(
    source: PropertySourceDefinition,
    adapterId: String,
    version: String
) = AdapterDescriptor(
    adapterId = adapterId,
    sourceId = source.sourceId,
    displayName = source.displayName,
    version = version,
    capabilities = source.capabilities
)

/**
 * Common base for consumer portal adapters (Zillow, Redfin, Realtor.com, Homes.com).
 *
 * The differences between portals are configuration, not logic:
 *  - which source definition they bind to,
 *  - which site specific parser (if any) they add on top of the shared chain,
 *  - extra request hints.
 *
 * Adding the next portal is therefore a definition + a small subclass + fixtures — see
 * [FutureSourceAdapters.apartmentsCom] for the annotated template.
 */
abstract class PortalSourceAdapter(
    private val source: PropertySourceDefinition,
    override val descriptor: AdapterDescriptor,
    private val siteParsers: List<SourceDocumentParser> = emptyList()
) : BasePropertySourceAdapter() {

    final override fun extraParsers(): List<SourceDocumentParser> = siteParsers

    override fun buildRequest(resolved: ResolvedPropertyUrl, context: AdapterContext): HttpRequest {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = HttpRequest.DEFAULT_ACCEPT
        context.credentials?.takeIf { !it.isEmpty }?.asHeaders()?.forEach { (key, value) -> headers[key] = value }
        headers.putAll(requestHeaders(resolved, context))
        return HttpRequest(url = resolved.url.normalized, headers = headers)
    }

    /** Exposed for diagnostics: which source definition this adapter is bound to. */
    val boundSource: PropertySourceDefinition get() = source
}

/**
 * Zillow listings.
 *
 * Zillow embeds a complete listing payload in `__NEXT_DATA__` and also publishes JSON-LD. The
 * adapter adds a `zpid` confirmation parser so idempotency survives layout changes.
 */
class ZillowAdapter : PortalSourceAdapter(
    source = SourceCatalog.ZILLOW,
    descriptor = portalDescriptor(SourceCatalog.ZILLOW, adapterId = "zillow-html", version = "1.0.0"),
    siteParsers = listOf(ZpidConfirmationParser())
) {

    class ZpidConfirmationParser : SourceDocumentParser {
        override val parserId: String = "zillow-zpid"
        override val version: String = "1.0.0"
        override val priority: Int = 15

        override fun canParse(document: RawSourceDocument): Boolean =
            document.isHtml && document.bodyOrEmpty.contains("zpid", ignoreCase = true)

        override fun parse(document: RawSourceDocument, context: ParserContext): ParserResult {
            val builder = ExtractedFactsBuilder()
            Regex("\"(?:zpid|listingId)\"\\s*:\\s*\"?(\\d{5,})\"?").find(document.bodyOrEmpty)
                ?.groupValues?.get(1)
                ?.let { zpid ->
                    builder.addText(
                        PropertyField.SOURCE_LISTING_ID,
                        zpid,
                        context.provenance.create(ProvenanceMethod.EMBEDDED_STATE, 0.9, "body.zpid")
                    )
                }
            return if (builder.isEmpty()) ParserResult.declined("no zpid found") else ParserResult(builder.build())
        }
    }
}

/** Redfin listings: rich JSON-LD plus `window.__reactServerState`, with MLS ids in the payload. */
class RedfinAdapter : PortalSourceAdapter(
    source = SourceCatalog.REDFIN,
    descriptor = portalDescriptor(SourceCatalog.REDFIN, adapterId = "redfin-html", version = "1.0.0"),
    siteParsers = listOf(RedfinIdentifierParser())
) {

    class RedfinIdentifierParser : SourceDocumentParser {
        override val parserId: String = "redfin-identifiers"
        override val version: String = "1.0.0"
        override val priority: Int = 15

        override fun canParse(document: RawSourceDocument): Boolean =
            document.isHtml && document.bodyOrEmpty.contains("mlsId", ignoreCase = true)

        override fun parse(document: RawSourceDocument, context: ParserContext): ParserResult {
            val builder = ExtractedFactsBuilder()
            Regex("\"mlsId\"\\s*:\\s*\"?([A-Za-z0-9-]{4,20})\"?").find(document.bodyOrEmpty)
                ?.groupValues?.get(1)
                ?.let { mls ->
                    builder.addText(
                        PropertyField.MLS_NUMBER,
                        mls,
                        context.provenance.create(ProvenanceMethod.EMBEDDED_STATE, 0.85, "body.mlsId")
                    )
                }
            Regex("\"listingId\"\\s*:\\s*\"?(\\d{5,})\"?").find(document.bodyOrEmpty)
                ?.groupValues?.get(1)
                ?.let { listingId ->
                    builder.addText(
                        PropertyField.SOURCE_LISTING_ID,
                        listingId,
                        context.provenance.create(ProvenanceMethod.EMBEDDED_STATE, 0.85, "body.listingId")
                    )
                }
            return if (builder.isEmpty()) ParserResult.declined("no redfin identifiers") else ParserResult(builder.build())
        }
    }
}

/** Realtor.com listings (`M####-####` ids, `__NEXT_DATA__` payload). */
class RealtorComAdapter : PortalSourceAdapter(
    source = SourceCatalog.REALTOR_COM,
    descriptor = portalDescriptor(SourceCatalog.REALTOR_COM, adapterId = "realtor-com-html", version = "1.0.0")
)

/** Homes.com listings. */
class HomesComAdapter : PortalSourceAdapter(
    source = SourceCatalog.HOMES_COM,
    descriptor = portalDescriptor(SourceCatalog.HOMES_COM, adapterId = "homes-com-html", version = "1.0.0")
)

/**
 * Fallback adapter for unknown hosts (brokerage sites, single-property landing pages).
 *
 * Intentionally site-agnostic: only the shared parser chain runs, so such a page can produce at
 * most a *partial* canonical record — which the pipeline reports as such instead of pretending.
 */
class GenericWebListingAdapter : BasePropertySourceAdapter() {

    override val descriptor: AdapterDescriptor = AdapterDescriptor(
        adapterId = "generic-web",
        sourceId = PropertyAdapterRegistry.GENERIC_SOURCE_ID,
        displayName = SourceCatalog.GENERIC_WEB.displayName,
        version = "1.0.0",
        capabilities = SourceCatalog.GENERIC_WEB.capabilities
    )

    override fun supports(resolved: ResolvedPropertyUrl): Boolean =
        resolved.detection.definition?.sourceId == PropertyAdapterRegistry.GENERIC_SOURCE_ID

    override fun buildRequest(resolved: ResolvedPropertyUrl, context: AdapterContext): HttpRequest {
        val headers = LinkedHashMap<String, String>()
        context.credentials?.takeIf { !it.isEmpty }?.asHeaders()?.forEach { (key, value) -> headers[key] = value }
        return HttpRequest(url = resolved.url.normalized, headers = headers)
    }
}

/**
 * Extension-point templates. Kept compiled so the "add a source later" story stays honest, and
 * covered by `AdapterContractTest` like any other adapter.
 */
object FutureSourceAdapters {

    /**
     * Whole cost of adding Apartments.com later:
     *  1. `SourceCatalog.APARTMENTS_COM` already declares domains + path rules (status PLANNED),
     *  2. this adapter,
     *  3. fixtures + tests (see `AdapterContractTest`),
     *  4. flip the catalogue status to AVAILABLE and register the adapter in
     *     `PropertyUrlIntelligenceFactory`.
     */
    fun apartmentsCom(): PropertySourceAdapter = ApartmentsComAdapter()

    class ApartmentsComAdapter : PortalSourceAdapter(
        source = SourceCatalog.APARTMENTS_COM,
        descriptor = portalDescriptor(SourceCatalog.APARTMENTS_COM, adapterId = "apartments-com-html", version = "0.1.0")
    )
}

/**
 * Parser for sites that expose a listing object through a JS assignment
 * (`window.__LISTING__ = {…}`) — used by the generic adapter when a deployment whitelists such a site.
 */
class JsonAssignmentParser(
    private val variableName: String,
    override val priority: Int = 25
) : SourceDocumentParser {

    override val parserId: String = "json-assignment:$variableName"
    override val version: String = "1.0.0"

    override fun canParse(document: RawSourceDocument): Boolean =
        document.isHtml && document.bodyOrEmpty.contains(variableName)

    override fun parse(document: RawSourceDocument, context: ParserContext): ParserResult {
        val json = HtmlScanner.embeddedJsonAfter(document.bodyOrEmpty, variableName)
            ?: return ParserResult.declined("assignment not found")
        val root = JsonParser.parseOrNull(json) ?: return ParserResult.declined("malformed JSON assignment")
        val facts = extractFacts(root, context)
        return if (facts.isEmpty()) ParserResult.declined("no facts in assignment") else ParserResult(facts)
    }

    private fun extractFacts(root: JsonValue, context: ParserContext): ExtractedFacts {
        val builder = ExtractedFactsBuilder()
        val provenance = context.provenance.create(ProvenanceMethod.EMBEDDED_STATE, 0.6, variableName)

        when (val address = root.path("address") ?: root.path("listing.address")) {
            is JsonValue.Str -> {
                val parts = ValueParsing.splitAddressLine(address.value)
                parts.streetAddress?.let { builder.addText(PropertyField.ADDRESS_LINE_1, it, provenance) }
                parts.city?.let { builder.addText(PropertyField.CITY, it, provenance) }
                parts.state?.let { builder.addText(PropertyField.STATE, it, provenance) }
                parts.postalCode?.let { builder.addText(PropertyField.POSTAL_CODE, it, provenance) }
            }
            is JsonValue.Obj -> {
                address.stringValue("streetAddress", "addressLine1", "street")
                    ?.takeIf { ValueGuards.addressLine(it).accepted }
                    ?.let { builder.addText(PropertyField.ADDRESS_LINE_1, it, provenance) }
                address.stringValue("city", "locality")?.let { builder.addText(PropertyField.CITY, it, provenance) }
                address.stringValue("state", "region")
                    ?.let { ValueParsing.parseUsState(it) }
                    ?.let { builder.addText(PropertyField.STATE, it, provenance) }
                address.stringValue("zip", "zipcode", "postalCode")
                    ?.let { ValueParsing.parsePostalCode(it) }
                    ?.let { builder.addText(PropertyField.POSTAL_CODE, it, provenance) }
            }
            else -> Unit
        }

        (root.path("price") ?: root.path("listPrice") ?: root.path("listing.price"))
            ?.asNumber()
            ?.let { price -> if (ValueGuards.price(price).accepted) builder.addNumber(PropertyField.PRICE_AMOUNT, price, provenance) }
        (root.path("bedrooms") ?: root.path("beds"))?.asNumber()?.let { beds ->
            if (ValueGuards.bedrooms(beds).accepted) builder.addNumber(PropertyField.BEDROOMS, beds, provenance)
        }
        (root.path("bathrooms") ?: root.path("baths"))?.asNumber()?.let { baths ->
            if (ValueGuards.bathrooms(baths).accepted) builder.addNumber(PropertyField.BATHROOMS, baths, provenance)
        }
        (root.path("squareFeet") ?: root.path("sqft") ?: root.path("livingArea"))?.asNumber()?.let { sqft ->
            if (ValueGuards.areaSqFt(sqft).accepted) builder.addInt(PropertyField.LIVING_AREA_SQFT, sqft.toInt(), provenance)
        }
        return builder.build()
    }

    private fun JsonValue.Obj.stringValue(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> entries[key]?.asString()?.trim()?.takeIf { it.isNotEmpty() } }
}
