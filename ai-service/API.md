# AI interaction and source contracts

The existing engine proxies `/api/v1/ai/*` using its local operator authentication. Direct orchestrator requests use the analyst credential in ignored `runtime/ai/credentials.json`. Credentials must remain local; examples below intentionally contain no secrets. All writes are JSON; errors return an HTTP status and `code/error` from the direct service.

## Interaction routes

Base: `http://127.0.0.1:8100/api/v1/ai`.

| Method / path | Purpose |
|---|---|
| GET /health | Role, protocol, model and key-configuration presence |
| GET /defaults | Budget template with a fresh deadline |
| GET /parameters | Typed registry of 42 exact editable paths |
| POST /workflows | Create or replay a requested experiment |
| GET /workflows?after=ID&limit=20 | Cursor page of analyst-owned workflow summaries; limit 1–100 |
| GET /workflows/ID | Versioned progress, scope, usage, analysis and compact trial scores |
| GET /workflows/ID/evaluations?offset=0&limit=20 | Full trial configurations, engine drafts, status and report references |
| GET /workflows/ID/artifacts/sha256:HASH | Authorized immutable report or agent-result artifact |
| POST /workflows/ID/scope-confirmations | Approve exact scope hash and expected version |
| POST /workflows/ID/messages | Clarify a pre-execution plan with expectedVersion/text |
| POST /workflows/ID/resume | Extend a paused budget/deadline with expectedVersion/budget |
| POST /workflows/ID/cancel | Stop future work |
| POST /workflows/ID/publication-previews | Prepare an exact final-tested candidate and review note |
| POST /publication-previews/ID/confirm | Explicitly publish that exact, unexpired preview |
| GET /publications?after=ID | Page of saved publication receipts and lineage |
| GET /publications/ID/performance?sourceMode=DEMO | Reconcile available source data and compare saved versus observed results |
| POST /publications/ID/investigations | Budgeted research/diagnostics/analysis/reflection workflow |
| POST /publications/ID/successors | New locked experiment using the published baseline |
| POST /publications/ID/forecasts | Freeze a prospective calibrated real forecast before enrollment |
| POST /conversations | Create a conversation linked to a publication and/or workflow |
| GET /conversations/ID | Read conversation state |
| POST /conversations/ID/messages | Idempotent contextual follow-up or explicit operation |
| GET /notices?after=ID | Page of durable outcome-monitor notices |
| POST /outcomes/refresh | Replay a bounded page batch from the native storage journal |
| GET /policies | Research-owned document metadata |
| POST /policies/refresh | Rescan local documents |
| GET /policies/ID/text?offset=0 | Up to 8,000 extracted characters, with total length |
| POST /policies/ID/approve | Approve hash/product/geography/expiresOn |
| GET /calibrations?product=PERSONAL_LOAN&policyContext=CONTEXT | Real-label readiness |
| POST /calibrations | Evaluate a candidate calibration on fixed chronological splits |
| POST /calibrations/activate | Explicitly activate a passing reviewed model version |
| POST /calibrations/forecast | Preview prospective probabilities for an active context |

Use the final returned item ID as the next cursor. Full workflow evaluations use numeric offsets; the response includes `nextOffset`. Publication/notices pages contain up to 20 items. Artifact access checks workflow ownership and actual references; a hash alone does not grant access.

Policy requests are forwarded to the Research service using a separate policy-review credential. Only its currently approved, readable, unexpired and product-applicable chunks may authorize ranges.

## Create, inspect and execute

Fetch `GET /defaults` for an unexpired budget before constructing this request; replace the illustrative deadline and resource IDs.

```json
{
  "requestId": "analyst-experiment-001",
  "draftId": "EXISTING_DRAFT_ID",
  "populationId": "DISCOVERY_POPULATION_ID",
  "validationPopulationId": "FRESH_VALIDATION_POPULATION_ID",
  "intent": "Explore the bureau minimum credit score only. Keep underwriting fixed.",
  "lockedStages": ["underwriting"],
  "seed": 20260918,
  "autoExecute": true,
  "budget": {
    "maxEvaluations": 200,
    "maxModelCalls": 60,
    "maxTokens": 500000,
    "maxCostUsd": 25,
    "deadline": "REPLACE_WITH_FUTURE_UTC_TIMESTAMP",
    "exploration": 119,
    "refinement": 60,
    "validation": 20,
    "minimumEligible": 30,
    "eligibilityFloor": 0,
    "acceptanceFloor": 0
  }
}
```

