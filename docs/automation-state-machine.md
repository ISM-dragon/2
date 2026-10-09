# Automation state machine — transitions, retry policy, audit contract

This document is the authoritative map of the automation job lifecycle implemented by
`JobState` / `JobStateMachine` / `AutomationEngine`, plus the retry semantics and the
audit-trail contract that the regression tests pin down.

Related code:

- `app/src/main/java/com/example/data/local/entity/AutomationEntities.kt` — `JobState` + topology
- `app/src/main/java/com/example/domain/automation/JobStateMachine.kt` — pure transition decisions
- `app/src/main/java/com/example/domain/automation/AutomationEngine.kt` — durable execution
- `app/src/main/java/com/example/domain/automation/AutomationRecoveryPolicy.kt` — crash recovery
- `app/src/main/java/com/example/domain/automation/AutomationExecutionLedger.kt` — side-effect ledger
- `app/src/main/java/com/example/domain/automation/AutomationAuditLogger.kt` — audit trail
- tests: `JobStateMachineTest` (full transition matrix), `AutomationRecoveryPolicyTest`,
  `AutomationAuditAndLedgerContractTest`, `AutomationEngineLifecycleTest`,
  `AutomationEngineCrashRecoveryTest`, `AutomationEngineConcurrencyTest`,
  `WorkflowFailureInjectionTest`

## 1. States

| State | Kind | Meaning |
|---|---|---|
| `DISCOVERED` | milestone | listing seen, no work done yet |
| `ANALYZING` | in-flight | underwriting step running |
| `ANALYZED` | milestone | underwriting result persisted |
| `QUALIFYING` | in-flight | qualification step running (pure) |
| `QUALIFIED` | milestone | deal passed the rules |
| `DISQUALIFIED` | terminal | deal failed the rules |
| `OFFER_GENERATION` | in-flight | offer draft being generated |
| `OFFER_READY` | milestone | offer persisted, `offerId` linked on the job |
| `VALIDATING_SEND` | in-flight | pre-send validation running |
| `SENDING` | in-flight | offer being transmitted |
| `RECONCILING` | in-flight | delivery outcome unknown (crash/lost ack) |
| `SENT` | terminal | delivery proven (message id / ledger / offer status) |
| `FAILED_RETRYABLE` | failure | transient failure; bounded retry scheduled (`nextAttemptAt`) |
| `FAILED_TERMINAL` | terminal | retry budget exhausted or deterministic defect |
| `BLOCKED` | parked | deterministic, operator-fixable blocker |
| `CANCELLED` | terminal | operator cancellation |

Terminal states (`isTerminal`): `SENT`, `DISQUALIFIED`, `FAILED_TERMINAL`, `CANCELLED`.

## 2. State-transition table

`JobState.canTransitionTo` is the pure topology. `JobStateMachine.transition` applies it on top of
structural preconditions (section 3) and turns same-state transitions into idempotent no-ops.
The complete 17×17 matrix is pinned by `JobStateMachineTest.every allowed and forbidden
transition is pinned by the transition table`; the condensed table:

