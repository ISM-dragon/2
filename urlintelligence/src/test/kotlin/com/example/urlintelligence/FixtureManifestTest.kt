package com.example.urlintelligence

import com.example.urlintelligence.adapter.GenericWebAdapter
import com.example.urlintelligence.adapter.HomesAdapter
import com.example.urlintelligence.adapter.PropertyParseResult
import com.example.urlintelligence.adapter.PropertySourceAdapter
import com.example.urlintelligence.adapter.RealtorAdapter
import com.example.urlintelligence.adapter.RedfinAdapter
import com.example.urlintelligence.adapter.SourceFetchRequest
import com.example.urlintelligence.adapter.SourceFetchResponse
import com.example.urlintelligence.adapter.ZillowAdapter
import com.example.urlintelligence.compliance.RobotsRules
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.parser.DriftLevel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixture corpus is a contract, not a folder.
 *
 * Every fixture that ships in `src/test/resources/fixtures` must be registered here with the
 * parser that consumes it and the outcome it is expected to produce. Adding a fixture without a
 * declaration fails the build, which is what keeps the corpus meaningful as providers change —
 * and what makes "a layout change" a deliberate, reviewed edit instead of a silent regress.
 */
class FixtureManifestTest {

    private enum class Parser { ZILLOW, REDFIN, REALTOR, HOMES, GENERIC, EXAMPLE_PORTAL, ROBOTS_FILE }

    private enum class Expected { SUCCESS, PARTIAL, FAILURE, RULES }

    private data class Case(
        val file: String,
        val parser: Parser,
        val url: String,
        val expected: Expected,
        val failureType: String? = null,
        val driftLevel: DriftLevel? = null,
        val note: String = ""
    )

    private val cases = listOf(
        Case("zillow_listing.html", Parser.ZILLOW, Fixtures.ZILLOW_URL, Expected.SUCCESS,
            driftLevel = DriftLevel.NONE, note = "canonical happy path"),
        Case("zillow_mobile.html", Parser.ZILLOW,
            "https://www.zillow.com/homedetails/512-Oak-Hollow-Ct-Round-Rock-TX-78681/44771122_zpid/",
            Expected.SUCCESS, driftLevel = DriftLevel.NONE, note = "mobile markup variant"),
        Case("zillow_partial.html", Parser.ZILLOW, Fixtures.ZILLOW_URL, Expected.PARTIAL,
            driftLevel = DriftLevel.NONE, note = "core fields missing"),
        Case("zillow_drifted.html", Parser.ZILLOW, Fixtures.ZILLOW_URL, Expected.PARTIAL,
            driftLevel = DriftLevel.MAJOR, note = "markup rework: JSON-LD and state blob gone"),
        Case("redfin_listing.html", Parser.REDFIN, Fixtures.REDFIN_URL, Expected.SUCCESS,
            driftLevel = DriftLevel.NONE),
        Case("redfin_minor_drift.html", Parser.REDFIN,
            "https://www.redfin.com/AZ/Phoenix/4102-N-32nd-St-85018/home/41526378",
            Expected.SUCCESS, driftLevel = DriftLevel.MINOR, note = "optional marker moved"),
        Case("realtor_listing.html", Parser.REALTOR, Fixtures.REALTOR_URL, Expected.SUCCESS,
            driftLevel = DriftLevel.NONE),
        Case("realtor_sold_listing.html", Parser.REALTOR,
            "https://www.realtor.com/realestateandhomes-detail/77-Beacon-St_Boston_MA-02108-MA77BEACON4",
            Expected.SUCCESS, driftLevel = DriftLevel.NONE, note = "sold status"),
        Case("homes_listing.html", Parser.HOMES, Fixtures.HOMES_URL, Expected.SUCCESS,
            driftLevel = DriftLevel.NONE),
        Case("generic_brokerage.html", Parser.GENERIC, Fixtures.GENERIC_URL, Expected.SUCCESS,
            note = "text + meta heuristics only"),
        Case("rent_listing.html", Parser.GENERIC,
            "https://www.elm-property-group.test/listings/2201-riverside-dr-1402",
            Expected.SUCCESS, note = "for-rent listing"),
        Case("malformed_jsonld.html", Parser.GENERIC,
            "https://www.cascaderealty.test/listings/9-juniper-way",
            Expected.SUCCESS, note = "one broken JSON-LD block, one good one"),
        Case("example_portal_listing.html", Parser.EXAMPLE_PORTAL, ExamplePortal.URL, Expected.SUCCESS,
            driftLevel = DriftLevel.NONE, note = "provider added in tests"),
        Case("blocked_captcha.html", Parser.ZILLOW, Fixtures.ZILLOW_URL, Expected.FAILURE,
            failureType = "Blocked", note = "anti-bot page with HTTP 200"),
        Case("captcha_cloudflare.html", Parser.ZILLOW, Fixtures.ZILLOW_URL, Expected.FAILURE,
            failureType = "Blocked", note = "cloudflare-style challenge"),
        Case("login_wall.html", Parser.ZILLOW, Fixtures.ZILLOW_URL, Expected.FAILURE,
            failureType = "AuthRequired", note = "login wall"),
        Case("consent_wall.html", Parser.ZILLOW, Fixtures.ZILLOW_URL, Expected.FAILURE,
            failureType = "Blocked", note = "consent interstitial"),
        Case("empty_page.html", Parser.GENERIC, Fixtures.GENERIC_URL, Expected.FAILURE,
            failureType = "ParseError", note = "no listing data at all"),
        Case("robots_disallow_listings.txt", Parser.ROBOTS_FILE, "", Expected.RULES),
        Case("robots_deny_all.txt", Parser.ROBOTS_FILE, "", Expected.RULES),
        Case("robots_wildcards.txt", Parser.ROBOTS_FILE, "", Expected.RULES)
    )

