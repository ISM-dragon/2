# End-to-end reliability audit — property → offer workflow

Auditor: Android Integration & Reliability (Arena agent, session `arena/e2ad05d9-2`).
Branch: `arena/e2ad05d9-2` (the session branch; the task asked for `feature/arina-e2e-reliability`,
which Arena pins to the session branch instead). Base: `main`.
Scope: the ten workflow steps below, tested across module boundaries and under realistic faults.

## 1. Method and non-claims

The audit asserts **one** thing per test: that two production components agree on data that crosses
their boundary, persisted through the real Room schema in a real SQLite file. Nothing in this suite
claims anything about a live provider:

* Portal HTTP (`FakeHttpFetcher`), Gmail delivery (`ScriptedGmailSender`) and the Gemini endpoint
  (no configured API key) are **fakes/absent by design**. They exist to inject a fault at a boundary
  and to keep the run deterministic and network-free. A green result here is evidence that *our*
  code handles the response correctly; it is **not** evidence that Zillow/Redfin/Gmail/Gemini behave
  as our parsers or our retry policy assume.
* Everything the app owns is the real implementation: `PropertyUrlIntelligence` (normalize → parse →
  idempotency → retry), `PropertyImportRepository` (dedup, provenance, satellites, jobs),
  `FinancialRepository` + `UnderwritingEngine`, `QualificationEngine`, `IntelligenceRepository`
  (deal-room read model), `OfferRepository` (offer, document, send ledger, audit trail),
  `FilePropertyImportJobStore` (durable job state).
* "Process death" is a real close-and-reopen of the database file plus a fresh pipeline and a fresh
  job-store instance over the same directory, so only bytes on disk survive — exactly what the app
  gets after a kill.
* No automated test sends a real email, and none could: the Gmail boundary is an interface
  (`GmailSender`) and the shipped `GmailService` requires a stored OAuth token that tests never
  provide. Tests only ever exercise the send *ledger* around that boundary.

## 2. Workflow coverage matrix

| # | Workflow step | Production wiring | Cross-boundary test | CI job |
| - | --- | --- | --- | --- |
| 1 | Import a property URL or manual property | `DiscoverViewModel → IntelligenceRepository.importPropertyUrl → PropertyUrlImportBridge`; manual: `PropertyImportRepository.runImport/importBundle` | `PropertyToOfferWorkflowTest.a portal url becomes a canonical row…`, `…a manual property runs the same job provenance and dedup pipeline`; invalid/ambiguous: `WorkflowFailureInjectionTest` (2 tests) | `E2E reliability CI → Run app integration suites` |
| 2 | Validate and normalize source data | `PropertyUrlValidator/Resolver`, adapters, `CanonicalPropertyMapper`, `UsPropertyNormalizer` | same tests (address/price/zip + `canonicalKey` assertions); parser-level: `urlintelligence` suite, `PropertyUrlMapperTest` | app + `module` steps |
| 3 | Resolve duplicates and persist provenance | `PropertyDeduplicator` + `property_provenance` rows in one transaction (`findProvenance(sourceId, externalId)` = level 1) | `…importing the same listing twice is idempotent…`, `…a forced refresh refetches and still converges…`, `…the same house arriving from a second source merges…`, `…comparables and enrichment … never duplicate`; portal origin (DEF-6): provenance row carries `externalUrl`/`externalId`/`SCRAPE` | app integration step |
| 4 | Load enrichment and comparable sales when available | **only** what a feed bundle carries (`writeSatellites`); no provider integration | `…comparables and enrichment carried by a source reach the deal room…` | app integration step |
| 5 | Estimate repairs when sufficient evidence exists | **not wired — see GAP-1** | none possible (no production consumer); estimator is unit-tested inside `:repairestimator` | module step runs the estimator's own suite |
| 6 | Run canonical underwriting and deterministic deal scoring | `FinancialRepository.analyzeProperty → UnderwritingEngine` (persisted analysis + scenarios); canonical *deal scoring* not wired — see GAP-4 | `…underwriting consumes stored rows only and stays finite on a thin record` | app integration step; oracle guard job |
| 7 | Persist AI analysis when configured and available | **no production writer — see GAP-3** | the audit asserts the *absence* is honest (deal room shows `aiAnalysis == null`) | app integration step |
| 8 | Render Deal Room state | `IntelligenceRepository.getCompleteDealRoom` + `DealRoomViewModel` mapping | read model + relaunch: `…the deal room rebuilds from disk after an app relaunch`; empty-state contract: `DealRoomStateContractTest`. VM/Compose: not covered (needs instrumentation) | app integration step |
| 9 | Create an offer and document | `OfferRepository.generateOffer` (+ `OfferPdfGenerator`) | `…an offer for a pipeline property persists its document and audit trail once`, `…a property without a price cannot produce an offer` | app integration step |
| 10 | Send only through the approved delivery workflow | `OfferRepository.sendOfferDetailed` (pre-send gate, durable claim, ledger, audit) | `…delivery through the approved workflow sends once and survives a relaunch`, 3 fault-injection delivery tests | app integration step |

