<!-- Explains bureau admission, independent credit decisions, batch and event interfaces, and the local runtime setup. -->
# Step 3: Bureau Qualification

This is a local demonstration for US personal loans, credit cards and auto loans in USD. It runs two Java engines with a real HTTP boundary. Fixed-format COBOL owns the credit policy. No commercial bureau is contacted, no credit inquiry is made, and no loan or marketing message is issued.

## Installed local runtime

This Windows machine already has PostgreSQL 18 and Docker Desktop. Ubuntu-22.04 in WSL supplies Java 17, Maven, GnuCOBOL, x3270 and s3270. Kafka 4.3.1 is supplied by this project's Docker Compose file; it does not need a separate Windows installation. Start Docker Desktop before starting the infrastructure.

From the project directory in PowerShell:

```powershell
.\scripts\infrastructure.ps1
.\scripts\build.ps1
.\scripts\run.ps1
```

Keep the last command running. In another PowerShell window:

```powershell
.\scripts\terminal.ps1
```

Select **9 — Bureau qualification / credit decision**. Ctrl+C in the server window stops the two Java processes and their COBOL workers. Kafka and the relay remain running; `scripts/infrastructure.ps1 -Stop` stops them while retaining Kafka's volume. Never use `docker compose down -v` to perform an ordinary restart.

The connection supplied for this machine is saved in **runtime/local.env**, which is ignored by version control. It is never embedded in source. The local relay translates WSL `127.0.0.1:15432` to the existing Windows PostgreSQL listener on port 5432 using Docker's `host.docker.internal`. This does not change PostgreSQL's access rules or start a second PostgreSQL server. The application uses the existing `lending_intelligence_engine` database.

For a new machine, create `runtime/local.env` with a URL-encoded PostgreSQL username/password and the correct local address:

```bash
DATABASE_URL='postgresql://USER:URL_ENCODED_PASSWORD@127.0.0.1:15432/lending_intelligence_engine'
KAFKA_BOOTSTRAP_SERVERS='127.0.0.1:9092'
```

The relay is needed for the verified Windows/WSL arrangement. A PostgreSQL server running directly in WSL can instead be addressed on its own local port. The Java services listen on WSL loopback: customer/marketing/bureau orchestration **8090**, credit bureau **8091**, and TN3270 **2323**. Kafka and the relay publish only Windows loopback ports **9092** and **15432**. The credit-service credential is distinct from the frontend API credential. They live in `runtime/bureau-api-token` and `runtime/api-token`.

## End-to-end workflow

1. Complete Step 1 and create a pre-screen population.
2. Preview and **finalize** a Step 2 marketing run. A preview or cancelled run cannot be submitted.
3. In terminal menu 9, choose a finalized run. Select an individual qualification, or enter **B** to submit the entire run as a durable batch. Excluded Step 2 rows do not enter the batch.
4. For an individual qualification, **P** opens its bureau profile and **Q** submits it. Missing profiles are generated independently of customer-entered credit values. You can inspect or edit them before requesting a decision.
5. The Bureau Decision Engine checks the current customer, underwriting decision, consent, suppression, campaign/offer version and reservation. It preserves the existing Risk ID, maps the customer to an opaque bureau subject, and invokes the Credit Bureau Decision Engine over HTTP.
6. The credit service loads the latest bureau-profile version and calls a persistent COBOL worker. It saves an immutable assessment containing the exact input, report, source, version, policy, reasons and expiration.
7. Before recording completion, the integration engine checks Step 2 again. Cancellation, profile changes, changed catalog terms, suppression or expiry invalidate the request. The result and its Kafka outbox event commit together in PostgreSQL.
8. Review **APPROVED**, **DECLINED** or **REVIEW** in the terminal. Queue statuses such as **RETRY**, **FAILED** and **INVALIDATED** describe processing or source validity, not a credit opinion. Enter refreshes a request/batch. Finished assessments remain historical; editing a bureau profile does not silently rewrite them. Submit a new request to assess the new version.

No Step 4 downstream consumer is implemented yet. Step 3 publishes a completion contract ready for that step.

## Data ownership and rules

Customer Data owns requested amount, gross monthly income/provenance and self-reported collateral information. Marketing owns the qualified product, offer terms and Risk ID. The synthetic bureau owns its score, debt payments, utilization, delinquency count, hard-inquiry count, oldest-account age, bankruptcy indication, report date and file status. An opaque customer-to-subject mapping replaces real bureau identity matching in this demo; no SSN is required. A genuine provider integration must replace this adapter with its approved identity-match protocol.

The initial synthetic report is reproducible from the customer ID and generator version `SYNTHETIC-BUREAU-2026-001`; customer-entered scores are never copied into it. Its observation timestamp is the generation time. Imported and edited reports carry **LOCAL_IMPORT** or **LOCAL_EDIT**. These labels indicate local provenance, not independently verified bureau data. Null facts remain unknown. All edits require `expectedVersion`; concurrent stale edits return 409. Every prior version is retained and history is paged.

