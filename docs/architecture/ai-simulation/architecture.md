# AI-assisted Business Simulation: proposed architecture
**Design record:** original target architecture. The Java implementation and current runtime boundaries are documented in [the AI implementation guide](../../../ai-service/README.md).  
**Source baseline:** Offer_Generation_Mainframe, branch main, commit 232eda9; inspected 27 September 2026.  
**Decisions:** analyst-selected fields only; six separate Java agent services using OpenAI GPT-6 Astra; A2A; bounded coarse-to-fine simulations; selection among eligible customers as acceptance; existing catalog publication; feedback across Steps 1–8.

Open [the editable architecture](ai-simulation-architecture.excalidraw). It contains seven connected views. [Parameter catalog](parameter-catalog.md) inventories every editable field. [Structured contracts](contracts.md) describes agent inputs, outputs, and examples. [Source evidence](source-evidence.md) separates existing behavior from proposed capabilities.

## 1. Result the analyst receives

The Business Simulation menu gains **08 AI-assisted simulation**; options 01–07 remain available. From a draft, an **AI explore this draft** action carries its ID and version. The analyst describes a change, reviews the resolved field names, ranges, evidence, and budget, then starts exploration.

The result contains two separately ranked tested configurations:
- Highest acceptance among eligible customers, with an eligibility floor and minimum support.
- Highest eligibility among the assessed population, with an acceptance floor if the analyst sets one.
- A trade-off table showing both metrics, expected acceptance count, eligibility gains/losses, cost/payment changes, uncertainty type, and exact changes for every completed candidate.
- References to the simulation runs, evidence, unchanged fields, and the validation population. “Best tested” is the claim; the system does not claim an unexplored global optimum.

The analyst can inspect any candidate, revise the permitted scope, or select one for publication. The chat asks for confirmation only when the concrete offer/rules and review note are ready. Publication uses the existing engine and returns its receipt. Later, the analyst can ask about that publication by name, date, or conversation context, see observed outcomes, investigate differences, and start a new constrained experiment.

Use the existing TN3270 interface for the complete workflow: multiline paged prompt, clarification screen, range review, progress, ranked results, evidence pages, and publication confirmation. The backend keeps conversation/work state so leaving the screen does not cancel work. A browser view is an optional later client of the same APIs, not a dependency. Reports are paginated; no large JSON is pasted into terminal fields.

## 2. What exists and what must be added

| Concern | Verified existing behavior | Proposed addition |
|---|---|---|
| Forms | 9 offer fields plus 11 per underwriting, marketing, and bureau form: **42 editable fields** | Typed parameter registry, stage-aware interpretation, authorized-change mask |
| Simulation | Frozen population, draft/version, date, seed, COBOL qualification, Java scoring | Experiment series, candidates, budgeted optimizer, independent final validation |
| Acceptance | Sum of utility probabilities **over eligible rows** | Explicit percentage and metric contract; observed selection percentage; separately calibrated forecasts |
| Concurrency | One experiment executor; admission semaphore of 3 queued/running runs | Durable admission controller that respects these existing limits |
| Publication | Completed/latest draft; unchanged baseline/catalog/policy; explicit review; new offer and campaign | Scoped publication gateway and conversation-to-receipt lineage |
| Selections | Exact terms/version, idempotent request ID, selection and storage journal commit together; labeled SELECTED_DEMO_ONLY | Historical eligible-cohort ledger, attribution, maturity, source-mode labeling |
| Feedback | Qualification/storage histories and APIs provide substantial evidence | Cross-step adapters, reconciled outcome facts, analysis, calibration and revision loop |
| A2A | The sibling Python example uses custom tasks/send envelopes | Standards-based Java clients/servers; do not copy the custom wire format |
| Identity | Loopback operator tokens and Customer/Business navigation modes | Authenticated analyst identity and per-tool/service permissions for the AI surface |

The current simulator fits an isolated cohort model for each comparison and exposes both overall and held-out-test reports. Its held-out set protects the K-means evaluation, but repeatedly choosing business rules by that same report would leak it into rule selection. This design adds an outer validation boundary.

The offer amount-range fields control eligibility; they do not replace each synthetic customer's requested principal. The acceptance score uses affordability, cost, amount match, and term preference. Bureau credit score is not a direct input to that utility formula. Changing a bureau minimum can change the eligible population and its mean utility without demonstrating a direct credit-score effect on willingness to select an offer.

Publication creates a **new catalog offer and campaign**. It does not overwrite an earlier offer, distribute messages, activate the experimental cohort model, or automatically requalify existing customers. Old and new offers can coexist. Any retirement/replacement operation is a separate explicitly reviewed action and a later extension.

## 3. Runtime and ownership

Use Java 17-compatible services initially, Maven, the official A2A Java SDK with a pinned released version supporting the chosen protocol, and the official OpenAI Java client. A Quarkus-based A2A reference-server deployment is the initial runtime choice; verify the selected release's complete JDK/dependency requirements before implementation. Existing engine services retain their current runtime. The API model identifier is **gpt-6-astra**; record the returned model identifier and prompt revision with each result. No silent substitution.

