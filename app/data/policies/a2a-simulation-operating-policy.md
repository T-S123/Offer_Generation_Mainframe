# Offer research, measurement and analyst-interaction policy

Document ID: A2A-DEMO-OPERATING-3
Version: 2026-11-10
Product: PERSONAL_LOAN
Geography: US
Owner: Lending Studio research and performance demonstration

## Mandate and authority

The companion personal-loan policy authorizes bureau minimumScore research
only within 680-740 inclusive, integer step 1. Underwriting, offer terms,
marketing and all other bureau fields remain fixed for that mandate.
This operating policy adds no parameter ranges or lending exceptions.
Approved range evidence must identify the parameter, product and bounds;
market statistics and statistical examples do not authorize rule changes.

The request, stage locks, approved internal limits and owner APIs govern.
Conflicting hard constraints produce BLOCKED with a concrete reason. Lack of
permission is not an invitation to ask the user to bypass a rule.
Publication requires review of the exact tested preview and separate explicit
confirmation. Customer acceptance is never an agent action.

These documents are approved local research governance, informed by public
financial sources. They do not make the synthetic generator representative
of U.S. applicants or certify regulatory compliance.

## Network 1: conversational planning before simulation

Turn a broad goal into a narrow experiment through model-generated conversation.
Clarify meaningful uncertainty about the objective, requested score field,
permitted search scope and tradeoffs before simulations. Do not silently replace
an unresolved user preference with a default merely to avoid a conversation.
Research retrieves policy facts; do not ask the analyst to supply them again.

Use zero to three persisted NEEDS_INPUT rounds across the entire workflow,
including execution, final review and linked follow-ups. When several gaps
remain, group related questions into one concise numbered prompt. The model
chooses the questions and their wording; no fixed questionnaire or random
question trigger is required. A completely clear request can use zero rounds.
An interaction rate around 75-80% across representative underspecified requests
is a behavioral evaluation target, not a guaranteed per-run quota.

Before simulation, scope interpretation and plan review must describe the
resolved objective and scope, report task-clarity confidence of at least 0.9
and leave no unresolved decisions. This is a model self-assessment, not a
calibrated probability, statistical interval or claim of financial certainty.
The application still validates exact fields, supported ranges and locks.

After the third answer, resolve residual ambiguity with conservative assumptions
inside the explicit request and approved bounds. Record those assumptions in
the saved workflow and final output. Do not ask a fourth question, override an
explicit answer, invent financial facts or expand authority. If hard constraints
leave no valid experiment, explain the constraint instead of running one.
Keep previous answers through retries, restarts and revised experiments.

Completed results remain available for user-initiated discussion within the
remaining model budget. Discussion cannot mutate completed trials, publish,
or launch another experiment. Additional input pauses share the same limit.

## Working defaults and the other networks

For Network 1, use these defaults for routine execution details, explicit
delegation by the analyst, or conservative resolution after the third round.
They do not replace clarification of an ambiguous goal while rounds remain.
Network 2 retains its material-decision policy. Network 3 uses the six-exchange
customer conversation policy below.

| Situation | Default action |
| --- | --- |
| Credit score is requested without a stage | Network 1 clarifies meaningful ambiguity; after its round limit, use bureau minimumScore if unlocked and record the assumption |
| User asks for better eligibility and acceptance | Keep the two objectives separate and return both best-tested winners |
| Acceptance and eligibility winners differ | Explain the tradeoff; do not require a preferred winner to finish research |
| Next exploration/refinement choices are routine | Use the existing optimizer, permitted range and actual phase allocation |
| Budget, floors, support or locks are already supplied | Use them exactly, including explicit zeros |
| Small cohort or uncertain utility | Report the limitation; do not demand a new confidence target |
| No candidate passes the gates | Return no supported winner for that objective |
| Optional segment breakdown is unspecified | Report the requested aggregate first; do not ask for a segmentation preference |
| Investigation has no requested dates | Use all recorded history through the evidence cutoff, with disclosed scope |
| Date basis or timezone is unspecified | Use FIRST_ASSESSED and America/Los_Angeles |
| Exact personal-loan publication/customer is unambiguous | Use the supplied identity; do not ask the user to confirm it again |
| Cause of an outcome difference is not recorded | State that the cause is undetermined; hypotheses remain labeled |
| Customer preferences are sufficient | Design from the saved profile and owner limits without a new interview |
| Customer financial facts are missing or changed | Use owner API status; never fill gaps with invented values |
| Optional improvement is outside the current request | Mention it in the result; do not pause or start another experiment |

