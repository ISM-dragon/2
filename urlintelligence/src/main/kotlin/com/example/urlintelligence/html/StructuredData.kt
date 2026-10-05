package com.example.urlintelligence.html

import com.example.urlintelligence.json.JsonParser
import com.example.urlintelligence.json.JsonValue

/**
 * Extracts schema.org objects (JSON-LD) from an HTML document, handling the three
 * shapes sources actually emit: a single object, an array of objects, or
 * `{"@graph": [...]}`. Malformed blocks are skipped rather than failing the import.
 */
object StructuredData {

    private val PROPERTY_TYPE_HINTS = listOf(
        "SingleFamilyResidence", "House", "Residence", "Apartment", "ApartmentComplex",
        "Condominium", "Townhouse", "Product", "Land", "ManufacturedHome",
        "SingleFamilyResidenceType", "Place", "RealEstateListing"
    )

    /** Every JSON-LD object in document order, with parse failures recorded in [malformed]. */
    fun objects(html: String, malformed: MutableList<String> = mutableListOf()): List<JsonValue.JsonObject> {
        val out = ArrayList<JsonValue.JsonObject>()
        Html.jsonLdBlocks(html).forEach { block ->
            val parsed = JsonParser.parseOrNull(prepare(block))
            when (parsed) {
                null -> malformed.add(block.take(120))
                is JsonValue.JsonObject -> collectFrom(parsed, out)
                is JsonValue.JsonArray -> parsed.objects().forEach { collectFrom(it, out) }
                else -> Unit
            }
        }
        return out
    }

    /** Objects that plausibly describe a property listing, best candidates first. */
    fun propertyLike(objects: List<JsonValue.JsonObject>): JsonValue.JsonObject? {
        val ranked = objects.sortedByDescending { score(it) }
        val best = ranked.firstOrNull() ?: return null
        return if (score(best) > 0) best else null
    }

    private fun score(node: JsonValue.JsonObject): Int {
        var score = 0
        val types = node.types().map { it.substringAfterLast('/') }
        if (types.any { it in PROPERTY_TYPE_HINTS }) score += 3
        if (node.has("address")) score += 3
        if (node.has("floorSize")) score += 2
        if (node.has("numberOfRooms") || node.has("numberOfBedrooms")) score += 2
        if (node.has("yearBuilt")) score += 1
        if (node.has("offers")) score += 2
        if (node.has("geo")) score += 1
        if (node.has("additionalProperty")) score += 1
        return score
    }

    private fun collectFrom(node: JsonValue.JsonObject, out: MutableList<JsonValue.JsonObject>) {
        out.add(node)
        val graph = node.array("@graph")
        if (graph != null) {
            graph.objects().forEach { out.add(it) }
        }
    }

    /**
     * Real-world blobs often contain control characters or trailing commas inside
     * JSON-LD. Trim control characters; the parser tolerates the rest or bails out.
     */
    private fun prepare(block: String): String =
        block.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ").trim()
}
