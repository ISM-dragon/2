# Property Identity & Deduplication

The deterministic identity layer that decides whether an incoming listing **is** a property the
app already knows. It is a pure-Kotlin, side-effect-free component (`com.example.domain.identity`)
with no Android dependencies, so every rule below is pinned by plain JVM unit tests.

```
Zillow ─┐
Redfin ─┼─►  PropertyIdentityDeduplicationEngine  ─►  DeduplicationResult
ATTOM  ─┘        (pure, stateless, deterministic)       (status + method + evidence + reason)
                          │
                          ▼
             CanonicalIdentityMerger / SourceValueResolver
             (explicit source priority + provenance, conflict audit trail)
                          │
                          ▼
             PropertySourceManager / PropertyImportRepository
             (persistence; only EXACT_MATCH / CANONICAL_MATCH may merge)
```

---

## 1. Matching hierarchy

Signals are evaluated in this fixed order. The **first** signal (strongest) that matched the
resolved candidate is reported as `DeduplicationResult.matchMethod`.

| # | Signal | `IdentityMatchMethod` | Namespace | Identifies |
|---|--------|-----------------------|-----------|------------|
| 1 | APN / parcel ID | `APN` | jurisdiction | the physical parcel |
| 2 | MLS listing number | `MLS_ID` | MLS board (cross-provider) | the listing |
| 3 | Source listing ID | `SOURCE_LISTING_ID` | `(source, providerListingId)` | one source record |
| 4 | Listing URL | `SOURCE_URL` | URL host | one source record |
| 5 | Normalized address | `NORMALIZED_ADDRESS` | USPS-style normalization + locality | the property |
| 6 | Normalized coordinates | `COORDINATES` | ≤ `coordinateMatchRadiusMeters` (20 m) | the property |

Normalization rules that make the keys effective:

* **Address** – case/punctuation folded, directionals and USPS suffixes abbreviated
  (`South Congress Avenue` ≡ `s. congress ave.`), unit designators unified (`Apt. 1402`,
  `#1402`, `Unit 1402` ≡ `unit 1402`), appended city/state/ZIP stripped, state names matched
  **only as trailing tokens** so `Maine St` / `Washington Ave` keep their street identity.
* **MLS ID** – label prefixes removed (`MLS# A10-50837` → `A1050837`), case and separators folded.
* **Source listing ID** – whitespace removed, lowercased, compared inside the provider namespace
  (the same id from two providers is *not* a match).
* **URL** – scheme folded to `https`, host lowercased, `www.`/default ports removed, tracking
  parameters (`utm_*`, `fbclid`, `gclid`, …) dropped, remaining parameters sorted, fragment and
  trailing slash removed. The host is the namespace: the same normalized URL is the same record
  even when two adapters report different source labels.
* **Coordinates** – non-finite, out-of-range and Null Island (`0,0`) values are treated as missing.

### Weak evidence (never merges)

* Coordinates inside `coordinatePossibleMatchRadiusMeters` (150 m) but outside the exact radius.
* Fuzzy address similarity ≥ `fuzzyAddressThreshold` (0.80).

Both produce `POSSIBLE_MATCH` **only**. Fuzzy tiers never merge; a candidate whose known parcel ID
differs from the incoming one is excluded from weak-signal matching entirely.

---

## 2. Outcomes

| Status | Meaning | Auto-merge? |
|--------|---------|-------------|
| `NEW` | No signal matched. The result carries `proposedCanonicalIdentity` for the caller to persist. | insert |
| `EXACT_MATCH` | Same source record re-imported (source listing ID or URL already linked). Idempotent refresh. | yes |
| `CANONICAL_MATCH` | Another source resolved to one existing canonical property through APN / MLS / address / tight coordinates. | yes |
| `POSSIBLE_MATCH` | Weak evidence (fuzzy address, nearby coordinates) suggests candidates. | **no – review** |
| `CONFLICT` | Exact signals disagree: an identifier matches several records, different signals point at different records, or a link contradicts a known parcel ID / address / coordinates. | **no – review** |

A *different* MLS ID, source listing ID, or URL is deliberately **not** a contradiction: listings
are relisted and re-published under new numbers. Parcel IDs, addresses and coordinates identify the
physical property, so disagreement there is a conflict.

## 3. Explainability

Every `DeduplicationResult` carries:

* `reason` – deterministic human-readable summary (including *why* nothing merged);
* `evidence` – ordered trail of the evaluated signals, each with the compared value, the canonical
  ids it matched, the ids it contradicted and a detail line;
* `confidence` – 1.0 for idempotent re-imports, otherwise fixed per method
  (APN 0.995 > MLS 0.99 > listing ID 0.985 > URL 0.98 > address 0.97 > coordinates 0.92);
* `candidateCanonicalIds` – stable, sorted review candidates for possible/conflict outcomes.

## 4. Conflicting source values: priority & provenance

`SourceValueResolver` resolves a field reported by several sources with a **total, deterministic
order** (never arrival order, never "last writer wins"):

1. lower `priority` wins (`property_sources.priority` semantics; MLS 10 beats wholesaler 90);
2. equal priority → more recent `observedAt` wins;
3. equal recency → lexicographically smaller `source` name;
4. final tie-break → `recordRef` (nulls last).

Missing values (null / blank / placeholder) never win and never create conflicts. Every differing
value that lost is recorded as a `FieldConflict` with the winning and rejected provenance.

