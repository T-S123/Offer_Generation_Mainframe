<!-- Documents catalog qualification, mandatory gates, reservation throttling and finalized marketing runs. -->
# Step 2 - Marketing qualification (DMG)

Step 2 reads a saved Step 1 population, checks current customer data, qualifies catalog offers and creates traceable qualification results. The supporting services are Java; `LIMK01C` executes the versioned marketing and throttling policy in COBOL. The terminal uses the public HTTP API for every operation.

## Terminal workflow

Start the engine and terminal with the existing launch scripts. Select **8 - Marketing qualification (DMG)** from the main menu.

1. Review or maintain **offer catalog** and **campaign catalog**. A new ID opens a creation form; choosing a row opens its current version. Enter saves a new revision. Disable records with `Active N`; historical versions remain available through the API.
2. Review the **versioned throttling policy**. Changes append a configuration version; they do not overwrite the COBOL algorithm version.
3. Optionally add a **global suppression** using an existing customer ID. Record a reason and optional inclusive expiry date. Save `Suppress N` to release it while retaining history.
4. Choose **Preview marketing qualification**, select a Step 1 population, and enter campaign IDs. Defaults are `DEMO-PL,DEMO-CC,DEMO-AL`. The preview saves outcomes but consumes no quota.
5. Use **R** to inspect results. Select a result to see its Risk ID, source decision, versions and reasons. F7/F8 page through lists and long reason sets.
6. Use **F** on the run screen to finalize and reserve capacity. If facts, versions or allocation outcomes changed, the API returns a conflict and requires a fresh preview. Use **C** to cancel a preview or release a finalized run's reservations.

All screens are local operator demonstration screens. Marketing qualification does not send messages, call a credit bureau, grant credit or create personalized customer offers. Later pipeline stages will consume these results and must recheck current data and reservation validity when acting on them.

For a manual happy path, use the Step 1 README's financial example with **Marketing opt-in Y** and **Prescreen opt-out N**, then create a pre-screen population. The Step 1 example originally used no marketing opt-in; such customers correctly receive `MARKETING_CONSENT_REQUIRED` in Step 2. Updating their preference creates a new profile, so create a new population afterward.

## Catalog and demonstration defaults

On the first marketing startup only, the engine seeds six illustrative offers and three campaigns. The catalog's initial dates run from that startup's UTC date through the same date a year later. Existing catalogs and operator edits survive restart.

| Campaign | Priority | Offers | Amount ranges in USD | Illustrative terms |
| --- | ---: | --- | --- | --- |
| DEMO-PL | 10 | DEMO-PL-1 / DEMO-PL-2 | 1,000-50,000 / 5,000-50,000 | 12% APR; 36 months; zero annual fee |
| DEMO-CC | 20 | DEMO-CC-1 / DEMO-CC-2 | 1,000-25,000 / 5,000-25,000 | 21% APR; revolving (term 0); zero annual fee |
| DEMO-AL | 30 | DEMO-AL-1 / DEMO-AL-2 | 1,000-100,000 / 5,000-100,000 | 7% APR; 36 months; zero annual fee |

These terms are invented catalog examples, not market quotes or BIAN-prescribed terms. Both offers in a campaign may qualify. No ML ranking or price personalization is performed in Step 2.

| Control | Default | Meaning |
| --- | ---: | --- |
| Customer cooldown | 7 days | Minimum elapsed time since a non-cancelled finalized reservation, across campaigns and products |
| Rolling window | 30 days | Count reservations created strictly after `now - window`; the upper end includes now |
| Maximum reservations | 3 | Maximum customer/campaign reservations within that window |
| Reservation lifetime | 7 days | Capacity reservation expires sooner if a source decision, campaign or qualifying offer expires |
| Campaign capacity | 1,000 | Maximum concurrent unexpired customer/campaign reservations across runs |
| Minimum banking tenure | 0 months | A positive configured minimum requires a known tenure value |
| Exclude existing product | Credit-card campaign only | Explicitly held target product excludes; unknown holdings exclude if this control is enabled |

One customer/campaign group uses one slot, even if several offers qualify. A customer's other campaigns compete according to ascending priority, then campaign ID. Customers are processed in stable customer-ID order; offers use offer-ID order. This is deterministic allocation, not a relevance or fairness ranking. With the default nonzero cooldown, at most one campaign per customer wins in a run.

The cooldown and rolling window count **finalized qualification reservations**, not outbound contacts. Expired but non-cancelled reservations remain in frequency history until the relevant window passes. Expiry frees concurrent campaign capacity. Cancellation releases both frequency and capacity usage because no outreach exists in this stage. A future campaign-execution/contact ledger must distinguish actual contacts from these reservations; cancelling a contacted campaign must not erase real contact history.

An active reservation for the same customer/campaign prevents another reservation even when cooldown is zero. The exact cooldown boundary passes. Reservation expiry is an exclusive UTC instant; catalog, decision and suppression dates are inclusive UTC dates. All qualified offers sharing a reservation use its earliest limiting expiry.

## Versioned rules and reason codes

`app/cbl/LIMK01C.cbl` owns **MKT-2026-001**. Its explicit per-run context holds tentative allocations; there is no hidden COBOL state between evaluations and no Java fallback. Changing the algorithm requires a new rule version and replay planning. Operator configuration changes create new `Policy.version` values.

