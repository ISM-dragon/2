# Property URL Intelligence

A standalone, platform-agnostic layer that turns an arbitrary real-estate URL into a
**Canonical Property** with per-field provenance, parser versioning, verification levels,
compliance gates, retry/idempotency guarantees and a typed failure taxonomy.

It lives in its own Gradle module (`:urlintelligence`) with **no Android dependencies**, so
parsers, the job state machine, retry, compliance and idempotency are all plain JVM unit tests —
no emulator, no Robolectric, no network.

```
app  ──►  :urlintelligence   (pure Kotlin, JVM 11, coroutines only)
              │
              ├── url/          validation + canonicalization (SSRF-guarded)
              ├── source/       descriptors, registry, detector
              ├── compliance/   robots.txt + access policy gate (fail-closed)
              ├── fetch/        fetch limits, content sniffing, response guard
              ├── adapter/      PropertySourceAdapter + Zillow/Redfin/Realtor/Homes/Generic
              ├── parser/       parser specs: versioning + schema-drift detection
              ├── html, json    dependency-free JSON-LD + HTML helpers
              ├── normalization/ draft ➜ CanonicalProperty + value normalizers
              ├── provenance/   FieldProvenance, merge policies, verification levels
              ├── retry/        clock, sleeper, backoff policy, executor
              ├── idempotency/  keys, reservation store, content digests, import ledger
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
AccessPolicy                     robots.txt / opt-in / operator rules  (fail-closed)
  │                              denied ➜ PolicyBlocked, zero network traffic
  ▼
ImportLedger                     "same property, different URL" → replay, no fetch
  │  IdempotencyStore           reserve canonical URL (double import ➜ Conflict)
  ▼
PropertySourceAdapter.fetch      transport boundary (OkHttp in :app, fake in tests)
  │  RetryExecutor               exponential backoff + jitter, Retry-After honored
  ▼
SourceFetchResponse.Success      size, content type, redirect hops, fetch origin
  │  ResponseGuard               empty/oversized/binary/off-host-redirect/challenge ✗
  ▼
PropertySourceAdapter.parse      pure: JSON-LD ➜ meta tags ➜ selector table
  │  ParserSpec                  versioned contract + schema-drift detection
  ▼
PropertyDraft                    parser id/version, document digest, field verification
  │  PropertyNormalizer          money/area/state/date coercion, ranges, provenance audit
  ▼
CanonicalProperty                + CompletenessReport + ProvenanceMap + VerificationSummary
```

The resolver (`PropertyUrlResolver.resolve`) drives all of the above and returns:

| Result                | Meaning                                                        |
|-----------------------|----------------------------------------------------------------|
| `Success`             | every required field resolved                                  |
| `Partial`             | usable, but `missingFields` is not empty                       |
| `Failure`             | typed `SourceFailure` (never an exception)                     |

Both success variants carry `idempotency` (`FRESH`, `REPLAYED_URL`, `REPLAYED_PROPERTY`) so a
caller can tell a fresh fetch from a replay. Partial success is first class: callers opt out
with `ResolveOptions(allowPartial = false)`.

## 2. URL validation

`PropertyUrlValidator` is a pure function and rejects:

* blank / > 2048 chars,
* non-`http(s)` schemes (`javascript:`, `data:`, `file:`, …),
* embedded credentials (`https://user:pass@host`),
* loopback, RFC1918, CGNAT, link-local, IPv6 literals and cloud metadata hosts (SSRF),
* bare hosts with no property path.

It also **canonicalizes**: lowercased scheme/host, default ports removed, duplicate slashes
collapsed, fragments dropped, tracking parameters (`utm_*`, `fbclid`, `gclid`, …) removed,
remaining query sorted. Two things follow:

* the canonical URL stays **faithful to the host the provider serves** (`www.zillow.com` is what
  the pipeline fetches, and what that origin's robots.txt governs — a share link that spells the
  host differently must not silently redirect our traffic),
