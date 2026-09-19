<!-- Explains the marketing offer repository and the independent customer and enterprise offer copies. -->
# Step 6: offer storage and enterprise copies

Step 6 extends the independent Java marketing service's PostgreSQL store. It keeps original qualified catalog offers (`PRE_SCREEN`) and personalized Step 5 offers (`MARKETING`) together under the existing decision-response ID. Each change creates an immutable storage version and a Kafka outbox event in the same database transaction. Financial calculations and eligibility continue to use the existing Java application services and versioned COBOL policies.

## End-to-end workflow

1. Finalize a Step 2 marketing run. A bounded source API supplies qualified customer/campaign/offer matches to the marketing service. They enter storage as **AWAITING_BUREAU**, hidden from customer views. Preview matches are excluded. Cancelled or expired matches become hidden **WITHDRAWN** records.
2. Process Step 3 and Step 4. Approved bureau decisions with valid Step 2 qualifications feed Step 5. Step 6 records **AWAITING_CREATION** until Step 5 activates the original offer. Declined, review, failed and revoked responses remain hidden audit records.
3. Step 5 creates the original and, when a model and exact variant approval are available, the personalized alternative. Storage retains both sets of terms, expiry, comparison metrics and qualification references. Personalized alternatives retain their variant qualification ID. A withdrawn alternative stays in history.
4. Offer revisions and customer selections append full-family events to `offers.storage.updated.v1`. Kafka's key is the **offer family ID**, not Risk ID. The event contains its own event ID, schema version, global recovery sequence and per-family storage version. Storage version is independent of Step 4 response version.
5. Two independent consumers load the customer-data read model and the enterprise warehouse. Each uses its own schema, Kafka group, deduplication table and HTTP recovery cursor. They communicate through Kafka and the marketing service's API, without reading its database tables.
6. Step 1 customer profiles include an `offerView`. The customer offer API checks current Step 2/bureau eligibility and exact variant approvals before returning copied offers. Selecting an original or personalized offer uses the existing Step 5 selection API. Selection history and the currently available choice propagate automatically to both copies.

## Running and terminal navigation

No new language runtime, application process, external account or database is needed. Use the existing PostgreSQL database and local Kafka, then rebuild and restart:

```powershell
.\scripts\infrastructure.ps1
.\scripts\test-bureau.ps1
.\scripts\run.ps1
```

Open `scripts\terminal.ps1` in another PowerShell window. Main menu **10** opens offer storage. Select **1** for authoritative storage, **2** for enterprise copies/history, **3** for a customer's current offers, or **4** for synchronization health. From a Step 1 customer detail screen, **F6** opens that customer's offers. **O** selects the original; **P** selects the personalized alternative. Admin detail **H** opens version history; **F7/F8** page through lists/history.

The interface is the existing real TN3270 terminal backed by Java APIs and COBOL rules. It does not install or emulate IBM CICS/RACF. The local operator credential permits customer impersonation and admin access; production customer identity and authorization remain separate work.

The normal launcher still supervises three JVMs: engine `8090`, credit service `8091`, marketing service `8092`. The two copy consumers run inside the engine JVM but own independent pools, schemas and Kafka groups. An outage of one copy does not advance the other copy's cursor. They can be separated into deployment units later through the same event/API contracts.

## Storage ownership and migration

| Schema | Owner and contents |
|---|---|
| `marketing_offers` | Marketing service: existing Step 5 offers/models/selections plus `storage_current`, `storage_events`, `storage_seen` |
| `customer_offer_read` | Customer-data loader: current offer copies, immutable events, recovery cursor and rejected-event fingerprints |
| `enterprise_offer_warehouse` | Enterprise loader: independent current copies and complete received revision history |

All three use the existing private `DATABASE_URL` in `runtime/local.env`. No credentials are embedded in contracts or examples. Existing H2 customer/Step 2 data and public-schema bureau/response data stay intact.

Marketing migration `V002__offer_storage.sql` is additive. Startup replays existing Step 5 offer and selection history, reconciles the final authoritative offer state and latest qualification responses, then records the migration checksum in one transaction. Original Step 5 tables are preserved. Old qualification references that were not captured in historical Step 5 data remain null; the migration does not invent historical bureau decisions. Copy migrations run separately in each new schema. Do not delete `runtime` or reset database volumes to upgrade.

For isolated deployments/tests, JVM properties are `storage.customer.schema`, `storage.warehouse.schema` (engine), and `storage.kafka.topic` (both engine and marketing). Kafka consumer groups are `<copy-schema>-storage-v1`. The default topic has 12 partitions and replication factor 1 for this local installation. Existing Step 5 schema/port/group options remain available.