## 3. Failure-scenario matrix

| Scenario | Covering tests | Verdict |
| --- | --- | --- |
| Successful end-to-end workflow | new `PropertyToOfferWorkflowTest` (9 tests) | covered (JVM, real Room) |
| Invalid URL and unsupported source | new `WorkflowFailureInjectionTest`: invalid url, ambiguous message, `trulia` (PLANNED source) rejected pre-fetch | covered |
| Duplicate import and idempotency | new (3 tests) + `IdempotencyLedgerTest`, `RetryAndIdempotencyTest`, `PropertyImportRepositoryTest` | covered |
| Partial provider outage | new: failing feed marks the job FAILED and leaves stored rows untouched; + GAP-2 pin for the swallowed adapter error | covered, with a documented observability gap |
| Network timeout and retry | new: 503 → backoff → success; timeout → attempts exhausted → nothing stored; deferred retry resumed after restart. `PropertyTransportTimeoutTest` pins the real OkHttp timeout posture | covered at our boundary; **no live-network test** |
| AI provider failure | new: `generateOffer` falls back to the deterministic letter, no AI row is fabricated; analyst contract suites (`RealEstateAnalyst*`) | covered; persistence gap = GAP-3 |
| Gmail authentication failure and ambiguous send | new (3 tests) + `OfferSendIdempotencyTest`, `GmailServiceConcurrencyTest`, `GmailOAuthSecurityTest` | covered at the `GmailSender` boundary |
| Process death and restart | new: interrupted import resumed by the durable queue **and persisted**; ledger/audit and deal room rebuilt after relaunch + `AutomationEngineCrashRecoveryTest`, `JobPersistenceTest` | covered |
| Concurrent workers | new: 4 racing imports of one listing; 3 concurrent cross-source imports of one house + `AutomationEngineConcurrencyTest`, `GmailServiceConcurrencyTest` | covered |
| Migration from supported older versions | `DatabaseMigrationTest` (v2 → v4 through Room) + `tools/room_schema_guard.py` (v1→v2→v3→v4 vs. historical entity declarations, `PRAGMA foreign_key_check`, 210 DAO queries prepared) — the guard now **runs in CI** (it never did) | covered |
| Persistence after navigation and app relaunch | new relaunch tests (file-backed) | covered at the read-model level; **screen/Compose level not covered** |

## 4. Defects found and fixed (all reproducible on `main`)