| From \ To | DISC | ANLZ | ANLZD | QUAL | QLFD | DQ | OFFG | OFFR | VALD | SEND | RECN | SENT | F_RTY | F_TRM | BLK | CAN |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| DISCOVERED | –¹ | ✓ | | | | | | | | | | | ✓ | ✓ | ✓ | ✓ |
| ANALYZING | ✓² | –¹ | ✓ | | | | | | | | | | ✓ | ✓ | ✓ | ✓ |
| ANALYZED | | | –¹ | ✓ | | | | | | | | | ✓ | ✓ | ✓ | ✓ |
| QUALIFYING | | | ✓² | –¹ | ✓ | ✓ | | | | | | | ✓ | ✓ | | ✓ |
| QUALIFIED | | | | –¹ | | | ✓ | ✓³ | | | | ✓³ | ✓ | ✓ | ✓ | ✓ |
| DISQUALIFIED | | | | | | –¹ | | | | | | | | | | ✓ |
| OFFER_GENERATION | | | | ✓² | –¹ | | | ✓³ | | | | ✓³ | ✓ | ✓ | ✓ | ✓ |
| OFFER_READY | | | | | | | | –¹ | ✓ | | | ✓³ | ✓ | ✓ | ✓ | ✓ |
| VALIDATING_SEND | | | | ✓² | | | | ✓² | –¹ | ✓ | | | ✓ | ✓ | ✓ | ✓ |
| SENDING | | | | ✓² | | | | | | –¹ | ✓ | ✓ | ✓ | ✓ | | ✓ |
| RECONCILING | | | | ✓² | | | | ✓² | | | –¹ | ✓ | ✓ | ✓ | | ✓ |
| SENT | | | | | | | | | | | | –² | | | | |
| FAILED_RETRYABLE | ✓ | ✓ | ✓ | ✓ | ✓ | | ✓ | ✓ | ✓ | ✓ | ✓ | | ✓ | ✓ | ✓ | ✓ |
| FAILED_TERMINAL | | | | | | | | | | | | | | –² | | ✓ |
| BLOCKED | ✓ | ✓ | ✓ | ✓ | ✓ | | ✓ | ✓ | ✓ | ✓ | ✓ | | ✓ | ✓ | –¹ | ✓ |
| CANCELLED | | | | | | | | | | | | | | | | –² |

- `–`¹ same-state edge for a non-terminal state: **allowed topologically**, applied as an
  idempotent no-op by `JobStateMachine.transition` (nothing mutated, no side effect, no audit).
- `–`² same-state edge for a terminal state (`SENT`, `DISQUALIFIED`, `FAILED_TERMINAL`,
  `CANCELLED`): **rejected**. Terminal states are absorbing for the engine.
- ³ evidence-based forward jumps, guarded by structural preconditions (section 3):
  `QUALIFIED|OFFER_GENERATION|OFFER_READY → SENT` needs delivery evidence; `QUALIFIED → OFFER_READY`
  needs a persisted offer id.
- recovery rewinds (`ANALYZING → DISCOVERED`, `QUALIFYING → ANALYZED`, `OFFER_GENERATION → QUALIFIED`,
  `VALIDATING_SEND → OFFER_READY|QUALIFIED`, `SENDING|RECONCILING → QUALIFIED`) are the documented
  continuations of `AutomationRecoveryPolicy`: replay the earliest step whose durable result is not
  proven. `OFFER_READY → QUALIFIED` stays forbidden — `OFFER_READY` itself proves an offer exists.
- `FAILED_TERMINAL → CANCELLED` remains: an operator may bury a dead job (the audit trail of the
  failure is never rewritten).

## 3. Structural preconditions (enforced inside `JobStateMachine.transition`)

1. **`OFFER_READY` requires a persisted offer id.** `offerId` must be passed in or already persisted
   on the job row; otherwise the transition is rejected (`"...requires a persisted offerId"`).
2. **`SENT` requires delivery evidence.** At least one of `offerId` / `emailMessageId` must exist;
   otherwise the transition is rejected. `runSendStep` additionally only enters `SENT` after the
   transmit call returned success or the idempotency ledger/offer status proves delivery.
3. A job is never marked successful before its required persisted outcome exists: the engine counts
   `offersSent` only when the `SENT` transition was actually committed.

## 4. Retry semantics

- **Attempt count** — `attempts` counts consecutive failed attempts of the *current step*
  (the failing step name is persisted in `failedStep`). A successful milestone resets it to 0.
- **Backoff** — `RetryPolicy` (exponential, jittered, capped): delay before attempt *n* is
  `base * multiplier^(n-1)` clamped to `maxDelayMs`, jittered by `±jitterFraction`. The job's
  `nextAttemptAt` gates pickup; a cycle never retries a job before its due time.
- **Terminal failure** — when the attempt that just failed reaches `maxRetries`, the transition to
  `FAILED_RETRYABLE` is escalated to `FAILED_TERMINAL` *in the same decision* (exactly one terminal
  outcome — never a terminal plus a queued retry). `FAILED_TERMINAL` is absorbing; re-discovery of
  the same property never creates a twin job behind it. `escalatedOutcome` refuses to touch rows
  that are already terminal.
