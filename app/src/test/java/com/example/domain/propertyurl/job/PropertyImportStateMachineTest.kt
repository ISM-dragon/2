package com.example.domain.propertyurl.job

import com.example.domain.propertyurl.Fixtures
import com.example.domain.propertyurl.model.CanonicalProperty
import com.example.domain.propertyurl.model.ExtractedFactsBuilder
import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.Provenance
import com.example.domain.propertyurl.model.ProvenanceMethod
import com.example.domain.propertyurl.model.SourceFailureKind
import com.example.domain.propertyurl.normalize.CanonicalPropertyMapper
import com.example.domain.propertyurl.source.SourceCatalog
import com.example.domain.propertyurl.source.SourceRegistry
import com.example.domain.propertyurl.url.PropertyUrlResolver
import com.example.domain.propertyurl.url.UrlResolutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The job state machine is the contract the UI and the persistence layer depend on: it decides which
 * transitions are legal, which states are terminal, and it records an append-only audit trail.
 */
class PropertyImportStateMachineTest {

    private fun job(state: PropertyImportJobState = PropertyImportJobState.RECEIVED) = PropertyImportJob(
        jobId = "job-1",
        rawInput = "https://www.zillow.com/homedetails/1/20451237_zpid/",
        normalizedUrl = "https://zillow.com/homedetails/1/20451237_zpid",
        sourceId = "zillow",
        adapterId = "zillow-html",
        idempotencyKey = "zillow:20451237",
        state = state,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_000
    )

    private fun assertApplied(result: TransitionResult): PropertyImportJob {
        assertTrue("expected an applied transition but was $result", result is TransitionResult.Applied)
        return (result as TransitionResult.Applied).job
    }

    @Test
    fun `happy path is legal end to end`() {
        var current = job()
        listOf(
            PropertyImportJobState.QUEUED,
            PropertyImportJobState.FETCHING,
            PropertyImportJobState.PARSING,
            PropertyImportJobState.NORMALIZING,
            PropertyImportJobState.SUCCEEDED
        ).forEach { target ->
            current = assertApplied(
                PropertyImportStateMachine.transition(current, target, "TEST", current.updatedAtEpochMillis + 1)
            )
        }

        assertEquals(PropertyImportJobState.SUCCEEDED, current.state)
        assertTrue(current.isTerminal)
        assertTrue(current.state.isSuccess)
        assertEquals(5, current.transitions.size)
        assertEquals("TEST", current.transitions.last().reasonCode)
    }

    @Test
    fun `illegal transitions are rejected instead of corrupting the job`() {
        val received = job()

        val result = PropertyImportStateMachine.transition(
            received, PropertyImportJobState.SUCCEEDED, "SHORTCUT", 2_000
        )

        assertTrue("a job cannot jump straight to success", result is TransitionResult.Illegal)
        assertEquals(PropertyImportJobState.RECEIVED, received.state)
    }

    @Test
    fun `terminal states accept no further transitions`() {
        PropertyImportJobState.entries.filter { it.isTerminal }.forEach { terminal ->
            val finished = job(terminal)
            val outcome = PropertyImportStateMachine.transition(finished, PropertyImportJobState.QUEUED, "RETRY", 5_000)
            assertTrue("$terminal must be final", outcome is TransitionResult.Illegal)
        }
    }

    @Test
    fun `retry transitions record the failure and the next attempt`() {
        val fetching = assertApplied(
            PropertyImportStateMachine.transition(
                assertApplied(PropertyImportStateMachine.transition(job(), PropertyImportJobState.QUEUED, "Q", 1_001)),
                PropertyImportJobState.FETCHING,
                "START",
                1_002,
                attemptIncrement = 1
            )
        )

        val retrying = assertApplied(
            PropertyImportStateMachine.transition(
                fetching,
                PropertyImportJobState.FETCH_RETRY_SCHEDULED,
                "BACKOFF",
                1_100,
                failure = Fixtures.failure(SourceFailureKind.TIMEOUT),
                nextAttemptAtEpochMillis = 3_100
            )
        )

        assertEquals(1, retrying.attempts)
        assertEquals(3_100L, retrying.nextAttemptAtEpochMillis)
        assertEquals(SourceFailureKind.TIMEOUT, retrying.lastFailure?.kind)
        assertEquals(1, retrying.failures.size)
        assertNotNull(PropertyImportJobState.FETCH_RETRY_SCHEDULED)
    }

