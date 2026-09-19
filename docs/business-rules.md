<!-- Documents demonstration policy ownership and the distinction between business decisions and infrastructure. -->
# Demonstration rule ownership

Business users can now create immutable, form-based rule versions for underwriting, marketing and bureau qualification. `LIRL01C.cbl` executes these offer-scoped forms; `BusinessRules` validates their shape and bounds. Simulation and active qualification use the same evaluator. Publishing attaches the tested rules to a new offer/campaign, preserving existing policies described below. Consent, opt-out, suppression and matched bureau identity remain mandatory. See [business-simulation.md](business-simulation.md) for editable fields, evaluation, source precedence and publication controls.

**SIM-2026-001** is an illustrative local underwriting policy. It is not a lender policy, BIAN rule, calibrated credit model or actual approval. The executable source is `app/cbl/LIUW01C.cbl`; changes to that policy must change its version. The table documents that source rather than implementing a second rule engine.

| Check | Personal loan | Credit card | Auto loan |
| --- | ---: | ---: | ---: |
| Minimum monthly gross income USD | 2000 | 1500 | 1800 |
| Minimum credit score | 660 | 640 | 620 |
| Maximum debt-to-income percent | 40 | 45 | 45 |
| Maximum delinquencies in 12 months | 1 | 1 | 1 |
| Maximum requested amount USD | 50000 | 25000 | 100000 |
| Maximum requested amount / monthly income | 24 | 6 | 36 |
| Maximum credit utilization percent | not used | 80 | not used |
| Maximum vehicle age years | not used | not used | 12 |
| Maximum loan-to-vehicle-value percent | not used | not used | 110 |

Income, debt payments, credit score, delinquency count and the relevant requested amount are required for each product's simulated assessment. Cards additionally require utilization; auto loans require vehicle value and age. Unknown inputs produce INCOMPLETE before threshold evaluation. Known zero income produces an ineligible result. Requested amounts and auto vehicle value must be positive to pass their relevant checks.

DTI compares `monthly debt * 100` against `monthly income * threshold`; LTV compares `loan amount * 100` against `vehicle value * 110`. This avoids floating-point currency calculations and division by zero. Equality at a threshold passes. All applicable rejection reasons are returned after completeness has passed.

## Supporting policies

- API input integrity: `CustomerRules.validate`. Monetary amounts have at most two decimal places and must fit the copybooks. Out-of-range input is rejected rather than silently clipped. Optional cohort attributes do not affect underwriting.
- Decisions: each simulated result expires after 30 calendar days. `validUntil` is inclusive in UTC. Imports retain their source's policy and expiration. They must identify the current stored profile version, not an unrelated customer snapshot.
- Effective decision: `Model.Customer.latest` uses assessment timestamp, with ingestion order breaking ties. Expired latest assessments never fall back to older approvals.
- Pre-screen: `CustomerRules.exclusionReasons` requires an eligible current assessment and an explicit absence of prescreen opt-out. Preference missing, stale, expired, declined and incomplete outcomes are excluded and counted. This is Step 1 population readiness. Step 2's `LIMK01C` additionally checks marketing opt-in, current source references, global suppressions, campaigns, offers and throttling.
- Simulation: `Generator` generates inputs from a seed, including edge cases. It never writes an eligibility outcome. The same COBOL binary evaluates manual and synthetic customers.
- Update/import/publication concurrency and idempotency: `CustomerEngine` application workflows and `SqlStore` transactions.

The interface exposes reasons, provenance and versions, rather than a misleading probability or confidence score for these deterministic demonstration rules. ML cohorting is not implemented in Step 1.

## Marketing qualification policy

`app/cbl/LIMK01C.cbl` owns demonstration rule version **MKT-2026-001**. Its configurable values are stored in retained marketing policy and campaign revisions. `services/.../marketing/domain/MarketingRules.java` owns catalog integrity and reservation time semantics; `MarketingEngine` owns atomic preview/finalize/cancel workflows. See [marketing-qualification.md](marketing-qualification.md) for every rule, reason code, default and boundary. [bian-marketing-mapping.md](bian-marketing-mapping.md) distinguishes BIAN vocabulary from demonstration policy values.
