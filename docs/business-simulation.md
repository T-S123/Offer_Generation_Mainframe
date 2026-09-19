<!-- Explains isolated Business populations, versioned rule experiments, comparison statistics and reviewed catalog publication. -->
# Business Simulation Engine

The mode selector has **01 Customer** and **02 Business**. Customer uses the manual form and the automatic Steps 1–8 workflow. Business opens a separate experiment workbench. The existing operational consoles are under **Business → 07 Active customer / campaign operations**. Modes organize this local demonstration; they are not authenticated roles.

## End-to-end workflow

1. **Business → 01** generates **60–10,000** synthetic people with a repeatable seed. Inputs include missing values, opt-outs, nonconsent, independent synthetic bureau observations, and suppression examples. The population freezes its evaluation date. These people never enter the active customer, reservation, bureau queue, offer repository, warehouse, outbound-file or live-model training pipelines.
2. **02** inspects population inputs. **04** creates an offer draft from a catalog offer and its campaign; the terminal uses the first associated campaign, and the API permits selecting a specific campaign. Baseline offer, campaign and global marketing policy are snapshotted.
3. Open **03**, select the draft and edit offer terms or Step 1/2/3 criteria. Each save makes an immutable draft/rule version. Financial forms include APR, fee, term, amount limits and dates. Rule forms include income, score, DTI, utilization, delinquencies, amount limits, tenure and existing-product eligibility. Auto rules include vehicle age and LTV. Bureau forms add inquiries, credit history, report freshness and bankruptcy. All three stages execute COBOL. Consent, known non-opt-out, suppression and matched bureau identity cannot be disabled.
4. Choose **05 Run baseline / candidate simulation** inside the draft, select a population and a model seed. One bounded worker compares both offers using identical people, bureau observations and time. There are at most three running/queued experiments. Leaving the screen does not stop the job; **Business → 05** reopens results. ENTER refreshes status. Interrupted jobs become FAILED after restart and can be rerun deliberately.
5. Review overall, held-out test, cohort and customer-level results. F7/F8 reveal every page, including reasons, model quality and assumptions. Every result retains its draft, hashes, model/scalers, population and seeds. Prior draft versions are retrievable through the API.
6. Choose **06 Review and publish** from a completed result. Enter a review note and **P** to confirm. There is no fit-uplift threshold overriding the Business user's judgment. Hard data validity, current baseline/policy, current tested draft and active dates are required. Publication atomically creates a new catalog offer, a new campaign copied from the tested campaign, and an audit receipt. The campaign's tenure and existing-product criteria come from the tested marketing form. Existing offers, campaigns and their qualifications remain unchanged. Repeating the same publication is idempotent; conflicting notes or a second run publishing the same draft version are rejected.
7. New Customer applications can qualify for the published offer automatically through Steps 1–8. The catalog offer carries immutable rules into marketing, the independent bureau API and personalized-term qualification. Default product assessments remain in the customer history; an offer may have different underwriting criteria. The latest source decision/profile remains a freshness fence, and supplied declines/incomplete decisions remain binding. Supplied approvals must also satisfy the new offer-specific criteria; source history is preserved. Publishing does not automatically reapply the offer to existing customers or deploy the experimental K-means model.

Customer → **06 My marketing files / delivery status** shows current customer packages, restrictions and local file locations. C/D verifies CSV or IBM037 files through a customer-correlated API. Files and offers have independent visibility gates: a delivery restriction does not hide a currently qualified offer. Consent, suppression, qualification changes and expiry still block invalid offers/files.

## Reading the statistics

