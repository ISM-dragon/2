# US Data Provider Audit — Evidence-Based Capability & Readiness Report

**Document:** `docs/US_DATA_PROVIDER_AUDIT.md`
**Repository:** `ISM-dragon/2`
**Base branch:** `main` @ `712f9b2844b216cf6c82ad7e57a49ee1308f5d68`
**Audit date:** 2026-10-09
**Auditor role:** Senior Real Estate Data Integration Auditor
**Scope:** documentation-only. No provider was implemented, no credential was added, no backend was introduced, and no anti-bot, CAPTCHA, authentication or access restriction was bypassed.

> **Relationship to `docs/US_REAL_ESTATE_DATA_PROVIDER_AUDIT.md`.** That document covers much of the same
> ground and reached several correct conclusions. It also contains material errors about *which code
> path is live in production*. Those errors are corrected in §10 rather than repeated here. This
> document is the authoritative one for wiring and readiness; the older document remains useful for
> its vendor-market commentary, which is out of scope here because it cannot be verified from this
> repository.

---

## 1. Evidence standard

Every claim in this report is one of three things, and is labelled as such:

| Label | Meaning |
| --- | --- |
| **VERIFIED** | Read directly out of this repository's source, or produced by a command run against it. The file and line are cited. |
| **DERIVED** | Follows from two or more VERIFIED facts. The reasoning is stated. |
| **UNVERIFIED** | A claim about the outside world (a vendor's terms, a published rate limit, an API's real behaviour) that **cannot** be checked from inside this repository. Never presented as fact. |

**Machine check.** 86 assertions covering every load-bearing structural claim in this document are
codified in `tools/data_provider_audit/verify_audit_claims.py`:

```
$ python3 tools/data_provider_audit/verify_audit_claims.py
...
ALL 86 CLAIM CHECKS PASSED
```

The script does not re-implement any audited logic — it asserts facts about source text and Gradle
wiring, so the report can be re-checked without a JVM. Run it before trusting any "VERIFIED" line.

**What was run, and what could not be.**

| Check | Result | Notes |
| --- | --- | --- |
| `python3 tools/data_provider_audit/verify_audit_claims.py` | **86/86 pass** | This audit's own claim harness. |
| `python3 tools/underwriting_oracle/selfcheck.py` | **402 pass, "ALL CHECKS PASSED"** | The repository's independent Python oracle for `UnderwritingEngine` arithmetic. |
| `python3 -m unittest discover -s tools/financial_audit` | **12 tests, OK (4 expected failures)** | The 4 expected failures are declared `expectedFailure` in the suite, not new breakage. |
| `python3 tools/room_schema_guard.py` | **Not run** | Refuses with *"guard cannot resolve the v2 baseline revision 05195aa; run in a full clone"*. This workspace is a shallow clone (`git rev-parse --is-shallow-repository` → `true`, 1 commit). |
| `gradle test` / any JVM test suite | **Not run** | No JDK, no Gradle, no Android SDK in this environment (`java: command not found`, `ANDROID_HOME` unset). **The Kotlin unit tests were not executed. Every statement about test coverage in this report is derived from reading test source, not from a green run.** |
| Live network fetch against any portal | **Not attempted** | Out of scope and explicitly forbidden. **No provider in this report is "live-verified".** |

---

## 2. Executive summary

**There are zero production-verified, authenticated, or live-tested external property-data providers
in this repository.** That is the headline, and it is VERIFIED: there is no vendor client, no API key
handling path that is wired to a real endpoint, and no code that has been observed to return real
property data from a third party.

The repository does contain a genuinely well-engineered **HTML import pipeline** — but it is verified
only against six local HTML fixtures, never against a live portal.

The findings that matter most, in priority order:

1. **A fabrication engine ships inside the APK.** Four adapters in
   `com.example.domain.intelligence.source.adapters` return hardcoded Austin-TX properties
   ($485k/$525k/$460k/$430k) parsed from nothing but the URL string, and stamp the synthetic property
   tax with `ProvenanceSourceTier.GOVERNMENT_DATA` attributed to "Travis County Tax Assessor".
   They are **not wired into production** — but they are in `src/main`, so they compile into the
   release APK, and one import statement away from being live. (§4.3, §7.1)

2. **The two "live-feed" adapters are registered but empty.** `OnMarketMlsAdapter` and
   `OffMarketWholesaleAdapter` are constructed in `RealEstateAiApp.onCreate` and read from
   `PropertySeedData.getSeedBundles()`, which is `return emptyList()`. The automation engine will
   always discover zero listings. (§4.1)

3. **Re-importing a URL destroys observed financial data.** `PropertyUrlImportBridge` writes
   `estimatedRent = 0.0`, `assessedValue = 0.0`, `estimatedValue = 0.0`; `writeSatellites` then
   `REPLACE`s the rent/tax/market rows unconditionally. A second import of a listing silently zeroes
   a real rent estimate. This is data loss, not merely absence. (§7.3)

4. **Two competing financial engines disagree, and the fabrication-heavy one drives the primary UI.**
   `PropertyUnderwritingFactory` + `UnderwritingEngine` is disciplined (missing data → explicit
   `ValidationIssue`, oracle-verified arithmetic). `DeterministicFinancialEngine` silently substitutes
   rent = 0.75 % of price, tax = 1.8 %, insurance = 0.6 %, rehab = flat $35k/$50k, ARV = +30 %.
   **The Deal Room screen uses the latter.** (§7.2)

5. **The enrichment store is schema without a writer.** Twelve categories are declared
   (`FLOOD_RISK`, `PERMIT`, `OWNER_OCCUPANCY`, `TAX_ASSESSMENT`, `LIEN`, `MARKET_TREND`, …); the Deal
   Room UI reads all of them; **no production code constructs a `PropertyEnrichmentEntity`**. They
   render empty forever. (§5, §7.4)

6. **Eleven of the audit's data domains have no canonical representation at all.** `PropertyField`
   has no slot for rent estimate, assessed value, flood, permit, ownership, comps or sale history.
   The import pipeline *cannot* carry them even if a provider existed. (§6)

**What is genuinely good and should not be rewritten.** The `domain.propertyurl` layer has
per-field provenance with method-weighted ceilings, value guards that reject implausible numbers,
explicit partial-success semantics, a 20-branch failure taxonomy, robots.txt fail-closed, an
SSRF-safe DNS policy, rate limiting and circuit breaking. The right move is to *route the missing
domains through it*, not to replace it (§9).

---

## 3. Verification levels used in this report

| Level | Definition | Evidence required to claim it |
| --- | --- | --- |
| **L0 — Fabricated** | Returns data not obtained from any source. | Code path returns constants / URL-derived guesses. |
| **L1 — Interface-only** | Types and contracts exist; no working implementation, or returns empty. | Implementation body is `emptyList()` / `TODO` / absent. |
| **L2 — Parser-only (fixture-backed)** | Parses documents correctly; verified against local fixtures. Zero live traffic. | Passing fixture tests **and** no network in the tested path. |
| **L3 — Live unauthenticated fetch** | Performs real network I/O; handles TLS/timeouts/status; no credentials. | Observed successful fetch against the real endpoint. |
| **L4 — Authenticated integration** | Credentials exchanged against a real vendor sandbox. | Observed authenticated response. |
| **L5 — Production-verified** | Contracted, monitored, licensed, real traffic. | Contract + production telemetry. |

**Crucially: L2 ≠ L3.** A parser that extracts price/beds/baths perfectly from a saved HTML file says
nothing about whether the live portal serves that HTML to an unauthenticated Android client. Every
portal adapter in this repository is **L2 at best**, and no L3 evidence exists in the repo.

Nothing in this repository is L3, L4 or L5.

---

## 4. Complete inventory

### 4.0 Three parallel adapter stacks (the root structural problem)

The repository contains **three independent implementations of the same idea**. This is VERIFIED and
is the single biggest source of confusion in the codebase:

