package com.example.urlintelligence.adapter

import com.example.urlintelligence.html.Html
import com.example.urlintelligence.model.PropertyDraft
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.provenance.ExtractionMethod

/** How a captured string is converted before it is stored in a draft. */
enum class ValueTransform {
    TEXT,
    MONEY,
    DOUBLE,
    INTEGER,
    AREA_SQFT,
    STATE,
    URL,
    DATE_EPOCH
}

/**
 * Declarative extraction rule: "capture group N of this regex is field F".
 *
 * Adapters are mostly tables of these. That keeps adding a provider (or repairing
 * one after a markup change) a data edit rather than a code change, and makes each
 * rule individually unit-testable against fixtures.
 */
data class FieldSelector(
    val field: PropertyField,
    val pattern: Regex,
    val group: Int = 1,
    val confidence: Double = Confidence.MEDIUM,
    val method: ExtractionMethod = ExtractionMethod.DOM_SELECTOR,
    val transform: ValueTransform = ValueTransform.TEXT,
    val note: String? = null
) {
    fun capture(document: String): String? {
        val match = pattern.find(document) ?: return null
        val value = match.groupValues.getOrNull(group) ?: return null
        return value.trim().takeIf { it.isNotBlank() }
    }
}

/** Applies a list of selectors to a document and writes the results into [draft]. */
object SelectorRunner {

    fun apply(
        draft: PropertyDraft,
        document: String,
        selectors: List<FieldSelector>,
        extractor: String
    ): Int {
        var applied = 0
        selectors.forEach { selector ->
            val captured = selector.capture(document) ?: return@forEach
            val transformed = transform(captured, selector.transform) ?: return@forEach
            if (draft.put(
                    field = selector.field,
                    value = transformed,
                    method = selector.method,
                    confidence = selector.confidence,
                    extractor = extractor,
                    rawValue = captured,
                    note = selector.note
                )
            ) applied++
        }
        return applied
    }

    fun transform(value: String, transform: ValueTransform): Any? {
        val cleaned = Html.decodeEntities(value).trim()
        if (cleaned.isBlank()) return null
        return when (transform) {
            ValueTransform.TEXT -> cleaned
            ValueTransform.MONEY -> com.example.urlintelligence.normalization.MoneyParser.parse(cleaned)
            ValueTransform.DOUBLE ->
                cleaned.replace(",", "").toDoubleOrNull()
                    ?: cleaned.replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull()
            ValueTransform.INTEGER ->
                cleaned.replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull()?.toInt()
            ValueTransform.AREA_SQFT -> com.example.urlintelligence.normalization.AreaParser.sqFt(cleaned)
            ValueTransform.STATE -> com.example.urlintelligence.normalization.StateNormalizer.normalize(cleaned)
            ValueTransform.URL -> cleaned.replace("&amp;", "&")
            ValueTransform.DATE_EPOCH -> com.example.urlintelligence.normalization.DateParser.epochMillis(cleaned)
        }
    }
}
