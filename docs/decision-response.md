<!-- Documents current marketing eligibility, versioned bureau responses and downstream event delivery. -->
# Step 4: decision response

Step 4 turns durable bureau results into a versioned qualification contract for the future marketing microservice. It publishes decisions and eligibility; it does not create personalized offers, write Offer Storage, or send marketing messages. All processing remains local, with Java services, the existing COBOL decision policies, PostgreSQL and Kafka.

## Workflow

1. Step 3 finishes a request admitted from a finalized Step 2 qualification. Its result remains durable even if Step 4 or Kafka is down.
2. The response worker reads unprojected results in bounded batches. It reads the frozen qualification/catalog evidence and rechecks current customer, underwriting, consent, suppression, reservation, campaign and offer state.
3. The versioned `DRS-2026-001` domain policy requires **APPROVED bureau decision AND valid Step 2 qualification**. `DECLINED`, `REVIEW`, `FAILED`, `INVALIDATED`, `REVOKED` and `EXPIRED` never authorize offer creation. A technical failure is not a credit decline.
4. A PostgreSQL transaction writes the response, immutable revision, both publication records, and the projection checkpoint together. A failed transaction leaves the original result available for retry.
5. The existing Kafka publisher delivers these records and records broker acknowledgments. Publication is at least once; consumers must deduplicate stable event IDs and compare response versions.
6. An automatic reconciler checks active responses, publishing a new revision when eligibility is lost. Current-response and customer-offer APIs also revalidate synchronously, so a delayed background check cannot make an expired or revoked offer visible through those routes.

## Identity and authority

There is one response per **Step 2 run / customer / campaign / offer qualification**. Its stable `DR-...` identity derives from the run and qualification ID. The existing Risk ID is retained and used as the Kafka partition key. A new Step 2 run has a new response identity; responses must not be merged solely by customer or Risk ID.

Repeated processing of a bureau request creates no duplicate revision or event. Multiple bureau requests for the same qualification use their database admission sequence: the newest completed request is authoritative, even if an older request finishes later. Every terminal bureau result still gets its own sanitized audit event. A newer technical failure removes the older response's marketing eligibility until a subsequent successful assessment is available. An in-flight request does not itself replace a completed assessment.

Existing Step 3 results are automatically projected after upgrade. Historical requests receive deterministic admission order by their stored creation timestamp and ID. Backfill always checks today's eligibility; it cannot revive expired qualifications. Original results and original Step 3 events remain intact.

## Downstream contract

| Kafka topic | Contents | Intended use |
| --- | --- | --- |
| `bureau.decision.published.v1` | Request/source/customer IDs, processing status and a concise bureau decision with reasons, policy and dates | Decision audit/integration; does not authorize marketing by itself |
| `marketing.qualification.updated.v1` | Complete current response, including bureau summary, Step 2 qualification, frozen catalog offer/version, assessed amount, USD currency, eligibility, reasons and exclusive expiry | Authoritative input contract for future Step 5 |
| `bureau.qualification.completed.v1` | Existing full Step 3 result | Internal operational audit; contains bureau observations and must not be used as the marketing contract |

The marketing event contains everything needed to identify the qualified candidate without joining two Kafka topics. It deliberately excludes raw reports, credit scores, income/debt figures, display names and bureau subject identifiers. `qualifiedOffer` is the immutable Step 2 catalog revision. `assessedAmountUsd` is the exact amount evaluated by the bureau. The catalog's larger maximum is **not** approval for a larger amount. Personalization must stay within that qualified offer; changes to assessed credit amount or terms require reassessment, and a different offer requires its own qualification.

Consumers must:

1. Accept supported `schemaVersion` values; quarantine unsupported or malformed messages in their own consumer infrastructure.
2. Deduplicate `eventId` and retain the greatest `response.version` for each `response.id`. A replay uses the same event ID and payload. Partitions, multiple publishers and replay can produce duplicates or older revisions after newer ones.
3. Treat `marketingEligible=false` as removal of that qualification from offer creation, while retaining its history for audit. Never treat a missing bureau decision as approval.
4. Enforce `now < validUntil`, then call `GET /decision-responses/{id}` before a future side effect. Events are historical observations and broker delivery may lag. API verification is a point-in-time check, not a reservation spanning a future service's transaction; Step 5 must define its final write/concurrency boundary when implemented.
5. Commit consumer offsets only after durable deduplication and state updates. Step 4's `PUBLISHED` status means Kafka acknowledged the event, not that a downstream consumer processed it.

Machine-readable contracts: [decision-response.asyncapi.yaml](../contracts/decision-response.asyncapi.yaml), [decision-response.openapi.yaml](../contracts/decision-response.openapi.yaml), and the standalone [JSON Schema](../contracts/decision-response.schema.json). Keep the OpenAPI response fields and standalone consumer schema synchronized when changing the contract; the JSON Schema uses explicit null alternatives for unavailable decisions. The real Kafka integration test includes a small contract consumer that receives an approval, its replay, a revocation, and a late duplicate approval without restoring eligibility. It is a test fixture, not a Step 5 service.

## Automatic withdrawals and expiry

Reconciliation detects withdrawn consent/prescreen opt-out, new profile or underwriting versions, global suppression, cancelled Step 2 runs, changed/deactivated campaigns or offers, released/expired reservations, and expired underwriting/bureau decisions. The response deadline is the earliest applicable expiry. Step 1 and catalog inclusive dates become the following UTC midnight; reservation and bureau timestamps remain exclusive instants.