The same analyst/request ID and identical body replay the same workflow; changing that body under the same ID fails. `autoExecute` defaults to true: the submitted request and budget authorize planning and execution. Inspect `workflow.scope`, including paths, ranges, evidence IDs, locks and budget. Only callers explicitly selecting `autoExecute:false` pause at AWAITING_SCOPE and send a scope confirmation:

```json
{"expectedVersion": 7, "scopeHash": "EXACT_DISPLAYED_SCOPE_HASH"}
```

The response envelope is `{id, version, workflow, usage}`. Relevant states are PLANNING, EXECUTING, COMPLETED, BLOCKED, NO_SUPPORTED_CANDIDATE, REVIEW_REQUIRED, PAUSED_BUDGET, FAILED and CANCELED. AWAITING_SCOPE applies only to explicit manual-review mode; feedback begins FEEDBACK_PENDING. Publication is a separate stateful operation. Completed experiments cannot be reinterpreted by appending another instruction; use a successor.

Interpret `usage.reservedTokens/reservedCostUsd` as a conservative accounting balance: settled known usage plus worst-case uncertain reservations. They are not an invoice. A resume body contains the current expectedVersion and a complete Budget with nondecreasing resource ceilings, a later deadline and unchanged phase/floor/support settings.

A publication preview request is `{candidateId, note}`; the candidate must have a completed final-validation run. Notes are 10–1,000 characters. The preview binds the exact run, draft version, configuration, scope revision, policy evidence and note for 15 minutes. Confirmation is:

```json
{"previewHash": "EXACT_DISPLAYED_PREVIEW_HASH", "confirm": true}
```

An uncertain response can be retried against the same preview. The durable COMMITTING state recovers the same existing-engine receipt instead of issuing a different catalog mutation.

## Conversations and feedback

Create `{requestId, publicationId, workflowId}`, omitting either optional context if not yet selected. A message includes `requestId`, `expectedVersion` and `text`. Supported explicit `operation` values are PERFORMANCE, EXPLAIN, REVISE, PREVIEW, PUBLISH, STATUS and CLARIFY. A small deterministic classifier recognizes common follow-ups. Unsupported phrasing asks for an operation rather than silently performing a write.

EXPLAIN also needs an explicit budget and can select sourceMode. REVISE additionally needs discovery/fresh validation population IDs, budget and optional lockedStages/seed. PREVIEW needs candidateId/note. PUBLISH requires a preview already saved in that conversation. A preview returned by the server is the concrete review artifact; clients must display it before confirmation.

Conversation state and each replay receipt commit together. Original publication context survives explanation workflows. Underwriting and other inherited locks remain in successor plans even when omitted from a later request.

Direct investigation body: `{requestId, text, budget, sourceMode}`.
Direct successor body: `{requestId, intent, populationId, validationPopulationId, budget, lockedStages, seed}`.
The deterministic performance response includes counts, rates, source/maturity/coverage, original or frozen real prediction, comparability status, descriptive interval and an immutable snapshot reference.

## Operational outcome events

This endpoint is separate from analyst operations:

`POST http://127.0.0.1:8100/internal/outcomes/events`

It is enabled only by `AI_OUTCOME_SOURCE_ID`; authenticate with the dedicated **outcomes** credential. Source mode is deployment configuration, not a caller-selected event field. Request: `{"events":[...]}`, at most 100 events per batch and 1 MB total. Partial batches can be replayed with identical event identities.

Each event uses:

```json
{
  "schemaVersion": "1.0.0",
  "eventId": "SOURCE_STABLE_EVENT_ID",
  "kind": "ENROLLMENT",
  "publicationId": "PUBLISHED_CATALOG_OFFER_ID",
  "customerKey": "64_CHARACTER_SHA256_SUBJECT_PSEUDONYM",
  "businessKey": "SAME_SUBJECT_PSEUDONYM_FOR_ENROLLMENT",
  "revision": 1,
  "correctsEventId": null,
  "correctionReason": null,
  "occurredAt": "ACTUAL_PAST_UTC_TIMESTAMP",
  "data": {
    "familyId": "SOURCE_OFFER_FAMILY_ID",
    "followUpEndsAt": "ELIGIBILITY_PLUS_CONFIGURED_FOLLOWUP_OR_EARLIER_EXPIRY",
    "terms": {"aprPct": 12, "amountUsd": 10000, "termMonths": 36},
    "sourceVersion": 1
  }
}
```

