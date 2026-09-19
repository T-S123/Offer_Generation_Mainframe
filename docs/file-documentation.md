<!-- Documents the purposes of strict data files and checksum-protected migrations that cannot safely contain inline documentation. -->
# Documentation for immutable and strict-format files

Source files use concise file headers and documentation immediately before declared functions, methods, constructors and COBOL paragraphs. JSON Schema uses `$comment`, and JSON-formatted API contracts use the `x-file-purpose` extension; ordinary JSON and NDJSON payloads cannot accept extra documentation fields under the application's strict decoders.

The files below retain their original bytes. Applied SQL migrations are checked by raw-content hashes during startup, so adding or removing even a comment would invalidate an existing installation; their one- or two-sentence purpose descriptions are maintained here instead.

## Data and examples

| File | Purpose |
| --- | --- |
| [`bureau-batch.example.ndjson`](../app/data/bureau-batch.example.ndjson) | Demonstrates a replayable bureau batch request referencing finalized Step 2 runs. Replace the placeholder run identifier before submitting it. |
| [`bureau-profile-import.example.ndjson`](../app/data/bureau-profile-import.example.ndjson) | Demonstrates an independent bureau profile import with an expected version and report facts. Replace the customer identifier and supply a suitable report timestamp before use. |
| [`campaign-execution-policy.json`](../app/data/campaign-execution-policy.json) | Supplies the versioned demonstration outbound cooldown and rolling package limit used during campaign execution. |
| [`offer-personalization-policy.json`](../app/data/offer-personalization-policy.json) | Supplies versioned APR floors and amount, rate and term limits for personalized offer variants. |
| [`underwriting-import.example.json`](../app/data/underwriting-import.example.json) | Demonstrates a supplied underwriting decision linked to a customer profile, source identity and validity interval. |
| [`qualification-response.example.json`](architecture/qualification-response.example.json) | Illustrates the complete downstream qualification event using synthetic identifiers and financial terms. It is an explanatory example rather than captured runtime data. |

## Database migrations

| File | Purpose |
| --- | --- |
| [`V002__marketing_qualification.sql`](../services/src/main/resources/db/migration/V002__marketing_qualification.sql) | Adds the versioned marketing catalog, qualification runs, results and reservation history to the customer H2 store. |
| [`V003__automatic_pipeline.sql`](../services/src/main/resources/db/migration/V003__automatic_pipeline.sql) | Adds synthetic customer contact records and durable automatic pipeline admission to the customer store. |
| [`V001__bureau.sql`](../services/src/main/resources/db/bureau/V001__bureau.sql) | Creates bureau identity mappings, report history, credit assessments and durable batch, work, inbox and outbox tables. |
| [`V002__decision_response.sql`](../services/src/main/resources/db/bureau/V002__decision_response.sql) | Adds versioned decision-response storage and recovery state for publishing current qualification eligibility. |
| [`V003__offer_variants.sql`](../services/src/main/resources/db/bureau/V003__offer_variants.sql) | Adds durable exact-term personalization qualifications and their source evidence. |
| [`V001__offers.sql`](../marketing-service/src/main/resources/db/V001__offers.sql) | Creates marketing offer workflows, qualification ingestion, customer selections and cohort-training persistence. |
| [`V002__offer_storage.sql`](../marketing-service/src/main/resources/db/V002__offer_storage.sql) | Adds the authoritative offer repository, immutable revisions, ordered event journal and recovery state. |
| [`V001__offer_copies.sql`](../services/src/main/resources/db/storage/V001__offer_copies.sql) | Creates independently owned customer or enterprise offer copies with revision history, deduplication, quarantine and recovery cursors. |
| [`V001__campaign_execution.sql`](../services/src/main/resources/db/execution/V001__campaign_execution.sql) | Creates final campaign packages, history, pending tasks, dispatch allocations and repository recovery state. |

Generated builds, runtime files, architecture exports and preview assets are excluded from source documentation changes. Their maintained generators and configuration carry the relevant file and function documentation.