## Contracts and APIs

See [OpenAPI](../contracts/offer-storage.openapi.yaml), [event JSON Schema](../contracts/offer-storage.schema.json), and [AsyncAPI](../contracts/offer-storage.asyncapi.yaml). All routes below require the existing operator token at engine port 8090.

| GET path under `/api/v1` | Result |
|---|---|
| `/offer-creation/storage/offers?after=&limit=20` | Admin current storage snapshots, including hidden records |
| `/offer-creation/storage/offers/{id}` | Latest authoritative stored event |
| `/offer-creation/storage/offers/{id}/history?after=0` | Next 50 revisions after a storage version |
| `/offer-creation/storage/events?after=0&limit=100` | Ordered events after a global recovery sequence |
| `/offer-creation/storage/health` | Scanner, publisher, pending events and counts |
| `/customers/{id}/offers?after=&limit=20` | Customer-data copies filtered by current eligibility |
| `/customers/{id}/offers/{offerId}` | One currently eligible copied family and selection |
| `/warehouse/offers?after=&limit=20` | Enterprise current copies, including withdrawn records |
| `/warehouse/offers/{id}/history?after=0` | Enterprise revision history |
| `/offer-copies/health` | Independent Kafka/HTTP states, history counts and recovery cursors |

Direct marketing storage routes remove `/offer-creation`, use port 8092 and the marketing API token. The scoped engine source route `/offer-source/prescreen` pages Step 2 matches using an opaque cursor. The scanner restarts from the beginning after reaching the end to discover newly finalized runs and changed eligibility.

Follow `nextAfter` even on an empty customer page: current eligibility may remove every stored record on that page. A customer profile includes only a small initial offer page; F6 opens the full paginated view. If dependencies are unavailable, profile CRUD still succeeds with `offerView.status=UNAVAILABLE`; the dedicated offer API reports the failure rather than asserting that no offers exist.

## Delivery, privacy and consistency

Delivery is **at least once**. Each destination atomically records the event and advances current state only for a higher storage version. Duplicate deliveries do not duplicate history. Older events can fill missing history without restoring old eligibility. Conflicting identities/versions and unknown/private fields are rejected. Kafka offsets advance only after a durable receive or quarantine; quarantined records contain a fingerprint and fixed reason, not the rejected payload.

Every append holds a short schema-level transaction lock through commit, giving the HTTP journal a safe global sequence order. HTTP catch-up pages 100 committed events and persists its cursor only after the whole page is stored. This recovers missed Kafka messages, including messages lost to retention; repeated pages are harmless. Unsupported or conflicting HTTP events pause recovery for investigation. Kafka delivery failures retain the pending outbox row and retry automatically.

Copies are **eventually consistent**: new offers and changed selections can briefly lag. Customer reads recheck eligibility against the local authoritative engines even before a revocation event reaches the copy. Expired personalized approvals hide only that alternative when the original still qualifies. Each selection carries its original qualification response version; a new qualification requires a new selection even when terms match. Unavailable authority fails closed. Warehouse/admin history can show an earlier state while delivery is pending and never grants customer eligibility. As in Step 5, changes racing after the final check require a future transactional acceptance/funding operation; a demo selection creates no loan or campaign message.

Published copies include customer IDs, catalog/campaign versions, qualification references, exact terms, model/cohort IDs, numeric evaluation metrics, selection state and visibility/expiry. They exclude customer names, external references, customer prose, raw profiles and raw bureau observations. The strict consumer allowlist is checked before either copy database receives an event. Acceptance metrics remain simulated, not measured customer outcomes.

## Capacity and verification

The implementation uses bounded pages, pooled PostgreSQL connections, Kafka partitions, persisted cursors, immutable versions and restart-safe outboxes. No million-customer throughput is claimed. The local Step 1/2 H2 source is serialized; the repeated pre-screen scan and global storage append lock are explicit scaling limits. Production capacity needs load measurements, source change feeds, partitioned append/recovery design, retention/archiving, replicated Kafka and separate database credentials. No existing history or volumes are automatically pruned.

Run `scripts/test-bureau.ps1` for the complete COBOL/Java/PostgreSQL/Kafka/HTTP/3270 suite. New cases cover hidden pre-screen storage, privacy, duplicates/out-of-order delivery, selections, variant withdrawal, transaction rollback, existing-data upgrade and independent copy recovery. `scripts/smoke-offers.sh` checks the packaged three-process launcher and both copy consumers while preserving application data.
