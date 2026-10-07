package com.example

import com.example.domain.ai.GeminiContentGenerator
import com.example.domain.ai.GeminiGenerationResponse
import com.example.domain.ai.analyst.AnalystEvidence
import com.example.domain.ai.analyst.AnalystEvidenceSource
import com.example.domain.ai.analyst.AnalystInputValue
import com.example.domain.ai.analyst.AnalystPropertyInput
import com.example.domain.ai.analyst.AnalystRentInput
import com.example.domain.ai.analyst.RealEstateAnalyst
import com.example.domain.ai.analyst.RealEstateAnalystInput
import com.example.domain.ai.analyst.RealEstateAnalystOutputSchema
import com.example.domain.ai.analyst.RealEstateAnalystOutputValidator
import com.example.domain.ai.analyst.RealEstateAnalystResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealEstateAnalystOutputValidatorTest {
    private val validator = RealEstateAnalystOutputValidator()

    @Test
    fun validOutputSupportsAllEpistemicTypesAndEvidence() {
        val result = validator.validate(validOutput().toString(), sampleInput().evidenceById())

        assertTrue(result.errors.toString(), result.isValid)
        val output = requireNotNull(result.output)
        assertEquals(RealEstateAnalystOutputSchema.VERSION, output.schemaVersion)
        assertEquals("INFERENCE", output.investmentThesis.classification.name)
        assertEquals("FACT", output.strengths[0].classification.name)
        assertEquals("ESTIMATE", output.strengths[1].classification.name)
        assertEquals("UNKNOWN", output.unknowns.single().classification.name)
        assertEquals("HIGH", output.dueDiligenceQuestions.single().priority.name)
        assertEquals("rent.estimated_monthly_rent_usd", output.strengths[1].evidenceRefs.single())
    }

    @Test
    fun jsonSchemaRejectsMissingRequiredPropertiesAndUnexpectedProperties() {
        val missingField = validOutput().apply { remove("unknowns") }
        val missingResult = validator.validate(missingField.toString(), sampleInput().evidenceById())
        assertFalse(missingResult.isValid)
        assertTrue(missingResult.errors.any { it.contains("\$.unknowns") })

        val extraField = validOutput().apply { put("portfolioDatabase", JSONArray()) }
        val extraResult = validator.validate(extraField.toString(), sampleInput().evidenceById())
        assertFalse(extraResult.isValid)
        assertTrue(extraResult.errors.any { it.contains("portfolioDatabase is not allowed") })
    }

    @Test
    fun jsonSchemaRejectsInvalidEnumsAndConfidenceRange() {
        val invalidClassification = validOutput().apply {
            getJSONObject("investmentThesis").put("classification", "GUT_FEELING")
        }
        val classificationResult = validator.validate(invalidClassification.toString(), sampleInput().evidenceById())
        assertFalse(classificationResult.isValid)
        assertTrue(classificationResult.errors.any { it.contains("allowed enum values") })

        val invalidConfidence = validOutput().apply {
            getJSONObject("investmentThesis").put("confidence", 1.5)
        }
        val confidenceResult = validator.validate(invalidConfidence.toString(), sampleInput().evidenceById())
        assertFalse(confidenceResult.isValid)
        assertTrue(confidenceResult.errors.any { it.contains("confidence must be no more than 1.0") })
    }

    @Test
    fun semanticValidationRejectsUnprovidedEvidenceAndNewEstimates() {
        val unknownReference = validOutput().apply {
            getJSONObject("investmentThesis")
                .put("evidenceRefs", JSONArray().put("database.all_properties"))
        }
        val refResult = validator.validate(unknownReference.toString(), sampleInput().evidenceById())
        assertFalse(refResult.isValid)
        assertTrue(refResult.errors.any { it.contains("evidence not present") })

        val unsupportedEstimate = validOutput().apply {
            getJSONArray("strengths").getJSONObject(0).put("classification", "ESTIMATE")
        }
        val estimateResult = validator.validate(unsupportedEstimate.toString(), sampleInput().evidenceById())
        assertFalse(estimateResult.isValid)
        assertTrue(estimateResult.errors.any { it.contains("supplied market or rent estimate") })
    }

    @Test
    fun unknownMustHaveNoEvidenceAndZeroConfidenceAndNarrativeCannotInventFigures() {
        val invalidUnknown = validOutput().apply {
            getJSONArray("unknowns").getJSONObject(0)
                .put("evidenceRefs", JSONArray().put("property.property_type"))
                .put("confidence", 0.4)
        }
        val unknownResult = validator.validate(invalidUnknown.toString(), sampleInput().evidenceById())
        assertFalse(unknownResult.isValid)
        assertTrue(unknownResult.errors.any { it.contains("UNKNOWN claims must not cite evidence") })
        assertTrue(unknownResult.errors.any { it.contains("UNKNOWN claims must have zero confidence") })

        val numericNarrative = validOutput().apply {
            getJSONObject("investmentThesis").put("statement", "Expected cash flow is \$500 each month.")
        }
        val numericResult = validator.validate(numericNarrative.toString(), sampleInput().evidenceById())
        assertFalse(numericResult.isValid)
        assertTrue(numericResult.errors.any { it.contains("must not contain numeric figures") })
    }

    @Test
    fun malformedJsonIsRejected() {
        val result = validator.validate("{this is not json", sampleInput().evidenceById())
        assertFalse(result.isValid)
        assertTrue(result.errors.single().contains("valid JSON object"))
    }

    @Test
    fun analystRetriesMalformedOrSchemaInvalidOutputOnce() = runBlocking {
        val valid = validOutput().toString()
        val generator = ScriptedGenerator(listOf("{this is not json", valid))
        val analyst = RealEstateAnalyst(generator)

        val result = analyst.analyze(sampleInput())

        assertTrue(result is RealEstateAnalystResult.Success)
        assertEquals(2, (result as RealEstateAnalystResult.Success).attempts)
        assertEquals(2, generator.prompts.size)
        assertTrue(generator.prompts[1].contains("Validation errors"))
        assertEquals("application/json", generator.mimeTypes.last())
    }

    @Test
    fun jsonSchemaIsDraft2020AndExplicitlyForbidsAdditionalProperties() {
        val schema = JSONObject(RealEstateAnalystOutputSchema.JSON_SCHEMA)
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.getString("\$schema"))
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(RealEstateAnalystOutputSchema.VERSION, schema.getJSONObject("properties").getJSONObject("schemaVersion").getString("const"))
    }

    private fun sampleInput(): RealEstateAnalystInput {
        val propertyEvidence = AnalystEvidence(
            id = "property.property_type",
            source = AnalystEvidenceSource.PROPERTY_RECORD,
            field = "propertyType"
        )
        val rentEvidence = AnalystEvidence(
            id = "rent.estimated_monthly_rent_usd",
            source = AnalystEvidenceSource.RENT_ESTIMATE,
            field = "estimatedMonthlyRentUsd"
        )
        return RealEstateAnalystInput(
            subject = AnalystPropertyInput(
                propertyType = AnalystInputValue("Multi-family", propertyEvidence.id)
            ),
            rent = AnalystRentInput(
                estimatedMonthlyRentUsd = AnalystInputValue(2500.0, rentEvidence.id)
            ),
            evidence = listOf(propertyEvidence, rentEvidence)
        )
    }

    private fun validOutput(): JSONObject {
        fun claim(
            classification: String,
            statement: String,
            evidenceRefs: List<String>,
            confidence: Double
        ) = JSONObject().apply {
            put("classification", classification)
            put("statement", statement)
            put("evidenceRefs", JSONArray(evidenceRefs))
            put("confidence", confidence)
        }

        return JSONObject().apply {
            put("schemaVersion", RealEstateAnalystOutputSchema.VERSION)
            put(
                "investmentThesis",
                claim(
                    "INFERENCE",
                    "The recorded property details support a cautious acquisition review.",
                    listOf("property.property_type"),
                    0.78
                )
            )
            put(
                "strengths",
                JSONArray()
                    .put(claim("FACT", "The saved property record identifies a multi-family asset.", listOf("property.property_type"), 0.95))
                    .put(claim("ESTIMATE", "The supplied rent figure is an estimate, not verified lease income.", listOf("rent.estimated_monthly_rent_usd"), 0.72))
            )
            put("risks", JSONArray().put(claim("INFERENCE", "Lease-up assumptions should be independently reviewed.", listOf("rent.estimated_monthly_rent_usd"), 0.65)))
            put("redFlags", JSONArray())
            put("unknowns", JSONArray().put(claim("UNKNOWN", "Current leases and verified occupancy were not supplied.", emptyList(), 0.0)))
            put(
                "recommendedStrategy",
                claim("INFERENCE", "Condition an acquisition on document review and physical inspection.", listOf("property.property_type"), 0.74)
            )
            put(
                "dueDiligenceQuestions",
                JSONArray().put(
                    JSONObject().apply {
                        put("question", "Can current leases and rent rolls be verified before closing?")
                        put("priority", "HIGH")
                        put("basis", claim("UNKNOWN", "Lease documents were not supplied.", emptyList(), 0.0))
                    }
                )
            )
        }
    }

    private class ScriptedGenerator(
        private val responses: List<String>
    ) : GeminiContentGenerator {
        val prompts = mutableListOf<String>()
        val mimeTypes = mutableListOf<String?>()
        private var index = 0

        override suspend fun generateContent(
            prompt: String,
            systemPrompt: String?,
            responseMimeType: String?
        ): GeminiGenerationResponse {
            prompts += prompt
            mimeTypes += responseMimeType
            val text = responses.getOrElse(index) { responses.last() }
            index += 1
            return GeminiGenerationResponse(
                success = true,
                text = text,
                slotUsed = 0,
                modelUsed = "test-model"
            )
        }
    }
}
