package com.example

import com.example.domain.finance.underwriting.UnderwritingAssumptions
import com.example.domain.finance.underwriting.UnderwritingEngine
import com.example.domain.finance.underwriting.UnderwritingFlatten
import com.example.domain.finance.underwriting.UnderwritingInputCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * Replays the golden vectors produced by the independent Python oracle
 * (`tools/underwriting_oracle`) through the real Kotlin engine.
 *
 * This is the load-bearing test of the underwriting package: the oracle is a
 * second implementation written in exact rational arithmetic, and every metric
 * it computes is asserted here against the shipped code, path by path. A
 * disagreement - including a *missing* or *extra* metric path - fails the build.
 *
 * The tolerance is 1e-9 relative (1e-6 absolute near zero). The two
 * implementations perform the same algebraic operations in different numeric
 * domains (exact rationals vs IEEE-754 doubles), so a few unit-in-the-last-place
 * differences are expected; anything larger is a real divergence, not rounding.
 */
class UnderwritingGoldenVectorsTest {

    private val relativeTolerance = 1e-9
    private val absoluteTolerance = 1e-6

    private fun vectorsDocument(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("underwriting/golden_vectors.json")
            ?: throw AssertionError("underwriting/golden_vectors.json is missing from test resources")
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }

    @Test
    fun goldenVectorSuiteIsPresentAndComplete() {
        val document = vectorsDocument()
        val vectors = document.getJSONArray("vectors")
        assertTrue("expected a substantial vector suite, found ${vectors.length()}", vectors.length() >= 40)
        assertEquals(
            "the vectors must be regenerated against the spec version the engine reports",
            UnderwritingAssumptions.SPEC_VERSION,
            document.getString("specVersion")
        )
        assertTrue(document.getInt("pathCount") > 4000)
    }

    @Test
    fun everyGoldenVectorReplaysThroughTheKotlinEngine() {
        val document = vectorsDocument()
        val vectors = document.getJSONArray("vectors")
        val failures = ArrayList<String>()

        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val id = vector.getString("id")
            val decoded = UnderwritingInputCodec.decode(inputMap(vector.getJSONObject("input")))
            val result = UnderwritingEngine.analyze(decoded.input, decoded.issues)
            val flattened = UnderwritingFlatten.flatten(result)
            val expected = vector.getJSONObject("expected")

            compareVector(id, expected, flattened, failures)
        }

        if (failures.isNotEmpty()) {
            fail(
                "golden vector mismatches (${failures.size}):\n" +
                    failures.take(40).joinToString("\n") +
                    if (failures.size > 40) "\n... and ${failures.size - 40} more" else ""
            )
        }
    }

    private fun compareVector(
        id: String,
        expected: JSONObject,
        actual: Map<String, Any?>,
        failures: MutableList<String>
    ) {
        val expectedKeys = expected.keys().asSequence().toSet()
        val actualKeys = actual.keys

        val missing = expectedKeys - actualKeys
        val extra = actualKeys - expectedKeys
        if (missing.isNotEmpty()) failures.add("[$id] missing metrics: ${missing.sorted()}")
        if (extra.isNotEmpty()) failures.add("[$id] unexpected metrics: ${extra.sorted()}")

        for (path in expectedKeys.intersect(actualKeys)) {
            val expectedValue = normalise(expected.get(path))
            val actualValue = normalise(actual[path])
            if (!valuesAgree(expectedValue, actualValue)) {
                failures.add("[$id] $path: expected <$expectedValue> but was <$actualValue>")
            }
        }
    }

    /** JSON null is `JSONObject.NULL`; everything else is already a primitive or list. */
    private fun normalise(value: Any?): Any? = when (value) {
        null -> null
        JSONObject.NULL -> null
        is JSONArray -> (0 until value.length()).map {
            when (val element = value.get(it)) {
                JSONObject.NULL -> null
                else -> element
            }
        }

        else -> value
    }

    private fun valuesAgree(expected: Any?, actual: Any?): Boolean {
        if (expected == null || actual == null) return expected == null && actual == null
        if (expected is List<*> && actual is List<*>) {
            if (expected.size != actual.size) return false
            return expected.indices.all { valuesAgree(expected[it], actual[it]) }
        }
        if (expected is Number && actual is Number) {
            val a = expected.toDouble()
            val b = actual.toDouble()
            if (a.isNaN() || b.isNaN() || a.isInfinite() || b.isInfinite()) return false
            return abs(a - b) <= max(absoluteTolerance, abs(a) * relativeTolerance)
        }
        return expected == actual
    }

    private fun inputMap(json: JSONObject): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        for (key in json.keys()) {
            map[key] = when (val value = json.get(key)) {
                JSONObject.NULL -> null
                else -> value
            }
        }
        return map
    }

    @Test
    fun goldenVectorsCoverEveryStrategyAndFinancingModel() {
        val document = vectorsDocument()
        val vectors = document.getJSONArray("vectors")
        val ids = (0 until vectors.length()).map { vectors.getJSONObject(it).getString("id") }
        for (expected in listOf(
            "hold_conventional_base", "flip_base", "brrrr_base", "wholesale_assignment",
            "hold_dscr_loan", "hold_hard_money_io", "hold_private_money_io", "hold_seller_financing_balloon"
        )) {
            assertTrue("golden vectors must include $expected", ids.contains(expected))
        }
        assertNotNull(document.getString("regenerate"))
    }
}