| ID | Defect | Evidence | Fix |
| --- | --- | --- | --- |
| DEF-1 | `main` does not compile, so **every** app unit test was unrunnable: `SellerOutreachTextGenerator.collectKeys` declared its parameter as the `JsonValues` *object* instead of `JsonValues.Value`. | `Seller outreach CI` run `37828264318` on `main`: `e: …/SellerOutreachTextGenerator.kt:158:37 …` plus `Execution failed for task ':app:compileDebugKotlin'` | parameter typed `JsonValues.Value`; `main`'s AI-Analyst job is green with the real report published |
| DEF-2 | The `AI Analyst CI` gate was **fake green**: the `>log 2>&1` redirect sat on its own line, so `status=$?` captured the exit code of a bare redirection (`0`) instead of Gradle's. The job passed while `:app:compileDebugKotlin` was failing and no test report existed (`No files were found with the provided path` annotation on run `37828264309`). | workflow diff + that run's missing report | redirect attached to the command; a guard test (`…every app test job in ci keeps gradle's exit status attached to its command`) now fails if the pattern returns in any of the three workflows |
| DEF-3 | A duplicate URL import was reported to the caller as a **failed** import: `ImportOutcome.Duplicate` is not `isSuccess`, so `IntelligenceRepository.importPropertyUrl` returned `JobStatus.FAILED` with an empty error for a listing that was already stored. | new test `…importing the same listing twice is idempotent end to end` | suppressed duplicates map to `COMPLETED` with a "reused the stored record" step description; a rejection/fetch failure still maps to `FAILED` |
| DEF-4 | The durable import queue could **fabricate an import**: `PropertyImportQueue` resumed/scheduled jobs and recorded them as SUCCEEDED in the job ledger while nothing wrote the canonical record (its only path to the database is the bridge, which the queue never saw). After process death the Discover screen would list an "imported" listing that had no property row. | `grep -rn "\.enqueue(" app/src/main` → only `start()`; `PropertyImportQueue.processOne` never persisted | the queue got an explicit `onPropertyImported` sink, invoked for queued *and* resumed work, and `RealEstateAiApp` wires it to the same bridge/`insertBundle` path the UI uses; pinned by `WorkflowWiringGuardTest` + `…an interrupted import is resumed by the durable worker and still reaches the database` |
| DEF-5 | `app/build.gradle.kts` claimed Room exports its schema JSON to `app/schemas` and that "migrations are verified against these files in tests" — `exportSchema = false` and no `app/schemas` directory exist, so the comment described a verification that does not happen. | `ls app/schemas` → none; `AppDatabase.kt` `exportSchema = false` | comment corrected to name the real verifiers (`LegacyV2Schema` + `tools/room_schema_guard.py`), and that guard is now executed in CI |
| DEF-6 | A portal URL import lost its own origin. `PropertyUrlImportBridge.toBundle` built the bundle without `sourceId`, `externalId`, `externalUrl` or `ingestionMethod`, so `PropertyImportRepository.touchProvenance` stored every scraped listing as source `src-internal-default`, method `API`, empty URL, and with the *canonical* id in the field that is supposed to hold the provider's record id. Consequences across the boundary: the deal room cannot say which portal a record came from, and level-1 deduplication (`findProvenance(sourceId, externalId)` — the exact-source-record match) can never hit for a URL import, so every re-import falls through to canonical-key matching. | new test `…a portal url becomes a canonical row with provenance and no invented economics` asserted the provenance row; before the fix it held `externalUrl = ""`, `ingestionMethod = "API"`, `externalId = "cp-…"` | `toBundle` now carries `property.primarySourceId`, `sourceListingId` (falling back to the canonical id only when the page and URL expose no listing id), the fetched `sourceUrl` and `PropertyIngestionMethod.SCRAPE`. `resolveSourceId` still maps an unregistered portal id onto the internal source row, so nothing about the existing fallback behaviour changed except the fields that were blank. |

## 5. Gaps (deliberately **not** papered over)

* **GAP-1 — the deterministic repair estimator is an island.** `:repairestimator` is not a
  dependency of `:app` (`grep -rn "import com.example.repairestimator" app/src/main` → empty), and
  `PropertyUnderwritingFactory` hard-sets `rehabCost = 0.0` with the `NO_RENOVATION_ESTIMATE` info
  issue. So workflow step 5 ("estimate repairs when sufficient evidence exists") has no production
  path: no stored condition evidence can ever reach underwriting, and `FIX_AND_FLIP`/BRRRR results
  silently assume a zero rehab. Closing it needs a decision (which catalog scope, whose provenance,
  which field is authoritative) plus the cross-boundary test — not a wiring that pretends.
* **GAP-2 — a total provider outage is invisible in the import ledger.** `PropertySourceManager`
  catches adapter exceptions per source and returns an empty list, so `runImport` records
  `fetched = 0`, `failed = 0`, `status = COMPLETED` and marks the source `SUCCESS`: "provider down"
  and "nothing new" are indistinguishable in the UI and in `property_sources.lastSyncStatus`.
  Pinned by `…a provider outage inside the source manager is currently recorded as an empty
  successful run`; the fix should propagate a degraded outcome through `ImportSummary`/source status.