    private fun fixtureDir(): File {
        val dir = File("src/test/resources/fixtures")
        assertTrue("fixture directory not found at ${dir.absolutePath}", dir.isDirectory)
        return dir
    }

    private fun adapterFor(parser: Parser, transport: FakeTransport): PropertySourceAdapter? =
        when (parser) {
            Parser.ZILLOW -> ZillowAdapter(transport)
            Parser.REDFIN -> RedfinAdapter(transport)
            Parser.REALTOR -> RealtorAdapter(transport)
            Parser.HOMES -> HomesAdapter(transport)
            Parser.GENERIC -> GenericWebAdapter(transport)
            Parser.EXAMPLE_PORTAL -> ExamplePortal.adapter(transport)
            Parser.ROBOTS_FILE -> null
        }

    // ---- coverage ---------------------------------------------------------------------------

    @Test
    fun `every fixture on disk is declared in the manifest`() {
        val onDisk = fixtureDir().listFiles()!!
            .filter { it.isFile }
            .map { it.name }
            .sorted()
        val declared = cases.map { it.file }.sorted()

        assertEquals(
            "fixtures must be declared with an expected outcome",
            onDisk,
            declared
        )
        assertEquals("a fixture may only be declared once", declared.distinct(), declared)
    }

    @Test
    fun `fixtures are small utf8 documents`() {
        fixtureDir().listFiles()!!.filter { it.isFile }.forEach { file ->
            val bytes = file.readBytes()
            assertTrue("${file.name} is empty", bytes.isNotEmpty())
            assertTrue("${file.name} is larger than a sane fixture", bytes.size < 64 * 1024)
            assertFalse(
                "${file.name} must be UTF-8 text",
                bytes.take(2048).contains(0x00.toByte())
            )
        }
    }

    // ---- parser expectations ------------------------------------------------------------------

    @Test
    fun `every declared fixture produces its declared outcome`() {
        cases.forEach { case ->
            when (case.parser) {
                Parser.ROBOTS_FILE -> assertRulesFixture(case)
                else -> assertDocumentFixture(case)
            }
        }
    }

    private fun assertRulesFixture(case: Case) {
        val rules = RobotsRules.parse(Fixtures.load(case.file))
        assertTrue("${case.file} must contain at least one user-agent group", rules.groupCount() > 0)
        if (case.file == "robots_deny_all.txt") {
            assertFalse("${case.file} must deny everything", rules.allows("/homedetails/1", "Anyone/1.0"))
        }
        if (case.file == "robots_disallow_listings.txt") {
            assertFalse(rules.allows("/search/homes", "Anyone/1.0"))
            assertTrue(rules.allows("/homedetails/1", "Anyone/1.0"))
        }
    }