`app/cbl/LICB01C.cbl` implements demonstration policy **CBR-2026-001**:

| Rule | Personal loan | Credit card | Auto loan |
|---|---:|---:|---:|
| Minimum bureau score | 680 | 660 | 640 |
| Maximum total debt-to-income | 45% | 45% | 50% |
| Maximum requested amount | $50,000 | $25,000 | $100,000 |
| Proposed monthly payment | Amortized installment | 3% of requested limit | Amortized installment |

Common limits: utilization at most 90%, delinquencies at most 2, inquiries at most 6 and no reported bankruptcy. Auto loan-to-value must be at most 110%. Exactly meeting a limit passes it. Installment payment uses the qualified offer's APR and term; zero-APR loans use amount/term. DTI includes bureau-reported existing monthly debt plus the proposed payment, divided by customer-supplied income. Income is not independently verified by this simulation.

Missing required facts, an unmatched/frozen/ambiguous file, a report older than 30 whole days or an oldest account younger than 6 months returns **REVIEW**. A future report timestamp is rejected at import. Successful policy execution returns an assessment valid for 7 days; the source is also revalidated before completion. Assessment expiration does not extend the underlying marketing reservation. Downstream systems must recheck current permissions and validity before using historical results.

Thresholds are demonstration choices, **not BIAN requirements or actual bank underwriting policy**. BIAN 14's [Customer Credit Rating](https://raw.githubusercontent.com/bian-official/public/main/release14.0.0/semantic-apis/oas3%20/yamls/CustomerCreditRating.yaml) separates bank credit assessment and external reporting. [Underwriting](https://raw.githubusercontent.com/bian-official/public/main/release14.0.0/semantic-apis/oas3%20/yamls/Underwriting.yaml) describes product underwriting. Those domains inform the report/assessment boundaries and vocabulary here; these APIs do not claim full BIAN conformance. `CreditRiskOperations` concerns a different trading/counterparty context and is not used as a retail-loan threshold source.

The COBOL policy has no HTTP, Kafka, SQL or terminal operations. `LICBAT01` is its stream adapter, with `LICBREQ`/`LICBRES` copybooks. Java owns HTTP transport, persistence, task scheduling and protocol validation. The persistent process pool avoids launching one operating-system process per credit decision. No Java policy fallback is used when COBOL is unavailable.

## Real-time and file interfaces

The public API uses `Authorization: Bearer <runtime/api-token>` and JSON. Real-time admission returns **202** with a durable request ID; poll its resource for the decision. This avoids keeping HTTP connections open during a bureau outage. Requests are checked before acceptance and again by the worker.

```http
POST /api/v1/bureau/requests
{"requestId":"CLIENT-REQUEST-001","source":{"runId":"FINALIZED-RUN-ID","riskId":"STEP2-RISK-ID","qualificationId":"QUALIFICATION-ID"}}

GET /api/v1/bureau/requests/CLIENT-REQUEST-001

POST /api/v1/bureau/batches
{"requestId":"BATCH-001","runIds":["FINALIZED-RUN-ID"]}

GET /api/v1/bureau/batches/BATCH-001
GET /api/v1/bureau/batches/BATCH-001/items?after=&limit=20
```

Use `GET /bureau/sources/{runId}?after=-1&limit=100` to traverse source rows by ordinal. General request/batch lists and batch items use an exclusive request-ID `after` cursor. Copy the last displayed ID to fetch the next page. Bureau source pages are at most 500 rows; result pages at most 100. These interfaces do not load an entire million-row batch into memory.

File mode streams **one JSON object per line**, with at most 1 MiB per line. Each batch line lists up to 1,000 finalized run IDs. Split larger submissions into multiple lines with stable, distinct request IDs:

```powershell
.\scripts\run.ps1 -BureauManifest C:\path\bureau-batches.ndjson
.\scripts\run.ps1 -BureauProfiles C:\path\bureau-profile-imports.ndjson
```

Examples are in `app/data/bureau-batch.example.ndjson` and `app/data/bureau-profile-import.example.ndjson`. Replace placeholder IDs and report timestamps. Use `expectedVersion: 0` only when no report exists; generating a report creates version 1. A failed file stops at the failing line and reports it. Earlier successful lines remain committed. Batch files can be replayed unchanged because request IDs are idempotent. Profile imports deliberately use optimistic version checks; review/update the failed line rather than blindly replaying successful profile edits.

An idempotency key belongs to one immutable request. Reusing it with different source/input produces 409. A repeated completed request returns its original result even if the source subsequently changes. Use a new key for a deliberate reassessment; acceptance then requires a still-current Step 2 qualification.

The public API contract is `contracts/bureau.openapi.yaml`; the separate internal service contract is `contracts/credit.openapi.yaml`. Event shapes are documented in `contracts/bureau.asyncapi.yaml`.

## Kafka and recovery