`CanonicalIdentityMerger` applies these rules to canonical fields (parcel id, MLS id, address,
city, state, postal code, coordinates-as-a-pair) and exposes `fieldProvenance`, `conflicts` and a
one-line `explain()` summary. Merging is **idempotent and order-independent**: re-merging the same
observation set in any order yields the identical canonical identity.

## 5. Idempotency

* The engine is a pure function: identical inputs → identical results. Candidate collections are
  de-duplicated and sorted by canonical id before evaluation, so row/iteration order is irrelevant.
* Re-importing the same source record (same provider listing ID or same normalized URL) returns
  `EXACT_MATCH`; re-running the import does not create a second canonical property.
* The catalog merge converges: two runs importing the same sources in any order end with the same
  field values and the same linked `sourceIdentities`.
* `(sourceId, externalId)` in `property_provenance` remains the storage-level uniqueness anchor.

## 6. Audit notes

The repository contains three deduplication implementations. Their status after this change:

| Implementation | Role | State |
|----------------|------|-------|
| `domain.identity.PropertyIdentityDeduplicationEngine` | source sync / identity resolution | deterministic hierarchy, five outcomes, evidence trail, provenance merge |
| `domain.intelligence.dedup.PropertyDeduplicator` | URL-intelligence imports against the Room catalog | now a thin, deterministic adapter over the identity engine; the previous first-hit-wins ladder (35 m coordinates, Jaccard ≥ 0.75 fuzzy merge in DAO order) is gone – weak evidence is returned as `needsReview` and never merged |
| `domain.property.PropertyDeduplicator` + `PropertyMergePolicy` | Room import pipeline (`PropertyImportRepository`) | unchanged: its ladder (canonical key → APN → MLS → fuzzy ≥ 0.72) is deterministic and every decision carries strategy + reason; a follow-up could route it through the shared engine |

Defects found and fixed as part of the audit:

* fuzzy addresses between 0.80 and 0.84 were silently classified as `NEW` instead of review
  candidates (the default threshold was above the score of a single-token typo, 0.82);
* state names were folded inside *all* address tokens, so `123 Maine St` and `123 Me St`
  normalized to the same key – different streets could merge. State equivalence now applies only
  to trailing state tokens;
* the identity engine lacked MLS-ID and listing-URL signals, `MATCHED` could not distinguish an
  idempotent re-import from a cross-source match, and merge decisions were not tied to source
  priority/provenance.

Pre-existing issues **not** changed here (out of scope, reported for follow-up):

* `data/local/entity/IntelligenceEntities.kt` redeclares classes that also exist in
  `PropertyEntities.kt` / `PropertySourceEntities.kt` / `PropertyInsightEntities.kt`
  (`PropertyCompEntity`, `PropertyEnrichmentEntity`, `PropertyFinancialEntity`,
  `PropertyProvenanceEntity`, `PropertyImportJobEntity`) inside the same package, and several
  entities map the same Room table name. The `:app` module cannot compile until one family wins.
* `domain.property.PropertyDeduplicator` still auto-merges fuzzy matches ≥ 0.72 inside the Room
  import pipeline. Decisions are explainable (strategy/confidence/reason) but the threshold is
  lower than the review-only tier used by the identity layer.

## 7. Tests

| Suite | Covers |
|-------|--------|
| `PropertyIdentityDeduplicationEngineTest` | precedence, all five outcomes, conflict cross-checks, placeholders, confidence, determinism |
| `PropertyIdentityDeduplicationScenariosTest` | exact/partial matches, conflicting listings, address formatting changes, duplicate URLs, multiple providers, missing identifiers, repeated-run determinism |
| `SourceValueResolutionTest` | source priority, recency/name tie-breaks, conflict recording, order independence, idempotent canonical merges |
| `PropertySourceDeduplicationIntegrationTest` | `PropertySourceManager` end-to-end: cross-provider sync, duplicate URLs, idempotent re-imports, priority-based catalog merges, review/conflict paths |
| `PropertyDeduplicatorHardeningTest` | URL-intelligence deduplicator: no silent fuzzy/nearby merges, DAO-order independence, conflict on duplicate rows |
| `domain.property.PropertyDeduplicatorTest`, `UsPropertyNormalizerTest` | legacy import ladder + normalization primitives |

## Database guarantees for canonical rows (Room catalog)

* **One canonical row per key.** `properties.canonicalKey` is UNIQUE. New rows are written with
  `PropertyIdentityDao.insertNewProperty` (`OnConflictStrategy.ABORT`), never the replacing insert,
  so a conflicting write cannot silently delete a row and cascade into its satellites. A race that
  loses that arbitration rolls the transaction back and the import re-decides once, which then
  matches the committed row and merges into it.
* **A key is claimed only when free.** Import and reconcile check the owner before they set a key
  (`claimableKey`); a key another row holds stays unclaimed and is resolved by reconcile.
* **Merging a legacy duplicate** (`PropertyImportRepository.reconcileCanonicalKeys`) runs in one
  transaction, in this order: move every referencing row to the survivor (provenance, enrichments,
  comps, financing scenarios, saved state, offers, conversations, automation jobs, AI analysis,
  source links); delete the loser; then the survivor claims the canonical key. Rows that would
  duplicate one the survivor already has (same source record, comp, enrichment) are dropped and the
  survivor's version is kept. Nothing is dropped where the survivor has no counterpart.
* Automation jobs and offers keep their own ids and history; only their `propertyId` reference moves.