Revoked responses are not automatically revived. A fresh Step 2 qualification is required after source invalidation. Suppression history is checked against the qualification's recorded version, so adding and then removing a suppression while the engine is stopped still invalidates the old qualification. Clearing suppression does not authorize replay of the previous approval. A subsequent bureau assessment may replace an earlier bureau outcome only while its Step 2 source remains valid.

No cross-database transaction is claimed. The existing single-process H2 source lock is held while writing a response, preventing concurrent Step 1/2 edits during that check. Immutable source revisions allow reconciliation to recover after outages. Revocation events are eventual; consumers also enforce expiry and use the current API. If either store is unavailable, the API fails rather than presenting an unchecked offer.

## Terminal and API

Rebuild and restart using the existing scripts; there are no additional packages to install for Step 4:

```powershell
.\scripts\infrastructure.ps1
.\scripts\build.ps1
.\scripts\run.ps1
# In another PowerShell window:
.\scripts\terminal.ps1
```

Select **9 → 6: Decision responses / customer eligible offers**:

- **1 Operator audit**: inspect current status, bureau summary, qualified catalog version, reasons and expiry. `H` opens revision/delivery history; `R` revalidates and queues the latest event for replay.
- **2 Customer view**: enter a saved customer ID. Only currently eligible catalog candidates appear. Enter checks again; F7/F8 page. These are qualifications, not personalized offers.
- **3 Status**: inspect projection errors, unprojected results, active projection count and pending Kafka publications.

All terminal actions use the authenticated local API. This is still an operator simulation with a shared local token, not public customer authentication. The catalog management and audit screens intentionally retain ineligible/history data; the customer view uses only the filtered route.

| Endpoint (under `/api/v1`) | Behavior |
| --- | --- |
| `GET /decision-responses?after=...&limit=20` | Revalidated operator page, including ineligible responses |
| `GET /decision-responses/{id}` | Current response; incorporates a newer completed bureau request even before the background projector reaches it |
| `GET /decision-responses/{id}/history?after=0&limit=20` | Immutable revisions and current publication metadata; `after` is an exclusive version cursor |
| `POST /decision-responses/{id}/replay` | Revalidate, then queue the latest revision with its original event ID |
| `GET /customers/{id}/eligible-offers?after=...&limit=20` | Only freshly verified eligible candidates for this customer |
| `GET /decision-responses/health` | Worker states and backlog counts |

ID pages return `items` and `nextAfter`. Follow a non-null cursor even if the current page is empty: candidates may have been revoked during the read. Limits are 1–100. The health count of eligible responses is stored state and can lag; only the current/eligible routes verify visibility.

## Persistence, operation and limits

The additive PostgreSQL migration `db/bureau/V002__decision_response.sql` adds response projection columns, indexed current responses, append-only revisions and outbox delivery metadata. Both migrations are checksum verified. No existing application data or H2 migration is rewritten. A projection error backs off and remains visible in health; retry does not consume the original bureau result. A Kafka outage leaves events queued. Replay is available for qualification revisions, and normal broker offset replay is available to authorized future consumers for either topic.

The default worker batch is 100, polled every 250 ms; active responses become due for another check after 5 seconds or at expiry, whichever is earlier. These are scheduling intervals, not delivery latency guarantees under backlog or outage. JVM settings `response.batch.size` (1–1000), `response.poll.ms` (50–60000) and `response.check.seconds` (1–3600) allow tuning. Reads are bounded and indexed; failed checks are deferred so one bad source does not permanently occupy the front of the due queue.

This change does not establish million-customer throughput. The H2 source remains single-process, the demo Kafka broker has replication factor 1, the publisher waits for one acknowledgment at a time, and eligible-response reconciliation consumes source reads. A production deployment needs measured load tests, partitioned/change-driven source ingestion, retention/archiving, replicated Kafka, service identities and an offer-write concurrency design. No history pruning or customer data export is introduced here.

Run `scripts/test-bureau.ps1` for the full regression and real infrastructure suite. Tests create/drop their own PostgreSQL schemas and Kafka topics, exercising actual COBOL, HTTP and s3270 without editing application customer records.

## BIAN reference boundary

BIAN's [Customer Credit Rating v14](https://raw.githubusercontent.com/bian-official/public/main/release14.0.0/semantic-apis/oas3%20/yamls/CustomerCreditRating.yaml) models customer credit assessment with internal and external reporting. [Customer Offer v14](https://raw.githubusercontent.com/bian-official/public/main/release14.0.0/semantic-apis/oas3%20/yamls/CustomerOffer.yaml) describes the downstream offer domain. These inform the separation between a credit decision, marketing qualification and eventual offer creation. The exact event names, Kafka envelope, approval gate and lifecycle rules here are this project's demonstration contract, not a BIAN certification or mandatory BIAN underwriting standard.

`response/domain/DecisionResponse.java` owns versioned eligibility projection; it combines the existing COBOL results and source validity without duplicating credit thresholds. `response/application` orchestrates source checks and recovery, `response/infrastructure` owns SQL/outbox mechanics, `response/api` owns transport, and `ResponseTerminal` only presents API data. The existing `LIUW01C`, `LIMK01C` and `LICB01C` remain the underwriting, marketing and bureau decision programs.