    @Test
    fun `interrupted jobs can be recovered into the queue after process death`() {
        PropertyImportJobState.entries.filter { it in PropertyImportStateMachine.RESUMABLE_STATES }.forEach { state ->
            val interrupted = job(state)
            val recovered = PropertyImportStateMachine.transition(interrupted, PropertyImportJobState.QUEUED, "RECOVERED", 9_000)
            assertTrue("$state must be resumable", recovered is TransitionResult.Applied)
        }
    }

    @Test
    fun `coarse status mapping matches the state groups`() {
        assertEquals(PropertyImportStatus.SUCCEEDED, PropertyImportStatus.of(PropertyImportJobState.SUCCEEDED))
        assertEquals(PropertyImportStatus.PARTIAL, PropertyImportStatus.of(PropertyImportJobState.PARTIAL_SUCCESS))
        assertEquals(PropertyImportStatus.DUPLICATE, PropertyImportStatus.of(PropertyImportJobState.DUPLICATE_SUPPRESSED))
        assertEquals(PropertyImportStatus.REJECTED, PropertyImportStatus.of(PropertyImportJobState.REJECTED_INVALID_URL))
        assertEquals(PropertyImportStatus.REJECTED, PropertyImportStatus.of(PropertyImportJobState.BLOCKED_BY_POLICY))
        assertEquals(PropertyImportStatus.FAILED, PropertyImportStatus.of(PropertyImportJobState.FETCH_FAILED))
        assertEquals(PropertyImportStatus.FAILED, PropertyImportStatus.of(PropertyImportJobState.PARSE_FAILED))
        assertEquals(PropertyImportStatus.CANCELLED, PropertyImportStatus.of(PropertyImportJobState.CANCELLED))
        assertEquals(PropertyImportStatus.PENDING, PropertyImportStatus.of(PropertyImportJobState.QUEUED))

        listOf(
            PropertyImportJobState.FETCHING,
            PropertyImportJobState.PARSING,
            PropertyImportJobState.NORMALIZING,
            PropertyImportJobState.FETCH_RETRY_SCHEDULED
        ).forEach { state ->
            assertEquals("$state is in progress", PropertyImportStatus.IN_PROGRESS, PropertyImportStatus.of(state))
        }
    }

    @Test
    fun `canonical property and warnings are attached when the job finishes`() {
        var current = job(PropertyImportJobState.NORMALIZING)
        current = assertApplied(
            PropertyImportStateMachine.transition(
                current,
                PropertyImportJobState.SUCCEEDED,
                "DONE",
                2_000,
                canonicalProperty = canonicalFixtureProperty(),
                usedParsers = listOf("schema-org-json-ld@1.2.0"),
                warnings = listOf(
                    ImportWarning(
                        code = ImportWarning.WarningCode.DERIVED_FIELD,
                        message = "price per sqft derived"
                    )
                )
            )
        )

        assertNotNull(current.canonicalProperty)
        assertEquals("4127 Oak Hollow Dr", current.canonicalProperty!!.addressLine1)
        assertEquals(listOf("schema-org-json-ld@1.2.0"), current.usedParsers)
        assertTrue(current.warnings.isNotEmpty())
    }

    /** Canonical record used by the terminal-transition test (built through the real mapper). */
    private fun canonicalFixtureProperty(): CanonicalProperty {
        val registry = SourceRegistry.build(SourceCatalog.ALL)
        val provenance = Provenance(
            sourceId = "zillow",
            method = ProvenanceMethod.STRUCTURED_DATA,
            confidence = 0.9,
            extractedAtEpochMillis = 1_000
        )
        val facts = ExtractedFactsBuilder().apply {
            addText(PropertyField.ADDRESS_LINE_1, "4127 Oak Hollow Dr", provenance)
            addText(PropertyField.CITY, "Austin", provenance)
            addText(PropertyField.STATE, "TX", provenance)
            addText(PropertyField.POSTAL_CODE, "78745", provenance)
            addNumber(PropertyField.PRICE_AMOUNT, 565_000.0, provenance)
            addNumber(PropertyField.BEDROOMS, 3.0, provenance)
            addNumber(PropertyField.BATHROOMS, 2.0, provenance)
        }.build()
        val resolved = (
            PropertyUrlResolver(registry).resolve("https://www.zillow.com/homedetails/1/20451237_zpid/") as
                UrlResolutionResult.Resolved
            ).resolved

        return CanonicalPropertyMapper(registry, now = { 1_000 }).map(facts, resolved, "zillow").property
    }

    @Test
    fun `attempts never exceed the configured maximum`() {
        val exhausted = job(PropertyImportJobState.FETCH_FAILED).copy(attempts = 3, maxAttempts = 3)

        assertFalse("no further attempts may be scheduled", exhausted.canRetry)
        assertTrue(exhausted.isTerminal)
    }
}
