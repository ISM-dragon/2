# Seller outreach engine

Provider-neutral seller communication workflow in `app/src/main/java/com/example/domain/outreach`. It prepares seller message drafts, follow-up sequences, and scheduled follow-ups. It does not send them.

## What this engine does not do

- It does not transmit SMS. SMS is an abstract channel and a draft only. `SmsChannelAdapter.transmit` always refuses.
- It does not perform email delivery and does not call the Gmail delivery service.
- It does not place manual-call follow-ups. A call step is a script for a person.
- It does not read or write CRM records, offer records, or analyst output.
- It does not determine price or financial terms. Those fields are not part of the draft, the AI request, or the template slots.

## Models

| Type | Role |
|---|---|
| `SellerMessageDraft` | Subject, body, and optional call script for one touch. |
| `OutreachChannel` | `email`, `sms`, or `manual-call`. None transmit automatically. |
| `OutreachPurpose` | Why the touch exists (introduction, follow-up, status check, and so on). |
| `OutreachTone` | Salutation and closing only. Tone does not add facts. |
| `PersonalizationFields` | Caller-supplied name, address, verified descriptive facts, and an attributed seller note. |
| `FollowUpSequence` | Ordered steps with delays relative to the previous step. |
| `ScheduledFollowUp` | One scheduled step, its status, and the draft id once composed. |

The default sequence is an email introduction, an SMS follow-up two days later, a manual-call follow-up three days after that, and an email status check three days after the call. Delays are cumulative and deterministic from the anchor time.

## Text

Deterministic templates are the fallback and the default. The same channel, purpose, tone, and normalized personalization always render the same prose.

`SellerOutreachTextGenerator` is the interface for AI-generated text. The engine may accept prose only. JSON from a model may contain `subject`, `body`, and `callScript` and no other keys. Price, offer, rent, loan, and similar keys are rejected. If generation is missing, empty, unsafe, or off-contract, the engine discards it and uses the template. The rejected text is not stored on the draft.

Output is sanitized before it can be kept. The guard blocks credentials, prompt injection, links, unsupported claims, fabricated seller motivation, and invented property facts. A seller note is quoted only when the caller attributes it to the seller, owner, or listing agent. Motivation language outside that note is rejected. Property counts, streets, years, and condition phrases must match the supplied record.

## Use

```kotlin
val engine = SellerOutreachEngine() // optional SellerOutreachTextGenerator
val workflow = engine.start(StartOutreachRequest(personalization = fields))
val preparation = engine.prepareChannel(workflow.drafts.single(), recipient)
val refusal = engine.transmit(preparation) // always a refusal
```

`recordOutcome` records a human result. `OPTED_OUT` cancels every open follow-up. `composeDue` is idempotent for an unchanged workflow.
