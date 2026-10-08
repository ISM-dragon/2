# Property URL Intelligence

A self-contained, dependency-free layer that turns **a pasted listing link** (or a whole message that
contains one) into a **canonical property record with provenance for every field** — including which
source it came from, how it was extracted and how confident the layer is.

Nothing in this module imports Android, Room, OkHttp, Moshi or `org.json`: it is plain Kotlin + the
Kotlin standard library (plus coroutines), so it is unit-testable on a plain JVM and reusable from a
future server-side worker.

```
app/src/main/java/com/example/domain/propertyurl/
├── json/        Json.kt                              — strict, dependency-free JSON parser/writer
├── model/       PropertyField, Provenance, CanonicalProperty,
│                ExtractedFacts, RawSourceDocument, SourceFailure
├── source/      SourceRegistry, SourceCatalog, SourceDetector, SourceModels
├── url/         PropertyUrl, PropertyUrlValidator, PropertyUrlNormalizer, PropertyUrlResolver
├── port/        Ports (Clock/IdGenerator/Telemetry/Sleeper/Credentials), HttpPort,
│                HttpUrlConnectionFetcher, FetchPolicy (robots.txt)
├── util/        Redaction
├── normalize/   ValueGuards, ValueParsing, CanonicalPropertyMapper
├── parse/       HtmlScanner, SourceDocumentParser (contract + ParserChain),
│                SchemaOrgJsonLdParser, EmbeddedJsonStateParser,
│                MetaAndTitleFactsParser, VisibleTextFactsParser, TextFactsHeuristics, RobotsTxt
├── adapter/     PropertySourceAdapter, BasePropertySourceAdapter, PropertyAdapterRegistry,
│                PortalAdapters (Zillow, Redfin, Realtor.com, Homes.com, generic + template)
├── job/         PropertyImportJob, PropertyImportStateMachine, RetryPolicy, Idempotency,
│                PropertyImportJobStore (+ in-memory implementation)
├── store/       JobCodec, FilePropertyImportJobStore (atomic, versioned JSON persistence)
└── pipeline/    ImportModels (options/requests/outcomes/summary), SourceGuardrails
                 (rate limiter + circuit breaker), PropertyUrlIntelligence (orchestrator),
                 PropertyImportQueue (background worker), PropertyUrlIntelligenceFactory
```

## The pipeline

```
raw input ─► resolve/validate ─► detect source ─► fetch policy ─► idempotency
         ─► fetch (retry, backoff, rate limit, circuit breaker)
         ─► parse (parser chain, highest trust first)
         ─► normalize (provenance merge, guards, derivations, completeness)
         ─► job state machine ─► persistence ─► CanonicalProperty (+ ImportOutcome)
```

Every stage is a pure function or an injected port, and the whole run is driven by one call:

```kotlin
val outcome = propertyUrlIntelligence.import("https://www.zillow.com/homedetails/…_zpid/")

when (outcome) {
    is ImportOutcome.Success   -> save(outcome.property!!)
    is ImportOutcome.Partial   -> save(outcome.property!!)   // explicit: outcome.missingFields
    is ImportOutcome.Duplicate -> ui.showAlreadyImported(outcome.reusedJobId)
    is ImportOutcome.Rejected  -> ui.show(outcome.failure!!.userMessage)
    is ImportOutcome.Failed    -> ui.showRetryLater(outcome.failure!!.userMessage)
    is ImportOutcome.Deferred  -> ui.showInProgress(outcome.nextAttemptAtEpochMillis)
    is ImportOutcome.Cancelled -> Unit
}
```

### Required capabilities (and where they live)

| Requirement | Implementation |
| --- | --- |
| URL validation | `url/PropertyUrlValidator` (~25 typed issue codes, scheme/host/SSRF guards) |
| Source detection | `source/SourceDetector` (domain → sub-domain → path rule → query param → alias, with evidence) |
| Source registry | `source/SourceRegistry` + `source/SourceCatalog` (11 definitions, immutable, `plus()` to extend) |
| Adapter interface | `adapter/PropertySourceAdapter` (+ `BasePropertySourceAdapter` shared behaviour) |
| Job state machine | `job/PropertyImportStateMachine` (15 states, explicit allowed-transition table, audit trail, recovery edges) |
| Generic URL resolver | `url/PropertyUrlResolver` (share text → one/several candidates, listing-id extraction) |
| Extensible adapters | `adapter/PortalAdapters` — adding a portal is a catalogue entry + a `PortalSourceAdapter` subclass + fixtures; `FutureSourceAdapters.apartmentsCom()` is the template |
| Canonical normalization | `normalize/CanonicalPropertyMapper` → `model/CanonicalProperty` |
| Provenance per field | `model/Provenance` + `ProvenancePolicy` (trust rank → method → confidence → freshness) |
| Retry | `job/RetryPolicy` (exponential backoff, `Retry-After`, cool-down multiplier, jitter) |
| Idempotency | `job/Idempotency` (`source:listingId`, `url:…`, `prop:…` keys, 6 h reuse window) |
| Partial success | `CompletenessReport` + `ImportOutcome.Partial` (missing fields enumerated) |
| Failure classification | `model/SourceFailure` (9 categories, ~31 kinds, retryability, user-facing copy) |

### Compliance and politeness (on by default where it matters)

* `port/FetchPolicy` gate before every fetch; `DefaultFetchPolicies.robotsAware(...)` parses the target
  origin's `/robots.txt` (cached 6 h, 404/410 ⇒ allow) with prefix, `*` and `$` semantics. When
  `robots.txt` cannot be evaluated at all (5xx, timeout, transport error) the origin is **denied**
  (`RobotsTxtFetchPolicy.UnavailableBehavior.DENY`); a deployment that has cleared an origin offline
  can opt back into `ALLOW`. A blank `User-agent:` token never matches a crawler, so a malformed file
  cannot shadow the `User-agent: *` rules.