The illustrative terms object must contain the source system's exact reviewed terms, in the same canonical representation in enrollment and selection events.

| Kind | Data fields | Identity |
|---|---|---|
| ASSESSMENT | status (ELIGIBLE/INELIGIBLE/INCOMPLETE/FAILED), profileVersion, policyVersion, admissionId | businessKey = customerKey |
| ENROLLMENT | familyId, followUpEndsAt, terms, sourceVersion | businessKey = customerKey |
| SELECTION | familyId, kind (ORIGINAL/PERSONALIZED), terms, sourceVersion | businessKey = stable selection request |
| EXPOSURE | familyId, variant, terms, sourceVersion, stage (CLIENT_RENDERED/SERVER_RENDERED/DELIVERED) | businessKey = stable exposure event |
| FORECAST_FEATURE | baseProbability (0–1), featureAsOf, product, policyContext, followUpDays, scope = OFFER_FAMILY | businessKey = customerKey |
| WATERMARK | through, complete, includesAllAdmissions, entryStartsAt, entryEndsAt, followUpDays | businessKey = publicationId; customerKey = null |

First records are revision 1. A correction increments the revision by exactly one, references the previous event ID, supplies a reason of at least ten characters and preserves the subject identity. Old source events are retained. Reusing an event ID with different content is rejected.

An enrollment is the historical eligible denominator, including non-selectors. Assessment completeness is a separate assertion and ledger needed for eligibility. Do not send only selected customers. Selection timestamps must lie within each enrollment's follow-up window to count. A watermark must state what has actually been reconciled; it is not a replacement for missing events. Exposure never substitutes for enrollment.

Forecast features must exist before the eligibility decision; neither future outcome labels nor post-decision customer changes may be used as forecast inputs. An operational adapter is responsible for pseudonymization and accurate source semantics. The native demo journal remains independently labeled.

## Calibration and prospective freezing

Training: `POST /calibrations` with `{product, policyContext}`. A returned EVALUATED_PASS model has a model ID, dataset hash and independent validation/test metrics. It is not active until:

```json
{
  "modelId": "REVIEWED_MODEL_ID",
  "datasetHash": "EXACT_DATASET_HASH",
  "confirm": true,
  "note": "Reviewed the independent validation and test results"
}
```

Preview a cohort through `POST /calibrations/forecast` with `{product, policyContext, probabilities:[0.2,0.8]}`. This preview alone is not a saved publication prediction.

To save a comparable prediction for a new publication, first ingest prospective FORECAST_FEATURE events from the configured real source. Then call `POST /publications/ID/forecasts` with `{product, policyContext}` before any real ENROLLMENT exists. The service freezes the active model, feature hash, exact prospective cohort hash and estimate. It rejects later changes and retrospective creation. Later performance in OBSERVED_REAL mode uses this saved prediction while retaining the original simulation prediction.

Cohort/target/source mismatch, late forecast creation, missing coverage and provisional maturity suppress the quantitative forecast-error comparison. Activating a different model affects future forecasts only.

## A2A and MCP boundaries

Every role exposes an authenticated `/.well-known/agent-card.json` and `POST /a2a`. Agent Cards and task/message objects are encoded and decoded using the pinned official SDK. The service supports the A2A 1.0 SendMessage, GetTask and CancelTask JSON-RPC operations; cards do not advertise streaming. The orchestrator credential is required for delegation.

Inside a message data part, Request contains schemaVersion, requestId, workflowId, stepId, planRevision, deadline and payload. Result contains the correlated IDs, role kind, Decision, bounded tool observations, modelId, promptVersion and producedAt. Decision contains status (READY, REVISE, BLOCKED or TOOL), concise summary, a null compatibility clarification field, allowedParameterIds, lockedStages, ranges, typed claims and optional toolCall. Every delegation includes executionContext with current budget reservations, remaining limits, floors and application safeguards. Legacy NEEDS_INPUT decisions are converted to internal revisions; new A2A tasks never emit TASK_STATE_INPUT_REQUIRED. See `Contracts.java` for the executable strict JSON schema.

A2A task IDs are local to their receiving role. The orchestrator records their association with application step/request IDs. Model and tool calls have local audit records; private reasoning is not requested as output or persisted. Internal budget/usage endpoints accept registered service roles and verify their exact delegated workflow revision and step.

MCP connections are read-only, configured by operators and restricted by agent role. The code never discovers an arbitrary server from user text or promotes tool output into instructions.
