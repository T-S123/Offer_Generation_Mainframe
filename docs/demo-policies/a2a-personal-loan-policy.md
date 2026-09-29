# A2A synthetic demonstration policy: personal-loan bureau score

Document ID: A2A-DEMO-PL-SCORE-1
Product: PERSONAL_LOAN
Geography: US

This is synthetic local test evidence, not a bank policy, regulatory guidance,
empirical research, or a recommendation for real lending.

For this demonstration, explore /rules/bureau/minimumScore from 680 through 740
inclusive, with integer step 1. The bounds are chosen to exercise retrieval and
bounded simulation. A successor may explore a narrower interval inside them.

Lock underwriting. Preserve all offer terms, marketing criteria, other bureau
fields, consent and suppression controls. Only the named bureau minimum score
may change after analyst scope approval.

Use separate discovery and final-validation populations with different seeds.
Never reuse held-out validation data. Report best-tested acceptance and
eligibility configurations separately. Simulated acceptance is expected
selections / eligible customers; eligibility is eligible / assessed customers.

These synthetic utility results are not calibrated real conversion forecasts.
Changing a bureau threshold may change the eligible cohort; this alone does not
establish why customers selected an offer or demonstrate a causal effect.

The analyst must review extracted text and approve its exact hash, product,
geography and expiry. Such approval authorizes this local test only.
