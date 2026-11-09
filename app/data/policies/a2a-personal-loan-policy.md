# U.S. personal-loan underwriting and offer-design policy

Document ID: A2A-DEMO-PL-SCORE-2
Version: 2026-11-07
Product: PERSONAL_LOAN
Geography: US
Owner: Lending Studio product and credit-risk demonstration
Evidence reviewed: 2026-11-07

## Approved experiment mandate: bureau minimum credit score

The approved local experiment parameter is /rules/bureau/minimumScore:
minimum 680, maximum 740 inclusive, integer step 1. These are internal demo
risk-appetite limits, not empirical optimal scores or regulatory requirements.
Only this bureau minimum score may vary when requested. Underwriting remains
locked. Preserve offer terms, marketing criteria, all other bureau fields,
consent, opt-out and suppression. Unmentioned values retain the saved draft.
Document approval is required once per exact content hash before Research can
use it; an already approved excerpt is sufficient evidence without reapproval.

This policy supplies financial context and a bounded research mandate. It does
not grant new permissions to change APR, amount, term, DTI, marketing or other
rules. Technical parameter domains are not policy approval. The application
must enforce the intersection of the request, locks, approved evidence and
owner-service limits.

## Evidence classes and permitted use

| Class | Meaning | Use |
| --- | --- | --- |
| External observation | A dated statistic published by an identified source | Market context; never a customer fact or a local acceptance target |
| Financial relationship | A definition or calculation with explicit assumptions | Explain tradeoffs; request authoritative calculations |
| Internal decision | This demo's approved range or operating default | Bound experiments; do not attribute it to a regulator |
| Local observation | A recorded assessment or selection with provenance | Evaluate this application's outcomes |
| Synthetic result | A result from generated customers and the utility model | Compare tested scenarios, not claim real conversion |

Creditworthiness, willingness to accept, and profitability are different
questions. A score threshold is one risk screen, not a calibrated default
probability. Raising it may change who is eligible; it does not prove an offer
became more attractive. Lowering it does not prove profitable growth.
No document in this pack supplies a validated loss model or a real acceptance
benchmark for this product.

## Dated U.S. market reference points

The Federal Reserve's G.19 release dated November 6, 2026 reports these Q3 2026
commercial-bank APR measures: [Federal Reserve G.19][g19].

| Product / reporting population | Q3 2026 APR |
| --- | ---: |
| Personal loans, 24-month | 11.90% |
| New-car loans, 60-month | 7.54% |
| Credit cards, all accounts | 21.19% |
| Credit cards, accounts assessed interest | 22.36% |

Personal/new-car figures average banks' most common rates; card series use
different populations and weighting. These are context, not individual quotes,
pricing limits, or acceptance rates. The source is a rolling release: retain
this dated snapshot until an explicit evidence refresh; do not call it current
after its review date.

The New York Fed reported that 4.7% of outstanding household debt was in some
stage of delinquency in Q2 2026. This is a balance-based stock measure across
debt types, not the probability this customer defaults, a personal-loan loss
rate, or an acceptance forecast. Source: August 11, 2026
[Household Debt and Credit release][nyfed].

**Application interpretation:** a saved 9.5% illustrative personal-loan APR
cannot be called universally competitive merely because it is below 11.90%.
Term, customer risk, fees, loan size, sample coverage and observation date must
match before drawing a pricing conclusion. Leave a locked APR unchanged.

## Borrower capacity, credit history and loan structure

Personal-loan pricing and terms can depend on credit information, income,
existing debts, amount and term; loans may use fixed or adjustable rates.
[CFPB: personal installment loans][installment]. Assess the actual supplied
fields rather than substituting a market average for missing customer facts.

DTI is monthly debt payments divided by gross monthly income; applicable limits
differ by lender and product. [CFPB: debt-to-income ratio][dti]. The application
must preserve the recorded income provenance and the owner API's definition.
Do not assume a self-reported monthly-income field has been verified as gross
income. Do not import mortgage DTI conventions as universal personal-loan limits.

For analytical explanation, post-loan DTI includes the proposed payment and
all continuing obligations. Existing debts remain until payoff is evidenced:
a debt-consolidation intention is not proof that an old payment disappeared.
The owner API decides how debts and the proposed payment are counted; agents
must not silently replace its calculation with a different formula.

