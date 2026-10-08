# AI Analyst Runtime Contract

The qualitative analyst (`com.example.domain.ai.analyst`) is a **narrow, auditable** boundary between
untrusted listing data and the app's UI. It deliberately cannot compute financial metrics, cannot
invent property facts, and cannot hand an unvalidated response to callers or to storage. The
deterministic engine (`FinancialEngine`, `DeterministicFinancialEngine`, `DealScoringEngine`) remains
the only owner of every number a user sees.

CI gate: `.github/workflows/ai-analyst-ci.yml` runs

```
gradle :app:testDebugUnitTest \
  --tests com.example.RealEstateAnalystOutputValidatorTest \
  --tests com.example.RealEstateAnalystInputFactoryTest \
  --tests com.example.RealEstateAnalystHardeningTest \
  --tests com.example.RealEstateAnalystAdversarialContractTest
```

## Boundary map

| Stage | Type | Responsibility |
|-------|------|----------------|
| 1. Mapping | `RealEstateAnalystInputFactory` | Strict field allow-list from `PropertyEntity`, market, rent, tax, engine metrics and up to `MAX_COMPARABLES` comps. Descriptions, images, coordinates, titles, URLs, other properties and DB IDs are never serialized. |
| 2. Sanitizing | `AnalystTextSanitizer` | Drops control/invisible/bidi/separator glyphs and framing characters, collapses whitespace, caps length, removes URLs and instruction-like text. Values that sanitize to nothing become explicit `null` gaps. |
| 3. Framing | `RealEstateAnalyst` | Per-request UUID packet delimiters that are absent from the payload; hard-system instructions; `responseMimeType = application/json`; bounded retries (default 3) with local validation errors appended. |
| 4. Validation | `RealEstateAnalystOutputValidator` | Strict single-value JSON parse, one outer code fence, balanced-object salvage only when unambiguous, Draft 2020-12 subset schema, then semantic provenance rules. |
| 5. Hand-off | `RealEstateAnalystPersistence` | Re-encodes the validated analysis, re-validates it, and produces column values that mirror `PropertyAiAnalysisEntity` one-for-one. Fails closed. |

## Epistemic rules (enforced, not suggested)

| Classification | Evidence | Confidence | Notes |
|----------------|----------|-----------|-------|
| `FACT` | ≥ 1 supplied record (`PROPERTY_RECORD`, `MARKET_DATA_RECORD`, `TAX_RECORD`, `COMPARABLE_RECORD`) | `> 0`, may be `1.0` | May not rest on an estimate, and may not restate `DETERMINISTIC_FINANCIAL_ENGINE` output. |
| `ESTIMATE` | must include a supplied `MARKET_ESTIMATE` or `RENT_ESTIMATE` | `0 < c < 1` | The model may only restate supplied estimates; it may not create new ones. |
| `INFERENCE` | ≥ 1 supplied reference | `0 < c < 1` | The only allowed reading of engine output, i.e. "what the recorded scenario implies". |
| `UNKNOWN` | none (`evidenceRefs` must be empty) | exactly `0.0` | Required for `unknowns[]`; forbidden in `strengths` / `risks` / `redFlags`. |

Additional structural rules: every referenced ID must exist in this request's packet; IDs must be
unique per claim; statements and questions must be plain prose with no numeric figures in any number
system (digits, fullwidth/Arabic-Indic, superscripts, fractions, Roman numerals, or spelled-out
number words); the JSON Schema forbids additional properties, so a response cannot add
`capRatePct`, `offerRange`, or any other metric field.

## Prompt-injection resistance

* Untrusted text is delimited by a per-request nonce that is verified absent from the payload, so a
  listing cannot close the packet early.
* Sanitized values are checked against an instruction/credential denylist *after* invisible glyphs
  are removed, so zero-width or bidi splitting cannot smuggle an instruction through.
* Output text is rejected if it echoes instruction-like or credential language, contains framing
  characters/control/invisible characters, or is empty/blank.
* **Retry diagnostics are a trusted channel and are kept boring**: validation messages are
  single-line, length- and count-capped, free of framing/control characters, must not contain the
  packet delimiters, and may only echo a bounded identifier-style fragment of a model-invented JSON
  key (instruction-like, camelCased, or reserved-vocabulary keys collapse to `?`). A hostile listing
  value echoed into a JSON key therefore cannot reopen the untrusted packet on the retry turn.
* Model output never grants new authority: provider, slot, key and cooldown handling stay in
  `GeminiManager`, and no packet content can change the schema, role, or output format.

## Persistence hand-off

`RealEstateAnalystPersistence.toPersistenceValues(...)` produces a
`RealEstateAnalystPersistenceValues` whose property names mirror `PropertyAiAnalysisEntity`
(`property_ai_analysis`) exactly — this is asserted by reflection in
`RealEstateAnalystAdversarialContractTest.persistenceColumnsMirrorPropertyAiAnalysisEntityExactly`,
so adding or dropping a column fails the contract instead of dropping analysis data.

Deliberate gaps and derivations:

* `weaknessesJson` is `[]` (this contract has no weaknesses section; risks carry that material).
* `recommendedOfferRange` is empty — offer ranges are financial figures owned by the deterministic
  engine, and the analyst contract emits no numbers.
* `summary` is the first sentence of the already-validated thesis (bounded preview, no new content).
* `confidence` is the **minimum** claim confidence, so one weak or `UNKNOWN` claim keeps the stored
  row conservative.
* `evidenceJson` is an audit digest (`id`, `source`, `field`, `asOfEpochMillis`) of exactly the cited
  evidence, sorted by ID, so stored claims stay explainable after the request-scoped packet is gone.
* `analyzedAt` is supplied by the caller: the mapper never reads a wall clock, which makes the
  mapping deterministic and testable.

The mapper re-validates before emitting values, so a DTO constructed by hand cannot bypass the
contract and reach a database column. Room, DAOs and repositories stay out of this package; wiring
the values into `IntelligenceRepository` is intentionally left to the persistence task.

## Verified by

* `RealEstateAnalystOutputValidatorTest` — schema/enum/confidence/evidence basics, retry behavior.
* `RealEstateAnalystInputFactoryTest` — allow-list mapping and gap handling.
* `RealEstateAnalystHardeningTest` — malformed output, injection on both sides, numeric smuggling.
* `RealEstateAnalystAdversarialContractTest` — schema↔DTO↔column drift, cross-request refs,
  classification/confidence counter-examples, malformed-JSON recovery matrix, retry-channel
  injection, engine/qualitative separation, persistence determinism and fail-closed behavior.

## Known limits

* Semantic truth cannot be verified mechanically: a `FACT` claim can only be checked for *provenance*
  (correct source), never for whether the sentence genuinely restates that source. The prompt and the
  classification rules reduce the risk; they cannot eliminate it.
* The injection denylist is intentionally conservative and heuristic; it complements, and does not
  replace, the packet framing and the trusted system prompt.
* Non-`en_US`/`en_GB` locales are out of scope: the contract assumes US properties and USD.
