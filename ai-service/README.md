# AI-assisted Business Simulation

Six Java 17 processes implement the [AI simulation architecture](../docs/architecture/ai-simulation/architecture.md). The existing TN3270 Business menu now has **08 AI-assisted simulation** and drafts have **AI explore this draft**. Manual simulation remains available independently.

Each role uses the official OpenAI Responses client with **gpt-6-astra**, structured JSON output, provider storage disabled and no silent model substitution. The six processes communicate through authenticated **A2A 1.0 JSON-RPC**. PostgreSQL owns durable workflow state; the existing engine remains responsible for simulation and catalog publication.

## Start locally

Use the existing project's WSL Ubuntu-22.04 / Java 17 environment. Build the original engine before starting AI:

```powershell
.\scripts\build.ps1
.\scripts\build-ai.ps1
Copy-Item .\ai-service\config\ai.env.example .\runtime\ai.env
.\scripts\run-ai.ps1 -Initialize
```

Edit the ignored `runtime/ai.env` using Bash-compatible `NAME=value` assignments. Supply `OPENAI_API_KEY` and an accessible PostgreSQL `AI_DATABASE_URL`, or reuse `DATABASE_URL` from the existing ignored `runtime/local.env`. Quote values containing shell metacharacters. URI-encode reserved characters in database credentials, or use `jdbc:postgresql://host:port/database` with `AI_DB_USER` and `AI_DB_PASSWORD`. Do not put keys in source files.

Start the existing application with its normal launcher. In a separate PowerShell terminal:

```powershell
.\scripts\run-ai.ps1
# From another terminal:
.\scripts\run-ai.ps1 -Check
```

The launcher requires the existing engine's local token files and starts six services. It checks role-specific health, records logs under ignored `runtime/ai`, and stops its own child processes on exit. Initialization preserves existing credentials. Bash equivalents are `bash scripts/build-ai.sh` and `bash scripts/run-ai.sh`.

| Role | Default port | PostgreSQL schema | Responsibility |
|---|---:|---|---|
| Orchestrator | 8100 | ai_orchestrator | Conversation, durable plans, approvals, budgets, feedback and publication |
| Research | 8101 | ai_research | Approved policy evidence, typed ranges and configured research tools |
| Designer | 8102 | ai_designer | Review search domains, product constraints and experiment design |
| Coordinator | 8103 | ai_coordinator | Review execution feasibility; Java admits and recovers engine runs |
| Analyzer | 8104 | ai_analyzer | Explain computed results, differences and supported associations |
| Reflection | 8105 | ai_reflection | Challenge scope, evidence, comparability and claims; request one revision |

Change `AI_BASE_PORT` in **both the engine's and AI services' environments** to move these ports. `AI_ENGINE_URL` and `AI_MARKETING_URL` default to the existing owner APIs on 8090 and 8092. `AI_ANALYST_ID` identifies the local operator; `AI_CREDENTIAL_FILE` supports role-specific service deployment.

This launcher is a local, single-operator deployment. Services bind to loopback and reject browser Origin requests. Scoped bearer files separate the analyst, research-policy reviewer, outcome source and service roles. The orchestrator alone may delegate A2A work. Each service owns a schema and holds a PostgreSQL advisory lock to reject a second process using it. Deploying across hosts requires infrastructure-provided TLS, isolated OS/database principals, secret management and identity integration; sharing a local OS account is not a tenant security boundary.

## Analyst workflow

Follow the [combined mainframe demo](../docs/demo-walkthrough.md) for a manual comparison followed by AI exploration of the same draft. All analyst actions use terminal menus; the [API verification reference](../docs/a2a-api-verification.md) is optional engineering documentation.

1. Create a draft and **two populations with different generation seeds** in the existing Business screens. Discovery and final validation are separate. A final-validation population cannot be reused by a later workflow, including as discovery data.
2. Place policies in [policy-documents](../policy-documents/README.md). In AI > Review local policy documents, refresh, read the extracted text, and approve its exact hash, product, US applicability and expiry. Approval is revoked when bytes change or a file disappears.
3. Explore a draft. Describe the requested fields, constraints and stage names; ambiguous credit-score stages should be clarified. Set the simulation, model-call, token, reserved-cost and time budgets, minimum eligible support, metric floors and stage locks.
4. The Orchestrator resolves exact parameter IDs; Research retrieves approved excerpts; Designer and Reflection review the plan. Inspect and approve the exact scope hash. No simulations run before this approval.
5. Java evaluates the baseline, bounded coarse exploration, two refinement rounds and a frozen shortlist on untouched validation data. It preserves every unmentioned business field, including hidden controls. Underwriting locks survive successor experiments.
6. Inspect separate best-tested acceptance and eligibility winners, frontier, all paged trials, exact configurations and full report artifacts. The existing reports retain paired baseline/candidate results, newly eligible and lost-eligibility groups. An unavailable winner remains unavailable.
7. Preview a final-tested candidate. Read its complete terms/rules/diff and add a review note. Explicit confirmation publishes through the existing engine into the active catalog. A lost HTTP response is recovered from the same engine receipt.
8. Select a publication for current performance, a researched explanation or a successor experiment. Successors use a fresh published baseline and fresh final validation. The original prediction and publication receipt remain unchanged.