- **Failure classification** — `FailureClassifier` maps errors to `RETRYABLE` (transient, bounded
  retries), `TERMINAL` (deterministic defect, immediate `FAILED_TERMINAL`), `BLOCKED` (fixable by
  the operator) and `CANCELLED`. Not everything is retryable on purpose.
- **Durable resumption** — every step persists its in-flight state *before* the side effect.
  A crash is reconciled from durable evidence only (`AutomationRecoveryPolicy` + ledger + offer
  status): analysis evidence resumes at `ANALYZED`, an existing offer is re-linked
  (`OFFER_GENERATION → OFFER_READY` with the persisted id, never regenerated), an ambiguous send is
  parked in `RECONCILING` and scheduled as `FAILED_RETRYABLE` with backoff instead of being blindly
  re-sent. Recovery rewinds replay the earliest unproven step; the recovery budget
  (`maxRecoveryAttempts`) stops crash loops with a terminal failure.
- **Duplicate delivery** — side effects go through `AutomationExecutionLedger`
  (deterministic idempotency keys, e.g. `SEND_OFFER:<offerId>`). A repeated worker execution finds
  the `SUCCEEDED` row and reconciles from `resultRef` instead of performing the effect again.

## 5. Audit contract

- **Exactly one `STATE_TRANSITION` record per committed transition.** The record carries
  `stateBefore`, `stateAfter`, the committed `attempt` and the `runId`.
- **No record for a rejected transition or a same-state no-op.** A lost compare-and-swap logs a
  `TRANSITION_REJECTED` warning instead, so the audit history never claims a transition that the
  database refused.
- Failure transitions, operator retries and operator cancellations are audited through the same
  path, so `audit history agrees with persisted state` holds for every lifecycle move, not only the
  happy path.

## 6. Root-cause analysis (observed failures → fixes)

