# Property URL Intelligence

A standalone, platform-agnostic layer that turns an arbitrary real-estate URL into a
**Canonical Property** with per-field provenance, retry/idempotency guarantees and a
typed failure taxonomy.

It lives in its own Gradle module (`:urlintelligence`) with **no Android dependencies**,
so parsers, the job state machine, retry and idempotency are all plain JVM unit tests —
no emulator, no Robolectric, no network.

```
app  ──►  :urlintelligence   (pure Kotlin, JVM 11, coroutines only)
              │
              ├── url/          validation + canonicalization (SSRF-guarded)
              ├── source/       descriptors, registry, detector
              ├── adapter/      PropertySourceAdapter + Zillow/Redfin/Realtor/Homes/Generic
              ├── html, json    dependency-free JSON-LD + HTML helpers
              ├── normalization/ draft ➜ CanonicalProperty + value normalizers
              ├── provenance/   FieldProvenance + merge policies
              ├── retry/        clock, sleeper, backoff policy, executor
              ├── idempotency/  keys + reservation store
              ├── job/          PropertyImportJob state machine + store
              └── resolver/     PropertyUrlResolver (the pipeline)
```

---

## 1. Pipeline

```
rawUrl
  │  PropertyUrlValidator        trim, scheme, credentials ✗, private hosts ✗,
  │                              tracking params dropped, canonical string
  ▼
NormalizedUrl
  │  SourceDetector              host + path + id regexes  ➜  sourceId + listing id
  ▼
SourceDetection
  │  SourceRegistry              descriptor ➜ adapter (opt-in gate here)
  ▼
PropertySourceAdapter.fetch      transport boundary (OkHttp in :app, fake in tests)
  │  RetryExecutor               exponential backoff + jitter, Retry-After honored
  ▼
SourceFetchResponse.Success
  │  PropertySourceAdapter.parse pure: JSON-LD ➜ meta tags ➜ selector table
  ▼
PropertyDraft
  │  PropertyNormalizer          money/area/state/date coercion, range validation
  ▼
CanonicalProperty                + CompletenessReport + ProvenanceMap
```

The resolver (`PropertyUrlResolver.resolve`) drives all of the above and returns:

| Result                | Meaning                                                        |
|-----------------------|----------------------------------------------------------------|
| `Success`             | every required field resolved                                  |
| `Partial`             | usable, but `missingFields` is not empty                       |
| `Failure`             | typed `SourceFailure` (never an exception)                     |

Partial success is first class: callers opt out with `ResolveOptions(allowPartial = false)`.

## 2. URL validation

`PropertyUrlValidator` is a pure function and rejects:

* blank / > 2048 chars,
* non-`http(s)` schemes (`javascript:`, `data:`, `file:`, …),
* embedded credentials (`https://user:pass@host`),
* loopback, RFC1918, CGNAT, link-local, IPv6 literals and cloud metadata hosts (SSRF),
* bare hosts with no property path.

It also **canonicalizes**: lowercased scheme/host, default ports removed, duplicate
slashes collapsed, fragments dropped, tracking parameters (`utm_*`, `fbclid`, `gclid`, …)
removed, remaining query sorted. The canonical string is the idempotency input, so
`?utm_source=x`, `#photos` and casing variants of one listing collapse to one import.

## 3. Source detection & registry

`SourceDescriptor` is a declarative record — host suffixes, path patterns, id regexes,
capabilities, tier and whether the provider needs an explicit opt-in. `KnownSources`
ships Zillow, Redfin, Realtor.com, Homes.com and a generic fallback.

* `SourceDetector` scores host (floor) + path shape (+0.3) + id extraction (+0.2).
* `SourceRegistry` is a thread-safe catalog that binds descriptors and adapters
  together. A provider can be **announced before it is implemented**: register the
  descriptor, and imports fail with `UnsupportedSource` instead of being silently
  parsed by the generic adapter.

## 4. Adapters

