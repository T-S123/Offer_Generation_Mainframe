<!-- Guides readers through the connected architecture drawing, qualification payload and implementation source references. -->
# Lending Intelligence Engine architecture

Open [lending-intelligence-engine.excalidraw](lending-intelligence-engine.excalidraw) in Excalidraw. It contains eight connected Customer sections and an additional Business Simulation section, traced against the local source on 18 September 2026.

The editable artifact follows `rao.excalidraw`: Virgil text, 400 × 270 rounded flow boxes, 22-point node text, 36-point section titles, and unboxed 18-point connector annotations. Green means UI actions; yellow means backend processing; red means databases, Kafka or a separate local service. Red does not imply a remote cloud service. Dashed connectors identify cross-step handoffs, recovery, model reuse or publication. The original reference file is unchanged.

## Reading the file

Read across a section's first row, then follow the arrows onto the next row. Detail cards beneath the flow explain its rules, inputs and outputs. Step 5 also connects its training cards and shows the saved model feeding per-customer inference. The Business publication arrow returns to the active catalog in Step 2.

| Section | What it explains |
| --- | --- |
| Step 1 | Customer form, input validation, COBOL underwriting, versioned profiles, decision imports and automatic pre-screening |
| Step 2 | Current-source checks, campaign/offer eligibility, global suppression, reservation throttles, finalization and Risk ID |
| Step 3 | HTTP/batch/Kafka admission, leased bureau work, independent report data, credit API and source revalidation |
| Step 4 | Eligibility projection, response fields, outbox publication, expiry and automatic revocation |
| Step 5 | Local K-means training, feature transformations, inference, fit scoring, candidate search and exact-term requalification |
| Step 6 | Hidden pre-screen storage, active alternatives, storage events, independent customer/warehouse copies and HTTP recovery |
| Step 7 | Candidate assembly, latest valid selection, final suppression/throttling, allocation accounting and CSV/EBCDIC files |
| Step 8 | TN3270-to-HTTP boundary, current visibility, comparison, explicit confirmation, selection receipt and file access |
| Business Simulation | Isolated populations, versioned U/M/B rules, matched experiments, statistics and explicit catalog publication |

## Qualification response example

[qualification-response.example.json](qualification-response.example.json) is a complete illustrative Step 4 Kafka message for a legacy catalog offer. Its IDs and timestamps are synthetic examples, not a captured production or local customer record. It is not an import fixture with resolvable application IDs.

- Topic: `marketing.qualification.updated.v1`; Kafka key: `response.source.riskId`.
- Envelope: `eventId`, `schemaVersion`, `type`, `occurredAt`, `response`.
- `response.qualification` identifies the exact customer/campaign/offer qualification, source decisions and policy versions.
- `response.qualifiedOffer` embeds the approved catalog snapshot. For Business-published offers it additionally includes `rules`: `id`, `version`, `createdAt`, `underwriting`, `marketing`, and `bureau`.
- `assessedAmountUsd` is the actual assessed amount. A larger catalog maximum does not authorize a larger customer loan.
- `bureauDecision` is a summary. Raw reports, customer prose and self-reported profile fields are not in this event.
- `marketingEligible` is true only when bureau approval and current Step 2 qualification both hold. Other statuses are `DECLINED`, `REVIEW`, `FAILED`, `INVALIDATED`, `REVOKED`, and `EXPIRED`.
- `validUntil` is an exclusive UTC deadline, shortened by the earliest applicable source deadline. In the example, the reservation ends before the bureau assessment.

The separate `bureau.decision.published.v1` audit event contains `eventId`, `schemaVersion`, `type`, `occurredAt`, `requestId`, `source`, `customerId`, `processingStatus`, `bureauDecision`, and `failure`. Its type is `BUREAU_DECISION_PUBLISHED`. Step 5 consumes the qualification event, not the full internal Step 3 completion record.