The local Kafka broker uses KRaft, 12 partitions per application topic and one replica. [Apache Kafka's official quickstart](https://kafka.apache.org/quickstart/) documents the Docker runtime. This is a single-machine development topology.

| Topic | Direction | Message |
|---|---|---|
| `bureau.qualification.requested.v1` | Input | `{eventId, request: {requestId, source: {runId, riskId, qualificationId}}}` |
| `bureau.qualification.completed.v1` | Output | `{eventId, type, result}` with the persisted response |
| `bureau.qualification.rejected.v1` | Output | `{eventId, reason}` for malformed/invalid admission; raw customer payload is omitted |

Use the Risk ID as the input record key. Results use the original Risk ID as their key. Kafka consumers must deduplicate output by `eventId`; the delivery guarantee is **at least once**, not exactly once across Kafka and PostgreSQL. Completion order for separate requests on one Risk ID is not guaranteed; each carries its own request and source references.

The inbox, work item and offset protocol prevents acknowledging an event before its durable admission. Offsets commit per processed record. Invalid events are persisted for dead-letter publication before acknowledgement. A conflicting reused event ID is rejected. A broker outage leaves outbox records pending. A database outage prevents offset acknowledgement. Restart resumes the work queue, batch cursors and outbox.

PostgreSQL workers claim rows using `FOR UPDATE SKIP LOCKED`, a pattern supported for queue-like tables in the [PostgreSQL SELECT documentation](https://www.postgresql.org/docs/current/sql-select.html). Leases last 45 seconds and carry unique fencing tokens. Reclaimed work rejects late completion by the prior worker. HTTP calls have a 15-second deadline. Technical failures back off from 2 to 60 seconds; after 8 worker attempts the next claim records **FAILED**. The remote assessment key is stable across retries, so a lost response does not create a second credit assessment.

Batch expansion saves its run index and ordinal cursor in the same transaction as its inserted work items. Only one worker expands a given chunk at a time. A cancelled run encountered during expansion stops that batch with an error; previously expanded items are still independently checked. A batch reports **COMPLETED_WITH_ERRORS** if source or technical failures occurred. Approved/declined/review outcomes all count as successfully processed credit decisions.

## Capacity and deployment limits

Planning targets are 5 million customers/month, 1 million customers per batch within 8 hours, and 50 real-time admissions/second. These are **targets, not certified capacity**. One customer may produce multiple qualified-offer requests; benchmark rates count qualifications, not unique customers. A million customers with two offers requires processing two million requests.

Step 3 has durable PostgreSQL work storage, indexed claims, bounded page sizes, bounded connection/process pools, idempotent retries and Kafka partitions. The initial main process uses 8 integration workers, the separate credit process 4 persistent COBOL workers and each database pool at most 12 connections. Advanced launches can set Java properties `bureau.workers` (1–64), `credit.workers`, and `bureau.db.pool`. Increasing concurrency must be benchmarked against the actual database and provider.

**Steps 1 and 2 still use their existing single-owner H2 database.** Keeping that database preserves all current customer/marketing records without an implicit migration. Its 10,000-customer generator and 50,000-customer snapshot bounds remain. Large Step 3 manifests can reference many finalized runs; this is not a claim that the earlier steps now accept a million-customer snapshot. The current launcher runs one source/orchestration process; starting a second copy against the same H2 file is not a supported scaling method. Before distributed production deployment, move the source store and atomic source checks to a shared transactional service/database and validate the cross-service version protocol. The source port and PostgreSQL fenced queue are the replacement boundaries.

Also required for production: a genuine bureau adapter and access agreement, approved credit policy and model governance, authentication/authorization and audit roles, transport encryption, multi-broker replication, backups, retention/partitioning, observability, distributed-load/failure tests and selected mainframe hosting. The current runtime executes COBOL and real TN3270 but is not IBM CICS/z/OS/RACF/VSAM.

## Verification

```powershell
.\scripts\test-bureau.ps1
.\scripts\test-launchers.ps1
```

The bureau test command loads the ignored local connection, creates unique `bureau_test_*` schemas, and removes only those schemas afterward. Kafka tests use unique topic names and remove only their own topics. Application data and application-topic offsets are preserved. Tests cover actual COBOL, HTTP, PostgreSQL, Kafka and s3270, including source invalidation, idempotency, retries, expired leases, optimistic edits, malformed events and restarted batch expansion. A 1,000-qualification run on this machine completed the HTTP/COBOL/PostgreSQL processing portion in 33.51 seconds (29.8 qualifications/second). Source generation, Step 2 preparation, batch expansion and Kafka drain were outside that timed interval; Kafka delivery is verified separately. This does not establish the 50 admissions/second target or the million-customer batch target. The batch test prints measured local throughput. Ordinary `build.ps1` runs offline/embedded tests and skips the explicitly opt-in PostgreSQL/Kafka suite unless `BUREAU_TEST_DATABASE_URL` is set.

Health: authenticated `GET /api/v1/bureau/health` reports database availability, pending outbox count and Kafka connection state. Credit startup diagnostics are in `runtime/credit-service.log`. If Step 3 is absent, check `runtime/local.env`; the original Step 1/2 profile can still run without that file.
