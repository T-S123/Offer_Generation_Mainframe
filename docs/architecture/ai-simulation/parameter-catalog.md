# Parameter catalog for AI-assisted simulation

**Proposed registry, derived from existing forms and validators on 27 September 2026.** This document supplies design metadata; it does not change the schemas or policies.

The terminal exposes **42 editable fields: 9 offer + 11 underwriting + 11 marketing + 11 bureau**. Of these, 38 are numeric/boolean financial or rule controls; name, activation and two dates are handled separately. Product is fixed by the chosen draft. Actual optimization dimensions are the analyst-authorized, policy-valid, product-applicable subset.

These are engine-valid domains, not recommended lending thresholds. Every proposed range must also satisfy approved bank policy, analyst scope and applicable evidence. Baseline values come from the selected immutable draft; this file does not invent policy defaults.

Sources: [terminal edit fields](../../../services/src/main/java/com/lending/engine/terminal/SimulationTerminal.java#L57), [rule validation](../../../services/src/main/java/com/lending/engine/simulation/domain/BusinessRules.java#L36), [offer validation](../../../services/src/main/java/com/lending/engine/marketing/domain/MarketingRules.java#L17), [money precision/bounds](../../../services/src/main/java/com/lending/engine/domain/CustomerRules.java#L51), [COBOL stage behavior](../../../app/cbl/LIRL01C.cbl#L31).

## Registry metadata

Each parameter entry needs parameterId (canonical JSON Pointer), stage, semantic label, fact source, value type, unit, precision/step, technical domain, policy-domain reference/version, baselineValue, sentinel semantics, applicableProducts, dependsOn, isSearchable, lockReason and evidence requirements. Use BigDecimal/integer arithmetic and canonical decimal serialization. Client-visible aliases are resolved to canonical IDs before any tool call.

## Offer: 9 fields

| Canonical path | Type | Engine-valid domain | Unit | Search interpretation |
|---|---|---|---|---|
| /offer/name | string | 1–60 characters | label | Metadata; fixed for search. Display name may be assigned separately. |
| /offer/active | boolean | false / true | boolean | Fixed by default. Publishing requires active=true. |
| /offer/startsOn | date | valid YYYY-MM-DD | date | Fixed by default; only explicit discrete dates may vary. |
| /offer/endsOn | date | valid YYYY-MM-DD ≥ startsOn | date | Fixed by default; selection horizon also tracks expiry. |
| /offer/minimumAmountUsd | decimal | >0 and ≤ maximumAmountUsd; ≤999999999.99; 2 decimals | USD | Eligibility bound; does not change a customer’s requested amount. |
| /offer/maximumAmountUsd | decimal | ≥ minimumAmountUsd; ≤999999999.99; 2 decimals | USD | Cross-check with stage caps; remain explicit, no silent coupled edits. |
| /offer/illustrativeAprPct | decimal | 0–100; 2 decimals | percent APR | Research/policy limits usually narrower; 100 is only a code bound. |
| /offer/annualFeeUsd | decimal | 0–999999999.99; 2 decimals | USD/year | Policy/research domains required; code bound is not an economic recommendation. |
| /offer/termMonths | integer | CREDIT_CARD=0; loans 1–120 | months | Card term locked to 0. Discrete product-supported loan terms. |

## Underwriting: 11 fields

Credit score and financial inputs are from customer-data assessments, distinct from bureau observations.

| Canonical path | Type | Engine-valid domain | Unit | Sentinel / applicability |
|---|---|---|---|---|
| /rules/underwriting/minimumScore | integer | 0 or 300–850 | points | 0 disables score threshold; explicit permission required. All products; stage selects self-reported versus bureau source. |
| /rules/underwriting/minimumIncomeUsd | decimal | 0–999999999; 2 decimals | USD/month | 0 is a permissive threshold. All products. |
| /rules/underwriting/maximumDtiPct | integer | 0–999 | percent | 999 is the demonstrated unbounded cap. Bureau includes proposed payment; customer-data stages use reported debt. |
| /rules/underwriting/maximumUtilizationPct | integer | 0–100 | percent | 100 is permissive. All products in the generic rule policy. |
| /rules/underwriting/maximumDelinquencies | integer | 0–99 | count / 12 months | 99 is permissive. All products. |
| /rules/underwriting/maximumAmountUsd | decimal | 0–999999999; 2 decimals | USD | 0 admits no positive amount. Requested principal/limit cap, not an instruction to change requested principal. |
| /rules/underwriting/incomeMultiple | integer | 1–999 | multiple of monthly income | 999 is permissive. Customer-data stages only. |
| /rules/underwriting/maximumLtvPct | integer | 1–9999 | percent | Large values are permissive. AUTO_LOAN only; inactive for other products. |
| /rules/underwriting/maximumVehicleAge | integer | 0–99 | years | 99 is permissive. AUTO_LOAN; customer-data stages only. |
| /rules/underwriting/minimumTenureMonths | integer | 0–1200 | months | 0 disables tenure floor. Customer-data stages only. |
| /rules/underwriting/excludeExistingProduct | boolean | false / true | boolean | Enumerate allowed values; no numeric interpolation. Customer-data stages only. |

## Marketing: 11 fields

Credit score and financial inputs are from customer-data assessments, distinct from bureau observations.

| Canonical path | Type | Engine-valid domain | Unit | Sentinel / applicability |
|---|---|---|---|---|
| /rules/marketing/minimumScore | integer | 0 or 300–850 | points | 0 disables score threshold; explicit permission required. All products; stage selects self-reported versus bureau source. |
| /rules/marketing/minimumIncomeUsd | decimal | 0–999999999; 2 decimals | USD/month | 0 is a permissive threshold. All products. |
| /rules/marketing/maximumDtiPct | integer | 0–999 | percent | 999 is the demonstrated unbounded cap. Bureau includes proposed payment; customer-data stages use reported debt. |
| /rules/marketing/maximumUtilizationPct | integer | 0–100 | percent | 100 is permissive. All products in the generic rule policy. |
| /rules/marketing/maximumDelinquencies | integer | 0–99 | count / 12 months | 99 is permissive. All products. |
| /rules/marketing/maximumAmountUsd | decimal | 0–999999999; 2 decimals | USD | 0 admits no positive amount. Requested principal/limit cap, not an instruction to change requested principal. |
| /rules/marketing/incomeMultiple | integer | 1–999 | multiple of monthly income | 999 is permissive. Customer-data stages only. |
| /rules/marketing/maximumLtvPct | integer | 1–9999 | percent | Large values are permissive. AUTO_LOAN only; inactive for other products. |
| /rules/marketing/maximumVehicleAge | integer | 0–99 | years | 99 is permissive. AUTO_LOAN; customer-data stages only. |
| /rules/marketing/minimumTenureMonths | integer | 0–1200 | months | 0 disables tenure floor. Customer-data stages only. |
| /rules/marketing/excludeExistingProduct | boolean | false / true | boolean | Enumerate allowed values; no numeric interpolation. Customer-data stages only. |

## Bureau: 11 fields

Credit score and report/history facts are bureau observations. Income can originate in customer data; do not label every input as bureau-verified.

| Canonical path | Type | Engine-valid domain | Unit | Sentinel / applicability |
|---|---|---|---|---|
| /rules/bureau/minimumScore | integer | 0 or 300–850 | points | 0 disables score threshold; explicit permission required. All products; stage selects self-reported versus bureau source. |
| /rules/bureau/minimumIncomeUsd | decimal | 0–999999999; 2 decimals | USD/month | 0 is a permissive threshold. All products. |
| /rules/bureau/maximumDtiPct | integer | 0–999 | percent | 999 is the demonstrated unbounded cap. Bureau includes proposed payment; customer-data stages use reported debt. |
| /rules/bureau/maximumUtilizationPct | integer | 0–100 | percent | 100 is permissive. All products in the generic rule policy. |
| /rules/bureau/maximumDelinquencies | integer | 0–99 | count / 12 months | 99 is permissive. All products. |
| /rules/bureau/maximumAmountUsd | decimal | 0–999999999; 2 decimals | USD | 0 admits no positive amount. Requested principal/limit cap, not an instruction to change requested principal. |
| /rules/bureau/maximumLtvPct | integer | 1–9999 | percent | Large values are permissive. AUTO_LOAN only; inactive for other products. |
| /rules/bureau/maximumInquiries | integer | 0–99 | count / 6 months | 99 is permissive. Bureau only. |
| /rules/bureau/minimumHistoryMonths | integer | 0–1200 | months | 0 disables history floor. Bureau only. |
| /rules/bureau/maximumReportAgeDays | integer | 0–99999 | days | 99999 is permissive. Bureau only. |
| /rules/bureau/allowBankruptcy | boolean | false / true | boolean | Policy-controlled explicit categorical permission. Bureau only. |

## Non-searchable fields and invariant rules

- Underwriting and marketing records also contain bureau-only fields, fixed at maximumInquiries=99, minimumHistoryMonths=0, maximumReportAgeDays=99999, allowBankruptcy=true. Preserve them exactly.
- Bureau records fix incomeMultiple=999, maximumVehicleAge=99, minimumTenureMonths=0 and excludeExistingProduct=false. Preserve these.
- Rule id/version/createdAt and draft id/version are server-assigned lineage, never optimization controls.
- Product cannot change inside a draft. Create another explicitly selected product experiment.
- Consent, known non-opt-out, global suppression and matched bureau identity have no “disable” parameter.
- Existing campaign capacity, priority, dates, global cooldown and reservation settings affect the experiment but are frozen context. They are not among the 42 editable Business form fields. If the analyst requests one, report an unsupported scope requiring a separate feature, not a made-up field.
- Marketing minimumTenureMonths and excludeExistingProduct are copied into the candidate campaign for simulation/publication; do not independently tune a second campaign value.
- AUTO_LOAN vehicle age/LTV are product-specific. Other product sweeps of these fields are rejected as inactive/no effect.
- Minimum score 0 is a discrete disabled state. Never generate scores 1–299 or interpolate from 0 to 300.
- The code's permissive sentinel values are not justifications for weakening policy. They require explicit analyst scope plus policy permission.
- USD fields use 2-decimal increments unless the approved search domain chooses a coarser grid. APR uses percentage points, not fractions.
- A “high credit score” request must resolve which of the three score paths is intended. Research cannot silently pick a different stage when one is locked.
- Cross-stage bottlenecks are reported. If an unchanged minimum score of 700 masks a tested lower bureau cutoff, preserve it and explain the plateau.
- Changing a maximum amount changes qualification, not the requested principal used by the current BusinessAnalysis term builder.

## Domain construction and trace

Proposed effective domain = technical domain ∩ product applicability ∩ approved policy ∩ explicit analyst constraints ∩ justified research range. Evidence and policy conflicts return a typed constraint conflict; an empty intersection is not a license to widen it.

Store the full baseline and a canonical unchanged-field hash. Assert that every actual business-field difference belongs to allowedParameterIds. A stage lock excludes all paths in that stage even if an LLM returns one.

For each interval return low/high, unit, step, discreteValues if applicable, evidenceIds, applicability, rationaleSummary, confidenceCategory, conflicts and dataCoverage. Boolean and name/date inputs are not described as continuous numeric ranges.

Example (illustrative only, not a recommended score policy): an authorized bureau score interval of 680–740 with integer steps can be sampled at up to 10 distinct rounded levels. Underwriting and all other unmentioned fields remain exactly at baseline. Record the effective sampled values rather than pretending all integers were tested.
