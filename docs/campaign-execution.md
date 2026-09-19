<!-- Documents automatic campaign packaging, outbound suppression checks and recoverable local marketing files. -->
# Step 7: campaign execution and automatic Steps 1–7

Saving a customer now starts the entire local workflow. This is asynchronous processing: saving returns the Step 1 assessments and a durable `QUEUED` status immediately; background workers produce offers and files when qualification completes. There is no manual population, finalization, bureau submission or model-training requirement for this path. An incomplete, opted-out, declined, suppressed or throttled customer does not receive an export.

## End-to-end workflow

1. The API validates a customer form. COBOL assesses the three products. The profile, self-reported credit label, synthetic contacts and automatic-workflow revision are committed together in H2.
2. The coordinator creates a customer-specific eligible population and finalizes a Step 2 run using the existing versioned marketing COBOL rules. It uses active campaigns in priority order. Automatic retry identifiers are stable for that customer assessment revision. Existing manually created runs are preserved. A later customer revision cancels the preceding automatic run and its reservations.
3. Every qualified match is submitted to the durable bureau queue. The independent local bureau can approve, decline or require review. Technical failures have retry/attention states and do not become credit declines.
4. The existing response engine publishes current bureau/marketing eligibility events. Step 2 validity and approved bureau decisions remain mandatory.
5. The independent Java marketing service creates original and personalized alternatives. On a fresh installation it automatically trains a deterministic 1,000-row local synthetic financial-feature cohort model. This bootstrap does not create customers or consume campaign capacity. Completed Step 1 simulation batches of at least 60 customers are subsequently scheduled for training once per batch. Manual training remains available. Acceptance metrics are simulations with zero observed acceptance labels.
6. The marketing offer repository retains original, personalized and selection records and publishes its existing minimized storage events. Customer-data and warehouse copies continue independently. Contacts are not added to that event contract or to warehouse copies.
7. DMG campaign execution follows the ordered repository HTTP event feed with its own durable cursor. For each customer/campaign it requests current candidates through the marketing service API, builds a package, runs final COBOL suppression/frequency checks, journals the result in PostgreSQL, and writes local CSV and EBCDIC files. It waits for pending personalization; a completed search with no qualified improvement can export the original alone.

The coordinator, bureau and execution loops wake about every 100–200 ms when idle. Model training, COBOL, HTTP, queue load and storage publication add latency; this is a near-real-time local workflow, not a synchronous latency guarantee. Large simulations queue behind the same policies and configured campaign capacity.

## Terminal and API use

Rebuild and run using the existing scripts. No additional product installation is needed beyond the existing Java 17/GnuCOBOL/WSL/PostgreSQL/Kafka setup.

```powershell
.\scripts\infrastructure.ps1
.\scripts\build.ps1
.\scripts\run.ps1
# In another PowerShell window:
.\scripts\terminal.ps1
```

On the customer form, set **Marketing opt-in Y**, **Prescreen opt-out N**, and preferred channels. Save, then press Enter to refresh progress. **F6** retains the original/personalized choice screen. **F2** opens campaign execution. Main menu **11** provides package review, suppression/processing reasons, and worker health. Files are verified through the API from the terminal, never read directly by the screen adapter.