OpenAI documents function calling and Structured Outputs for GPT-6 Astra. API account entitlement is a deployment prerequisite, not something this source review verifies. [Model documentation](https://developers.openai.com/api/docs/models/gpt-6-astra), [Java client documentation](https://developers.openai.com/api/docs/libraries), [A2A Java SDK](https://github.com/a2aproject/a2a-java).

### Six agent microservices

Each row is a distinct deployable Java service, A2A server/client, and GPT-6 Astra agent. “Tools” below are allowlisted capabilities, not unrestricted access.

| Agent / skill | Structured input → output | Owns | Tools / delegation |
|---|---|---|---|
| **Orchestrator** / coordinate-experiment | AnalystRequest + conversation references → WorkflowPlan, Clarification, AnalystAnswer | Intent, task graph, revision, budget authority, analyst interaction | Baseline reader; delegates to all agents; requests publication preview |
| **Research** / research-parameter-ranges | ResearchRequest + allowed field IDs → EvidenceBundle | Source retrieval, applicability, evidence-supported bounds, conflict/uncertainty | Internal policy/document MCP, aggregate historical profiles, allowlisted external research |
| **Experiment Designer** / design-search | FrozenBaseline + AuthorizedScope + EvidenceBundle → SearchPlan | Field resolution, domains, objectives, candidate strategy | Parameter registry, constraint validator, deterministic optimizer |
| **Simulation Coordinator** / execute-experiment | ApprovedSearchPlan + budget lease → ExperimentResultIndex | Admission, run mapping, progress, failed-run diagnosis | Draft/create-edit/run/read adapters; asks Designer for refinements |
| **Analyzer** / analyze-results | ResultIndex or OutcomeQuery + metric contract → AnalysisReport | Numerically grounded comparisons and testable explanations | Deterministic metrics, cohort queries, decomposition; can request focused research |
| **Reflection Reviewer** / review-evidence | Plan/report/forecast/outcome comparison → ReflectionReport | Independent challenge of scope, claims, evidence and forecast error | Evidence and result reads; requests specific follow-up checks; cannot publish |

The Coordinator's model handles execution interpretation and exceptional cases; ordinary polling, arithmetic, candidate generation and retries are Java code. Do not spend a model request on every numerical combination. Each agent's output must pass schema and semantic validation before downstream use.

### Deterministic supporting components

- **AI Interaction API:** analyst session, messages, scope approval, progress, run/table/report links, publication confirmation. It can run alongside the Orchestrator but exposes a separate authenticated API.
- **Workflow Store / Scheduler:** PostgreSQL workflow state, budget reservations, child-task links, inbox/outbox, leases, retry deadlines, artifact references. The LLM never is the authoritative scheduler.
- **Tool Gateway + MCP Facade:** invokes permitted engine APIs, validates arguments, enforces identities and immutable-scope rules. Separate read, simulation-write, and publication capabilities.
- **Optimization library:** seeded mixed-domain search, rounding, de-duplication, candidate hashing, Pareto ranking and stopping criteria.
- **Outcome & Metrics service:** ingests historical qualification and selections, creates stable denominators, computes statistics and lineage. Java workers own arithmetic and model fitting.
- **Publication Gateway:** only component with catalog-publication permission; validates a human confirmation bound to a specific completed run.
- **Model Gateway:** common Java client/configuration library or internal service for GPT-6 Astra, request budgets, redaction, model/prompt versions and typed tool execution.
- **Private artifact store:** versioned reports, evidence snapshots and row-level result references. Start with restricted local storage and PostgreSQL metadata; choose an object-store adapter when deployed across machines.

All six agents persist in independently owned schemas or stores. They exchange artifact IDs and minimal typed data, not another service's database credentials. Engine H2 ownership remains with the engine process. AI services never open its files directly.

## 4. End-to-end first experiment

1. **Capture:** analyst selects a draft/product/population and enters intent. Freeze baseline offer, campaign, global policy, rules, schema/scoring versions, date and input hashes.
2. **Resolve scope:** Orchestrator and Designer map language to parameter IDs. “Credit score” needs a stage when ambiguous: self-reported underwriting/marketing score versus bureau score. Show the suggested mapping and seek clarification before a dependent run. A locked stage wins over any agent suggestion.
3. **Gather evidence:** Research queries approved policy, then relevant historical aggregates, then primary external research as needed. It returns ranges with citations and applicability; no evidence means an explicitly unsupported suggestion or a clarification, never a fabricated citation.
4. **Build ranges:** Designer intersects engine-valid domains, bank policy, explicit analyst constraints, and justified research bounds. When the intersection is empty, report the conflict and retain the baseline as a reference only. Do not run a fallback as though it satisfied the analyst's constraints.
5. **Review:** display changed fields, endpoints, units, discrete values, expected search size, unchanged-field summary, goals, floors and budgets. The analyst can adjust these. Deterministic validation signs an immutable plan revision.
6. **Execute:** Coordinator obtains a budget lease, submits isolated candidate drafts/runs through engine adapters and reports progress. Every candidate applies only allowed field differences to the frozen baseline.
7. **Refine:** optimizer uses successful exploration results to spend the remaining budget around promising feasible regions. A refinement cannot enlarge approved ranges or unlock fields; it creates a traceable child plan.
8. **Analyze:** Analyzer requests computed rankings and paired/mix decompositions. Reflection challenges conclusions and scope. Small or missing samples, contradictions, incomplete runs and research gaps appear as structured limitations.
9. **Validate:** freeze the shortlist and evaluate on a separately reserved population. Report both selection-stage and final-validation results. If validation changes the apparent winner, show that; another optimization cycle requires a new untouched validation population.
10. **Present:** show separate acceptance and eligibility winners, alternatives on the trade-off frontier, all trial statuses and links.
11. **Publish if instructed:** show the chosen full offer/rules, exact diff, run, metrics and review note. Analyst confirmation calls the Publication Gateway. Only a verified engine receipt produces “Published.”
12. **Follow up:** publication lineage and measured outcomes become available to later conversations and scheduled feedback jobs.

## 5. Search and parameter control

The [parameter catalog](parameter-catalog.md) is the contract between language and executable rules. Each entry declares path, stage, source, units, type, valid domain, sentinels, product applicability, dependencies, optimization eligibility and policy evidence requirements.

Canonical comparison uses normalized decimal values and meaningful rule/offer fields. Server-assigned IDs, versions and timestamps are excluded from the unchanged-field hash; their mapping is separately recorded. After materializing a candidate, compare the entire normalized configuration against the baseline. Every changed business-field path must belong to the approved allowlist. Recheck at tool invocation, run admission, and publication preview.

A proposal for one stage does not adjust another stage to “make it work.” If unchanged underwriting masks a bureau-score sweep, report that bottleneck and show the results; retain underwriting. Consent, known non-opt-out, suppression and bureau identity matching stay mandatory. Stage-specific sentinel fields are never candidates.

For a numeric range, use **up to 10 distinct levels including endpoints**, snapped to the registry step, plus the baseline value if it lies in the approved domain. Integer and decimal grids are de-duplicated. Booleans use permitted explicit values; dates use approved date sets; names are descriptive metadata. Never interpolate through score 0 or other disabling sentinels. Dates and activation flags stay fixed unless explicitly requested.

**Default proposal, configurable:** 200 evaluation attempts, 10,000 synthetic customers per run maximum, 30-minute wall limit, one engine submission in flight, two transient retries at most, two refinement rounds. Model-call count, model-token allowance and dollar ceiling are additional mandatory deployment-configured limits; the UI resolves and displays them before approval. They are separate from the simulation budget. No unverified dollar/runtime estimate is asserted.

Example allocation: 1 baseline check + 119 exploration evaluations + 60 refinement evaluations + 20 final-validation slots = 200. Every started evaluation, including reruns/retries and final validation, consumes an attempt; a cache hit is recorded but uses no new evaluation. Unused final slots remain reserved until the shortlist is frozen. The user can choose another allocation.

Search procedure:
- For a tiny valid Cartesian grid that fits the allocation, enumerate it.
- Otherwise use seeded stratified/space-filling coverage across all authorized dimensions, explicitly include baseline/boundaries, and add pairwise interaction coverage. Do not materialize 10^n combinations.
- Retain non-dominated feasible candidates across both objectives. Refine neighborhoods of several frontier candidates and reserve some exploration to avoid locking onto an early local peak.
- Stopping conditions: budget, deadline, cancellation, no feasible points, or configured diminishing improvement. Report coverage and stop reason.
- Reject combinations with invalid amount/date ordering or product/stage restrictions before execution. Log rejected proposals separately.
- Cache key includes canonical configuration, baseline, population, as-of time, seed, rule/scoring/model versions and simulation profile. A schema/model/data revision invalidates equivalence.
- A minimum eligible count and optional eligibility/acceptance floors prevent ranking a tiny selected subgroup as an unrestricted success. Default support warning is 30; policy owners set the production decision threshold.

Do not promote an acceptance-only winner by shrinking the denominator without showing the lost eligible customers. Every row displays N assessed, E eligible, expected accepted count, acceptance among E, and E/N.

## 6. Metrics that remain comparable

### Metric contract v1

Fix product, publication, catalog/rule versions, population/cohort rule, entry period, as-of timestamp, follow-up duration, accepted-selection semantics, and source mode.

For a simulation candidate:
- N = all assessed simulation customers.
- E = customers passing underwriting, marketing (including mandatory controls/capacity), and bureau.
- S = sum of simulated selection probabilities for those E customers.
- Eligibility percentage = 100 × E/N.
- Simulated acceptance among eligible = 100 × S/E.
- Expected selections per assessed customer = S/N, a separate volume metric.

The current report provides E, E/N and S; an adapter can derive S/E without replacing the existing scoring formula. E=0 means acceptance is unavailable, not 0%. Keep a null value with NO_ELIGIBLE_CUSTOMERS.

For observations:
- Freeze an enrollment cohort keyed by publication/offer/rule version and customer. Enroll at the first qualifying Step 4 event in the entry window; this best matches the simulator's completed three-stage qualification. Preserve the enrollment facts even if eligibility later expires.
- Y = distinct enrolled customers making a valid selection of the relevant offer within their defined follow-up window.
- Observed acceptance among eligible = 100 × Y/E.
- Delivery, visibility and actual exposure are additional funnel measures, not exclusions from the user's primary denominator.
- To compute observed eligibility E/N, the assessment ledger must include ineligible, incomplete and failed admissions as well as approved customers. Until that ledger is complete, eligibility is unavailable; do not manufacture N from selected/approved records.

Default follow-up proposal: 14 days from first eligibility, ending earlier if the offer expires; report the resulting opportunity duration distribution. New cohorts remain **provisional** until that window and the allowed ingestion lag close. Preserve canceled/expired offers in the original cohort and report their lost opportunity separately. A “right now” answer includes as-of, provisional/mature status, numerator, denominator, coverage and data freshness. The 14-day choice is configurable and is not implied by the current simulator.

Deduplicate by business identity and source event ID. Retries, requalification, later selections and repeated storage revisions do not multiply customers. The first valid selection for a publication family counts once; changing between original and personalized variants in the same family does not add an acceptance. A later loss of current availability does not erase a historically valid selection. Corrections append a revision with a reason; they do not silently rewrite the published report.

### Offer family versus exact terms

A published catalog offer may produce both original and personalized Step 5 alternatives. The primary observed **family** rate counts an eligible customer selecting either alternative once. Store exact variant and terms hash so an **original/exact-terms** metric can also be computed.

The existing Business Simulation scores candidate catalog terms only. It does not model Step 5 variant competition, Step 7 reach, Step 8 exposure or the follow-up clock. Therefore its S/E is initially labeled **SIMULATED_UTILITY, CATALOG_TERMS_ONLY, NO_OBSERVED_CALIBRATION**. Matching a denominator alone does not make it a calibrated prediction of family selection.

Provide two explicit comparison modes:
1. **Catalog-terms diagnostic:** compare simulated S/E with observed selection of the same original terms among the matching eligible cohort; show the personalized-selection share and unmodeled exposure/competition. Treat gaps as diagnostic.
2. **Family outcome forecast:** once suitable observed data exist, a versioned outcome model estimates family selection within the same follow-up window and personalization/exposure policy. Its prediction target matches the primary observed rate. Terms-only simulation still evaluates eligibility and utility.

A later simulation profile may replay versioned variant/choice/exposure behavior in isolation. That is a separate deterministic simulation extension with a validated contract, not an LLM assumption. Never relabel an existing utility score as a real acceptance forecast.

### Statistical interpretation

Metrics service computes intervals and support from observed customer-level labels; Analyzer cites those outputs. Use customer/campaign/time-aware bootstrap or appropriate clustered intervals when repeated observations are dependent. Wilson intervals suffice only for independent binomial-style summaries. Synthetic eligibility intervals and the utility sensitivity band are labeled separately from forecast uncertainty.

Always present:
- Baseline and candidate on the same frozen population.
- Paired changes among customers eligible for both.
- Newly eligible and lost-eligibility groups.
- Conditional acceptance and expected volume.
- Original/personalized selection mix and cohort composition.
- Missing, late, censored and corrected data counts.

This prevents a composition shift from being described as a change in customer behavior.

## 7. A2A protocol and agent interaction

Use the current **A2A v1.0** model as the target, with SDK versions and generated contracts pinned together. Clients discover a card at `/.well-known/agent-card.json`, select an advertised interface, and authenticate separately. Use JSON-RPC initially; A2A also specifies REST and gRPC bindings. Initial operations are SendMessage, GetTask, CancelTask and optional SendStreamingMessage/SubscribeToTask. Messages carry typed data Parts; tasks carry status and artifacts. [Official specification](https://a2a-protocol.org/latest/specification/).

A fixed private allowlist of Agent Cards is sufficient initially. A service registry may cache validated cards, but public arbitrary agent discovery is not required. Card skill descriptions aid routing; application payload schemas describe the exact business contract.

A2A task IDs are scoped to their agent. The Workflow Store maps workflowId → planRevision → agent endpoint + taskId + contextId + stepId. It does not assume two agents share a context merely because their strings match. requestId is for business idempotency; JSON-RPC id correlates a transport request; messageId identifies a message. None of these automatically gives exactly-once business execution.

The Orchestrator owns the durable task graph. Research and baseline reads can run concurrently. Analyzer may call Research directly for a focused explanation, and Coordinator may request a Designer refinement. Each such call must carry a delegated capability, parent step, remaining budget and scope revision, and be registered with the scheduler. A single depth/call counter bounds delegation; no untracked recursive peer routing.

A2A WORKING covers several application states: RESEARCHING, SEARCH_PLANNING, EXECUTING, ANALYZING, VALIDATING and REFLECTING. INPUT_REQUIRED carries a typed clarification or review request. COMPLETED returns immutable artifacts, not proof of publication. Publication is a separate command and receipt. PAUSED_BUDGET and WAITING_FOR_ENGINE are application substates. [Core concepts](https://a2a-protocol.org/latest/topics/key-concepts/), [task lifecycle](https://a2a-protocol.org/latest/topics/life-of-a-task/).

Use streaming for browser clients and UI gateway updates; TN3270 polls compact progress pages. Persistent task state lets work survive a UI disconnect. Optional webhook delivery can come later, behind destination validation and receiver authentication. [Streaming guidance](https://a2a-protocol.org/latest/topics/streaming-and-async/).

## 8. Structured model and tool boundary

Every agent uses the same processing shell:
1. Validate the inbound schema, authenticated identity, parent workflow and immutable scope.
2. Load only authorized artifacts.
3. Call GPT-6 Astra through Responses with a strict output schema and role-specific tools.
4. Validate returned tool names/arguments against role, scope and budget; execute in Java.
5. Attach observed tool results and continue within bounded iterations.
6. Validate the final output, persist the evidence-linked artifact and emit task progress/completion.

Use Responses structured output via `text.format` with a JSON schema, and strict function arguments for model-selected tool calls. Model refusals, incomplete responses and transport errors are explicit outcomes; they never become empty “successful” plans. JSON shape validity does not establish correctness: ranges, field locks, permissions, computed numbers and citations receive separate checks. [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs).

A2A transports the validated business output. It is not the model-output schema itself. The contract document supplies required fields and examples for all six agents.

### Tools and MCP

The shared Java tool interface describes tool name/version, input/output schemas, permission, side-effect class, timeout, idempotency requirement, data classification and provenance. Adapters support existing HTTP APIs and MCP servers. Registering an approved tool does not require changing agent business logic.

Use a Java MCP client in the Tool Gateway for private services; expose selected tool definitions to GPT-6 Astra as controlled function tools. The local runtime executes calls and sends minimized results back to the model. Initialize/validate MCP capabilities, pin server identity, filter discovered tools by role, and validate arguments/results. Streamable HTTP is the default for remote MCP; approved local subprocess MCP is an optional development adapter. [MCP Java client](https://java.sdk.modelcontextprotocol.io/latest/client/).

Direct model-side remote MCP is also supported by OpenAI, but is optional here. If adopted, apply equivalent server allowlists, approval rules, scoped credentials and data controls. A server's tool description cannot grant additional authority. [OpenAI MCP documentation](https://developers.openai.com/api/docs/guides/tools-connectors-mcp).

| Capability class | Allowed callers | Examples |
|---|---|---|
| Read baseline/rules/schema | Orchestrator, Designer, Reviewer | getBaseline, describeParameter, validatePatch |
| Read evidence | Research, Analyzer, Reviewer | searchPolicies, getEvidenceExcerpt, searchPrimarySources |
| Read aggregate outcomes | Research, Analyzer, Reviewer | queryOfferCohort, getFunnel, compareSegments |
| Isolated simulation writes | Coordinator through gateway | createCandidateDraft, submitRun, getRun, listRunRows |
| Metrics/search calculations | Designer, Analyzer | sampleCandidates, rankFrontier, computeRates, compareMix |
| Prepare publication | Orchestrator through gateway | createPublicationPreview |
| Commit publication | Authenticated analyst command only | confirmPublication(previewId, approvalToken) |

Agents cannot submit arbitrary SQL, modify COBOL, create executable business rules, mint consent, select offers on a customer's behalf, or directly publish. They choose values within the existing validated parameter system. Research documents and MCP output are untrusted evidence content, never new instructions.

## 9. Four reasoning patterns working together

| Pattern | Where it runs | Observable artifact and bounds |
|---|---|---|
| **Plan-and-Execute** | Orchestrator creates the task graph; scheduler/Coordinator execute it | Versioned plan, dependencies, budgets, progress, immutable inputs, resumable steps |
| **ReAct** | Research/Analyzer/Coordinator alternate model decisions with real tool calls and observations | Action/observation references, tool receipt, concise rationale; bounded per-agent calls |
| **Chain-of-Thought** | GPT-6 Astra reasons internally when interpreting scope and comparing evidence | Expose decision, assumptions, citations and concise rationale; do not request, store or display private reasoning transcripts |
| **Reflection** | Independent Reviewer challenges a plan/report and revisits forecast errors | Structured findings, counterevidence, severity, required checks and a revision request; bounded correction rounds |

For the first experiment: plan → research/tool observations → propose ranges → validate/execute → analyze → reflect → final validation → analyst review. For the later outcome gap: retrieve immutable prediction → compute observed rate → gather cross-step evidence → reflect on assumptions → plan a constrained revision → execute fresh tests → publish after confirmation.

Reflection can correct an unsupported statement, request missing evidence or change the next experiment plan. It does not change an already recorded forecast, overwrite historical outcomes, or self-approve publication. “Self-correction” is measurable changes to hypotheses, estimates and tested proposals; GPT-6 Astra fine-tuning is not part of this architecture.

## 10. Feedback across all eight steps

The feedback loop reads through owner APIs and owned event contracts. It does not merge application databases.

| Step | What the loop needs | Available anchor / proposed gap |
|---|---|---|
| 1 Customer/underwriting | As-of profile, product request, assessment version, consent/readiness; all assessment admissions for N | CustomerEngine/API and profile/decision history; add bounded minimized historical projection for analysis |
| 2 Marketing | Pass/fail reasons, suppression, campaign/rule versions, capacity/reservations | MarketingEngine run/results and frozen evidence; add ledger projection for eligible/ineligible populations |
| 3 Bureau | Subject mapping status, bureau facts/report date, approval/review/failure, version | BureauEngine/credit APIs; expose only authorized fields and distinguish technical failure from decline |
| 4 Decision response | Eligibility entry/revocation/expiry, response versions and provenance | ResponseEngine and qualification Kafka events/history; primary cohort enrollment source |
| 5 Offer creation | Catalog mapping, personalized/original terms, model version, creation delays, selection receipts | OfferEngine/OfferStore; selection journal is atomic; add paged selection-fact export if current journal retention is insufficient |
| 6 Storage | Ordered immutable offer revisions, current and historical copies | StorageJournal and storage events/history APIs; CDC/replay adapter uses stable cursor and dedup |
| 7 Campaign execution | Suppression/throttle/package status, channels, timing, files | ExecutionEngine/package history; files indicate export, not actual message delivery |
| 8 Presentation/selection | Actual rendered offer/variant exposure, confirmed choice, timing | Existing exact-term selection path; add presentation-exposure events for diagnosis, not primary acceptance denominator |

**Current data is demo data.** Preserve SOURCE_MODE = DEMO or OBSERVED_REAL in every fact, query and report. Never pool SELECTED_DEMO_ONLY events with real outcomes or call them market performance. Real deployment would need authenticated customer identity, operational exposure/outcome ingestion and data authorization; the feedback architecture supports those adapters without pretending they exist now.

Ingestion flow: source event/API → schema/correlation validation → deduplicated inbox → append-only outcome/eligibility/exposure facts → materialized cohort snapshots → computed metrics → threshold/time-triggered feedback request. Each connector saves a high-water mark and lag; periodic bounded API reconciliation recovers missed Kafka events. Out-of-order records join by business IDs and versions, not arrival order. An unavailable source yields INCOMPLETE_DATA and known coverage, not zero acceptance.

Lineage:
conversation → workflow → baseline snapshot → scope/evidence → search plan → candidate → simulation run → prediction snapshot → publication receipt → catalog offer/campaign/rules → qualification response → generated offer/variant → eligible cohort → selection → metric snapshot → reflection → successor workflow.

Catalog IDs from the publication receipt must be retained because generated offer-family IDs are qualification-response IDs, not catalog IDs. Storage evidence already carries both. Trace exact terms, policy, model, population, dates and campaign in every join.

Feedback enrollment after publication is automatic within the product. Proposed worker cadence is daily, plus analyst on-demand queries; schedule is configurable. It emits an analysis request when an observation window matures, sufficient support appears, a configured deviation threshold is crossed or source quality changes. It does not launch an unlimited simulation loop. A previously approved monitoring-only budget does not authorize new simulation spend or publication.

## 11. Explaining an 80% versus 40% gap

Resolve “the offer from two weeks ago” against analyst-visible publication history; clarify if several match. Load the immutable original prediction and its kind. Compute the matching observed cohort as-of now. Never invent the example's numbers.

An answer template is:
“At [as-of], [Y] of [E] eligible customers selected this offer family: [rate]%. The saved estimate was [estimate]% and was [uncalibrated utility / calibrated outcome forecast]. [Maturity/coverage]. The rates [share/do not share] the same target definition; [specific limitations].”

Investigation sequence:
1. Verify denominator, offer/rule/terms/variant identity, timestamps, data lag, follow-up maturity and duplicate handling.
2. Compare simulated versus observed population mix and feature coverage.
3. Examine Step 2 capacity/suppression, Step 3 missing/declined bureau facts, Step 5 creation/personalization, Step 7 delivery restrictions, and Step 8 exposure.
4. Compare acceptance by sufficiently supported, predeclared segments and within matched/eligible-for-both groups.
5. Research plausible explanations applicable to the product, geography, population and period; attach support and counterevidence.
6. State facts, model-derived associations and hypotheses separately. A higher credit-score threshold does not prove customers were more willing to select because they were “more credible.”
7. Reviewer checks the explanation and produces a constrained next-experiment request.

If the analyst says “do not touch underwriting,” freeze all underwriting fields at the new baseline and carry that lock through every refinement and publication. Possible changes are restricted to explicitly authorized offer/marketing/bureau fields, not everything in those stages by default. If the observed gap is mainly lack of exposure, say so; do not fabricate a rule change as the cure.

### Calibration and learning

Retain the utility formula as a named scenario metric. Introduce a separately versioned **selection outcome model** only when real, mature, sufficiently supported labels exist. A simple regularized probability model or calibrator can run in Java, using pre-decision feature snapshots and offer/choice-set context; it is not an additional LLM agent.

The training target must match Y/E and the forecast scope. Include eligible non-selectors only after their follow-up closes. Split by time/publication/customer to prevent leakage; hold a final temporal evaluation set; report calibration error, Brier/log loss, support and segment stability. Exposure information is for prediction only when known at prediction time; otherwise model exposure prospectively or use it strictly for diagnosis.

Evaluate candidate models offline against the current model and baseline. Activate only after explicit model-policy gates; retain rollback, dataset hash and evaluation report. Research-based conjecture cannot alter labels or train a new credit decision rule. Feedback never changes underwriting/eligibility authority. Observational model fit alone cannot establish causal uplift; controlled experiments or defensible causal study designs would be separate future work.

## 12. Publication transaction and authority

Only a completed tested candidate can reach a publication preview. Reflection produces advice, not authorization. The preview contains full canonical offer/rules, changed and unchanged fields, chosen final-validation run, baseline catalog/campaign/policy versions, metric contract, evidence, limitations, review note and a content hash.

The analyst sees the concrete preview. “Good, let's push the offer” is valid confirmation when the conversation has exactly one current preview selected. If ambiguous, ask which candidate. The AI Interaction API creates a short-lived single-use approval token bound to authenticated analyst, preview hash, run, draft version, scope revision and review note. The model cannot mint or modify it.

The gateway calls the existing POST /api/v1/business/runs/{id}/publish with confirm=true and the approved note. Notes must satisfy the existing 10–1000 character constraint; the analyst reviews any AI-drafted note. The engine remains the transaction authority:
- Completed run and latest draft must match.
- Source offer/campaign/global-policy snapshots must still match.
- Offer and campaign must be active and in date, with catalog capacity available.
- Offer, campaign, rules and receipt commit together.
- Repeating the identical run/note fingerprint recovers the same receipt; conflicting review data returns a conflict.

Use the final validated run as the publication run and store its link to the optimization candidate. One candidate gets its own draft; other candidates never edit that draft. After testing, no business-field edit is permitted without a new version and new runs.

On an ambiguous timeout, query the engine publication receipt and retry only the same publication identity and exact note. Never blindly create a second draft/run to recover. The AI-side approval/job state is not in the H2 catalog transaction, so reconcile it against the authoritative receipt. If a crash follows engine commit, recovery completes local lineage from that receipt before reporting success.

“Published” means a new offer/campaign in the existing active catalog; the response includes their IDs and the tested rule version. Model promotion, customer reprocessing, external delivery and retirement of the prior offer are separate operations. This scope preserves the engine's current behavior.

## 13. Proposed interfaces and persistence

These paths are proposals under /api/v1/ai; existing /business endpoints retain their contracts.

| Interface | Purpose |
|---|---|
| POST /conversations; POST /conversations/{id}/messages | Structured analyst intents, follow-ups and clarification answers |
| GET /workflows/{id}; GET /workflows/{id}/events | Durable status and cursor-based progress; optional SSE |
| POST /workflows/{id}/scope-confirmations | Approve exact parameter scope/ranges/budget; optimistic revision |
| POST /workflows/{id}/cancel | Stop further agent work and admissions; track in-flight work |
| GET /experiments/{id}/candidates; GET /candidates/{id} | All trials, diffs, metrics, errors, cache/retry lineage |
| GET /artifacts/{id} | Authorized immutable reports/evidence; paged data where large |
| GET /publications/{id}/performance | As-of metric snapshot, cohort/maturity and forecast comparison |
| POST /publication-previews; POST /publication-previews/{id}/confirm | Concrete publication review and analyst-authorized commit |

Every state-changing request has a caller-scoped idempotency key and canonical request hash. Same key/different content is a conflict. Lists use bounded cursor pagination. A report reference is access-controlled even if its ID is known.

| Logical store | Authoritative contents |
|---|---|
| workflows / workflow_steps / agent_tasks | Task graph, A2A identity mapping, revisions, state, leases, parent/child relationships |
| baseline_snapshots / authorized_scopes / parameter_registry_versions | Reproducible baseline, permitted paths, stage locks, rules and schema versions |
| evidence / evidence_claims | Source URI, publisher, retrieved/published dates, applicability, excerpt/hash, claim-to-range linkage, contradictions |
| experiment_plans / candidates / evaluations | Seed/domain/strategy, canonical changes, fingerprints, engine draft/run IDs, status and costs |
| prediction_snapshots / analysis_reports / reflections | Immutable estimates, metric kind/target, evidence/limitations, challenge and revision records |
| budget_ledger / tool_calls / inbox / outbox | Reserved and consumed resources, attempt receipts, dedup and reliable scheduling |
| publication_requests / publication_links | Analyst confirmation, exact payload/hash, engine receipt, successor/predecessor relationship |
| assessment_facts / eligibility_enrollments / selection_facts / exposure_facts | Versioned source facts, business identity, occurred/ingested times, source mode and corrections |
| cohort_snapshots / metric_snapshots / model_registry | Frozen denominators, mature outcomes, as-of/revision/coverage, calibrator evaluations and versions |

One physical PostgreSQL instance can host separate schemas initially. Ownership and credentials stay separate; agents have API access to outcome aggregates, not raw shared-table write privileges. Apply additive versioned migrations during implementation; preserve existing migration checksums and strict fixtures. Keep source documents/results in private artifact storage with hashes and retention classification. Avoid raw customer data in LLM traces.

## 14. Reliability and resource control

The existing experiment runner is not an unlimited distributed queue. Place a durable scheduler in front of it. Default one submission in flight and reserve capacity for interactive work; handle engine 429 with bounded backoff. A future scale-out worker design requires separate validation and is not assumed by this proposal.

Existing POST /business/runs does not accept a business idempotency key. The AI adapter must not blindly retry an uncertain POST. Implementation must add narrowly scoped idempotent admission/lookup (or an equivalent engine-owned run-admission key) before automated retries are enabled. Until then, reconcile by unique candidate draft/version and recorded run list; an ambiguous duplicate is held for reconciliation. API support for a request key is the required production solution.

Likewise, prepare candidate draft creation with an engine-owned idempotency key, or reconcile an uncertain create before editing/submitting it. This is an additive integration gap, not an existing guarantee. Never use AI-side memory alone for exactly-once admissions.

| Condition | Required behavior |
|---|---|
| Model timeout/refusal/invalid schema | Bounded retry or explicit task failure; preserve tool results already committed |
| Research unavailable/contradictory | Mark unsupported/conflict; request analyst input or remain within approved internal evidence |
| Locked field changed / stale scope | Reject before run; record POLICY_SCOPE_VIOLATION; no automatic unlocking |
| Engine busy/unavailable | Wait with lease and deadline; do not count unavailable result as poor eligibility |
| Run fails/partial series | Keep failure detail; exclude failed metrics from ranking; report coverage/partial status |
| Cancellation | Stop admissions/model calls; request child cancellation; existing run cancellation is not implemented, so let that engine run finish and suppress further workflow use/publication |
| Process restart | Resume saved A2A tasks/workflow leases; reconcile candidate admissions and publication receipts |
| Catalog/rules changed | Invalidate preview and require fresh baseline/testing; preserve old evidence |
| Outcome source lag/duplicates | Reconcile/deduplicate; surface watermark/coverage and provisional status |
| No eligible customers | Null conditional acceptance, explicit E=0; never divide by zero |
| Insufficient real labels | Retain utility scenario; calibrated forecast unavailable |
| Budget exhausted | Return completed results plus stop reason; request additional budget only for additional work |

Reserve budget atomically before dispatch and settle actual usage afterward. Cap retries, tool-loop iterations, delegation depth and total calls separately. Support a workflow deadline and per-call timeout. A2A cancellation and a closed SSE stream do not imply rollback of an engine action.

## 15. Deployment, access and observability

Initial deployment: six Java agent containers/processes, a Java interaction/tool/outcome tier, PostgreSQL AI schemas and private artifacts. Keep engine/credit/marketing services at their existing boundaries and ports. Give new services non-conflicting ports (for example 8100–8110, configuration only). Do not reuse the sibling a2a demo's overlapping 8090–8094 ports.

The model gateway connects outbound to OpenAI; research adapters access approved sources. The analyst sees that AI requests send approved data outside the machine; the original local-only architecture changes at this specific boundary. Default model input is rule/catalog data, synthetic facts and aggregate outcome summaries. Raw bureau reports, direct customer identifiers and free-text profile data stay behind internal tools unless separately approved by the data policy.

Bind development listeners to loopback/private networks. Before shared use, introduce authenticated analyst identity and scoped service credentials; current terminal mode selection is not authorization. Publication approval requires a real identified analyst even in a local pilot (single configured operator identity is acceptable as an explicit pilot constraint).

Record workflow/task/experiment/candidate/run/publication trace IDs; model and prompt version; tool receipts; evidence source IDs; stage latency; token/cost/evaluation usage; retries; queue depth; invalid output; scope violations; feedback freshness and forecast-error diagnostics. Audit decisions and tool observations, not private reasoning transcripts or secrets.

Use HTTPS/service identity for A2A and MCP, restrict artifact access by analyst/workflow ownership, and keep publication credentials away from general model/tool contexts. Research retrieval enforces destination controls and content-size limits. A document's instructions cannot override the analyst's locked fields or the tool gateway's policy.

## 16. Implementation order and acceptance gates

1. **Metric and provenance foundation.** Add derived simulation acceptance percentage, metric contract, publication/offer/variant lineage and historical eligible-cohort/selection adapters. Verify exact-term and family measures plus DEMO/REAL separation.
2. **Deterministic experiment service.** Add parameter registry, field-mask enforcement, idempotent draft/run admission, immutable candidate drafts, budgets, discovery/final populations and results tables. Demonstrate coarse/refined search without any model.
3. **Java A2A agents.** Add the six independently deployable services, pinned SDK compatibility, GPT-6 Astra structured I/O, tool schemas, real task persistence and bounded delegation.
4. **Analyst journey and publication.** Add the terminal AI option, clarification/range approval, progress, evidence/report navigation, concrete review and receipt-based publication.
5. **Feedback and reflection.** Add all-step evidence connectors, performance queries, source-quality checks, gap explanations and constrained successor experiments.
6. **Observed calibration.** Enable a versioned family-selection forecast only after real data, maturity, comparability and evaluation gates pass.

Verification targets for implementation (not tests run for this document):
- Contract interoperability with an independent A2A client; malformed input/output and version mismatch.
- Every unmentioned/locked field remains identical for all candidates and every refinement.
- Zero eligibility, sentinel score 0, mixed boolean/numeric domains, invalid dates and product-specific no-op fields.
- Budget never exceeded by concurrent tasks, restarts or retries; no exponential grid allocation.
- Same candidate/input/version produces the same recorded evaluation or a traceable retry; uncertain POST cannot duplicate silently.
- No use of the outer final population during search; later re-optimization rotates validation data.
- Replayed selections and expired current eligibility do not inflate or erase historical counts; partial cohorts stay provisional.
- 80/40 conversation uses saved estimates and computed observations, with target/mode mismatch disclosed.
- “Do not touch underwriting” survives research, optimizer, direct peer delegation and publication.
- Adversarial documents/MCP metadata cannot unlock tools, inject unsupported parameter IDs or approve publication.
- Publish timeout after commit recovers the original receipt; changed note/draft/catalog causes a conflict.
- Existing manual Business and Customer Steps 1–8 behavior remains intact.
- Small/unsupported cohorts, personalization/choice-set shifts and exposure gaps cannot generate a causal assertion.

## 17. Design decisions still configurable

These are product/deployment settings, not unanswered blockers: follow-up window and entry-period policy; support/floor thresholds; simulation/model/cost budgets; allowed internal/external research sources; artifact retention; investigator query permissions; observation cadence; outcome-model activation gates; Java SDK patch versions.

The architecture deliberately does not assign real financial thresholds from general web text. An approved internal policy is the authority for permissible ranges. Technical input bounds in the existing code are format/engine constraints, not recommended lending policies.

## 18. How to use the artifacts

The Excalidraw file contains:
1. Services and trust boundaries.
2. Analyst request through bounded simulation and review.
3. Discovery, structured A2A tasks and tool calls.
4. Frozen fields and coarse-to-fine search.
5. Eight-step outcome ingestion and metric alignment.
6. Feedback, reasoning patterns and constrained revision.
7. Publication, persistence and recovery.

Green = analyst/UI actions. Yellow = owned backend, Java agents, deterministic services and data stores. Red = external provider/services. Existing engine components are labeled EXISTING; additions are labeled PROPOSED. Connector labels are small unboxed text. View references connect sections; the full document supplies exact behavior and contracts.

All numeric search settings and sample messages are proposed or illustrative. No live simulation, API model call, publication, calibration or integration test was performed to create this architecture.