Repayment capacity deserves separate attention from score and collateral.
The OCC's retail-lending guidance describes evaluating capacity and aggregate
debt, without prescribing a universal maximum debt-service ratio.
[OCC: Retail Lending, capacity discussion][occ]. The internal 680-740 mandate
does not establish capacity, approve real lending, or prove fair outcomes.

Preserve identities, source modes, bureau freshness, imported declines and
contact restrictions. Never infer protected characteristics or invent income,
employment, credit history or debts to make a candidate pass. A missing fact is
a data limitation, not permission to substitute the most favorable value.

## Explain payment, term and cost together

Origination and other charges can increase total borrowing cost; compare the
actual disclosures rather than an interest rate alone.
[CFPB: installment-loan fees][fees]. The engine's illustrative APR and modeled
fee fields are not a complete real-world disclosure calculation.

For a fee-free, fixed-rate amortizing illustration, let principal be P, monthly
contract rate r, and number of monthly payments n:

payment = P * r / (1 - (1 + r)^(-n)); when r = 0, payment = P / n.

Use the contract interest rate for r. Dividing a disclosure APR by 12 is not
generally a substitute when fees or payment conventions differ.

Calculated illustration, not a market statistic: a $10,000 principal at a
12% annual contract rate, with no fees, gives approximately $470.73 per month
over 24 months or $332.14 over 36 months. Total interest is approximately
$1,297.63 versus $1,957.15, using unrounded payments. Final-payment rounding
can change cents. A longer term reduces the payment here but increases cost.
The owner calculation service, not mental arithmetic in the model, supplies
figures for an actual candidate.

## Offer-generation decision rules

1. Diagnose the failing stage from supplied results before proposing a change.
   If underwriting rejects the same customers throughout the bureau search,
   keep underwriting fixed and report the bottleneck; do not ask to unlock it.
2. Establish the saved baseline, then explore only the authorized score range.
   Use the engine's deterministic trial allocation and distinct validation
   population. Do not ask which individual score to try or permission to run
   the next already-authorized phase.
3. Treat broader eligibility and acceptance among eligible customers as
   separate objectives. Unless the request explicitly prioritizes one, report
   both best-tested configurations and their counts; no priority question is
   needed merely because the winners differ.
4. Check the actual minimum-support gate and cross-metric floors. Report no
   supported winner when necessary. The default floor of zero is a valid
   configured value, not missing analyst input.
5. Explain any observed tradeoff using counts, changed cohort composition and
   exact candidate/run identities. Do not convert an uncalibrated utility score
   into a promise of take-up, funded volume, credit performance or profit.
6. Describe lender economics only when inputs exist. Interest and fee receipts
   must be considered alongside funding, servicing, acquisition and credit-loss
   costs. No loss estimate, cash-flow model or cost data means profitability is
   unmeasured; higher eligibility alone is not a business case.

## Customer-specific personalization boundary

Network 3 uses its current owner-supplied personalization policy and exact saved
profile, not this population score-search mandate. Prior eligibility is context.
Favor a permitted cost reduction without increasing the monthly payment; retain
amount and term when that can meet the request. Otherwise use recorded
preferences and returned evaluations to explain the tradeoff. Only ask about a
preference when it changes the feasible next action and no supplied default
resolves it. Rejected candidates should be revised using authoritative reasons.

No chat answer rewrites financial facts. Profile edits belong in the Customer
API; a changed saved version invalidates stale work. Never promise a better
offer from hypothetical income/debt changes. Acceptance remains an explicit
customer action after review and current-authority checks.

## Source register and maintenance

Sources were reviewed on 2026-11-07. Statistics are dated observations, not a
live feed. Refresh market evidence at least quarterly for demonstrations
intended to portray current conditions, or label the snapshot historical.
Update the document version, bundled copy and reviewed hash together. Preserve
the old evidence in completed experiment history; never rewrite predictions.

[g19]: https://www.federalreserve.gov/Releases/g19/current/
[nyfed]: https://www.newyorkfed.org/newsevents/news/research/2026/20260811/
[installment]: https://www.consumerfinance.gov/ask-cfpb/what-is-a-personal-installment-loan-en-2114/
[dti]: https://www.consumerfinance.gov/ask-cfpb/what-is-a-debt-to-income-ratio-en-1791/
[occ]: https://www.occ.gov/publications-and-resources/publications/comptrollers-handbook/files/retail-lending/pub-ch-retail-lending.pdf
[fees]: https://www.consumerfinance.gov/ask-cfpb/do-personal-installment-loans-have-fees-en-2120/