Authenticated engine endpoints on port 8090:

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/customers/{id}` | Assessments, synthetic `contact`, durable `pipeline`, existing offer view and current `executionView` |
| `GET /api/v1/customers/{id}/campaign-packages` | Freshly checked customer packages and processing tasks |
| `GET /api/v1/campaign-execution/packages?customerId=&after=&limit=20` | Admin audit list, including withdrawn records; not an eligibility assertion |
| `GET /api/v1/campaign-execution/packages/{id}` | Current package with final authority checks |
| `GET /api/v1/campaign-execution/packages/{id}/history?after=0` | Up to 50 immutable package revisions after a version |
| `GET /api/v1/campaign-execution/packages/{id}/files?format=csv` | Rechecked file as base64 JSON, with version, encoding and SHA-256; `dat` for EBCDIC |
| `GET /api/v1/campaign-execution/tasks?customerId=` | Up to 100 processing states and reasons |
| `GET /api/v1/campaign-execution/health` | Coordinator, execution worker, repository recovery and file publication health |
| `GET /api/v1/campaign-execution/policy` | Loaded demonstration values and content-derived policy version |

The service-side repository endpoint is `GET /api/v1/storage/candidates?customerId=...&campaignId=...` on port 8092, using the existing marketing credential. `GET /api/v1/offer-source/simulation-batches` on port 8090 uses the scoped source credential and returns completed batch IDs/counts/seeds only. HTTP contract: `contracts/campaign-execution.openapi.yaml`.

## Packages, preferences and contact accounting

A stable `PK-...` ID identifies one customer/campaign package; changes create immutable numbered revisions. Every current qualified original/personalized alternative is preserved. The latest valid selection across that campaign leads; otherwise the highest fit score leads, with deterministic response-ID/kind tie breaking. Selection remains bound to the exact response version and terms. Selection propagation is asynchronous; refresh until the package's `selectionId` matches the selected request. Step 5 selection does not itself send anything.

`preferredChannels` is an ordered, unique list drawn from `EMAIL`, `SMS`, `POSTAL`. The first entry is used. An empty list disables file generation; omitted/null preserves an existing preference, defaulting to EMAIL for new customers. Changing preferences through customer maintenance creates a new profile/assessment revision, just like other profile edits.

Contacts are explicitly `SYNTHETIC`, including for manually entered customers. The generated email ends in `@customers.invalid`; `.invalid` is a [special-use domain](https://www.iana.org/assignments/special-use-domain-names). Telephone numbers use the fictitious [555-0100–0199 range](https://nanpa.com/numbering/555-line-numbers); these 100 placeholder numbers are intentionally not unique customer identifiers. Postal values are conspicuously simulated. No real contact-data collection or delivery connector is introduced. Existing customer profiles receive contact backfill at startup without being requalified or having their old reservations changed.

Final execution decisions live in `app/cbl/LIEX01C.cbl`, using `LIEXREQ.cpy`. Java supplies infrastructure and current facts. The policy checks consent, prescreen opt-out, global suppression, valid qualification, enabled channel, cooldown and rolling cap. `app/data/campaign-execution-policy.json` supplies configurable demonstration values:

| Value | Default |
| --- | --- |
| Cooldown between new customer dispatches | 86,400 seconds |
| Counting window | 30 days |
| Maximum new packages per customer/window | 3 |

Restart after changing the file. The exposed policy identifier combines `CEX-2026-001` and its configuration hash. These are demonstration business values, not BIAN-prescribed thresholds or a compliance certification. Step 2 reservation controls still apply separately. In particular, multiple eligible campaigns do not override the final customer cooldown.

A dispatch key binds the customer/campaign and underlying Step 2 run IDs. Late alternatives and choice changes within the same qualified run revise the existing dispatch rather than consuming another slot. A new qualification run consumes a new slot if allowed. A PostgreSQL advisory lock serializes allocations per customer; reservations are recorded before file writing to prevent retry oversubscription. File errors therefore retain the allocation for recovery. Withdrawal does not erase historical allocations. The counter measures export allocation/generation, never confirmed customer contact or delivery.

## Files and consistency

Each package owns `runtime/outbound/<package-id>/`:

- `offers.csv`: UTF-8, CRLF, quoted fields, one record per alternative and one `lead=Y` record.
- `offers.dat`: IBM037/CP037 EBCDIC fixed-block records, 643 bytes per record, no line delimiters. `app/cpy/LIOUTREC.cpy` is the field layout; text/numerical strings are left aligned and space padded. Overlength or unencodable fields fail rather than being truncated.
- `manifest.json`: package/version/status, expiry, synthetic marker, record length/count and both SHA-256 hashes. The manifest is replaced atomically after both data files are complete. A `WRITING` or `WITHDRAWN` manifest is not an export to consume.

`campaign_execution` is a separate schema in the existing PostgreSQL database. It owns recovery cursors, tasks, current packages, immutable package history, dispatch allocations and the last published file revision. A failed write leaves its revision pending for retry. Missing or checksum-invalid files are regenerated after current eligibility checks, without allocating another dispatch. A process crash between filesystem publication and SQL commit is recoverable; this is not a distributed exactly-once transaction. An external consumer must validate the manifest/checksums and current package API before using a file.

Files are generation-time snapshots. Consent withdrawal, added suppression, cancelled qualification/campaign and expiry cause automatic withdrawal; current customer/package/download APIs check the authority immediately. Reconciliation marks the manifest withdrawn and removes the package's active CSV/DAT, retaining SQL audit history. It cannot recall a file already copied elsewhere. No email, SMS, postal delivery, loan funding or external campaign submission takes place.

## Recovery and limits

Customer save and pipeline admission share one H2 transaction. Latest assessment tokens fence stale work. Stable Step 2/bureau request identifiers make retries safe. Existing Steps 4–6 provide their durable outboxes and replay; the independent Step 7 cursor and package journal survive restart. `RETRYING` indicates a dependency/file issue; `SUPPRESSED`, `NO_ELIGIBLE_OFFERS` and `ATTENTION_REQUIRED` retain their distinct meanings. `COMPLETED` means an eligible campaign package was exported; inspect campaign tasks for other matches that are pending or suppressed.

The default runtime has one local coordinator and execution worker. Step 7 currently consumes the durable HTTP recovery feed; existing Step 4/6 Kafka publication and copy consumers continue unchanged. It does not introduce a new Kafka execution topic or deliver marketing messages. At most 20 active campaigns are accepted by the automatic coordinator, and the repository rejects more than 1,000 active candidate families for one customer/campaign instead of returning a partial package. API pages are bounded.

The H2 customer store, per-package file directories and repeated current-authority checks need further work for production volume. Millions-per-month throughput has not been benchmarked or claimed. PostgreSQL tasks/cursors, stable keys, separate contexts and API boundaries leave room for leased partitioned workers, streaming/batched exports and external object storage. Multi-host execution must add coordinated leases and shared publication storage before scaling workers beyond this local host. The current local operator token does not implement customer authentication or per-customer authorization.