* the **identity keys** (`IdempotencyKey.forUrl` and the `canonicalPropertyId` URL fallback) are
  deliberately insensitive to a leading `www.`, so `?utm_source=x`, `#photos`, casing variants
  and `www.`/bare spellings of one listing collapse to one import.

## 3. Source detection & registry

`SourceDescriptor` is a declarative record — host suffixes, path patterns, id regexes,
capabilities, tier and whether the provider needs an explicit opt-in. `KnownSources` ships
Zillow, Redfin, Realtor.com, Homes.com and a generic fallback.

* `SourceDetector` scores host (floor) + path shape (+0.3) + id extraction (+0.2).
* `SourceRegistry` is a thread-safe catalog that binds descriptors and adapters together. A
  provider can be **announced before it is implemented**: register the descriptor and imports
  fail with `UnsupportedSource` instead of being silently parsed by the generic adapter.

## 4. Adapters

```kotlin
interface PropertySourceAdapter {
    val descriptor: SourceDescriptor
    suspend fun fetch(request: SourceFetchRequest): SourceFetchResponse
    fun parse(response: SourceFetchResponse.Success, request: SourceFetchRequest): PropertyParseResult
}
```

`fetch` is I/O (owned by the transport, retried by the resolver); `parse` is a pure function of
(document, url) — that split is what makes parser fixtures trivial.

`StructuredDataPropertySourceAdapter` implements the shared, best-first strategy **and** the
cross-cutting concerns no provider should re-implement:

```
schema.org / JSON-LD (confidence 1.0)  ▸  meta tags (0.7)  ▸  per-source selector table (0.45–0.9)
                       + response guard + parser versioning + drift detection + verification
```

`PropertyDraft.put` never lets a weaker signal overwrite a stronger one, so adapters can apply
extractors greedily. Each adapter is mostly a table of `FieldSelector`s plus a `ParserSpec`:

```kotlin
val PARSER_SPEC = ParserSpec(
    parserId = "zillow.html",
    version = "3",                              // bump when the selector table changes
    probes = listOf(
        SignatureProbe("zpid", Regex("\"zpid\"\\s*:\\s*\"?[0-9]{6,12}"), required = true),
        SignatureProbe("json-ld", Regex("application/ld\\+json", RegexOption.IGNORE_CASE)),
        SignatureProbe("zillow-cdn", Regex("zillowstatic\\.com", RegexOption.IGNORE_CASE))
    ),
    minProbesMatched = 2,
    minFieldsExtracted = 3
)
```

### Providers covered

| Source id        | Parser id / version   | Adapter                       | Opt-in | Extraction                                      |
|------------------|-----------------------|-------------------------------|--------|-------------------------------------------------|
| `zillow`         | `zillow.html@3`       | `ZillowAdapter`               | yes    | JSON-LD, embedded state, meta, selectors        |
| `redfin`         | `redfin.html@2`       | `RedfinAdapter`               | yes    | JSON-LD (`{value:…}` shapes), embedded state    |
| `realtor`        | `realtor.html@2`      | `RealtorAdapter`              | yes    | JSON-LD, preloaded state, `data-testid`         |
| `homes`          | `homes.html@2`        | `HomesAdapter`                | yes    | JSON-LD, flat embedded model, selectors         |
| `generic.web`    | `generic.html@1`      | `GenericWebAdapter`           | no     | JSON-LD, meta, text heuristics                  |
| *(example)*      | `example-portal.html@1` | `DeclarativeSourceAdapter`  | yes    | descriptor + spec + selector table only         |

### Adding a provider

1. Add a `SourceDescriptor` to `KnownSources` (host suffixes, path/id regexes, `requiresOptIn`).
2. Add a `ParserSpec` (id, version, signature probes, minimum fields).
3. Provide the selector table — either subclass `StructuredDataPropertySourceAdapter` or, for a
   provider that needs no custom code, hand the table to `DeclarativeSourceAdapter`.