Exact definitions: [event JSON schema](../../contracts/decision-response.schema.json), [OpenAPI](../../contracts/decision-response.openapi.yaml), [Business rule schema](../../contracts/business.rules.schema.json), and [Java response records](../../services/src/main/java/com/lending/engine/response/domain/DecisionResponse.java).

## The two throttling stages

| Control | Step 2: qualification reservations | Step 7: outbound allocations |
| --- | --- | --- |
| Default cooldown | 7 days, customer-wide | 86,400 seconds, customer-wide |
| Default rolling cap | 3 reservations in 30 days | 3 new package allocations in 30 days |
| Capacity/lifetime | 1,000 concurrent groups per campaign; reservation up to 7 days | Current package expires with its earliest alternative |
| When checked | After static eligibility, during preview and again before finalization | After current candidate/term checks, before allocating an export |
| Counting unit | One customer/campaign group, potentially several offers | Customer/campaign plus underlying qualification run IDs |
| Retry/update | Finalizing again does not add another reservation | Same-run alternatives or selection changes revise the existing dispatch |
| Cancellation/withdrawal | Cancellation releases reservation frequency/capacity; expiry frees capacity but retains frequency history | Withdrawal does not erase historical allocations; file failure retains allocation for repair |

These are demonstration defaults, not banking standards. The Step 2 policy and campaign records are versioned in H2. Step 7 reads [campaign-execution-policy.json](../../app/data/campaign-execution-policy.json) at startup. Neither counter proves a marketing message was delivered.

## How personalization works

The local Java microservice uses actual fitted K-means for similarity cohorts. It does not call GPT or another LLM. Synthetic data are split 60/20/20; only training data fit the scaler and centroids. Validation chooses K, and held-out test silhouette is reported separately. The artifact retains the seed, feature order, means/scales, centers, cohort preference weights and dataset hash.

For a customer, saved preprocessing assigns the nearest cohort. Individual financial preferences contribute 70% of the fit weights; cohort-average preferences contribute 30%. The search scores affordability, borrowing cost, amount match and term preference consistently for the original and candidate offers. Up to 108 combinations are considered; at most 12 improving candidates are submitted for authoritative requalification. Default search deltas are bounded by [offer-personalization-policy.json](../../app/data/offer-personalization-policy.json).

K-means does not grant credit. Exact proposed terms must pass the COBOL envelope, underwriting and applicable published marketing rules, then an independent credit API assessment. The qualified original remains available when no personalized improvement passes. Acceptance is a sigmoid utility scenario with zero observed response labels; it is not a trained conversion forecast.

Business experiments reuse the same cohort/scoring implementation but keep their populations and models isolated. Publishing creates a new offer, campaign and immutable rules; it does not install the experiment model or reapply the offer to existing customers.

## Source map

Every flow/detail box also carries its source paths in Excalidraw `customData.sourceFiles`. [qa/source-index.json](qa/source-index.json) maps all box labels to those paths.