The terminal provides forms and paginated reports; the conversation API additionally supports contextual follow-ups such as “Why?” and “Good, let's push the offer.” The publish phrase only works after an exact preview was created in that conversation. It does not grant agents a publication tool.

## Search, metrics and budgets

The registry covers the existing **42 editable fields**. Numeric domains are rounded to supported precision, with about ten coarse levels and a baseline point when applicable. Booleans and dates use explicit values. Disabled credit-score zero must be explicitly included; interpolation through scores 1–299 is rejected. Auto-only fields cannot silently affect other products. Invalid cross-field amount/date combinations are excluded before engine admission.

Default allocation is 200 attempts: 1 baseline + 119 exploration + 60 refinement + up to 20 final-validation slots. The search avoids allocating a Cartesian product for large domains. Small domains may finish below budget after deduplication. There is one engine submission in flight. Transient dependency calls have at most two retries; stable operation IDs prevent a retry from becoming another simulation. Distinct failed agent tasks have at most three attempts, with every model call budgeted.

Acceptance is **100 × expected selections / eligible** in simulation, and **100 × distinct selecting customers / historical eligible customers** for observations. Eligibility is **100 × eligible / assessed**. Zero eligible customers produces a null acceptance rate. The acceptance winner requires minimum support and the eligibility floor; the eligibility winner separately applies its acceptance floor.

The existing utility model is labeled **SIMULATED_UTILITY / CATALOG_TERMS_ONLY / DEMO**. It is not an observed or calibrated real conversion forecast. Models cannot supply the computed rates or change the deterministic ranking. Structured factual claims must reference supplied metric/evidence IDs. Explanations distinguish associations and hypotheses from established causal effects.

Budgets reserve a conservative model cost before dispatch and settle reported tokens afterward. Unknown provider outcomes retain their full reservation. `AI_MAX_USD_PER_TOKEN` cannot be configured below 0.00005; the client pins standard service tier and caps input reservations below the long-context threshold. This is a conservative accounting ceiling, not a billing quote. Review it if provider pricing changes. A model output can use up to 16,384 tokens. No paid model requests are made by the tests.

`PAUSED_BUDGET` preserves the plan. Terminal option 8 or the resume API can extend resource limits/deadline; phase allocation and ranking criteria stay fixed. Cancellation stops future admissions. A run already accepted by the existing engine may finish and retain its receipt. Failed evaluations remain visible and consume their original attempt.

## Research and tools

[Local document ingestion](../policy-documents/README.md) supports PDF, Word, text, Markdown, HTML, RTF and ODT through Apache Tika. The parser receives an immutable snapshot of the exact hashed bytes in a disposable bounded Java process. Scanned PDFs require optional Tesseract OCR and analyst review. Empty, truncated, oversized and failed extraction states cannot become approved evidence. Research cannot invent a range when applicable approved evidence is absent. Current evidence is rechecked before every simulation and publication preview.

`AgentToolbox` is the Java extension boundary. Policy search is Research-only. [mcp-servers.example.json](config/mcp-servers.example.json) shows an operator-controlled read-tool connector; copy chosen entries into `mcp-servers.json` or set `AI_MCP_CONFIG`. Defaults contain **no external servers**.

MCP uses the official Streamable HTTP client, explicit role/tool allowlists, HTTPS or loopback URLs, no redirects, environment-supplied credentials, bounded responses and timeouts. The discovered input schema must exactly match the configured schema. Arguments are validated before dispatch. Supported schema keywords are type, properties, required, additionalProperties, items, enum, numeric minimum/maximum, string/array length bounds, and descriptive metadata. Unsupported combinators/references fail closed; expose a simple adapter schema for those tools. Tools are read-only; adding a server does not authorize publication or scope changes.

Approved internal policy governs ranges. Historical aggregates and primary external research can explain evidence, but an external snippet cannot override internal policy or independently authorize a parameter domain. No external research source is enabled until an operator configures it.

## Feedback, real data and calibration

Native storage journals provide **DEMO** eligibility and selection events. Replay is idempotent. Enrollment is retained when an offer expires or disappears from current presentation; both non-selectors and selectors remain in the denominator. Follow-up defaults to 14 days, followed by a 24-hour reconciliation allowance. Original-term selection is reported separately from family selection. Client-rendered exposure is diagnostic and never replaces the eligible denominator.