* `pipeline/SourceRateLimiter` — per-source token bucket (default 12 req/min, burst 1).
* `pipeline/SourceHealthTracker` — circuit breaker: after N source-level failures the source is parked
  for a cool-down and then probed half-open.
* `util/Redaction` — every URL that is logged or persisted goes through it (query params dropped).

## Wiring into the Android app

Wired in `RealEstateAiApp.onCreate()`:

```kotlin
val propertyImportJobStore = FilePropertyImportJobStore(File(filesDir, "property-url-intelligence"))
propertyUrlIntelligence = PropertyUrlIntelligenceFactory.create(
    jobStore = propertyImportJobStore,
    options = PropertyUrlIntelligenceFactory.mobileOptions(),
    credentialProvider = CredentialProvider.NONE,   // nothing embedded in the APK
    useRobotsTxt = true
)
propertyUrlImporter = PropertyUrlImportBridge(propertyUrlIntelligence, propertyRepository)
propertyImportQueue = PropertyUrlIntelligenceFactory.createQueue(
    intelligence = propertyUrlIntelligence,
    jobStore = propertyImportJobStore,
    clock = SystemClock(),
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
)
propertyImportQueue.start()   // resumes interrupted imports + deferred retries
```

`PropertyUrlImportBridge` (`data/adapter`) is the only class that knows both sides. It maps a
`CanonicalProperty` onto the existing `NormalizedPropertyBundle`, using the canonical id (`cp-…`) as the
Room primary key so the same house imported from two portals updates one row. It writes **zeros** for
`MarketDataEntity` / `RentEstimateEntity` on purpose — an import must never invent valuation numbers;
the app's own analysis fills them in later.

UI usage stays trivial:

```kotlin
val inspection = app.propertyUrlImporter.inspect(pastedText)   // offline: is this importable?
if (inspection.isSupported) {
    val outcome = app.propertyUrlImporter.importAndStore(pastedText)  // stores on success/partial
}
```

### Swapping persistence

`job/PropertyImportJobStore` is a port with three capabilities (`save`, `findById`,
`findLatestByKey`, `recent`, `deleteOlderThan`, `count`). The shipped implementation is
`store/FilePropertyImportJobStore` (versioned JSON, atomic writes, cache, bounded file count). A
Room-backed implementation (`JobCodec` is reusable for the payload column) can be dropped in without
touching a single call site — including `PropertyImportQueue` and `PropertyUrlIntelligence`.

### Adding a portal

1. Add a `PropertySourceDefinition` to `SourceCatalog` (`domains`, `pathRules`, `trustRank`, `status`).
2. Add the adapter — usually:

```kotlin
class ApartmentsComAdapter : PortalSourceAdapter(
    source = SourceCatalog.APARTMENTS_COM,
    descriptor = portalDescriptor(SourceCatalog.APARTMENTS_COM, adapterId = "apartments-com-html", version = "1.0.0")
)
```

3. Register it in `PropertyUrlIntelligenceFactory.defaultAdapters()` and flip `status` to `AVAILABLE`.
4. Add HTML fixtures under `app/src/test/resources/fixtures/property-url-intelligence/` and extend
   `AdapterContractTest` — the contract tests already cover the new adapter automatically.

## Tests

`app/src/test/java/com/example/domain/propertyurl/` — 129 tests, no network, no clock dependence:

| File | Covers |
| --- | --- |
| `parse/*ParserTest` | JSON-LD, embedded state (`__NEXT_DATA__`, `window.__reactServerState`), meta/title and visible-text extraction from HTML fixtures, malformed/hostile documents, determinism |
| `url/PropertyUrlResolverTest` | validation, normalization, detection, listing ids, SSRF and lookalike-domain rejection, ambiguity handling |
| `source/SourceRegistryAndDetectorTest` | catalogue invariants, immutability, detection confidence, planned sources |
| `normalize/CanonicalPropertyMapperTest` | provenance merge, guard rejection, derived fields, identity stability, completeness/partial success |
| `adapter/AdapterContractTest` | descriptor uniqueness, per-source claiming, 404/anti-bot classification, parser chain usage, extension template |
| `job/PropertyImportStateMachineTest` | legal/illegal transitions, audit trail, recovery, coarse status |
| `job/RetryAndIdempotencyTest` | backoff, jitter, `Retry-After`, never-retry kinds, reuse windows |
| `store/JobPersistenceTest` | codec versioning/round-trip, in-memory eviction, file store across instances |
| `pipeline/ComplianceTest` | robots.txt parsing/matching/caching, rate limiter, circuit breaker |
| `pipeline/PropertyUrlIntelligenceTest` | end-to-end success/partial/rejected/failed/duplicate/deferred, batch, inspection, resume after process death, queue |

Fixtures live in `app/src/test/resources/fixtures/property-url-intelligence/`.

## Deliberate non-goals

* **No secrets in the app.** Credentials arrive through `port/CredentialProvider`; the default is
  `CredentialProvider.NONE`. Nothing in this layer reads `BuildConfig` or an `.env`.
* **No UI, no financial code.** The layer exposes data (`CanonicalProperty`, `ImportOutcome`,
  provenance) and never touches `com.example.ui` or the financial engine.
* **No scraping framework.** Parsing is hand-rolled on purpose: one bounded, auditable HTML scanner
  plus strict JSON, so the module has no dependency surface to keep patched.