4. Register it (`SourceRegistry.registerAdapter`) in `UrlIntelligenceModule.createRegistry`
   (Android) / `PropertyUrlResolverFactory.create`.
5. Drop a fixture in `src/test/resources/fixtures` and declare it in `FixtureManifestTest`.

No change is needed in the resolver, job machine, retry, compliance, provenance, idempotency or
persistence code. `ProviderExtensionTest` proves this by adding a complete provider ("Example
Portal") in the test sources and running it end-to-end through the production pipeline.

## 5. Parser versioning & schema-drift detection

Every parser declares a versioned contract. Two things follow:

* **Every extracted field records `parserId` + `parserVersion`** (`zillow.html@3`) in its
  provenance, so "which parser produced this value, and when did it change?" is answerable.
* **A markup rework becomes visible.** `ParserSpec.inspect(document, fieldsExtracted)` compares
  the document against the parser's signature and reports:

| Drift     | Trigger                                                        | Effect (default policy `WARN`)                       |
|-----------|----------------------------------------------------------------|------------------------------------------------------|
| `NONE`    | all probes matched, enough fields extracted                     | fields are parser-verified                           |
| `MINOR`   | optional probes missing                                         | warning; data still parser-verified                  |
| `MAJOR`   | required probe missing, too few probes, or too few fields       | warning, fields downgraded to `UNVERIFIED`, and the parse is reported as **partial** (usable, not a clean success); with `DriftPolicy.FAIL` the parse fails with `SourceFailure.SchemaDrift` |

`DriftPolicy` is per adapter (`OFF` / `WARN` / `FAIL`). `SchemaDriftReport.summary()` is
log-safe: it names the parser's own signature probes (`missing-probes=zpid,json-ld`) but never
quotes the document.

## 6. Verification levels: parser-verified vs live-fetch-verified

Imported data is never described as more verified than it is:

| Level                  | Meaning                                                                    |
|------------------------|----------------------------------------------------------------------------|
| `UNVERIFIED`           | heuristic/degraded/derived value, or a drifted document                     |
| `PARSER_VERIFIED`      | a versioned parser matched the document and produced the value              |
| `LIVE_FETCH_VERIFIED`  | as above **and** the document was fetched from the provider live, now       |

The upgrade to `LIVE_FETCH_VERIFIED` depends on exactly one input:
`SourceFetchResponse.Success.origin == FetchOrigin.LIVE_NETWORK`. The default is
`UNSPECIFIED`, a scripted/replayed transport reports `REPLAYED`, and only the app's
`OkHttpPropertyTransport` sets `LIVE_NETWORK` — because only it really performs the request.
Fixtures and replays therefore can never produce a live-verified record.

Results carry `CanonicalProperty.verification` (`VerificationSummary`):
`origin`, field counts per level, the parser versions involved, the document digests, plus
`isLiveVerified`, `isParserVerified` and a human-readable `describe()`. Per-field truth lives in
`FieldProvenance.verification`.

## 7. Fetch hardening

`FetchLimits` (defaults: 10 s connect, 15 s read, 30 s call, 4 MB body, 3 redirects,
HTML/JSON/plain-text allow-list) is given to the transport through `SourceFetchRequest.limits`
**and** enforced again on everything that comes back:

* **oversized** → `PayloadTooLarge` (the Android transport reads at most the budget + 1 byte, so
  an oversized body is detected without buffering it),
* **empty / truncated** → `EmptyResponse`,
* **binary / disallowed content type** → `UnsupportedContentType` (with body sniffing when the
  header is missing or wrong),
* **redirect chains** beyond the budget → `TooManyRedirects`,
* **redirects that leave the provider's host family** (or point at credentials/private hosts) →
  `RedirectNotAllowed`, re-validated by the same SSRF guard as the original URL,
* **CAPTCHA / bot walls, login walls, consent interstitials served as HTTP 200** →
  `Blocked(CAPTCHA|CONSENT_WALL)` / `AuthRequired`,
* **`X-Robots-Tag: noindex/noarchive`** → `Blocked(NOINDEX_DIRECTIVE)`.

Guarding happens twice: inside the base adapter (so direct `parse()` calls in fixtures are just
as safe) and in the resolver (so an adapter that only implements the interface inherits it).

## 8. Compliance

This layer never bypasses CAPTCHAs, authentication, robots rules or other access controls.

* `AccessPolicy` is consulted **before every fetch**; a denial is a non-retryable
  `PolicyBlocked` and no request for the listing is made.
* `RobotsPolicy` fetches `/robots.txt` once per origin through the same credential-free
  transport (6 h TTL) and evaluates it with longest-match precedence, `*` wildcards, `$` anchors
  and user-agent group selection (RFC 9309 subset). It is **fail-closed**: an unevaluable
  robots.txt (5xx, network error) denies; 404/410 allows, per RFC 9309.
* Providers whose terms require an explicit decision are marked `requiresOptIn` and refused
  unless the host app allow-lists them (`UrlIntelligenceModule.OPT_IN_SOURCES`).
* `ResolveOptions.requireAccessPolicy` turns "no policy configured" into a hard failure;
  `UrlIntelligenceModule.createDefaultResolver()` wires the robots gate for production, and
  `PropertyUrlImportService(requirePolicyCheck = true)` refuses to construct without one.
* Challenge/interstitial pages are classified as policy stops, are never parsed and are never
  retried. There is no code path that solves a challenge, logs in or dismisses a consent wall.
* No credential headers exist in this layer: `SourceFetchRequest.FORBIDDEN_HEADERS` is enforced
  by the resolver's header sanitizer and by `ArchitectureGuardsTest`.

## 9. Import job state machine

```
CREATED ──► VALIDATING ──► DETECTING ──► DISPATCHING ──► FETCHING ⇄ RETRY_SCHEDULED
   │            │              │               │            │
   │            ▼              ▼               ▼            ▼
   │          FAILED       FAILED(-POLICY)  FAILED       PARSING ──► NORMALIZING
   │                                                        │             │
   └──► CANCELLED                                        FAILED     ┌─────┴─────┐
                                                                    ▼           ▼
                                                               SUCCEEDED   PARTIALLY_SUCCEEDED
```

* `PropertyImportJob` is immutable; `transition(event, now, note, patch)` returns
  `TransitionResult.Accepted | Rejected`.
* Illegal transitions are **rejected, never coerced** — a double `FETCH_SUCCEEDED` surfaces as a
  rejected event instead of corrupting the job.
* Terminal states only allow `RESET`; `FAILED` additionally allows a retry from `VALIDATING`.
* A request served from the ledger is recorded as `CANCELLED` with an audit note naming the
  canonical property that was reused, so duplicates stay visible.
* Every transition is recorded (`JobTransition`) so a job can be replayed or inspected after
  process death through `PropertyImportJobStore` (back it with Room in production).

## 10. Failure classification

| Type               | Category    | Retryable |
|--------------------|-------------|-----------|
| `Network`, `Timeout` | TRANSIENT   | ✅ |
| `HttpStatus(5xx/408/425/429)` | TRANSIENT | ✅ |
| `RateLimited` (honors `Retry-After`) | TRANSIENT | ✅ |
| `NotFound`, `ParseError`, `IncompleteData`, `PayloadTooLarge`, `EmptyResponse`, `UnsupportedContentType`, `TooManyRedirects`, `SchemaDrift` | PERMANENT | ❌ |
| `Blocked` (robots/ToS/CAPTCHA/bot wall/consent/noindex), `AuthRequired`, `PolicyBlocked`, `RedirectNotAllowed` | POLICY | ❌ |
| `InvalidUrl`, `UnsupportedSource` | CLIENT | ❌ |
| `Conflict` (duplicate in-flight import) | TRANSIENT | ❌ (caller waits) |
| `Unknown` | UNCLASSIFIED | ❌ |

`SourceFailureClassifier` maps throwables (including timeouts), HTTP statuses and anti-bot pages
served with HTTP 200 onto these types. `toLogString()` is redaction-safe (no bodies, no headers).

## 11. Retry & idempotency

* `RetryPolicy`: attempts, initial delay, multiplier, cap, jitter ratio, retryable categories.
  `Clock`, `Sleeper` and the jitter source are injected → tests are instant and deterministic.
* `RetryExecutor` records an `AttemptRecord` per attempt and exposes `onRetry` so the job
  machine can move to `RETRY_SCHEDULED`.
* **URL-level idempotency**: `IdempotencyKey.forUrl(canonicalUrl)` (SHA-256 of the dedup form) +
  `IdempotencyStore`. The second request for the same listing returns the cached result
  (`REPLAYED_URL`); a concurrent import gets `Conflict`; **failures are released** so the same URL
  can be retried. Entries expire on a TTL measured from the moment the store wrote them, so a
  reservation can never be purged before the request that made it has finished.
* **Property-level idempotency**: `ImportLedger` keyed by `canonicalPropertyId`
  (`zillow:12345678`). Zillow's `/homedetails/…_zpid/`, a share link with tracking noise, a
  mobile subdomain and a canonicalised variant are the same house, so the second import is
  answered from the ledger with `REPLAYED_PROPERTY` and **zero network traffic**.
* **Content digests** (`Digests.fieldsDigest`) make "did anything actually change?" answerable;
  a refresh that returns different data adds an explicit `source content changed since the last
  import` warning instead of silently overwriting.

## 12. Normalization & provenance

`PropertyNormalizer` converts a draft into `CanonicalProperty`:

* coercions: money (`$1.25M`), area (`0.14 acres`), state names (`Texas` ➜ `TX`), dates, unit
  splitting (`#1402`), postal codes, image de-duplication;
* range validation: absurd values are dropped **with a warning** instead of failing the whole
  import (a `$12` price is a parsing bug, not data);
* `CompletenessReport` (required fields weighted 0.7, optional 0.3) drives partial success;
* **provenance audit**: every surviving field must have a provenance entry; a gap is reported as
  an internal warning rather than being swallowed;
* every field gets a `FieldProvenance(sourceId, sourceUrl, extractor, parserId, parserVersion,
  documentDigest, method, confidence, rawValue, extractedAt, verification)`.

`ProvenanceMap` supports `HighestConfidence`, `PreferExisting`, `PreferIncoming` and
`SourcePriority(ranked)` merge policies, can be filtered by verification level
(`withVerificationAtLeast`), and `CanonicalPropertyMerger` can fuse two sources while keeping
provenance for whatever it took.

## 13. Android integration (`:app`)

* `CanonicalPropertyMapper` — `CanonicalProperty` ➜ the existing Room bundle
  (`PropertyEntity` + images/market/rent/tax), plus `provenanceSummary()` /
  `verificationSummary()` for surfacing where data came from.
* `OkHttpPropertyTransport` — the only I/O implementation and the only place that reports
  `FetchOrigin.LIVE_NETWORK`; **credential-free**, no body logging, size-capped, honouring
  `FetchLimits`, SSRF-validated URLs only.
* `UrlIntelligenceModule` — composition root; `OPT_IN_SOURCES` is the explicit allow-list,
  `createDefaultResolver()` wires the robots-aware policy, `robotsAwarePolicy()` is exported for
  hosts that build their own resolver.
* `PropertyUrlImportService` — resolve + persist through `PropertyDao`; pass
  `requirePolicyCheck = true` in production to refuse imports without a compliance decision.

No UI code and no financial/underwriting code are touched by this layer.

## 14. Tests

```
:urlintelligence  src/test/kotlin/com/example/urlintelligence
  ArchitectureGuardsTest              no Android deps, no secrets, no java.time
  PropertyUrlValidatorTest            canonicalization, SSRF, credentials, tracking params
  SourceDetectionTest                 zillow/redfin/realtor/homes/generic + id recovery
  ParserFixturesTest                  every adapter against HTML fixtures + normalization
  ParserVersionAndDriftTest           parser contracts, drift levels, FAIL policy, field stamps
  FetchGuardTest                      size/content-type/redirect/interstitial rejection, limits
  ComplianceRobotsTest                robots rules, caching, fail-closed policy, resolver gating
  VerificationLevelsTest              parser-verified vs live-fetch-verified, provenance audit
  IdempotencyLedgerTest               URL + property idempotency, digests, failure hygiene
  ProviderExtensionTest               adding a new provider needs no core change
  FixtureManifestTest                 every fixture is declared with its expected outcome
  NormalizationAndProvenanceTest      value normalizers, ranges, completeness, provenance
  RetryAndFailureTest                 backoff, retryability, failure taxonomy
  JobStateMachineAndIdempotencyTest   legal/illegal transitions, reservations, TTL
  PropertyUrlResolverTest             end-to-end pipeline with a scripted transport
  JsonAndHtmlTest                     dependency-free JSON/HTML helpers
```

Fixtures live in `src/test/resources/fixtures` and are enumerated in `FixtureManifestTest`:
`zillow_listing`, `zillow_mobile`, `zillow_partial`, `zillow_drifted`, `redfin_listing`,
`redfin_minor_drift`, `realtor_listing`, `realtor_sold_listing`, `homes_listing`,
`generic_brokerage`, `rent_listing`, `malformed_jsonld`, `example_portal_listing`,
`blocked_captcha`, `captcha_cloudflare`, `login_wall`, `consent_wall`, `empty_page`,
`robots_disallow_listings`, `robots_deny_all`, `robots_wildcards`. Adding a fixture without
declaring its parser and expected outcome fails the build.

Run them with:

```bash
./gradlew :urlintelligence:test
```

## 15. Source behaviour that cannot be verified offline

Everything in this repository is exercised against fixtures and scripted transports; no test in
this repo contacts a provider. The following therefore **cannot be verified offline** and must be
re-checked against the live site before a source is enabled in production:

1. **Current markup.** Provider pages are re-rendered frequently and much of their content is
   JavaScript-hydrated. The shipped selectors and probes reflect the shapes captured in the
   fixtures; a real page may differ. `SchemaDrift`/`DriftLevel.MAJOR` is the signal for that.
2. **robots.txt contents and terms of service.** `RobotsPolicy` is only as correct as the
   provider's live `robots.txt`; the legal decision to import at all is an operator decision
   (`requiresOptIn` + `OPT_IN_SOURCES`), never an offline one.
3. **Anti-bot behaviour.** Whether a provider serves a challenge to a given client, honours a
   user agent, or requires JS execution cannot be reproduced offline; the layer only promises
   that such a page is *detected* and treated as a non-retryable policy stop.
4. **Redirect topology.** Real redirect chains (geo, consent, canonicalisation, shorteners)
   differ from the synthetic ones; cross-host redirects are refused by default and must be
   cleared per provider.
5. **Rate limits and `Retry-After` semantics.** The retry/backoff code is unit-tested, but the
   provider's real thresholds are not.
6. **Field-level truth.** A parsed number is only as good as the page it came from: the layer
   guarantees provenance and verification *status*, not that the provider's data is accurate.
7. **HTTP-level behaviour of the Android transport.** `OkHttpPropertyTransport`,
   `UrlIntelligenceModule` and `PropertyUrlImportService` live in `:app`, which is not compiled
   by the JVM test task used here; they need the Android build (and a real device/emulator) to
   be verified.

Where such a gap exists, the code records the weaker verification level rather than pretending
otherwise: `VerificationLevel.UNVERIFIED` / `PARSER_VERIFIED` are the honest maximum offline,
and `LIVE_FETCH_VERIFIED` is only ever produced by a transport that performed a real fetch.