- **Coverage and impact:** denominator, eligible count/rate, newly eligible, lost eligibility, stage funnel, incomplete/review counts and overlapping rejection reasons. Campaign capacity is applied separately in deterministic customer order, with zero prior reservations. This is a single-campaign counterfactual; competition with other active campaigns is not modeled.
- **Customer fit and cost:** eligible-customer mean, median and p90 fit score, monthly payment and borrowing cost. Paired changes compare only customers eligible for both offers. Empty groups show `n/a`, not a misleading zero. Cards use one year at full utilization; loans use amortized term cost plus annual fees.
- **Acceptance scenarios:** sums of utility-curve probabilities among eligible customers and conservative/base/optimistic sensitivity using ±10 fit points. These are assumptions, not calibrated acceptance forecasts. No observed acceptance, defaults, losses, profit or fairness outcome is inferred.
- **Risk mix:** bureau score and self-reported DTI summaries among eligible customers. Credit observations stay distinct from self-reported customer values.
- **Uncertainty:** 95% Wilson intervals concern synthetic eligibility sampling only. Small groups below 30 are flagged. They do not establish real-world model confidence or lending viability.
- **Cohorts:** the existing Java marketing microservice fits K-means, train-only scaling, 60/20/20 train/validation/test splits, K=2–6 and three initializations. Validation selects K; the test partition is held out. Features are income, DTI, utilization, tenure, savings/income and spend/income transforms. Silhouette uses a bounded sample of up to 200 rows per evaluation partition. All customers participate in cohort construction regardless of qualification; only qualified subsets enter eligible-offer metrics.

The POC's reproducible experiments, cohort analysis and explicit business review informed this implementation. Financial preference scoring reuses `Cohorts` and `Personalizer` from the existing Java marketing service. No Python or external AI is introduced.

## Storage and boundaries

`runtime/business-simulations.mv.db` is a separate local H2 database for immutable populations, draft revisions, experiment states and paged result rows. Existing customer H2 and PostgreSQL schemas are retained. Publication receipts use the existing live H2 revision journal in the same transaction as new offer/campaign revisions. The Business receipt copy can be recovered by retrying publication if an interruption occurs after the active catalog commit.

`simulation/domain` defines experiment records and validates forms. `simulation/application` coordinates comparisons and publication. Infrastructure provides SQL and COBOL transport. `LIRL01C.cbl` and `LIRLREQ.cpy` apply the forms; legacy catalogs still use their original COBOL policies. The terminal performs only HTTP calls. The stateless `/api/v1/business-analysis` endpoint on the Java marketing service accepts minimized synthetic features, returns an experimental model and paired scores, and writes no active model, inbox or offer records.

The old `POST /api/v1/simulations` is disabled in the configured application with an instruction to use `/business/populations`. Existing legacy simulation records remain readable for compatibility. Previously completed legacy batches remain valid existing training sources; new Business populations cannot enter that discovery path.

## Local commands and API

No new runtime installation is needed. Stop the application, run `scripts/build.ps1` (or `scripts/test-bureau.ps1` for all PostgreSQL/Kafka integrations), then `scripts/run.ps1` and `scripts/terminal.ps1`. Never delete `runtime` to apply this change. The first start creates the separate Business database.

The local engine token authorizes `/api/v1/business/*`. Complete contract: `contracts/business.openapi.yaml`.

| Method | Path after `/api/v1/business` | Purpose |
| --- | --- | --- |
| GET | `/catalog` | Baseline offers/campaigns |
| POST / GET | `/populations` | Generate `{count, seed}` / summaries |
| GET | `/populations/{id}/customers?offset=0&limit=20` | Inspect frozen synthetic records |
| POST / GET | `/drafts` | Create `{name, baselineOfferId, campaignId}` / list |
| GET / PUT | `/drafts/{id}` | Read or save `{expectedVersion, name, offer, rules}` |
| GET | `/drafts/{id}?version=1` | Read immutable prior revision |
| POST / GET | `/runs` | Submit `{draftId, draftVersion, populationId, seed}` / list |
| GET | `/runs/{id}` | State, frozen draft, complete report/model |
| GET | `/runs/{id}/rows?offset=0&limit=20` | Customer-level outcomes/scores |
| POST | `/runs/{id}/publish` | Explicit `{confirm: true, note: "review reasoning"}` |
| GET | `/publications` | Reviewed publication receipts |

Rules are illustrative bank policies, not BIAN-mandated thresholds. Limits remain local: 10,000 people per experiment, bounded in-process execution and local databases. This is not a validated production deployment or a proof of millions-per-month throughput. Experiments intentionally do not send messages, make real credit approvals or estimate actual profitability.