```kotlin
interface PropertySourceAdapter {
    val descriptor: SourceDescriptor
    suspend fun fetch(request: SourceFetchRequest): SourceFetchResponse
    fun parse(response: SourceFetchResponse.Success, request: SourceFetchRequest): PropertyParseResult
}
```

`fetch` is I/O (owned by `PropertyHttpTransport`, retried by the resolver); `parse` is a
pure function of (document, url) — that split is what makes parser fixtures trivial.

`StructuredDataPropertySourceAdapter` implements the shared, best-first strategy:

```
schema.org / JSON-LD (confidence 1.0)  ▸  meta tags (0.7)  ▸  per-source selector table (0.45–0.9)
```

`PropertyDraft.put` never lets a weaker signal overwrite a stronger one, so adapters can
apply extractors greedily. Each adapter is mostly a table of `FieldSelector`s:

```kotlin
FieldSelector(
    field = PropertyField.LIST_PRICE,
    pattern = Regex("\"price\"\\s*:\\s*\"?\\$?([0-9][0-9,]{3,})\"?"),
    transform = ValueTransform.MONEY,
    confidence = Confidence.HIGH
)
```

### Adding a provider (Zillow/Redfin/Realtor/Homes or the next one)

1. Add a `SourceDescriptor` to `KnownSources` (host suffixes, path/id regexes, `requiresOptIn`).
2. Add an adapter class — usually just `StructuredDataPropertySourceAdapter` + a selector table.
3. Register it in `UrlIntelligenceModule.createRegistry` (Android) / `PropertyUrlResolverFactory`.
4. Drop a fixture in `src/test/resources/fixtures` and add a `ParserFixturesTest` case.

No change is needed in the resolver, job machine, retry or persistence code.

## 5. Import job state machine

```
CREATED ──► VALIDATING ──► DETECTING ──► DISPATCHING ──► FETCHING ⇄ RETRY_SCHEDULED
   │            │              │               │            │
   │            ▼              ▼               ▼            ▼
   │          FAILED       FAILED           FAILED       PARSING ──► NORMALIZING
   │                                                        │             │
   └──► CANCELLED                                        FAILED     ┌─────┴─────┐
                                                                    ▼           ▼
                                                               SUCCEEDED   PARTIALLY_SUCCEEDED
```

* `PropertyImportJob` is immutable; `transition(event, now, note, patch)` returns
  `TransitionResult.Accepted | Rejected`.
* Illegal transitions are **rejected, never coerced** — a double `FETCH_SUCCEEDED`
  surfaces as a rejected event instead of corrupting the job.
* Terminal states (`SUCCEEDED`, `PARTIALLY_SUCCEEDED`, `FAILED`, `CANCELLED`) only allow
  `RESET`; `FAILED` additionally allows a retry from `VALIDATING`.
* Every transition is recorded (`JobTransition`) so a job can be replayed or inspected
  after process death through `PropertyImportJobStore` (back it with Room in production).

## 6. Failure classification

| Type               | Category    | Retryable |
|--------------------|-------------|-----------|
| `Network`, `Timeout` | TRANSIENT   | ✅ |
| `HttpStatus(5xx/408/425/429)` | TRANSIENT | ✅ |
| `RateLimited` (honors `Retry-After`) | TRANSIENT | ✅ |
| `NotFound`, `ParseError`, `IncompleteData`, `PayloadTooLarge` | PERMANENT | ❌ |
| `Blocked` (robots/ToS/CAPTCHA/bot wall), `AuthRequired`, `PolicyBlocked` | POLICY | ❌ |
| `InvalidUrl`, `UnsupportedSource` | CLIENT | ❌ |
| `Conflict` (duplicate in-flight import) | TRANSIENT | ❌ (caller waits) |
| `Unknown` | UNCLASSIFIED | ❌ |

`SourceFailureClassifier` maps throwables, HTTP statuses and anti-bot pages served with
HTTP 200 onto these types. `toLogString()` is redaction-safe (no bodies, no headers).