| Observed failure | Root cause | Fix |
|---|---|---|
| `JobStateMachineTest`: SENT can transition to SENT | `canTransitionTo` returned `true` for every same-state pair, so terminal states were not absorbing | same-state edges now require `!isTerminal`; `SENT`/`CANCELLED` accept no edge at all (`JobStateMachineTest` transition matrix + absorbing test) |
| `JobStateMachineTest`: OFFER_READY reachable without a persisted offer identifier | `QUALIFIED → OFFER_READY` was rejected as an *illegal transition* before the offer-id guard could name the missing id, and the resume-with-evidence edge did not exist (the engine's "reuse existing offer" path silently failed) | the topology now includes the evidence-based `QUALIFIED → OFFER_READY` edge, and `transition` enforces the offer-id precondition before the state can be entered — from every resumable state |
| `AutomationEngineLifecycleTest`: missing state-transition audit events | step-entry transitions (`DISCOVERED → ANALYZING`, `ANALYZED → QUALIFYING`, `QUALIFIED → OFFER_GENERATION`, `OFFER_READY → VALIDATING_SEND`) and failure transitions were persisted without any `STATE_TRANSITION` record | every committed transition is logged exactly once via `logTransition`, which also suppresses no-op records so rolled-back/duplicated attempts leave no misleading entries |
| `AutomationEngineLifecycleTest`: more than one result where a single result is expected during retry exhaustion | `registerDiscoveredProperty` only treated `SENT`/`OFFER_READY` as "already resolved", so a re-discovered listing whose job had just gone `FAILED_TERMINAL` (or `DISQUALIFIED`/`CANCELLED`) grew a twin job and re-ran the whole pipeline | any terminal outcome is final for the property; rediscovery returns `null` (operator-initiated re-runs create an explicit successor via `retryJob`) |
| `WorkflowFailureInjectionTest`: SUCCESS where a deferred retry is expected | the failure-injection harness's `maxTotalImportMillis` knob was snapshotted when the pipeline was built in `init`; setting it afterwards was silently ignored, so the layer burned the retry inline (a second attempt succeeded → `SUCCESS`) instead of deferring within the call budget | the knob is live: changing it rebuilds the pipeline (`WorkflowHarness.rebuildPipeline`), so the injected budget actually reaches the import layer and the retry is scheduled (`ImportOutcome.Deferred`, `FETCH_RETRY_SCHEDULED`) |
| `AutomationEngineCrashRecoveryTest`: restart recovery saw neither the offer generated before the "crash" nor the sends issued after it (`expected OFFER_READY but was QUALIFIED`, `exactly one email in total: expected 1 but was 0`) | `EngineTestHarness` built a fresh set of gateway fakes for every instance, so a "restarted process" shared the durable database but lost the *external* world (offer store, Gmail, financial store, device clock) — recovery concluded "no offer behind" and sends landed in counters the test never observed | harnesses that share a database now share one `TestWorld` (device time, connectivity, property feed, offer/Gmail gateway, financial store); only the engine and its in-memory scheduler are per-process |
| `AutomationEngineConcurrencyTest`: takeover/second-engine sends invisible (`expected 1 but was 0`) | same fixture defect: `secondEngine`'s `transmitOffer` incremented its own private counter while the assertions watched the first harness's | same `TestWorld` sharing; a single delivery is now counted exactly once across racing engines |
| `AutomationEngineLifecycleTest`: offline pause overwritten (`expected PAUSED_OFFLINE but was IDLE`) | `executeCycle`'s success path unconditionally reset the status to `IDLE`, even when `runCycleBody` had just persisted an offline pause | the success path keeps `PAUSED_OFFLINE` (with its explanation) when the cycle ended offline |
| `AutomationEngineCrashRecoveryTest`: durable work not re-armed after a restart (`durable work must be re-enqueued`) | `onProcessStart` re-armed the scheduler only when automation was enabled, so work a previous process had already begun stayed stranded after a crash-restart | a process start that reconciled unfinished work also enqueues an immediate cycle — durable resumption is not "starting automation" |
| `AutomationEngineCrashRecoveryTest`: adopted offer auto-sent by the next cycle (`expected OFFER_READY but was SENT`) | the suite armed `autoSendOffers = true` in `setUp`, contradicting the scenario's own contract ("continuing the cycle must not regenerate the offer" / the job must stay parked at `OFFER_READY` for the operator) | the suite now seeds the neutral rules (`autoSendOffers = false`); the scenario that must send (`work interrupted …`) arms sending explicitly for its resumed phase |

Additional hardening found while mapping the topology (also covered by tests):

- recovery policy targets that had no legal edge (`ANALYZING → DISCOVERED`, `QUALIFYING → ANALYZED`,
  `VALIDATING_SEND → OFFER_READY|QUALIFIED`, `SENDING|RECONCILING → QUALIFIED`, evidence-based
  `→ SENT`) would make crash recovery silently stall (`recovery skipped: illegal transition`); the
  topology now carries the documented rewinds and evidence jumps.
- `runOfferStep`'s "reuse existing offer" path reported `advanced = true` even when the transition
  was refused, looping the step up to `MAX_STEPS_PER_JOB` times and emitting bogus same-state audit
  entries; it now advances only when the state actually moved.
- `offersSent` was incremented even when the `SENT` transition was not committed; it is now counted
  only on a committed `SENT` state.

## 7. Test coverage map

- `JobStateMachineTest` — full 17×17 transition table, absorbing terminals, structural
  preconditions, recovery rewinds, retry exhaustion (exactly one terminal outcome), duplicate
  delivery no-ops, backoff envelope.
- `AutomationRecoveryPolicyTest` — where interrupted work resumes/rewinds, delivery reconciliation,
  recovery budget.
- `AutomationAuditAndLedgerContractTest` — one audit record per committed transition, none for
  rejected/no-op attempts, audit chain continuity, ledger exactly-once semantics.
- `AutomationEngineLifecycleTest` — end-to-end cycles on a real Room database: happy path,
  idempotency, bounded retries, retry exhaustion (single terminal outcome, no twin jobs),
  blocked/kill-switch/operator flows, audit history vs. persisted state.
- `AutomationEngineCrashRecoveryTest` — process-death recovery from durable evidence.
- `AutomationEngineConcurrencyTest` — cycle/job leases, compare-and-swap, duplicate delivery under
  racing workers.
- `WorkflowFailureInjectionTest` — deferred retry under a tight import budget, resume after
  restart, duplicate suppression, ambiguous delivery handling.
