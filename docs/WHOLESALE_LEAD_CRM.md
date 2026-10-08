# Wholesale Lead & Seller CRM

The CRM model for wholesale deals: a **lead** is one seller (or seller group) and the property (or
properties) they may sell, worked through a nine-stage pipeline with deterministic qualification
and a complete audit trail.

Everything lives in `app/src/main/java/com/example/domain/crm/` and is **plain Kotlin**: no Android
imports, no Room annotations, no network. `LeadCrmIsolationTest` fails the build if that ever
changes, and it also pins the pipeline ladder and the policy weights.

## Pipeline

| # | Status | Meaning |
|---|---|---|
| 0 | `NEW` | captured, nothing attempted yet |
| 1 | `CONTACTED` | at least one outreach attempt is on record |
| 2 | `RESPONDED` | the seller (not a third party) has engaged |
| 3 | `NEGOTIATING` | price/terms discussion with a qualified seller |
| 4 | `OFFER_SENT` | a written offer from the offer pipeline is on the table |
| 5 | `UNDER_CONTRACT` | the offer was accepted and the contract is referenced |
| 6 | `DUE_DILIGENCE` | inspection window open |
| 7 | `CLOSED` | won |
| — | `LOST` | exit from any open stage, with its own reason |

Moves go through `LeadPipelineStateMachine.transition(...)`, gated by
`LeadTransitionPolicy` (`wholesale-lead-transition-v1`):

- a **contact path** must exist before any outreach stage (a do-not-contact freeze blocks it);
- `RESPONDED` needs a logged seller response (`CommunicationOutcome.isSellerResponse`);
- `NEGOTIATING` needs a fresh qualification and a motivation signal;
- offer stages need a referenced offer, a linked subject property and a seller conversation;
- committed stages need the referenced contract, and `DUE_DILIGENCE` needs its inspection window;
- a `DISQUALIFIED` lead can only re-enter through `LOST` → `RE_ENGAGED`;
- same status is a `NoOp` (mobile retries must be idempotent); a bad edge is `Illegal`; missing
  evidence is `Blocked` with stable blocker codes (`CrmBlockerCodes`).

Every applied move appends a `LeadTransition` (from, to, reason, actor, timestamp, correlation id,
evidence snapshot) and bumps `CrmAuditMetadata`, whose timestamps can never move backwards.

## Qualification

`LeadQualificationEngine` is a pure function of the facts (`wholesale-lead-qualification-v1`):

| Factor | Max | Source |
|---|---|---|
| `SPREAD` | 30 | asking vs ARV minus repairs and a contingency |
| `MOTIVATION` | 25 | `MotivationSignals` index (strength, distinct kinds, freshness) |
| `TIMELINE` | 15 | `SellerTimeline.urgencyPoints` |
| `CONDITION` | 10 | `RehabSeverity` of the condition assessment |
| `REACHABILITY` | 12 | contact point + seller response + connected call |
| `DATA_COMPLETENESS` | 8 | which evidence items are present |

States: `NOT_ASSESSED` → `DISQUALIFIED` / `UNQUALIFIED` (< 25) / `NURTURE` (≥ 25) / `WARM` (≥ 45) /
`QUALIFIED` (≥ 65). Qualification is a judgement about evidence, so any state may move to any other
state — but only via a reason that fits (`FIRST_ASSESSMENT`, `EVIDENCE_ADDED`, `SELLER_RESPONDED`,
`EVIDENCE_DECAYED`, `EVIDENCE_CONTRADICTED`, `MANUAL_OVERRIDE`), and `NOT_ASSESSED` is entry-only.

Blockers (missing evidence) cap the computed state at `NURTURE`; **disqualifiers win outright**:
a seller decline, a do-not-contact request, a property outside the service area, or a spread below
the floor. Missing repair scope is never treated as "no repairs" — the spread simply cannot be
computed, which is a blocker.

An assessment decays when it is older than 30 days (`LeadQualificationEngine.decayIfStale`), and an
operator override must be justified with a note.