## 7. Retry & idempotency

* `RetryPolicy`: attempts, initial delay, multiplier, cap, jitter ratio, retryable
  categories. `Clock`, `Sleeper` and the jitter source are injected → tests are instant
  and deterministic.
* `RetryExecutor` records an `AttemptRecord` per attempt and exposes `onRetry` so the job
  machine can move to `RETRY_SCHEDULED`.
* `IdempotencyKey.forUrl(canonicalUrl)` (SHA-256) + `IdempotencyStore`:
  * the second request for the same listing returns the cached result,
  * a concurrent import gets `Conflict` instead of a second fetch,
  * **failures are released**, so the same URL can be retried later.

## 8. Normalization & provenance

`PropertyNormalizer` converts a draft into `CanonicalProperty`:

* coercions: money (`$1.25M`), area (`0.14 acres`), state names (`Texas` ➜ `TX`), dates,
  unit splitting (`#1402`), postal codes, image de-duplication;
* range validation: absurd values are dropped **with a warning** instead of failing the
  whole import (a `$12` price is a parsing bug, not data);
* `CompletenessReport` (required fields weighted 0.7, optional 0.3) drives partial
  success;
* every surviving field gets a `FieldProvenance(sourceId, sourceUrl, extractor, method,
  confidence, rawValue, extractedAt)`.

`ProvenanceMap` supports `HighestConfidence`, `PreferExisting`, `PreferIncoming` and
`SourcePriority(ranked)` merge policies, and `CanonicalPropertyMerger` can fuse two
sources while keeping provenance for whatever it took.

## 9. Android integration (`:app`)

* `CanonicalPropertyMapper` — `CanonicalProperty` ➜ the existing Room bundle
  (`PropertyEntity` + images/market/rent/tax).
* `OkHttpPropertyTransport` — the only I/O implementation; **credential-free**, no body
  logging, size-capped, SSRF-validated URLs only.
* `UrlIntelligenceModule` — composition root; `OPT_IN_SOURCES` is the explicit allow-list
  for providers whose terms require one.
* `PropertyUrlImportService` — resolve + persist through `PropertyDao`.

No UI code and no financial code were touched by this layer.

## 10. Compliance

* Fetches are anonymous: no API keys, tokens or cookies anywhere in the layer
  (`ArchitectureGuardsTest` fails the build on `import android.*`, `java.time`,
  or hardcoded-secret patterns).
* Public-web providers are marked `requiresOptIn = true`; the resolver refuses them
  unless the host app allow-lists the source id.
* Respect each provider's `robots.txt` and terms of service before enabling a source in
  production, and prefer `SourceTier.OFFICIAL_API` / `LICENSED_FEED` feeds over scraping.

## 11. Tests

```
:urlintelligence  src/test/kotlin
  PropertyUrlValidatorTest            canonicalization, SSRF, credentials, tracking params
  SourceDetectionTest                 zillow/redfin/realtor/homes/generic + id recovery
  SourceRegistryTest                  registration, announce-before-implement, lookup
  ParserFixturesTest                  every adapter against HTML fixtures + normalization
  NormalizationAndProvenanceTest      value normalizers, ranges, completeness, provenance
  RetryAndFailureTest                 backoff, retryability, failure taxonomy
  JobStateMachineAndIdempotencyTest   legal/illegal transitions, reservations, TTL
  PropertyUrlResolverTest             end-to-end pipeline with a scripted transport
  JsonAndHtmlTest                     dependency-free JSON/HTML helpers
  ArchitectureGuardsTest              no Android deps, no java.time, no secrets
```

Fixtures live in `src/test/resources/fixtures` (`zillow_listing.html`,
`redfin_listing.html`, `realtor_listing.html`, `homes_listing.html`,
`generic_brokerage.html`, `zillow_partial.html`, `blocked_captcha.html`,
`empty_page.html`).

Run them with:

```bash
./gradlew :urlintelligence:test
```