| Check | Exclusion reason |
| --- | --- |
| Target product absent from the selected pre-screen population | NOT_IN_PRESCREEN |
| Current profile or effective decision differs from the population reference | SOURCE_CHANGED |
| Current underwriting decision is missing, declined or incomplete | UNDERWRITING_NOT_ELIGIBLE |
| Current decision is missing or expired | UNDERWRITING_EXPIRED |
| Marketing opt-in is false or unknown | MARKETING_CONSENT_REQUIRED |
| Pre-screen opt-out is true or unknown | PRESCREEN_OPT_OUT_OR_UNKNOWN |
| Active, unexpired operator suppression | GLOBAL_SUPPRESSION |
| Inactive campaign or offer | CAMPAIGN_INACTIVE / OFFER_INACTIVE |
| Outside inclusive catalog dates | CAMPAIGN_OUTSIDE_DATES / OFFER_OUTSIDE_DATES |
| Required tenure unknown or below threshold | TENURE_UNKNOWN / TENURE_BELOW_MINIMUM |
| Target product already held, or holdings unknown when exclusion enabled | EXISTING_PRODUCT / PRODUCT_OWNERSHIP_UNKNOWN |
| Requested amount unknown or outside inclusive offer range | AMOUNT_UNKNOWN / AMOUNT_OUTSIDE_OFFER |
| Active reservation already exists for this customer/campaign | ALREADY_RESERVED |
| Customer cooldown or frequency cap fails | CUSTOMER_COOLDOWN / CUSTOMER_WINDOW_LIMIT |
| Campaign capacity exhausted | CAMPAIGN_CAPACITY |

The policy reports all applicable static reasons. Throttling is evaluated only for otherwise qualified candidates. Reason counts are per offer evaluation and may overlap; unique customer counts and reservation groups are reported separately.

## Traceability, concurrency and persistence

- Each new run gets one Risk ID per customer, shared across all of that customer's campaign/offer results, including exclusions. It is a correlation identifier, not a risk score. A new run gets new Risk IDs.
- `requestId` is required for preview creation. The same ID and canonical campaign selection returns the original run; changed content returns 409. A timeout can be retried safely with that ID. Finalization and cancellation are independently idempotent by run ID.
- A preview stores the complete catalog and policy revisions and immutable qualification rows. Rows include current and source profile/decision references, suppression revision/reason, underwriting policy and marketing rule/configuration versions. Historic customer profiles and suppression/catalog histories retain the underlying evidence.
- Finalization requires a preview less than 30 minutes old, unchanged catalog/configuration versions and identical reevaluated results. Current-data reads, COBOL evaluation and reservation writes share a serialized local transaction. Two previews cannot oversubscribe the same slot. A failure commits no partial run or reservation set.
- Cancellation changes lifecycle metadata and stamps `releasedAt`; it preserves qualification rows. A repeated finalization of an already finalized run returns its historical state and never renews an expired reservation.
- `V002__marketing_qualification.sql` adds tables without deleting or rewriting Step 1 data. A checksum guards the applied migration. H2 DDL is restart-safe via `IF NOT EXISTS`; the migration marker is written only after all statements succeed. Catalog seeding is separately transactional.
- Reservations and run results remain historical after profile changes, suppression changes or expiry. They are not a perpetual authorization. Downstream consumers must revalidate before taking action; Steps 3-8 are not implemented here.

## API examples

The existing local bearer token applies. The complete contract is `contracts/openapi.yaml`, which references `contracts/marketing.openapi.yaml`.

```powershell
$engineToken = (Get-Content -LiteralPath .\runtime\api-token -Raw).Trim()
$headers = @{ Authorization = "Bearer $engineToken" }
$base = 'http://localhost:8090/api/v1'
Invoke-RestMethod "$base/marketing/campaigns" -Headers $headers

$body = @{
    requestId = [guid]::NewGuid().ToString()
    populationId = '<saved Step 1 population ID>'
    campaignIds = @('DEMO-PL', 'DEMO-CC', 'DEMO-AL')
} | ConvertTo-Json
$run = Invoke-RestMethod "$base/marketing/runs" -Method Post -Headers $headers `
    -ContentType 'application/json' -Body $body
Invoke-RestMethod "$base/marketing/runs/$($run.id)/results" -Headers $headers
Invoke-RestMethod "$base/marketing/runs/$($run.id)/finalize" -Method Post -Headers $headers
```

`GET /marketing/runs/{runId}/results?riskId=...` filters a run's rows by Risk ID. Catalog and suppression history endpoints retain all revisions. To create an offer or campaign use PUT on its chosen ID with `expectedVersion: 0`; to edit, provide the current version. Disabling a record preserves its history.

## Local limits

One local Java process shares the Step 1 database transaction port. Runs are synchronous and bounded to 20 campaigns, 10 offers per campaign and 100,000 customer/offer evaluations. The catalog supports 1,000 offers and 100 campaigns. The existing customer snapshot cap remains 50,000 customers. Large evaluations hold the local transaction lock while COBOL runs; this profile does not claim distributed or production-scale throughput.

The 3270 catalog editor supports 60-character offer-ID lists. Longer API-created lists and text outside CP037 are refused for terminal editing to prevent silent truncation; use the API for those records.
