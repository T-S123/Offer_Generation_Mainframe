<!-- Explains domain ownership, infrastructure boundaries and the local application architecture. -->
# Customer data and marketing architecture and ownership

The default customer-save path now runs Steps 1–7 automatically. `automation/PipelineEngine` coordinates existing qualification use cases; `execution/ExecutionEngine` consumes the marketing repository API and invokes `LIEX01C` for final suppressions. Its PostgreSQL schema and local output adapter are separate from customer/marketing reservations and offer storage. See [campaign-execution.md](campaign-execution.md) for the complete workflow and consistency boundaries.

Step 8 presents current offers through Customer/Business TN3270 modes. `terminal/CustomerTerminal` reads customer-copy and customer-correlated outbound-file APIs, displays financial comparisons and submits explicitly reviewed terms to the separate marketing-service API. Customer-copy reads and selection writes recheck current qualification, consent and suppression independently of outbound channel/throttle gates. The engine reserves callback worker capacity for the marketing service. Business opens the isolated [Simulation Engine](business-simulation.md), with active operational consoles in its option 07.

The approved scope includes local customer forms, simulated/imported underwriting, synthetic populations, Step 2 marketing qualification, Step 3 bureau qualification and Step 4 decision response. All input stays on this computer. Java is the supporting service language; underwriting, marketing/throttling and credit bureau policies execute in COBOL. Step 3 adds a separate credit HTTP service and PostgreSQL/Kafka durability; see [bureau-qualification.md](bureau-qualification.md). Step 4 projects those decisions into versioned eligibility events and automatically withdraws invalid qualifications; see [decision-response.md](decision-response.md) for source consistency, domain ownership, consumer contracts and capacity limits.

## End-to-end workflows

Manual entry: TN3270 form -> normalized JSON -> API shape validation -> customer integrity checks -> COBOL batch adapter -> three independent product assessments -> atomic customer/profile/assessment save -> terminal response. The terminal shows a review page before persistence. The server repeats validation independently of the terminal.

Maintenance: read profile -> submit expected version and changed fields -> assess proposed version -> compare expected version again inside the write transaction -> append profile and decisions. Concurrent edits return 409. All prior profiles and assessments remain queryable. Assessment history is not overwritten.

Business simulation: generate frozen synthetic inputs and independent bureau facts in a separate H2 store -> snapshot baseline offer/campaign/policy -> edit immutable versions of financial terms and three-stage rule forms -> compare baseline/candidate through COBOL using the same population/date -> call stateless Java K-means/fit analysis over HTTP -> persist report/model and row outcomes atomically -> explicit Business review -> atomically publish a new catalog offer and campaign copy with frozen rules. No synthetic experiment customer enters the active pipeline. Interrupted runs become failed; users choose whether to rerun. Previously saved legacy simulation batches remain readable, but the old bulk-customer POST is disabled in the configured host.

Import: validate file/API body -> verify exact source identity, current profile version, dates and reason codes -> append decision and source index atomically. Same source ID with identical data returns the original decision; conflicting reuse fails. The entire import batch rolls back on any invalid member. Assessment time determines which decision is current; ingestion order only breaks ties.

Prescreen: select products and optional source batch -> read customer and decision snapshots in a single transaction -> apply prescreen preference, profile version, latest status and expiry checks -> persist summary and membership references. Later edits do not alter historical membership. Current customer views and frozen population membership have intentionally different lifetimes.

## Domain boundaries

| Responsibility | Owner |
| --- | --- |
| Customer input integrity, opt-out and snapshot readiness | `services/.../domain/CustomerRules.java` |
| Profile and assessment identity/history | `services/.../domain/Model.java` |
| Simulated underwriting thresholds, required inputs and rejection reasons | `app/cbl/LIUW01C.cbl` |
| Use-case sequence, source precedence and atomic publication | `services/.../application/CustomerEngine.java` |
| Synthetic attribute generation | `services/.../application/Generator.java` |
| Underwriting execution and persistence ports | `services/.../application/Ports.java` |
| JDBC, JSON serialization and executable/file transport | `services/.../infrastructure/` |
| HTTP parsing, token checks and response DTOs | `services/.../api/` |
| 3270 fields, keyboard navigation and HTTP client | `services/.../terminal/` |
| Customer progress, financial comparisons and reviewed-choice confirmation | `services/.../terminal/CustomerTerminal.java` |
| Customer offer visibility and current qualification checks | `services/.../storage/StorageViews.java`, `services/.../offers/OfferSource.java` |
| Exact-term confirmation and atomic selection/storage events | `marketing-service/.../application/OfferEngine.java`, `marketing-service/.../infrastructure/OfferStore.java` |