    private fun assertDocumentFixture(case: Case) {
        val body = Fixtures.load(case.file)
        val transport = FakeTransport()
        val adapter = adapterFor(case.parser, transport)
        assertNotNull("no adapter for ${case.parser}", adapter)

        val result = adapter!!.parse(
            SourceFetchResponse.Success(
                statusCode = 200,
                body = body,
                contentType = "text/html",
                finalUrl = case.url.ifBlank { "https://example.test/listing/1" },
                fetchedAtEpochMillis = 1_700_000_000_000L
            ),
            SourceFetchRequest(case.url.ifBlank { "https://example.test/listing/1" }, "corr-manifest")
        )

        when (case.expected) {
            Expected.SUCCESS -> assertTrue(
                "${case.file} should parse fully but produced $result",
                result is PropertyParseResult.Success
            )
            Expected.PARTIAL -> assertTrue(
                "${case.file} should parse partially but produced $result",
                result is PropertyParseResult.Partial
            )
            Expected.FAILURE -> {
                assertTrue(
                    "${case.file} should fail but produced $result",
                    result is PropertyParseResult.Failure
                )
                val failure = (result as PropertyParseResult.Failure).failure
                assertEquals(
                    "${case.file} produced the wrong failure class",
                    case.failureType,
                    failure::class.java.simpleName
                )
            }
            Expected.RULES -> Unit
        }

        case.driftLevel?.let { expectedDrift ->
            val draft = result.draftOrNull
            assertNotNull("${case.file} produced no draft for a drift assertion", draft)
            assertEquals(
                "${case.file} reported the wrong drift level",
                expectedDrift,
                draft!!.drift?.level
            )
        }

        // Every document fixture must also be traceable: a draft that produced fields must have
        // a parser identity (except deliberately generic pages, which still stamp their parser).
        result.draftOrNull?.let { draft ->
            if (draft.present().isNotEmpty()) {
                assertNotNull("${case.file} has no parser id", draft.parserId)
                assertNotNull("${case.file} has no document digest", draft.documentDigest)
            }
        }
    }

    @Test
    fun `failure fixtures classify anti bot pages as non retryable policy stops`() {
        listOf("blocked_captcha.html", "captcha_cloudflare.html", "login_wall.html", "consent_wall.html")
            .forEach { file ->
                val result = ZillowAdapter(FakeTransport()).parse(
                    SourceFetchResponse.Success(
                        statusCode = 200,
                        body = Fixtures.load(file),
                        contentType = "text/html",
                        finalUrl = Fixtures.ZILLOW_URL,
                        fetchedAtEpochMillis = 0L
                    ),
                    SourceFetchRequest(Fixtures.ZILLOW_URL, "corr")
                ) as PropertyParseResult.Failure
                val failure = result.failure
                assertFalse("$file must not be retried", failure.retryable)
                assertEquals(
                    "$file must classify as a policy stop",
                    com.example.urlintelligence.failure.FailureCategory.POLICY,
                    failure.category
                )
                assertTrue(
                    "$file produced an unclassified failure: ${failure.code}",
                    failure is SourceFailure.Blocked || failure is SourceFailure.AuthRequired
                )
            }
    }

    @Test
    fun `sold and rent fixtures normalize their listing status`() {
        val sold = RealtorAdapter(FakeTransport()).parse(
            response("realtor_sold_listing.html",
                "https://www.realtor.com/realestateandhomes-detail/77-Beacon-St_Boston_MA-02108-MA77BEACON4"),
            SourceFetchRequest(Fixtures.REALTOR_URL, "corr")
        )
        val soldProperty = com.example.urlintelligence.normalization.PropertyNormalizer(TestClock())
            .normalize((sold as PropertyParseResult.Success).draft)
        assertEquals(
            com.example.urlintelligence.model.CanonicalListingStatus.SOLD,
            (soldProperty as com.example.urlintelligence.normalization.NormalizationOutcome.Success)
                .property.listingStatus
        )

        val rent = GenericWebAdapter(FakeTransport()).parse(
            response("rent_listing.html", "https://www.elm-property-group.test/listings/2201-riverside-dr-1402"),
            SourceFetchRequest("https://www.elm-property-group.test/listings/2201-riverside-dr-1402", "corr")
        )
        val rentProperty = com.example.urlintelligence.normalization.PropertyNormalizer(TestClock())
            .normalize((rent as PropertyParseResult.Success).draft)
        assertEquals(
            com.example.urlintelligence.model.CanonicalListingStatus.FOR_RENT,
            (rentProperty as com.example.urlintelligence.normalization.NormalizationOutcome.Success)
                .property.listingStatus
        )
    }

    private fun response(fixture: String, url: String) = SourceFetchResponse.Success(
        statusCode = 200,
        body = Fixtures.load(fixture),
        contentType = "text/html",
        finalUrl = url,
        fetchedAtEpochMillis = 0L
    )
}