| Area | Primary implementation and contract |
| --- | --- |
| Customer data | [CustomerEngine](../../services/src/main/java/com/lending/engine/application/CustomerEngine.java), [CustomerRules](../../services/src/main/java/com/lending/engine/domain/CustomerRules.java), [SqlStore](../../services/src/main/java/com/lending/engine/infrastructure/SqlStore.java), [LIUW01C](../../app/cbl/LIUW01C.cbl) |
| Automatic coordination | [PipelineEngine](../../services/src/main/java/com/lending/engine/automation/PipelineEngine.java), [runtime wiring](../../services/src/main/java/com/lending/engine/Main.java) |
| Marketing | [MarketingEngine](../../services/src/main/java/com/lending/engine/marketing/application/MarketingEngine.java), [LIMK01C](../../app/cbl/LIMK01C.cbl), [marketing API](../../contracts/marketing.openapi.yaml) |
| Published rules | [PublishedPolicies](../../services/src/main/java/com/lending/engine/simulation/application/PublishedPolicies.java), [LIRL01C](../../app/cbl/LIRL01C.cbl) |
| Bureau admission | [BureauEngine](../../services/src/main/java/com/lending/engine/bureau/application/BureauEngine.java), [MarketingSource](../../services/src/main/java/com/lending/engine/bureau/application/MarketingSource.java), [BureauQueue](../../services/src/main/java/com/lending/engine/bureau/infrastructure/BureauQueue.java), [batch/event contract](../../contracts/bureau.asyncapi.yaml) |
| Credit assessment | [CreditEngine](../../services/src/main/java/com/lending/engine/credit/CreditEngine.java), [credit records](../../services/src/main/java/com/lending/engine/bureau/domain/Bureau.java), [LICB01C](../../app/cbl/LICB01C.cbl), [credit API](../../contracts/credit.openapi.yaml) |
| Decision response | [ResponseEngine](../../services/src/main/java/com/lending/engine/response/application/ResponseEngine.java), [ResponseRepository](../../services/src/main/java/com/lending/engine/response/infrastructure/ResponseRepository.java), [KafkaTransport](../../services/src/main/java/com/lending/engine/bureau/infrastructure/KafkaTransport.java) |
| Training and scoring | [Cohorts](../../marketing-service/src/main/java/com/lending/offers/domain/Cohorts.java), [Personalizer](../../marketing-service/src/main/java/com/lending/offers/domain/Personalizer.java), [OfferEngine](../../marketing-service/src/main/java/com/lending/offers/application/OfferEngine.java) |
| Variant qualification | [OfferSource](../../services/src/main/java/com/lending/engine/offers/OfferSource.java), [LIOF01C](../../app/cbl/LIOF01C.cbl), [source API](../../contracts/offer-source.openapi.yaml) |
| Offer storage | [StorageEngine](../../marketing-service/src/main/java/com/lending/offers/application/StorageEngine.java), [StorageJournal](../../marketing-service/src/main/java/com/lending/offers/infrastructure/StorageJournal.java), [StoredOffers](../../marketing-service/src/main/java/com/lending/offers/domain/StoredOffers.java) |
| Customer/warehouse copies | [OfferCopyLoader](../../services/src/main/java/com/lending/engine/storage/OfferCopyLoader.java), [OfferCopyStore](../../services/src/main/java/com/lending/engine/storage/OfferCopyStore.java), [StorageViews](../../services/src/main/java/com/lending/engine/storage/StorageViews.java) |
| Campaign execution | [ExecutionEngine](../../services/src/main/java/com/lending/engine/execution/ExecutionEngine.java), [ExecutionStore](../../services/src/main/java/com/lending/engine/execution/ExecutionStore.java), [OutboundFiles](../../services/src/main/java/com/lending/engine/execution/OutboundFiles.java), [LIEX01C](../../app/cbl/LIEX01C.cbl) |
| Terminal | [TerminalServer](../../services/src/main/java/com/lending/engine/terminal/TerminalServer.java), [CustomerTerminal](../../services/src/main/java/com/lending/engine/terminal/CustomerTerminal.java), [ApiClient](../../services/src/main/java/com/lending/engine/terminal/ApiClient.java) |
| Business experiments | [SimulationEngine](../../services/src/main/java/com/lending/engine/simulation/application/SimulationEngine.java), [BusinessAnalysis](../../marketing-service/src/main/java/com/lending/offers/domain/BusinessAnalysis.java), [Business API](../../contracts/business.openapi.yaml) |

## Scope and verification

The diagram documents current code and configured defaults, not a live production deployment or measured capacity claim. It distinguishes H2 source data, PostgreSQL-owned records, independent Java services and local Kafka. It does not present an IBM CICS/z/OS deployment, real bureau integration, actual marketing delivery or production customer authentication as implemented.

The `.excalidraw` file contains editable primitives, not embedded screenshots. Local previews are in `qa/`; [validation.json](qa/validation.json) records structural checks. Generation code and content remain alongside the artifact for maintenance. Application source, runtime databases and the reference drawing were not modified.