| # | Package | Sources known | Adapters | Wired into production? |
| --- | --- | --- | --- | --- |
| **A** | `com.example.domain.propertyurl` | 11 (`SourceCatalog`) | 5 registered | **YES** — `RealEstateAiApp.onCreate` |
| **B** | `com.example.urlintelligence` (separate Gradle module) | 5 (`KnownSources`) | 5 | **NO** — referenced only by tests |
| **C** | `com.example.domain.intelligence.source` | 4 | 4 | **NO** — referenced only by tests |

Wiring evidence (all VERIFIED, all in check block **C2**):

- `app/src/main/java/com/example/RealEstateAiApp.kt:147` — `PropertyUrlIntelligenceFactory.create(...)` (stack A).
- `app/src/main/java/com/example/RealEstateAiApp.kt:153` — `PropertyUrlImportBridge(propertyUrlIntelligence, propertyRepository)`.
- `RealEstateAiApp.kt` never mentions `UrlIntelligenceModule` (stack B).
- No file under `app/src/main/java/com/example/ui/` references `UrlIntelligenceModule` or `PropertyUrlImportService`.
- The UI reaches imports via `IntelligenceRepository.importPropertyUrl` → `PropertyUrlImportBridge` → stack A
  (`IntelligenceRepository.kt:77`, `DiscoverViewModel.kt:120`).

Stack B is nonetheless a declared dependency of `:app` (`app/build.gradle.kts:138`,
`implementation(project(":urlintelligence"))`), so **it ships in the APK while being unreachable** —
dead weight plus a second, divergent source catalogue (§8).

---

### 4.1 Feed / seed adapters — `com.example.data.adapter`

| Adapter | File | Mechanism | Level |
| --- | --- | --- | --- |
| `OnMarketMlsAdapter` | `data/adapter/PropertyDataSeed.kt:9` | `sourceName = "MLS Feed (Demo Seed Dataset)"`; filters `PropertySeedData.getSeedBundles()` | **L1** |
| `OffMarketWholesaleAdapter` | `data/adapter/PropertyDataSeed.kt:27` | `sourceName = "Off-Market Wholesale (Demo Seed Dataset)"`; same | **L1** |
| `PropertySeedData.getSeedBundles()` | `data/adapter/PropertyDataSeed.kt:66` | Body is literally `return emptyList()` | **L1** |
| `PropertySourceManager` | `data/adapter/PropertySourceAdapter.kt:94` | Fan-out + identity dedup orchestration; real logic, but the fan-out is over the two empty adapters | n/a (orchestrator) |
| `PropertyUrlImportBridge` | `data/adapter/PropertyUrlImportBridge.kt:39` | Maps stack-A `CanonicalProperty` → `NormalizedPropertyBundle` | n/a (mapper) |

**VERIFIED:** both adapters are constructed at `RealEstateAiApp.kt:117-118`, and
`AutomationEngine` receives the same `propertySourceManager` (`RealEstateAiApp.kt:205`). The
class comment claims *"realistic curated real estate seed data for MLS on-market testing"* — the
data is not there. `PropertyRepository.seedInitialDataIfEmpty()` (`PropertyRepository.kt:143`)
iterates the same empty list, so **a fresh install has zero properties**.