Eight-stage diagnostics read owner APIs and historic profile versions where available. They send supported aggregates to agents, with a minimum cell support of five and at most 128 sampled enrollments. Current status/export snapshots are labeled; they do not prove historical delivery. No raw customer identifiers or bureau documents are sent to models. Observed Wilson intervals are descriptive and explicitly assume independent subject selections; they are not clustered causal estimates.

For an actual operational source, set `AI_OUTCOME_SOURCE_ID`, choose `AI_OUTCOME_SOURCE_MODE=OBSERVED_REAL`, and send the [versioned events](API.md#operational-outcome-events) using only the source credential. Assessments, eligibility, selections, exposures, pre-decision forecast features and completeness watermarks are separate facts. Stable SHA-256 subject pseudonyms, exact versions and append-only correction chains are required. Real and demo observations never pool. There is no preconfigured connection to a real customer system.

A real admission ledger and its complete entry window are required for observed eligibility. Missing data, provisional follow-up, mismatched population/target/source or changed cohort produces an explicit comparison status instead of a false forecast-error claim. The UI always retains the original saved estimate and its kind.

Calibration requires at least 500 mature real labels across five independent publication cohorts, disjoint customers, chronological train/validation/test groups, and class support in each group. It fits a versioned regularized probability mapping, compares Brier score against a constant baseline, checks calibration error on untouched validation/test data, and requires exact analyst activation. Activating a prior passing version provides rollback.

For a new publication, an operational adapter can submit prospective pre-decision forecast features and then freeze a forecast using an active model **before any real enrollment**. Its cohort hash, context and model version are immutable. Later feedback compares it only to the matching mature cohort. The original simulation estimate is retained. The system does not train or rewrite credit decisions.

Hourly monitoring creates durable notices when maturity, coverage, support or acceptance bands change. It does not automatically spend model budgets or publish offers. A running orchestrator is required; this is not an OS scheduled job.

## Interfaces and implementation map

See [API.md](API.md) for routes and structured examples. Application envelopes are version 1.0.0 inside standard A2A data parts. Agent Cards advertise JSON-RPC and the implemented SendMessage, GetTask and CancelTask lifecycle; streaming/webhooks are not advertised. The orchestrator polls durable tasks and registers every delegation. Peer agents cannot start untracked recursive delegation.

| Architecture behavior | Implementation |
|---|---|
| Plan-and-Execute | WorkflowService, Store, Migration; persisted plans, steps, evaluations, revisions and budgets |
| ReAct | AgentRunner; bounded model/tool/observation iterations |
| Private reasoning | AstraClient + strict Decision contract; concise rationale only, no reasoning transcript storage |
| Reflection | Separate REFLECTION role; plan challenge and one bounded analysis revision |
| Evidence and safe scope | PolicyIndex, PolicyExtractor, ParameterRegistry, ToolSchema, McpTools |
| Experiment search and results | Optimizer, EngineGateway, WorkflowService |
| Publication and conversation | PublicationService, ConversationService, existing engine's publish API |
| Feedback and calibration | OutcomeService, SourceFacts, Diagnostics, FeedbackService, CalibrationService |
| Mainframe entry and bridge | AiTerminal, AiProxy, existing SimulationTerminal |
| Process lifecycle | Main, scripts/run-ai.sh and PowerShell wrapper |

The proposal remains the design record. This implementation uses centralized deterministic scheduling, two local refinement rounds and polled task status. Optional browser streaming, cross-host deployment and causal experimentation are not runtime capabilities claimed by this build.

## Verification

```bash
# Original COBOL + Java + real terminal regressions:
bash scripts/build.sh

# AI tests and shaded runnable JAR:
bash scripts/build-ai.sh

# Additional existing owner-service tests on disposable PostgreSQL:
mvn -f ai-service/pom.xml test -Dtest=EnginePostgresTest -Dai.engineRegression=true

# Exercise startup from the actual shaded artifact:
mvn -f ai-service/pom.xml test -Dtest=ServiceLaunchTest \
  -Dai.packagedJar="$PWD/ai-service/target/ai-simulation-1.0.0.jar"
```

The embedded PostgreSQL dependency is test-only and downloads its native binary on first use. Tests run as a non-root user, use temporary databases/ports and controlled model doubles, and never publish to the active application catalog. Coverage includes official A2A decoding, six process startup, workflow and feedback traversal, evidence extraction/revocation, budgets, idempotency/lost responses, locks, separate winners, source correction/coverage, calibration gates and persistence.

Live GPT-6 Astra inference requires a configured account/key; external MCP and real customer-source ingestion require operator configuration. Passing deterministic tests does not demonstrate real-world acceptance accuracy. Existing broker-dependent integration suites additionally require the project's Kafka infrastructure.