## The operator layer

- **Priority** (`wholesale-lead-priority-v1`): qualified ≥ 50 HIGH, ≥ 72 URGENT; due follow-ups
  raise it, a frozen contact path caps it at LOW, terminal leads are LOW.
- **Follow-up** (`LeadFollowUpPolicy.DEFAULT_INTERVALS`): a cadence per stage, a 24 h due window,
  a 24 h grace period, and an explicit breach reason when an open lead has no next touch.
- **Tags** are canonical (`lowercase-hyphens`, ≤ 32 chars) and capped; **notes** are immutable and
  may supersede each other; **communications** are append-only references to an artifact
  (`externalRef`, `relatedOfferId`), never a copy of the transcript.
- **Sellers** carry normalized contact points (`+1XXXXXXXXXX`), per-channel and blanket
  restrictions, occupancy, entity type and an inert `SkipTraceReference`.

## Service & storage

`LeadCrmService` is the only place that composes store + state machines + validation:

```kotlin
val crm = LeadCrmService(InMemoryLeadStore())
val created = crm.createLead(request, atEpochMillis = T0, actor = "rep.avery")
crm.recordCommunication("LEAD-…", draft, atEpochMillis, "rep.avery")   // auto-advances the stage
crm.moveTo("LEAD-…", LeadPipelineStatus.NEGOTIATING, NEGOTIATION_OPENED, at, "rep.avery")
```

Every method takes `atEpochMillis` (the domain never reads a clock) and returns a typed result:
`Applied` / `Rejected` / `NotFound`, `Applied` / `NoOp` / `Blocked` / `Illegal`, or
`Applied` / `Unchanged` / `Rejected` / `Illegal` for qualification. A write that would leave the
record inconsistent is **rejected before it reaches the store**.

`LeadStore` is the persistence port. `InMemoryLeadStore` is bounded, thread-safe and orders open
leads by priority, then time in stage, then id.

## Deliberately out of scope on this branch

- **No Room registration**: `AppDatabase` is untouched, so no migration or schema guard update is
  needed. A Room-backed `LeadStore` maps the aggregate onto `leads` + child tables in one
  transaction.
- **No skip tracing**: `DisabledSkipTracePort` is inert; candidates would require an operator to
  apply them, and the seam returns `NotConfigured` without touching the network.
- **No UI and no changes to `OfferRepository` / DealRoom**: integrations enter through the ports
  (`LeadStore`, `SkipTracePort`) and the read-only `LeadOfferBridge`, which maps offer statuses to
  CRM moves (`SENT`/`OPENED` → `OFFER_SENT`, `SIGNED` → `UNDER_CONTRACT`, `DECLINED`/`EXPIRED` →
  `LOST`; drafts and transport failures move nothing).

## Tests

`app/src/test/java/com/example/domain/crm/` (144 tests, pure JVM):

| Suite | Covers |
|---|---|
| `LeadPipelineStateMachineTest` | topology, reasons, blockers, policy knobs, overrides |
| `LeadPipelineLifecycleTest` | happy path to `CLOSED`, every loss exit, back-edges, DNC freeze |
| `LeadQualificationEngineTest` | golden 76/100 deal, disqualifiers, blockers, decay, override |
| `LeadSignalsAndModelTest` | motivation, condition, timeline, priority, tags, contacts, ids, bridge |
| `LeadFollowUpAndCommunicationTest` | cadence, due/overdue/breach, outcome compatibility, DNC |
| `LeadValidationTest` | all validation codes and severities, clean-record guarantee |
| `LeadCrmServiceTest` | capture → evidence → move → qualification → follow-up, store behaviour |
| `LeadCrmIsolationTest` | framework-free source scan, ladder and policy guards |

`CrmTestFixtures` anchors everything to fixed instants and ids; the default lead
(asking $180k / ARV $300k / repairs $45k / strong documented tax delinquency / 30-day timeline /
minor rehab / reachable seller) scores **68/100 → QUALIFIED** on capture and **76/100** once a
conversation is logged.