Defaults apply only when consistent with explicit user choices and enforced
constraints. A submitted request and budget authorize the existing simulation
phases. Choosing an experiment setting is different from publishing an offer.

## Network 3 customer conversation

After a saved profile update finishes qualification, open a session-bound offer
conversation; initial signup permits manual access. Prefer useful model-written
questions about unresolved goals, payment/cost tradeoffs and relevant financial
outlook. Bundle related gaps within at most six input rounds across all roles.
Customer replies can guide terms but cannot rewrite verified facts or eligibility.
Review actual candidates with the customer and revise when feedback calls for it.
A sufficiently informed request may proceed without questions. A model assessment
of at least 0.9 with supporting comparisons can conclude that no useful permitted
improvement remains; this is not calibrated financial confidence. After six rounds,
use conservative assumptions and explain them. No forced questions or random quota.
Compare current eligible offers and disclose unavailable acceptance/profit measures.
Unselected quotes survive refresh but expire on logout/session expiry; selected
terms and their audit records persist. Only explicit customer selection accepts.

## Human input in Networks 2 and 3

The following material-decision rules apply to Network 2 investigations.
Networks 1 and 3 use their respective conversation allowances.

Before NEEDS_INPUT, check the saved request, previous answers, executionContext,
owner facts, approved evidence and available tools. Ask one focused question
only when all of these leave a material decision unresolved and choosing would
change the authorized scope, the records measured, or a necessary customer
preference. Explain the concrete next-action difference and offer useful choices.

Examples of material ambiguity: two matching publications with no supplied
identity; an unclear date phrase that cannot be resolved from context; an
individual variant versus a catalog family; incompatible customer preferences
where feasible options genuinely require a choice. Do not fabricate ambiguity.

Do not ask "shall I continue?", "which score should I try?", "is five customers
enough?", "what budget did you mean?", or "should I report both rates?" when
the context already answers it. Do not turn small samples, a missing causal
explanation, or an ordinary review limitation into a mandatory questionnaire.
Apply prior answers throughout the same run without asking again in new words.

NEEDS_INPUT is a persisted pause, not a timer-driven interview. The application
extends the deadline for human waiting but does not increase spend or trial
caps. REVISE handles internal repairs; BLOCKED handles unsatisfied hard
constraints. Neither state should contain disguised requests for basic input.

## Measurement contract

| Measure | Definition and denominator |
| --- | --- |
| Eligibility | 100 * eligible customers / assessed customers in the defined cohort |
| Recorded acceptance | 100 * customers with a qualifying recorded selection / eligible customers, under the configured follow-up window |
| Synthetic acceptance proxy | 100 * simulated expected selections / eligible synthetic customers |
| Selection per assessed customer | 100 * selected / assessed; optional and distinct from acceptance among eligible |
| Credit loss / delinquency | Requires the relevant repayment records and a separately defined measure; not inferred from selections |

Use owner-returned measurements and deduplication. Report numerator,
denominator, missing evidence, coverage and maturity, together with source
mode, publication/version, variant, cohort, date basis, timezone, window and
evidence cutoff. No eligible customers means unavailable acceptance. A missing
historical decision means not recorded, not ineligible.

Period aggregation combines counts from non-overlapping, consistently defined
cohorts. Do not average daily percentages or add overlapping customer groups.
Illustration only: 8 selections among 10 eligible plus 20 among 100 yields
28/110 = 25.45%, not the 50% unweighted mean of 80% and 20%.
A 40% result versus an 80% reference differs by -40 percentage points,
or -50% relative to that reference; label the unit and check comparability
before interpreting either difference. These are arithmetic examples, not
recorded platform outcomes.

## Uncertainty and real-world statistical interpretation

For recorded binary outcomes, use deterministic statistics tools when supplied.
A Wilson interval is preferable to blindly applying a symmetric normal
interval, especially near zero or one; exact binomial methods can be useful
for small counts. [NIST confidence-interval reference][nist]. Do not calculate
an invented interval in prose when the available service does not provide it.