**Impact:** the entire autonomous "deal finder" premise (`metadata.json`: *"Autonomous real estate
deal finder"*) has no input. Automation cycles run, discover nothing, and report success.

---

### 4.2 Live URL import pipeline — `com.example.domain.propertyurl` (stack A, production)

This is the only ingestion path a user can actually reach. Composition root:
`pipeline/PropertyUrlIntelligenceFactory.kt`.

**Registered adapters** (`PropertyUrlIntelligenceFactory.kt:52-58`, VERIFIED in check **C10**):

| Adapter | Source id | Adapter id | Site-specific parser | Level |
| --- | --- | --- | --- | --- |
| `ZillowAdapter` | `zillow` | `zillow-html` | `ZpidConfirmationParser` (regex `"zpid":"<digits>"`) | **L2** |
| `RedfinAdapter` | `redfin` | `redfin-html` | `RedfinIdentifierParser` (`mlsId`, `listingId`) | **L2** |
| `RealtorComAdapter` | `realtor_com` | `realtor-com-html` | none — shared chain only | **L2** |
| `HomesComAdapter` | `homes_com` | `homes-com-html` | none — shared chain only | **L2** |
| `GenericWebListingAdapter` | `generic_web` | `generic-web` | none — shared chain only | **L2** |
| `FutureSourceAdapters.ApartmentsComAdapter` | `apartments_com` | `apartments-com-html` | none | **L1** (not registered; source is `PLANNED`) |

**Shared parser chain** (`PropertyUrlIntelligenceFactory.kt:61-66`), ordered by trust:

| Parser | Lines | Fields it can emit | Method / ceiling |
| --- | --- | --- | --- |
| `SchemaOrgJsonLdParser` | 449 | 23 fields incl. price, beds, baths, sqft, lot, year built, MLS, images, agent, broker | `STRUCTURED_DATA` / 0.98 |
| `EmbeddedJsonStateParser` | 499 | 28 fields incl. `ANNUAL_TAX_AMOUNT`, `HOA_FEE_MONTHLY`, `DAYS_ON_MARKET`, `COUNTY`, `LISTED_AT` | `EMBEDDED_STATE` / 0.95 |
| `MetaAndTitleFactsParser` | 159 | 7 fields (address, city, state, zip, title, description, images) | `HTML_META` / 0.80 |
| `VisibleTextFactsParser` | 58 | 3 fields (price, beds, sqft) via `TextFactsHeuristics` | `TEXT_HEURISTIC` / 0.65 |

**Normalisation & guardrails** (`normalize/CanonicalPropertyMapper.kt`, `normalize/ValueGuards.kt`):

| Guard | Rule |
| --- | --- |
| price | `1_000 ≤ p ≤ 500_000_000`, must be finite |
| bedrooms / bathrooms | `0 ≤ v ≤ 50` |
| living area | `50 ≤ v ≤ 5_000_000` sqft |
| year built | `1600 ≤ v ≤ currentYear + 2` |
| postal code | must contain a digit; US ⇒ `^\d{5}(-\d{4})?$` (blocks `"NOT A ZIP"`) |
| address line | must contain a digit; rejects `"n/a"` / `"address not available"` |
| image URL | https/http only, public host only, no IP literal, no credentials, no `..` |

Rejected values become a `VALUE_REJECTED_BY_GUARD` warning — they are **not** silently replaced.
This is the correct behaviour and should be preserved.

**Failure taxonomy** (`model/SourceFailure.kt`): 9 categories, 34 kinds, each with a
`Retryability` and a user-facing message. `AntiBotDetector` (`SourceFailure.kt:179`) recognises
PerimeterX, Cloudflare (`cf-mitigated`, `cf_chl_`), DataDome, Incapsula, reCAPTCHA/hCaptcha,
consent walls, login walls and paywalls — and classifies them as failures rather than parsing them.

**Compliance & safety (VERIFIED):**

- `RobotsTxtFetchPolicy` (`port/FetchPolicy.kt:91`) — **fail-closed**: an unevaluable `robots.txt`
  (5xx, timeout, redirect loop) denies the origin. `404`/`410` ⇒ "no rules published" ⇒ allow.
  Production wiring passes `useRobotsTxt = true` (`RealEstateAiApp.kt:151`).
- `PublicOnlyDns` (`port/PublicOnlyDns.kt`) — OkHttp resolves *through* the guard, closing the
  DNS-rebinding window. Blocks loopback, RFC1918, CGNAT, link-local/cloud-metadata, IPv6 ULA.
- Both transports disable redirects (`OkHttpHttpFetcher.kt:42-43`, `OkHttpPropertyTransport.kt:43-44`).
- Credentials are attached **only** to sources declaring `requiresCredentials`
  (`PropertyUrlIntelligence.credentialsFor`) — a credential registered under `zillow` cannot turn a
  public page fetch into an authenticated one.
- `SourceRateLimiter` (token bucket, default 12 rpm) and `SourceHealthTracker`
  (circuit breaker: 3 failures ⇒ open 10 min ⇒ half-open) exist and are wired by default.
- Default User-Agent is self-identifying: `RealEstateAI-PropertyIntel/1.0 (+https://realestate-ai.example/bot; contact: ops@realestate-ai.example)` (`port/HttpPort.kt:39`).
  **The contact URL and mailbox are `example` placeholders — they do not resolve. A real operator
  contact is required before any production crawling.** (VERIFIED; the placeholder is in source.)

**Budget** (`FetchOptions`, `HttpPort.kt:20-27`): connect 8 s, read 15 s, max body 4 MB, max 5 redirects.

**Test evidence:** `app/src/test/resources/fixtures/property-url-intelligence/` holds exactly six
fixtures — `zillow-homedetails.html`, `redfin-listing.html`, `homes-com-listing.html`,
`generic-brokerage-listing.html`, `anti-bot-page.html`, `robots.txt`. That the anti-bot page is a
fixture means *blocking is a tested outcome*, which is good. It also bounds the claim: the parsers
are proven against **six hand-written documents**, not against real portal output.

---

### 4.3 Fabrication engine — `com.example.domain.intelligence.source` (stack C)

| Adapter | File | What it actually does | Level |
| --- | --- | --- | --- |
| `ZillowUrlSourceAdapter` | `intelligence/source/adapters/ZillowUrlSourceAdapter.kt` | Regexes the URL slug. No HTTP. Returns `$485,000`, 3bd/2ba, 1,850 sqft, rent `$3,450`, `propertyTax = basePrice * 0.018`, `apn = "02-1400-098"`, `parcelId = "TR-0491-002"`, lat/long `30.2501/-97.7495`, 3 Unsplash stock photos | **L0** |
| `RedfinUrlSourceAdapter` | `.../RedfinUrlSourceAdapter.kt` | Returns `$525,000`, 4bd/2.5ba, 2,200 sqft, hardcoded address `"2804 East 4th St, Austin, TX 78702"`, `apn = "02-1800-401"` | **L0** |
| `RealtorUrlSourceAdapter` | `.../RealtorUrlSourceAdapter.kt` | Returns `$460,000`, `"1105 Nueces St, Austin, TX 78701"`, `listingId = "REA-992144"` (constant), `apn = "01-1105-001"` | **L0** |
| `HomesUrlSourceAdapter` | `.../HomesUrlSourceAdapter.kt` | Returns `$430,000`, `"5204 Menchaca Rd, Austin, TX 78745"`, `listingId = "HMS-4819"` (constant) | **L0** |
| `GenericUrlSourceAdapter` | defined inside `HomesUrlSourceAdapter.kt` | same pattern | **L0** |

**The provenance forgery is the aggravating factor.** From `ZillowUrlSourceAdapter.kt:104-110`:

```kotlin
FieldProvenance("listPrice",    "$$basePrice", sourceName,               ProvenanceSourceTier.DIRECT_LISTING,     now, 0.98),
FieldProvenance("squareFeet",   "$sqft",       sourceName,               ProvenanceSourceTier.DIRECT_LISTING,     now, 0.96),
FieldProvenance("estimatedRent","$$rentEst",   "Zillow Rent Zestimate",  ProvenanceSourceTier.SECONDARY_ESTIMATE, now, 0.88),
FieldProvenance("propertyTax",  "$${basePrice * 0.018}", "Travis County Tax Assessor",
                                                                    ProvenanceSourceTier.GOVERNMENT_DATA,      now, 0.95)
```

A number computed as `price × 0.018` is labelled **`GOVERNMENT_DATA`, sourced to a named county
assessor, at 0.95 confidence**. Any downstream consumer reading `tier` or `confidence` — which is
exactly what a provenance system is for — would treat it as an assessor record. This is worse than
having no provenance system, because the system is actively lying.

`RealtorUrlSourceAdapter` and `HomesUrlSourceAdapter` also return a **constant** `listingId`
(`"REA-992144"`, `"HMS-4819"`), so every Realtor.com import would collide on
`sourceId + externalId` — the importer's first deduplication level — and overwrite each other.

**Wiring status (VERIFIED, check C1):** zero references from any file under `app/src/main` outside
the adapters' own package. They **are** referenced by `app/src/test/java/com/example/PropertyUrlIntelligenceTest.kt:104-108`,
which registers all five into a `SourceRegistry` and asserts on the results — i.e. **the test suite
passes by asserting that fabricated data equals fabricated data.** Those tests give false assurance
and should not be cited as coverage.

**Risk:** they are in `src/main`, so they are compiled into the release APK. ProGuard is off for
release (`isMinifyEnabled = false`, `app/build.gradle.kts:55`), so they are not even stripped.

---

### 4.4 Unwired second pipeline — `:urlintelligence` module (stack B)

| Component | File | Notes |
| --- | --- | --- |
| `KnownSources` | `urlintelligence/.../source/SourceDescriptor.kt:104` | 5 descriptors: `zillow`, `redfin`, `realtor`, `homes`, `generic.web` |
| `ZillowAdapter` / `RedfinAdapter` / `RealtorAdapter` / `HomesAdapter` / `GenericWebAdapter` | `urlintelligence/.../adapter/` | `StructuredDataPropertySourceAdapter` subclasses |
| `RobotsPolicy` / `AccessPolicy` | `urlintelligence/.../compliance/AccessPolicy.kt` | second, independent robots implementation |
| `PropertyUrlResolver` | `urlintelligence/.../resolver/PropertyUrlResolver.kt` | ~660 lines |
| `OkHttpPropertyTransport` | `app/.../data/urlintelligence/OkHttpPropertyTransport.kt` | the Android transport for stack B |
| `UrlIntelligenceModule` / `PropertyUrlImportService` | `app/.../data/urlintelligence/PropertyUrlImportService.kt` | the bridge — **referenced only by tests** |

Level: **L2** for the parsers (its own test suite under `urlintelligence/src/test/` uses fixtures),
**not reachable in production**. `UrlIntelligenceModule.OPT_IN_SOURCES = setOf("zillow","redfin","realtor","homes")`
is a good compliance idea that the live stack does not have.

---

### 4.5 Repositories, persistence and fallbacks

| Component | File | Role | Assessment |
| --- | --- | --- | --- |
| `PropertyImportRepository` | `data/repository/PropertyImportRepository.kt` | Atomic write of property + satellites + provenance + jobs; dedup; canonical-key reconciliation | Real, substantive logic. **Contains the §7.3 overwrite defect.** |
| `PropertyRepository` | `data/repository/PropertyRepository.kt` | UI read model + write facade | Sound. |
| `PropertyEnrichmentRepository` | `data/repository/PropertyEnrichmentRepository.kt` | Multi-provider store keyed `(propertyId, enrichmentType, provider)`, confidence-ranked, `expiresAt`, `expireStale()` | **Correctly designed. No producer exists (§7.4).** |
| `PropertyFinancialRepository` | `data/repository/PropertyFinancialRepository.kt` | Asset financial baseline | `monthlyRent = rent?.estimatedRent ?: 0.0` — honest zero. |
| `FinancialRepository` | `data/repository/FinancialRepository.kt` | Underwriting orchestration | Routes everything through `UnderwritingEngine`. Sound. |
| `PropertyUnderwritingFactory` | `data/repository/PropertyUnderwritingFactory.kt` | Assembles `UnderwritingInput` from observed data | **The model of correct behaviour** — see §7.2. |
| `PropertyIdentityDeduplicationEngine` | `domain/identity/PropertyIdentityDeduplicationEngine.kt` | Fixed-order exact matching (APN → MLS → source listing ID → URL → address → coordinates) with typed, explainable outcomes | Real logic; unexercised in practice because the feed adapters return nothing. |
| `FilePropertyImportJobStore` | `domain/propertyurl/store/FilePropertyImportJobStore.kt` | Crash-safe job ledger under `filesDir/property-url-intelligence` | Real. |
| `PropertySourceDefaults` fallback | referenced from `PropertyUrlImportBridge` KDoc | An unregistered portal id collapses onto the internal source row | Documented; means a blank `sourceId` loses traceability. |

---

## 5. Provider capability matrix

Legend — **Level** per §3. **Live?** = has any code path that contacts the real provider.

| Provider / source | id | Level | Live? | Auth | Domains matched | Fields it can actually yield | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Zillow (HTML) | `zillow` | **L2** | No | None | `zillow.com` + subdomains; `zpid` query; `/homedetails/…_zpid`, `/b/…_zpid`, `/apartments|community/…_zpid` | 28-field ceiling via shared chain + zpid confirmation | `trustRank 9`, 8 rpm. Catalogue note: *"automated retrieval requires a Zillow partner agreement in most deployments."* |
| Redfin (HTML) | `redfin` | **L2** | No | None | `redfin.com`; `/{ST}/{city}/{addr}/{id}`, `/home/{id}`, `?listingId=` | shared chain + `mlsId`/`listingId` | `trustRank 8`, 8 rpm, `supportsRentEstimate = false` |
| Realtor.com (HTML) | `realtor_com` | **L2** | No | None | `realtor.com`; `M#####-#####`, `/realestateandhomes-detail/…_######` | shared chain only | `trustRank 8`, 8 rpm. Note: *"use the OFFICIAL RESO Web API feed when available."* |
| Homes.com (HTML) | `homes_com` | **L2** | No | None | `homes.com`; `/property/{slug}/{id}`, `?listingId`, `?pid` | shared chain only | `trustRank 7`, 8 rpm |
| Generic brokerage site | `generic_web` | **L2** | No | None | any host not otherwise matched | shared chain only → at most a *partial* record | `trustRank 2`, 6 rpm. `allowGenericFallback = true` by default |
| Apartments.com | `apartments_com` | **L1** | No | None | `apartments.com` | none — `PLANNED`, rejected pre-fetch | Adapter class exists but is unregistered |
| Trulia | `trulia` | **L1** | No | None | `trulia.com` | none — `PLANNED` | Detection only, so users get an actionable message |
| LoopNet | `loopnet` | **L1** | No | None | `loopnet.com` | none — `PLANNED` | Note: commercial needs cap-rate/NOI model |
| MLS / IDX feed | `mls_feed` | **L1** | No | `requiresCredentials = true`, `requiresApiKey = true` | **none** (`domains = emptyList()`) | none — no adapter registered | **Declared `AVAILABLE` yet unreachable** (§8.3) |
| County records | `county_records` | **L1** | No | `requiresCredentials = true` | **none** | none — no adapter registered | Same unreachable-`AVAILABLE` defect |
| Off-market wholesale | `off_market_wholesale` | **L1** | No | None | **none** | none — the wired adapter returns `emptyList()` | Same unreachable-`AVAILABLE` defect |
| Zillow/Redfin/Realtor/Homes "URL source" | n/a | **L0 fabricated** | No | None | `zillow.com`, `redfin.com`, `realtor.com`, `homes.com` | Hardcoded constants | **Not wired. Delete or quarantine (§11 P0).** |
| MLS on-market seed | n/a | **L1 empty** | No | None | n/a | none | `getSeedBundles() = emptyList()` |
| ATTOM / CoreLogic / RentCast / HouseCanary / First American / RESO / Bridge / MLS Grid | — | **absent** | No | — | — | — | **No client, no interface, no stub.** Verified: no vendor SDK is imported and no class/interface/object is named after a vendor anywhere in `app/src/main`. These names appear only as illustrative strings in KDoc comments (e.g. `PropertyEnrichmentEntity`'s *"Provider key, e.g. `"ATTOM"`, `"HOUSECANARY"`"*). |
| FEMA flood, Walk Score, GreatSchools, county permits, liens | — | **absent** | No | — | — | — | Enum constants exist in `PropertyEnrichmentType`; no producer. Test fixtures in `IntelligenceRepositoryTest` name `"FEMA"`, `"WalkScore"`, `"GreatSchools"` as *string literals*, which is not an integration. |
| Repair cost data | — | **L2 (module isolated)** | No | — | — | Deterministic catalogue, no external cost feed | `:repairestimator` is **not a dependency of `:app`** (verified, check C8). Its own `build.gradle.kts` states: *"The Android app does not depend on this module yet."* It carries 15 test files, all unused by the app. |

**Summary, counted by adapter/source rather than by table row:**

| Level | Count | Which |
| --- | --- | --- |
| **L3+ (live / authenticated / production-verified)** | **0** | — none exist — |
| **L2 (parser-only, fixture-backed)** | **5** | `zillow`, `redfin`, `realtor_com`, `homes_com`, `generic_web` |
| **L1 (interface-only / empty / unreachable)** | **8** | `apartments_com`, `trulia`, `loopnet`, `mls_feed`, `county_records`, `off_market_wholesale`, `OnMarketMlsAdapter`, `OffMarketWholesaleAdapter` |
| **L0 (fabricated)** | **5** | `ZillowUrlSourceAdapter`, `RedfinUrlSourceAdapter`, `RealtorUrlSourceAdapter`, `HomesUrlSourceAdapter`, `GenericUrlSourceAdapter` |
| **Absent entirely** | — | every commercial vendor, FEMA/Walk Score/GreatSchools, permits, liens, ownership |

**No provider in this repository has any evidence of having returned real data from a real endpoint.**

---

## 6. Domain-by-domain coverage

For each of the domains in scope, the question is not "is there a UI field?" but **"is there a source,
and can the data model carry it?"**

| Domain | Canonical field? | Producer? | Consumer? | Verdict |
| --- | --- | --- | --- | --- |
| **Property URL import & normalisation** | yes (28 `PropertyField`s) | stack A, 5 adapters | Discover screen | **Works as designed, fixture-verified only.** Best-supported capability in the repo. |
| **Active listings (on-market)** | yes | HTML scrape only | Discover, Deal Room | **No licensed feed.** Scrape-only, L2. |
| **Off-market / distressed / probate** | yes | `OffMarketWholesaleAdapter` → `emptyList()` | Discover (`OFF_MARKET` tab) | **Empty. Zero capability.** |
| **Sold comparables** | **NO** — `PropertyField` has no comp field (check C9) | `PropertyCompEntity` / `ComparablePropertyEntity` tables + `upsertComps` exist; `PropertyUrlImportBridge` passes `comps = emptyList()` | Deal Room comps tab, `getComps` | **Schema + merge logic, no source. Always empty.** |
| **Sales history** | **NO** | `SalesHistoryEntity`; bridge passes `salesHistory = emptyList()` | `getSalesHistory` | **Schema, no source.** |
| **Property taxes** | `ANNUAL_TAX_AMOUNT` (`ENRICHMENT`) | `EmbeddedJsonStateParser` / `TextFactsHeuristics` *may* find it in a portal page; bridge writes `annualTaxAmount ?: 0.0` and `assessedValue = 0.0` | Deal Room, underwriting | **Partial and unreliable.** No assessor integration. `assessmentYear` is set to `Calendar.YEAR` — the *import* year, not the assessment year. That is a fabricated field. |
| **Assessed value** | **NO** (`ASSESSED_VALUE` absent) | none | `TaxRecordEntity.assessedValue` | **Always 0.0.** |
| **Rent estimates / rental AVM** | **NO** (`RENT_ESTIMATE` absent) | none; bridge writes `estimatedRent = 0.0` | Deal Room, underwriting, ROI | **No source at all.** See §7.2/§7.3 for what fills the gap. |
| **Repair / rehab estimates** | n/a | `:repairestimator` (deterministic, offline, no cost feed) — **not linked to `:app`** | Deal Room uses flat `$35k`/`$50k`/`$5k` from `DeterministicFinancialEngine` | **The good engine exists but is not wired; the wired path uses constants.** |
| **Flood risk** | **NO** | none (`FLOOD_RISK` enum only) | Deal Room `intelligenceFor(FLOOD_RISK)` ×3 places | **Renders empty forever.** |
| **Permits** | **NO** | none (`PERMIT` enum only) | — | **Absent.** |
| **Ownership / occupancy / absentee** | **NO** | none (`OWNER_OCCUPANCY` enum only; `Seller.kt` models are user-entered CRM data, not a records source) | CRM only | **Absent as a data source.** |
| **Liens** | **NO** | none (`LIEN` enum only) | — | **Absent.** |
| **Market statistics / submarket trends** | **NO** | none; `MarketDataEntity` written as zeros by the bridge | Deal Room coverage panel, ROI calculator | **No source.** ROI calculator fabricates (`medianAreaPrice ?: price * 1.05`, appreciation `?: 4.0`, DOM `?: 30`) — see §7.2. |
| **Walk / transit / bike score** | **NO** | none | Deal Room ×3 | **Empty.** |
| **School rating** | **NO** | none | Deal Room | **Empty.** |
| **Crime index** | **NO** | none | Deal Room ×2 | **Empty.** |
| **Images** | `IMAGE_URLS` | portal pages, SSRF-screened | Property cards | Works; Unsplash URLs appear only in the fabricated stack C. |

**The structural point:** eleven of these domains cannot be fixed by adding a provider alone, because
`PropertyField` — the canonical vocabulary every parser emits into — has no entry for them. The
`property_enrichments` table *was* built to carry exactly these (per-value confidence, `effectiveAt`,
`expiresAt`, `provenanceId`, provider-keyed so two vendors can disagree), and it is the right home.
It simply has no writer.

---

## 7. Critical data gaps and risks

### 7.1 RISK-01 (Critical) — Fabricated records with forged government provenance

Covered in §4.3. Fabricated `listPrice`, `squareFeet`, `estimatedRent`, `propertyTax`, `apn`,
`parcelId`, `latitude`/`longitude` and `county`, attributed to `GOVERNMENT_DATA` /
`DIRECT_LISTING` at 0.95–0.99 confidence.

Currently unreachable from production (VERIFIED). The risk is that it is **one import away**, sits in
the release APK unminified, and its test suite asserts on the fabricated values — so anyone wiring it
would see green tests.

**Evidence required before any adapter may be called production-ready:** a recorded, timestamped
response from the real endpoint, a fixture captured from that response, and a test asserting the
parsed output against the fixture — never against a constant.

### 7.2 RISK-02 (Critical) — Plausible-looking defaults standing in for missing data

This is the "missing data filled with plausible-looking defaults" question, and the answer is **yes,
in four places**, all VERIFIED (check C6):

**(a) `DeterministicFinancialEngine`** (`domain/intelligence/engine/DeterministicFinancialEngine.kt`) —
**wired into the Deal Room** at `DealRoomViewModel.kt:215` and `:235`:

| Line | Substitution | Presented as |
| --- | --- | --- |
| 21 | `monthlyRent = customRent ?: (property.estimatedRent ?: purchasePrice * 0.0075)` | gross monthly rent |
| 52 | `propertyTaxMonthly = (property.propertyTax ?: purchasePrice * 0.018) / 12` | property tax |
| 53 | `insuranceMonthly = (purchasePrice * 0.006) / 12` | insurance — **always**, even when `annualInsurance` is stored |
| 37 | `closingCosts = purchasePrice * 0.025` | closing costs |
| 38-43 | rehab: BRRRR `$35,000`, flip `$50,000`, buy-and-hold `$5,000` | renovation cost |
| 83-88 | ARV: `×1.30` BRRRR, `×1.35` flip, `×1.10` wholesale, `×1.05` hold | after-repair value |
| 91 | 5-yr equity at `1.045^5` | appreciation |

None of these carries a `ValidationIssue`, a provenance record, or a confidence value.

**(b) `AnalyzerViewModel.kt:40,46,47,51,52`** — `rent ?: price * 0.008`, `closingCosts = price * 0.025`,
`renovationCost = if OFF_MARKET 35000 else 5000`, `propertyTaxAnnual = price * 0.012`,
`insuranceAnnual = price * 0.006`. **No disclosure anywhere in the file** (check C6 asserts the word
"assumption" does not appear).

**(c) `PropertyRoiCalculatorViewModel.kt:343-363`** — `renovationCost = if OFF_MARKET 35000 else 10000`,
`medianAreaPrice ?: price * 1.05`, `pricePerSqFt ?: 250.0`, `averageDaysOnMarket ?: 30`,
`neighborhoodAppreciationRate ?: 4.0`, `marketDemand ?: "Moderate"`,
`estimatedMonthlyRent ?: price * 0.008`, rent range `?: 3000.0 × 0.9/1.1`,
`propertyTaxAnnual ?: price * 0.015`, `insuranceAnnual = price * 0.006`. The input type compounds it:
`LocalMarketDataInput` itself defaults `fairMarketRentRangeLow = 3000.0` and `fairMarketRentRangeHigh = 3600.0`
(`:65-66`), so an omitted rent range silently becomes a concrete $3,000–$3,600 band.

**(d) `TaxRecordEntity.assessmentYear`** — `PropertyUrlImportBridge.kt:131` sets it to the current
calendar year regardless of the actual assessment period.

**Mitigating finding — and it is the template to copy.** Two places do this correctly:

- `PropertyUnderwritingFactory` refuses to guess and emits `MISSING_RENT_ESTIMATE`,
  `MISSING_PROPERTY_TAX_RECORD`, `INSURANCE_NAMED_DEFAULT`, `NO_RENOVATION_ESTIMATE` as typed
  `ValidationIssue`s, naming the exact constant that will apply. Its KDoc even documents the
  fabrications it *removed*.
- `DealRoomViewModel.modelInputsOf` discloses each input to the user with strings like
  *"not stored · engine default assumption"* and *"stored fact not consumed by the engine · default
  assumption used"*, and `coverageOf` renders *"Source-backed coverage: N of M tracked fields"*.

So the honest pattern already exists in this codebase. The Deal Room **discloses** the assumptions but
still **computes with** the fabricated engine; the Analyzer and ROI screens neither disclose nor flag.

**Answer to the audit question:** yes, missing data is filled with plausible-looking defaults, in
three UI paths. Two of the three do not tell the user.

### 7.3 RISK-03 (Critical) — Re-import destroys observed financial data

VERIFIED chain (check C5):

1. `PropertyUrlImportBridge.toBundle` always emits `estimatedRent = 0.0`, `rentRangeLow = 0.0`,
   `rentRangeHigh = 0.0`, `rentConfidenceScore = 0.0`, `assessedValue = 0.0`, `estimatedValue = 0.0`,
   `medianAreaPrice = 0.0`, `neighborhoodAppreciationRate = 0.0`. The class KDoc calls these
   *"Placeholders… written as zeros on purpose — the layer never invents numbers"*, which is the
   right instinct for a *fresh* record.
2. `PropertyImportRepository.writeSatellites` (`:420-422`) then calls `insertMarketData`,
   `insertRentEstimate`, `insertTaxRecord` **unconditionally** — no `isNotEmpty()` guard, unlike
   `images`/`salesHistory`/`comps` which are guarded.
3. All three DAO methods are `@Insert(onConflict = OnConflictStrategy.REPLACE)`
   (`PropertyDao.kt:122,133,144`).

**Consequence:** if a property already has a real rent estimate — from a future provider, from
`PropertyEnrichmentRepository`, or from manual entry — and the user re-pastes the listing URL (or the
import queue retries the job, or `forceRefresh` is used), **the real value is replaced by 0.0.**
`PropertyUnderwritingFactory` then correctly reports `MISSING_RENT_ESTIMATE`, and the deal silently
loses its rent basis.

This is the most consequential defect in the audit because it is a *regression* mechanism: it can
destroy good data, not merely fail to obtain it.

### 7.4 RISK-04 (High) — Enrichment store is schema without a writer

VERIFIED (check C4): `PropertyEnrichmentType` declares 12 categories; **no file under `app/src/main`
constructs a `PropertyEnrichmentEntity`** (outside the entity declaration itself); the only production
bundle producer never sets `enrichments`. The only writer is
`PropertyImportRepository.upsertEnrichments`, which iterates `bundle.enrichments` — always empty.

The only code that ever populates the table is `IntelligenceRepositoryTest`, using provider names as
**string literals** (`"FEMA"`, `"WalkScore"`, `"GreatSchools"`). A passing test that writes literal
strings is not evidence of an integration.

`DealRoomScreen` reads `FLOOD_RISK` (3 sites), `WALK_SCORE` (2), `CRIME_INDEX` (2), `SCHOOL_RATING` (1)
and `MARKET_TREND` (1). All render empty. Because `coverageOf` counts them as *tracked but not sourced*, the
UI is at least honest about the gap — the coverage panel will read `2 of 10`.

### 7.5 RISK-05 (High) — Stale data and cross-property contamination

The audit asked specifically whether stale data or one property's data can leak into another's.
Findings:

| Vector | Status | Evidence |
| --- | --- | --- |
| Cross-property leakage via canonical id | **Low risk, well handled** | `CanonicalIds.canonicalId(addressLine1, city, state, postalCode)` keys by address, so the *same* house from two portals collapses to one row (intended). Different houses get different ids. `PropertyIdentityDeduplicationEngine` evaluates exact signals in a fixed order — APN/parcel → MLS ID → source listing ID → normalized source URL → normalized address → coordinates — and returns a typed `DeduplicationStatus` (`EXACT_MATCH`, `CANONICAL_MATCH`, `POSSIBLE_MATCH`, `CONFLICT`, `NEW`). Weak signals (loose coordinates, fuzzy address ≥ 0.80) can only ever yield `POSSIBLE_MATCH`, never an auto-merge. |
| Cross-property leakage via fabricated adapters | **Real, if stack C is wired** | `RealtorUrlSourceAdapter` returns a constant `listingId = "REA-992144"` and `HomesUrlSourceAdapter` a constant `"HMS-4819"`. Every import from those portals would collide on `sourceId + externalId`, so property B's record would overwrite property A's. This is a concrete contamination path. |
| Stale data via `sourceUpdatedAt` | **Correctly handled** | `PropertyUrlImportBridge` leaves it at `0` with an explicit comment: *"a page fetch has no trustworthy 'last modified by the source' value, and the schema reads 0 as unknown."* Not fabricated. Good. |
| Stale enrichment | **Designed for, unusable** | `PropertyEnrichmentEntity.expiresAt` + `isCurrent` + `expireStale()` exist. No producer ⇒ nothing to expire. |
| Stale `lastVerifiedAt` | **Overwritten on merge** | `mergeWith` sets `lastVerifiedAt = maxOf(existing.lastVerifiedAt, now)` — the *import* time, not a source-asserted verification time. Acceptable, but it means "last verified" really means "last imported". |
| Stale cache reuse | **Flagged, not silent** | `STALE_CACHE_REUSED` warning is emitted on redirect (`BasePropertySourceAdapter.kt:172`). Note the code reuses a `STALE_CACHE_REUSED` code for a redirect notice, which is a mild mislabel. |
| Truncated payload | **Flagged** | `PARTIAL_PARSE` warning at `BasePropertySourceAdapter.kt:182`. |

### 7.6 RISK-06 (Medium) — Two divergent source catalogues

VERIFIED (check C10): `SourceCatalog` knows 11 sources; `KnownSources` knows 5. They disagree on the
identifier for the same portal (`realtor_com` vs `realtor`) and on coverage (only `SourceCatalog`
knows `apartments_com`/`trulia`/`loopnet`). Stack A has no opt-in concept; stack B's
`requiresOptIn` / `OPT_IN_SOURCES` allow-list has no equivalent in the live path.

Consequence: a compliance decision recorded in one catalogue does not apply to the other, and a
developer adding a source must guess which file matters. Since only stack A is live, stack B's
compliance gate is inert.

### 7.7 RISK-07 (Medium) — Sources declared `AVAILABLE` but structurally unreachable

VERIFIED (check C10): `mls_feed`, `county_records` and `off_market_wholesale` all carry
`status = AVAILABLE` (explicitly for `mls_feed`, by default for the others) while having
`domains = emptyList()` **and** no registered adapter.

`SourceRegistry.supported()` therefore reports them as supported, and `SourceCatalog.availableOnly()`
includes them — but no URL can ever detect them and no adapter can ever serve them. Any capability
dashboard or "supported sources" list built on `supported()` will overstate readiness by three
sources, two of which are the *only* credentialed, non-scrape sources in the catalogue.

### 7.8 RISK-08 (Medium) — Parser confidence is asserted, not measured

The `SourcePathRule.confidence` values (0.96 Zillow homedetails, 0.93 Redfin address-slug, 0.92
Realtor detail, 0.90 Homes slug) and `ProvenanceMethod` ceilings (0.98 structured data, 0.95 embedded
state, 0.65 text heuristic) are **hand-set constants**. Nothing in the repository measures actual
extraction accuracy against real pages, so these numbers are design intentions, not measurements.
`Provenance.effectiveConfidence` further scales by `trustRank`, which is also hand-set.

This is not a defect — it is the correct shape for a provenance system — but the numbers must not be
quoted as measured accuracy. **UNVERIFIED as accuracy; VERIFIED as declared.**

### 7.9 RISK-09 (Medium) — `:repairestimator` is built but not linked

VERIFIED (check C8): `settings.gradle.kts:29` includes `:repairestimator`, but
`app/build.gradle.kts` has no `project(":repairestimator")` dependency and no app source imports
`com.example.repairestimator`. The module's own header says so explicitly.

So the app ships **without** the deterministic repair engine, while `DeterministicFinancialEngine`
uses flat `$35,000` / `$50,000` / `$5,000` rehab constants. The better engine is compiled, tested
(15 test files under `repairestimator/src/test/`) and unused.

---

## 8. Unsupported URL domains — do they fail explicitly?

**Yes. This is one of the strongest parts of the system, and it is VERIFIED.**

`PropertyUrlIntelligence.import()` performs static rejection **before any network call**
(`PropertyUrlIntelligence.kt:148-181`, check C7):

| Input | Outcome | Job state | Reason code |
| --- | --- | --- | --- |
| Not a URL / private host / IP literal / credentials in URL | `ImportOutcome.Rejected` | `REJECTED_INVALID_URL` | `INVALID_URL` |
| Two or more listing links in one paste | `ImportOutcome.Rejected` | `REJECTED_INVALID_URL` | `MULTIPLE_URLS` |
| `apartments.com`, `trulia.com`, `loopnet.com` (`PLANNED`) | `ImportOutcome.Rejected` | `REJECTED_UNSUPPORTED_SOURCE` | `SOURCE_NOT_SUPPORTED` — *"We recognise this website but do not support importing from it yet."* |
| A source with no adapter | `ImportOutcome.Rejected` | `REJECTED_UNSUPPORTED_SOURCE` | `NO_ADAPTER` |
| `DISABLED` source | `ImportOutcome.Rejected` | — | `SOURCE_DISABLED` |
| Any other host | Falls through to `generic_web` | — | partial record at best |

`allowPlannedSources` defaults to **`false`** (`ImportModels.kt:18`), so the three planned portals
are rejected by default. **No fabricated record is produced for an unsupported domain.**

**The one caveat, and it is deliberate:** `allowGenericFallback` defaults to **`true`**
(`ImportModels.kt:16`). An unknown domain such as `randomblog.example/123-main-st` is *not* rejected —
it is fetched and parsed by `GenericWebListingAdapter` at `trustRank 2`, producing at most a partial
record. This is honest (the record carries low-confidence provenance and a completeness report), but
it means **"unsupported" is not the same as "rejected"**: arbitrary user-supplied URLs are fetched by
default. The SSRF guard and robots gate make this safe rather than reckless, but a deployment that
wants a strict allow-list must set `allowGenericFallback = false`. Nothing in the production wiring
does.

`UrlValidationOptions` additionally blocks cleartext HTTP, IP-literal hosts, private-network hosts,
credentials in URLs, non-80/443 ports, path traversal, and >48 query parameters.

---

## 9. Licensing, access restrictions and rate limits

### 9.1 What the repository itself asserts (VERIFIED)

These are the repository's own declared positions, from `SourceCatalog`:

| Source | Declared rpm | `requiresCredentials` | Catalogue note (verbatim) |
| --- | --- | --- | --- |
| `zillow` | 8 | no | *"Deep listing links only; automated retrieval requires a Zillow partner agreement in most deployments."* |
| `redfin` | 8 | no | *"Redfin publishes extensive structured data; keep parsers pinned to the JSON-LD/embedded state contract."* |
| `realtor_com` | 8 | no | *"Realtor.com is a licensed MLS aggregator; use the OFFICIAL RESO Web API feed when available."* |
| `homes_com` | 8 | no | — |
| `mls_feed` | 60 | **yes** (+ `requiresApiKey`) | *"Canonical source of truth. Credentials are injected at runtime from the server side and never stored in the APK."* |
| `county_records` | 6 | **yes** | *"Appraisal district / recorder data for tax, lien and ownership enrichment."* |
| `off_market_wholesale` | 10 | no | *"Curated/wholesale inventory (deal lists, county lists)."* |
| `generic_web` | 6 | no | *"Only structured data, meta tags or title heuristics are trusted."* |

`SourceCatalog`'s header states the governing principle: *"automated retrieval is a contractual
decision, not a technical one… Always prefer a sanctioned MLS/IDX or partner API where one exists."*

### 9.2 Compliance mechanisms actually implemented (VERIFIED)

- robots.txt fetched, parsed and cached per origin with a 6 h TTL; **fail-closed** on unevaluable.
- `SourceCapabilityFetchPolicy.allowsAutomatedFetching` — a per-source kill switch. **No source in
  the catalogue currently sets it to `false`**, so the switch exists but is unused.
- Self-identifying User-Agent naming the app and a contact address.
- `SourceStatus.DISABLED` for incident/contract-driven shutdown.
- Rate limiter (token bucket, per source) and circuit breaker (3 failures → open 10 min).
- Credential isolation: headers only for `requiresCredentials` sources; `.env.example` is
  documentation-only and states that Gradle does not load it into `BuildConfig`.

### 9.3 What this audit **cannot** verify (UNVERIFIED — do not treat as fact)

The following are outside this repository and were **not** checked. This environment has no outbound
access to any provider, and checking terms of service would require browsing the live web:

- Whether Zillow, Redfin, Realtor.com or Homes.com currently permit unauthenticated automated
  retrieval, and what their present terms say.
- Whether the declared 8/10/60/6 rpm figures match any provider's published limits. **They are
  self-imposed budgets, not verified provider limits.**
- Whether the parsers still match current portal markup. The six fixtures are hand-written, so they
  prove nothing about today's live HTML.
- Whether any named commercial vendor offers an endpoint, and at what price. **No vendor capability,
  endpoint, pricing or SLA is asserted anywhere in this document.**
- MLS/RESO participation requirements, IDX display rules, or county-recorder bulk-access terms.

**Action:** every licensing statement in a shipped product must come from a reviewed agreement or a
dated capture of the provider's published terms, held outside this repository. Nothing here
substitutes for that review.

---

## 10. Corrections to the prior audit document

`docs/US_REAL_ESTATE_DATA_PROVIDER_AUDIT.md` is largely sound on vendors and licensing. Four of its
structural claims are **wrong**, verified against source:

| # | Prior claim | Correction | Evidence |
| --- | --- | --- | --- |
| 1 | Diagram labels `:urlintelligence` as "Layer 1" feeding the ingestion bridges, and `domain.propertyurl` as *"Layer 2: Legacy In-App Parser Pipeline"*. | **Reversed.** `domain.propertyurl` is the live production stack; `:urlintelligence` is the unreachable one. `domain.propertyurl` is not legacy — it is what `RealEstateAiApp` builds. | `RealEstateAiApp.kt:147,153`; no `ui/**` reference to `UrlIntelligenceModule` (check C2) |
| 2 | Diagram places the fabricated adapters in "Layer 3" between the parser layers and the ingestion bridges, implying they are in the live flow. | They have **zero** main-source references. They are dead code in `src/main`, exercised only by tests. | check C1 |
| 3 | `OkHttpPropertyTransport` is described as *"Network transport for URL intelligence"* and classified **Live-fetch-tested**. | It is the transport for the **unwired** stack B. The live transport is `OkHttpHttpFetcher`. Separately, "Live-fetch-tested" overstates it: no live fetch is evidenced anywhere. Both transports disable redirects — that part is correct. | `OkHttpHttpFetcher.kt:42`; `RealEstateAiApp` wiring |
| 4 | Header states `Working Branch: arena/359ef2d2-2`. | Stale. | — |

**Not covered by the prior audit at all**, and found here: RISK-02 (`DeterministicFinancialEngine`
and the Analyzer/ROI fabrications), RISK-03 (satellite overwrite), RISK-04 (enrichment store has no
writer — the prior doc says "NO VENDORS" but not that no code constructs the entity), RISK-07
(unreachable `AVAILABLE` sources), RISK-09 (`:repairestimator` unlinked).

**Agreement:** the prior audit's findings 1 (no authenticated providers), 2 (synthetic adapters),
3 (empty seed adapters) and 5 (no secrets in the APK) are all confirmed here.

---

## 11. Is a new provider-neutral interface needed?

**No — and proposing one would be the wrong recommendation.** The task asked for a proposal *only if
the architecture lacks an adequate equivalent*. It does not.

What already exists, all VERIFIED:

| Requirement | Existing equivalent |
| --- | --- |
| Explicit provenance | `Provenance(sourceId, method, confidence, extractedAtEpochMillis, adapterId, adapterVersion, sourceUrl, rawPath, note)` — per **field**, not per record |
| Timestamps | `extractedAtEpochMillis` per field; `builtAtEpochMillis` per record; `fetchedAt` / `sourceUpdatedAt` / `firstSeenAt` / `lastSeenAt` on `PropertyProvenanceEntity` |
| Confidence | `Provenance.confidence` + `ProvenanceMethod.ceilingFor()` + `effectiveConfidence(trustRanks)`; `PropertyEnrichmentEntity.confidence` (0–100) for provider facts |
| Missing-field semantics | `CompletenessReport` with `FieldCriticality` (IDENTITY/ESSENTIAL/LOCATION/ENRICHMENT/OPTIONAL), `MISSING_CRITICAL_FIELD` / `MISSING_RECOMMENDED_FIELD` warnings, `ImportOutcome.Partial(missingFields)`, `MapperOptions.requiredFields` |
| Conflict resolution | `ProvenancePolicy.pick()` — trust rank → method weight → confidence → recency, deterministic, records `FieldConflict` |
| Failure semantics | 34 `SourceFailureKind`s across 9 categories, each with a `Retryability` |
| Multi-provider disagreement | `property_enrichments` keyed `(propertyId, enrichmentType, provider)` |

**The gap is adoption, not design.** Three concrete gaps:

1. `PropertyField` has no entries for rent estimate, assessed value, flood, permit, ownership, liens,
   comps or sale history (§6). The enrichment table can carry them; the canonical field vocabulary
   cannot.
2. `PropertyUrlImportBridge` discards the provenance it just built. `CanonicalProperty` holds a
   `SourcedField` (value + provenance) for every field; `toBundle` flattens to bare `Double`/`Int`
   columns, so per-field provenance does not survive into Room. `NormalizedPropertyBundle` keeps only
   record-level provenance (`sourceId`, `externalId`, `externalUrl`, `ingestionMethod`).
3. Nothing writes `property_enrichments`, so the multi-provider store is unused.

**Recommendation:** extend `PropertyField` and route enrichment domains through
`PropertyEnrichmentRepository`, rather than introducing a fourth parallel abstraction. Three
competing stacks is already the core structural problem (§4.0); adding a fourth would compound it.

---

## 12. Prioritized recommendations

### P0 — Stop the bleeding (correctness and safety)

| # | Action | Why | Files |
| --- | --- | --- | --- |
| P0-1 | **Delete `com.example.domain.intelligence.source.adapters`** (4 adapters + `GenericUrlSourceAdapter` + `PropertyUrlSourceAdapter` + the stack-C `SourceRegistry`/`PropertyUrlResolver`), and delete or rewrite `PropertyUrlIntelligenceTest` so it no longer asserts on fabricated values. | L0 fabrication with forged `GOVERNMENT_DATA` provenance, shipping in the APK, one import from being live. If it must be kept as a design reference, move it to `src/test` where it cannot ship. | `intelligence/source/**`, `app/src/test/.../PropertyUrlIntelligenceTest.kt` |
| P0-2 | **Guard the satellite writes.** Only overwrite `rent_estimates` / `tax_records` / `market_data` when the incoming bundle carries a non-zero, positive value; otherwise leave the stored row untouched. | Currently a re-import zeroes real observed data (§7.3). | `PropertyImportRepository.writeSatellites:420-422`, `PropertyUrlImportBridge.toBundle` |
| P0-3 | **Retire `DeterministicFinancialEngine` from the Deal Room** and route the screen through `FinancialRepository`/`UnderwritingEngine`. If the screen needs instant strategy switching, keep it but source every input through `PropertyUnderwritingFactory` so gaps become `ValidationIssue`s. | The primary deal-analysis UI runs on silent price-ratio guesses while an oracle-verified engine sits unused beside it (§7.2). | `DealRoomViewModel.kt:215,235` |
| P0-4 | **Remove the silent defaults in `AnalyzerViewModel` and `PropertyRoiCalculatorViewModel`**, or surface them with the Deal Room's existing `ModelInputBasis` disclosure pattern. | Two screens present fabricated economics with no disclosure at all (§7.2b, §7.2c). | `AnalyzerViewModel.kt:40-58`, `PropertyRoiCalculatorViewModel.kt:343-364` |
| P0-5 | **Stop fabricating `assessmentYear`.** Write `0` (the schema's "unknown") instead of `Calendar.YEAR`. | A wrong year is worse than an absent one; it will be read as an assessment period. | `PropertyUrlImportBridge.kt:131` |
| P0-6 | **Correct the three unreachable `AVAILABLE` sources** — mark `mls_feed`, `county_records`, `off_market_wholesale` as `PLANNED` (or a new `DECLARED_NO_ADAPTER` status) until an adapter exists. | Any readiness view built on `supported()` overstates capability by three sources (§7.7). | `SourceCatalog.kt` |

### P1 — Make the honest state visible

| # | Action | Why |
| --- | --- | --- |
| P1-1 | **Collapse the three stacks into one.** Designate `domain.propertyurl` as the only ingestion path; port `KnownSources`' `requiresOptIn` allow-list into `SourceCapabilities`, then delete the `:urlintelligence` module and its app-side bridge, or wire it and delete stack A. | Two catalogues that disagree, two robots implementations, two compliance gates — only one of which is live (§4.0, §7.6). |
| P1-2 | **Label the seed adapters honestly.** `sourceName = "MLS Feed (Demo Seed Dataset)"` implies data. Rename to `"MLS Feed (not connected)"`, or make `syncFromSources` return an explicit `SOURCE_NOT_CONNECTED` summary. | A user watching automation "succeed" with zero discoveries cannot tell the difference from a real empty market. |
| P1-3 | **Surface `CompletenessReport` and `ImportWarning`s in the UI.** `ImportOutcome.Partial(missingFields)` is computed and then dropped by `IntelligenceRepository.importPropertyUrl`, which reports only COMPLETED/FAILED. | The layer already knows exactly what is missing; the user is not told. |
| P1-4 | **Extend the coverage panel to name the absent domains** (flood, permits, liens, ownership, comps, market stats) as *"no provider connected"* rather than *"not stored"*. | "Not stored" implies it could be. It cannot, today. |
| P1-5 | **Add a build-time guard** asserting that no `PropertyExtractionResult`/`FieldProvenance` value can be constructed with `GOVERNMENT_DATA` or `DIRECT_LISTING` tier outside a real adapter. | Makes P0-1's regression structurally impossible. |

### P2 — Build real capability (only with evidence)

| # | Action | Why |
| --- | --- | --- |
| P2-1 | **Extend `PropertyField`** with the missing domains and write producers into `property_enrichments` via `PropertyEnrichmentRepository.importAll`. | The store, confidence ranking, `expiresAt` and provider-keyed conflict handling already exist and are unused (§11). |
| P2-2 | **Wire `:repairestimator` into `:app`** to replace the flat `$35k`/`$50k`/`$5k` constants. | A tested (15 test files), deterministic, offline engine already exists and is not linked (§7.9). |
| P2-3 | **Preserve per-field provenance into Room.** Add a field-level provenance table so `SourcedField.provenance` survives the bridge. | The layer computes it; persistence throws it away (§11.2). |
| P2-4 | **Before calling any provider production-ready, require:** (a) a dated capture of the provider's terms and rate limits, (b) a recorded live response, (c) a fixture derived from that response, (d) a test asserting parsed output against the fixture, (e) measured extraction accuracy replacing the hand-set confidence constants, (f) an operator contact in the User-Agent that actually resolves. | Converts §7.8's declared confidence into measured confidence, and is the only defensible route from L2 to L3+. |
| P2-5 | **Decide the generic-fallback policy explicitly.** Either set `allowGenericFallback = false` for a strict allow-list, or document that arbitrary user-supplied URLs are fetched. | Today it defaults to on and nothing in the wiring changes it (§8). |
| P2-6 | **Prefer licensed feeds over scraping.** The catalogue already says this for Realtor.com (*"use the OFFICIAL RESO Web API feed when available"*) and for `mls_feed` (*"Canonical source of truth"*). Scraping is L2 at best and is contractually fragile; a licensed feed is the only route to L4/L5. | Note that this requires a backend, which is out of scope for this branch — recorded as a recommendation, not implemented. |

---

## 13. Acceptance criteria — self-assessment

| Criterion | Met? | Where |
| --- | --- | --- |
| Every discovered provider has an evidence-based status and capability list | **Yes** | §5 matrix — 16 entries, each with a level, wiring status, auth, domains and field ceiling |
| Every critical financial field has an identified source or is explicitly marked unavailable | **Yes** | §6 domain table + §7.2 substitution table. Rent, assessed value, insurance, comps, flood, permits, liens, ownership and market stats are all marked **no source**. |
| No mocked or fixture-backed integration is described as live production data | **Yes** | §3 separates L2 from L3; §2 states plainly that nothing is L3+; the prior document's "Live-fetch-tested" label is corrected in §10.3 |
| Licensing and access restrictions are documented | **Yes** | §9.1 (repository's own declarations), §9.2 (implemented mechanisms), §9.3 (explicitly UNVERIFIED — no external terms asserted) |
| The report separates verified facts from assumptions and unverified capabilities | **Yes** | Every claim carries VERIFIED / DERIVED / UNVERIFIED per §1, and §1 lists exactly what was run and what could not be |

---

## 14. Evidence appendix — how to reproduce

```bash
# 1. This audit's claim harness (86 assertions over source text and Gradle wiring)
python3 tools/data_provider_audit/verify_audit_claims.py          # -> ALL 86 CLAIM CHECKS PASSED

# 2. The repository's own JVM-free guards
python3 tools/underwriting_oracle/selfcheck.py                    # -> PASSED: 402
python3 -m unittest discover -s tools/financial_audit -q          # -> Ran 12 tests, OK (expected failures=4)

# 3. Spot-check the headline findings by hand
grep -n "return emptyList()" app/src/main/java/com/example/data/adapter/PropertyDataSeed.kt
grep -n "GOVERNMENT_DATA" app/src/main/java/com/example/domain/intelligence/source/adapters/ZillowUrlSourceAdapter.kt
grep -n "PropertyUrlIntelligenceFactory.create\|UrlIntelligenceModule" app/src/main/java/com/example/RealEstateAiApp.kt
grep -n "estimatedRent = 0.0" app/src/main/java/com/example/data/adapter/PropertyUrlImportBridge.kt
grep -n "insertRentEstimate" app/src/main/java/com/example/data/repository/PropertyImportRepository.kt
grep -n "0.0075\|0.018\|0.006" app/src/main/java/com/example/domain/intelligence/engine/DeterministicFinancialEngine.kt
grep -c "PropertyEnrichmentEntity(" -r app/src/main/java/com/example --include=*.kt   # entity decl only
grep -n "repairestimator" app/build.gradle.kts settings.gradle.kts
```

**Not reproduced, and why:** the Kotlin unit suites (`app`, `urlintelligence`, `repairestimator`) and
`tools/room_schema_guard.py`. The first need a JDK, Gradle 9.3.1 and the Android SDK, none of which
are present in this environment; the second needs a full (non-shallow) clone. All three run in CI
(`.github/workflows/*.yml`). **Any statement in this report about test coverage was derived from
reading test source, not from executing it.**