`LIUW01C` has no I/O statements. Its only inputs/outputs are copybook structures in a LINKAGE SECTION. `LIBAT01` owns sequential file handling and calls that domain program. Java maps decimals to fixed-width integer cents and decodes status/reason bits; it does not duplicate credit thresholds. Missing flags are explicit.

The terminal uses an HTTP client with redirects disabled. Its only network destination is the loopback API. There are no external model clients, analytics calls, telemetry exports, or unrestricted text processing. Runtime logs report error classes/status rather than customer bodies.

## Mainframe deployment boundary

The root `app/cbl`, `app/cpy`, `app/data` and batch naming follow CardDemo conventions. Portable policy code is separated from its local runtime adapter. The terminal protocol is genuine TN3270, model 2, using CP037 and extended colors; Java implements the host-side screen controller. This is not a CICS command translator or a general 3270 application server.

A future CICS adapter would handle BMS RECEIVE/SEND and invoke the same published backend API. VSAM or Db2 adapters would replace the local store behind its port. JCL would allocate datasets and execute a host-compatible batch adapter. Each requires host-specific compilation and tests; none is claimed operational by the local tests. The current fixed-format policy can be evaluated independently through its copybooks.

## Persistence and operations

H2 is an embedded local SQL database. Customer aggregates retain profile and decision history as versioned JSON; SQL tables enforce customer/external identity, imported source identity, and frozen population membership. All statements are parameterized. Imports, updates and population publication are transactional. A single process owns the database file; it does not expose an H2 web console or TCP listener.

API and TN3270 bind only to 127.0.0.1. A randomly generated local token protects API operations; health is public. The terminal is a local demo operator session and is not a bank user-authentication or RACF implementation. Exposing either listener remotely requires an explicit authentication and transport-security design.

Step 1's original tables are retained. `SchemaMigrations` applies the additive, checksum-verified `V002__marketing_qualification.sql`; subsequent changes require new versioned migrations rather than runtime-directory deletion. New COBOL policies must get a new policy version and retain compatibility/replay support for historical versions that need reevaluation.

## Marketing context

The `marketing/domain`, `marketing/application`, `marketing/infrastructure` and `marketing/api` packages form the Step 2 context. `MarketingTerminal` depends only on HTTP transport and presentation data. The existing API applies local authentication before dispatching marketing routes.

Workflow: select Step 1 population -> load its frozen members and current customer/decision facts -> snapshot campaign/offer/configuration versions -> compiled `LIMK01C` qualification and tentative allocation -> save preview with immutable result rows and per-customer Risk IDs. Finalize -> read current facts and usage in the same transaction -> repeat evaluation -> require unchanged versions/results -> persist customer/campaign reservations and finalized status atomically. Cancel -> mark lifecycle state and release reservations without changing result history.

`MarketingRules` validates configuration and defines date/reservation semantics. `LIMK01C` owns consent, suppression, catalog eligibility, amount/tenure/ownership checks and numeric throttling. The Java application supplies observed facts and reservations; it does not implement a parallel qualification fallback. `LIMBAT01` provides file transport and initializes the policy's explicit run context. Three ordered data structures separate customer facts, configuration and temporary allocation counters.

Both contexts share a local transaction port to ensure customer updates cannot race marketing finalization. The marketing repository adds catalog revision, run, result and reservation methods to the local unit of work. This is a modular single-process implementation, not an independently deployed DMG microservice. A future service split must replace the shared transaction with explicit version checks and atomic reservation guarantees; putting a network call between current reads and writes would not preserve these guarantees automatically.

Persistence uses append-only catalog/policy/suppression revisions, immutable qualification rows, and lifecycle updates to runs/reservations. Run DTOs freeze catalog terms and configuration for review. Current profiles and decisions are accessed through the existing customer port. APIs return historical evidence; later downstream execution must revalidate eligibility, preference and reservation validity.

See `marketing-qualification.md` for limits, reservation semantics and the terminal workflow; `bian-marketing-mapping.md` explains which concepts are BIAN-aligned and which policies are local demonstration choices.

## Technical references

- CardDemo local source: `../aws-mainframe-modernization-carddemo/app/`.
- GnuCOBOL compilation and source format: https://gnucobol.sourceforge.io/doc/gnucobol.html
- x3270 terminal family: https://x3270.bgp.nu/
- TN3270 protocol: https://www.rfc-editor.org/rfc/rfc1576
- BIAN service/API models: https://github.com/bian-official/public/tree/main/release14.0.0