Calculated teaching example, not observed data: 4 selections from 5 independent
eligible customers gives 80%, but a two-sided 95% Wilson interval is about
37.6%-96.4%. Thus five eligible customers can satisfy a demo execution gate
without establishing a stable conversion rate. An interval assumes an
appropriate sampling/outcome model; it does not repair selection bias or
incomplete follow-up. Do not apply a binomial interval to fractional expected
selections from the utility model.

Larger synthetic cohorts reduce generator noise; they do not establish external
validity. A different synthetic seed is a useful held-out check, not an external
population or an independent field experiment. A minimum sample size, detectable
effect and confidence level are study-design choices; do not silently turn
examples into new hard execution gates.

## Experiment protocol

1. Retain the saved baseline and the allowed field set. Use the same discovery
   population for baseline/candidate comparisons to make differences interpretable.
2. Use approved bounds, integer steps and the deterministic optimizer. Test a
   bounded search, then refine promising discovery configurations.
3. Preserve the actual budget from executionContext. In the standard 20-attempt
   demonstration, maxima are 1 baseline, 12 exploration, 5 refinement and
   2 final-validation evaluations. These are caps, not a mandate to consume them.
4. Keep discovery and validation population identities and generation seeds
   distinct. Freeze the shortlist before inspecting final validation.
5. Report discovery and validation separately. Do not retune this experiment
   against final-validation results or call the best tested point a global optimum.
6. Keep each objective's configured support and cross-metric floor. Zero floors
   mean no additional cross-metric floor, not waived support. No supported
   winner is an acceptable result.
7. Explain whether changed eligibility altered the measured customer population.
   An acceptance increase among a narrower set need not mean more total selections.
8. Leave publication to the explicit review/confirm flow. Retain the original
   prediction, definition and configuration as immutable evidence.

The default minimum eligible support of 5 and the standard $10 demo cap are
internal demonstration settings, not statistical or financial industry norms.
The actual submitted budget overrides these examples. Java reserves trials and
model calls before dispatch; internal repairs consume that same budget.

## Investigating predicted versus recorded results

First establish target compatibility: catalog terms versus personalized terms,
offer family versus exact variant, source mode, population definition,
follow-up maturity, outcome definition and evidence cutoff. The Performance
API's comparability result is authoritative; only MATCH permits its forecast
gap. Never coerce a mismatch into MATCH through analyst clarification.

A simulation utility percentage is not automatically a calibrated probability
of acceptance. Side-by-side figures can be a diagnostic comparison, with an
explicit limitation. Missing exposure, maturity or individual-decision evidence
must stay visible. A positive selection is not proof of loan funding or repayment.

For a recorded change, inspect available counts, cohort mix, qualification
versions, terms, coverage and timing before proposing explanations. Pricing,
customer mix and distribution can be hypotheses only where relevant; do not
assert they occurred without evidence. Missing campaign context is normally a
limitation, not a reason to interrupt the user. Ask for context only when it is
necessary to the requested investigation and the user can resolve it.

Network 2 reads evidence and requests deterministic calculations. Its dashboard
reads Performance APIs without agents. An investigation cannot run simulations,
modify a rule or republish. Network 1 requires a separate authorized request
for a new experiment.

## Using and refreshing this pack

Research uses approved extracted excerpts with content-hash citation IDs.
Supporting empirical material must retain its source, period and denominator;
approval of this document does not turn external context into a bank credit
limit. When citing this pack, identify the section as an internal decision,
external observation or calculation.

An edit invalidates the old content approval. In Business > Policy evidence,
refresh the index, review the new extraction and approve the new hash for
PERSONAL_LOAN, US and an appropriate expiry. Do this once per changed document,
not once per agent task. Restart AI services after a code build to load revised
agent instructions. The Demo A2A preparation path installs and approves the
matching bundled versions as part of its existing reset-and-prepare operation;
it must not be used merely to refresh policies in a database you want to keep.

Current code still owns calculations, admission, qualification, budget
enforcement, publication and acceptance. Policy prose guides decisions without
duplicating those rules in a browser or granting agents write access.

[nist]: https://itl.nist.gov/div898/handbook/prc/section2/prc241.htm
