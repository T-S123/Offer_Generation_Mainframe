# A2A local simulation operating constraints

Document ID: A2A-DEMO-OPERATING-1
Product: PERSONAL_LOAN
Geography: US

This document defines the synthetic local demonstration's operating rules.
It is not a bank credit policy, a regulatory standard, or BIAN certification.
The approved a2a-personal-loan-policy.md supplies the bureau minimum score
range 680-740, integer step 1. This document adds no new lending thresholds.

## Responsibility and precedence

The application coordinates separate responsibilities: product/draft design,
credit-rule evaluation, marketing eligibility, bureau assessment, simulation
analysis, and explicit catalog publication. Each service owns its state and
exposes typed operations. This separation is inspired by BIAN's Service Domain
and Business Scenario concepts, not a claim of BIAN API conformance.

Sources for that architecture terminology:
- https://bian.org/bian-portal/
- https://bian.org/servicelandscape-13-0-0/object_21.html?object=31010

The submitted request, its locks and its budget bound this experiment.
Approved policy bounds and technical parameter domains also must hold.
An analyst cannot override an approved range merely by typing a wider range.
Historical or external evidence cannot override approved internal policy.
If constraints conflict, return a declarative BLOCKED result. Never broaden
scope, weaken a lock, increase a budget or ask an analyst clarification question.

## Autonomous decisions

Submitting the request and budget authorizes planning and simulation.
The agents resolve interpretation and review issues internally with bounded
revisions. For an otherwise unspecified credit-score stage, select bureau
minimumScore if it is unlocked and disclose the assumption. Explicit stage
names take precedence. All unmentioned fields retain their saved draft values.
Document approval already present for the exact bytes remains valid until
expiry or a content change; never request the same approval or excerpt again.
Missing applicable approved range evidence produces a final blocked reason.

## Budget and phase allocation

The request's actual budget and current reservations are supplied to every role
as executionContext. Use those numbers rather than inferring missing values.
For the 20-attempt demo: at most 1 baseline, 12 exploration, 5 refinement and
2 final-validation evaluations. Duplicate configurations consume no new slot.
Java reserves each new trial and each model call atomically before dispatch.
The $10 demo cap, call/token caps and deadline are hard limits. An internal
revision consumes the same budget. Exhaustion pauses the saved plan; agents
cannot automatically increase any limit.

## Objectives, floors and no-winner handling

Simulated acceptance percent = 100 * expected selections / eligible customers.
Eligibility percent = 100 * eligible / assessed customers.
Zero eligible customers yields unavailable acceptance, never division by zero.

Rank acceptance and eligibility separately. For the demo, the acceptance
winner's eligibility floor is explicitly 0%, and the eligibility winner's
acceptance floor is explicitly 0%. Zero means no additional cross-metric
floor; it does not remove the eligible-support gate. If the submitted budget
uses other floors or support, those submitted values apply unchanged.

Minimum eligible support 5 is a local demo execution/ranking gate, not evidence
of statistical reliability. Never substitute a higher support threshold just
because a reviewer prefers it. Report assessed and eligible counts, expected
selections, both rates, candidate/run IDs, and a small-cohort limitation.
If no candidate qualifies for one objective, report no supported winner for
that objective. If neither qualifies, return the no-supported-candidate result.
Do not loosen floors, fabricate a winner, or ask the analyst to choose defaults.

## Validation, interpretation and publication

Discovery and final validation use distinct generation seeds. Freeze the
shortlist before using final validation; do not tune against its results.
Final-validation reservations from a failed plan with zero evaluations do not
mean data was examined. A documented AI-history reset can release such an
unused reservation while preserving the manual comparison.

The output describes best-tested configurations only, never a global optimum.
Synthetic utility is not observed take-up, calibrated conversion, profit,
credit-loss performance or proof of causation. Credit-threshold changes also
change the eligible cohort. Explanations are hypotheses unless demonstrated
by supplied evidence; small samples remain limitations in the final report.

AI agents cannot publish. Publication requires the analyst to inspect one
tested candidate's exact preview and explicitly confirm it in the existing
engine. The manual draft and manual comparison remain available.