* **GAP-3 — nothing persists an AI analysis.** `RealEstateAnalystPersistence` and `AiAnalystEngine`
  have no production caller; `AiViewModel` renders the analyst output in memory only, so the
  `property_ai_analysis` table (and therefore the Deal Room's AI section) is written by tests, never
  by the app. Workflow step 7 ("persist AI analysis when configured and available") is unimplemented;
  the audit verifies the honest half of that (no stale or fabricated analysis is rendered).
* **GAP-4 — canonical deal scoring is not in the shipped pipeline.** `DealScoringEngine`/
  `DealAnalysisOrchestrator` (61 passing tests) have no production caller; the persisted
  `dealScore`/`isQualified` come from `QualificationEngine`, a different model. Tests of the
  orchestrator therefore prove the engine, not the app; "deterministic deal scoring" in the workflow
  is only integrated as qualification scoring. Wiring the orchestrator in needs its own migration
  plan (stored inputs → manifest → persisted score column) plus the end-to-end test.
* **GAP-5 — durable/second-path components are constructed but never fed.** `PropertyImportQueue`
  is started in `Application.onCreate` yet no code calls `enqueue` (the UI imports synchronously in a
  view-model scope, so a killed process loses the *user-initiated* import); `PropertyEnrichmentRepository`
  and `PropertyFinancialRepository` are built and never called, so no enrichment provider runs and
  `expireStale()` never expires stale intelligence. Both need product decisions, not test doubles.

## 6. Tests executed and results

Local (no JVM available in the audit sandbox, so only the JVM-free verifiers ran here):

* `python3 tools/room_schema_guard.py` → `room data layer guard: OK` (34 tables / 72 indices /
  24 FKs at v4; v1→v2 normalizer, v2→v3, v3→v4 chains; 210 DAO queries prepared) — after
  `git fetch --unshallow`, because the guard needs the pinned historical revisions.
* `python3 tools/underwriting_oracle/selfcheck.py` → `PASSED: 402 — ALL CHECKS PASSED`.
* `python3 tools/underwriting_oracle/generate_golden_vectors.py --check` →
  `golden vectors are up to date (43 vectors, 4902 paths)`.

CI (the authoritative run — `E2E reliability CI`, `AI Analyst CI`, `Seller outreach CI`): fill in
below from the PR's "E2E reliability audit" comment, then link the run.

| suite | tests | passed | failed | skipped | run |
| --- | --- | --- | --- | --- | --- |
| `com.example.reliability.*` (new) | 17 |   |   |   |   |
| `com.example.data.*` (Room repositories + migration) |   |   |   |   |   |
| other app integration filters listed in the workflow |   |   |   |   |   |
| `:urlintelligence:test`, `:repairestimator:test` |   |   |   |   |   |
| JVM-free guards (schema guard, oracle self-check, golden vectors) |   |   |   |   |   |

Reproduce locally (Android SDK + JDK 21 required):

```bash
gradle :app:testDebugUnitTest --tests "com.example.reliability.*" --tests "com.example.data.*"
gradle :urlintelligence:test :repairestimator:test
python3 tools/room_schema_guard.py            # needs a full clone (git fetch --unshallow)
```

## 7. What still needs a device or an instrumentation run

* `OfferPdfGenerator` renders through `android.graphics.pdf.PdfDocument`; the JVM harness asserts the
  document row, its path and the audit trail, not the rendered bytes. A Robolectric/PDFBox-level
  check or an instrumentation test is the right home for "the PDF is a valid one-page document".
* Deal Room rendering: `DealRoomViewModel` needs the `RealEstateAiApp` graph (WorkManager, Firebase),
  so no JVM test instantiates it. The screen's inputs (`IntelligenceRepository`) and its state
  contract are covered; the Compose state→layout mapping is not.
* Real provider behaviour (portal HTML drift, robots changes, Gmail rate limits and OAuth rotation)
  needs the recorded-fixture pipeline in `:urlintelligence` plus a staging run with a real
  configured Gmail account, explicitly behind test configuration.
