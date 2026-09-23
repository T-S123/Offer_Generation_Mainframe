<!-- Provides repeatable Customer and Business demonstrations with explicit customer-ID, consent, keyboard and startup guidance. -->
# End-to-end demo walkthrough

These cases exercise the local application using synthetic information. They assume the seeded `DEMO-PL-1` offer and `DEMO-PL` campaign are still active, their default policies remain unchanged, and the campaign has available capacity. Use a new external reference for each new customer; do not delete your databases to repeat a demo.

## Start the application

From PowerShell in `Offer_Generation_Mainframe`:

```powershell
.\scripts\infrastructure.ps1
.\scripts\build.ps1
.\scripts\run.ps1
```

If an older instance is running, use **Ctrl+C in its server window before rebuilding/restarting**. Keep the new server window open. The configured three-service launcher now waits for each API before printing:

```text
All three APIs are responding. Marketing offers: 127.0.0.1:8092
Terminal: 01 Customer for an application; 02 Business for simulations.
```

Open a second PowerShell window in the same folder:

```powershell
.\scripts\terminal.ps1
```

The default console client opens directly inside PowerShell without WSLg. Maximize the window. Use **Tab** to move between editable fields, **Enter** to submit, **F3** to return, and **F7/F8** to page. **Ctrl+U** clears the current field before entering a replacement; **Ctrl+R** resets a locked keyboard. **Ctrl+]**, then `Quit` and **Enter**, returns to PowerShell. Select displayed row numbers rather than assuming that an existing customer, offer or experiment is always row 1.

The optional `.\scripts\terminal.ps1 -Gui` opens x3270 when WSLg is working. If it reports `Can't open display`, use `.\scripts\terminal.ps1` without `-Gui` instead. If the launcher says port 2323 is unavailable, start `scripts/run.ps1` in another window first.

If startup still fails, inspect `runtime/credit-service.log`, `runtime/marketing-service.log` and the server window. This read-only command checks the engine listener:

```powershell
wsl.exe -d Ubuntu-22.04 --exec curl --fail --silent --show-error --max-time 10 --noproxy '*' http://127.0.0.1:8090/api/v1/health
```

## Customer case: application to personalized offers and marketing files

### 1. Enter a synthetic customer

Choose **01 Customer → 01 Enter my information**. Fill in both pages:

| Page 1 field | Value |
| --- | --- |
| Display name | `Alice Demo` |
| External ref | `DEMO-CUST-001` |
| Gross monthly income | `7000` |
| Monthly debt payments | `500` |
| Credit score (300–850) | `760` |
| Delinquencies (12 mo) | `0` |
| Credit utilization % | `20` |
| Personal loan amount | `10000` |
| Requested card limit | `3000` |
| Auto loan amount | `15000` |
| Vehicle value | `20000` |

Press **Enter** for page 2.

| Page 2 field | Value |
| --- | --- |
| Vehicle age in years | `3` |
| Banking tenure months | `36` |
| Deposit balance USD | `5000` |
| Monthly spend USD | `1200` |
| Products held | `CHECKING` |
| Marketing opt-in Y/N | **`N` initially, for the controlled setup below** |
| Prescreen OPT-OUT Y/N | `N` |
| Channels in order | `EMAIL` |

Press **Enter** to review, then **Enter** to save. Record the generated **Customer ID** from the progress or profile screen.

**Customer ID and External ref are different.** `DEMO-CUST-001` is the label you entered; saving creates a separate 36-character UUID such as `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`. Use that generated ID on the bureau screen. If you did not record it, choose **Customer → 02 Open a demo customer profile**, select Alice's row, and read **ID** on her profile; do not create another customer.

Expected: all three default underwriting assessments are `ELIGIBLE`, but application progress settles on `NO_ELIGIBLE_OFFERS` because marketing consent is off. This is an intentional intermediate result, not a failed save or a bureau rejection. No marketing offers or outbound files should be available. Continue with steps 2 and 3 below to prepare the independent bureau report and then enable consent for the positive demo.

For an unprepared, randomized demo you can use `Y` immediately instead. The whole pipeline runs automatically, but a good self-reported score does **not** guarantee bureau approval: each new customer's independent synthetic report can decline or require review. Use the following setup for a repeatable positive case.

### 2. Prepare the independent bureau report in Business mode

Press **F3** until the mode selector appears. Choose:

**02 Business → 07 Active customer / campaign operations → 09 Bureau qualification / credit decision → 4 Generate / edit an independent bureau profile**.

Enter the generated Customer ID recorded above, **not `DEMO-CUST-001`**. The screen loads a generated report; replace its facts with:

| Bureau field | Value |
| --- | --- |
| File status | `MATCHED` |
| Bureau score | `780` |
| Bureau debt USD/mo | `500` |
| Utilization % | `20` |
| Delinquencies 12m | `0` |
| Inquiries 6m | `1` |
| Oldest account mo | `120` |
| Bankruptcy Y/N | `N` |
| Reported UTC | Keep the freshly generated timestamp |

If you reuse an older report, use a current nonfuture UTC timestamp instead. Press **Enter**. Expected: a new bureau version saved with source `LOCAL_EDIT`. This changes synthetic input evidence, not the qualification rules or a historical decision.

### 3. Enable consent and let Steps 1–8 run

Return to the mode selector with **F3**. Choose **01 Customer → 02 Open a demo customer profile**. Select Alice's row, or enter her Customer ID in **Or customer ID**.

On the profile, press **F4** to edit. Keep page 1 unchanged and press **Enter**. On page 2 change **Marketing opt-in to `Y`**, keeping **Prescreen OPT-OUT `N`** and **Channels `EMAIL`**. Press **Enter** to review and **Enter** to save.

The new profile version automatically starts the entire workflow. The progress screen refreshes every two seconds. Give the first model bootstrap and offer-copy processing time to finish; entering another customer is unnecessary.

| Step | Expected evidence |
| --- | --- |
| 1 — Customer data | A new profile version and simulated underwriting assessments; credit input remains labeled `SELF_REPORTED`. |
| 2 — Marketing qualification | A finalized automatic qualification with a Risk ID for eligible catalog offers. |
| 3 — Bureau qualification | `APPROVED` using the independent `LOCAL_EDIT` report and versioned credit rules. |
| 4 — Decision response | Approved qualification responses and current marketing eligibility become available downstream. |
| 5 — Offer creation | Original offers activate; qualified personalized alternatives appear after model processing. |
| 6 — Offer storage | Current offer families appear in the Customer offer view and enterprise copies. |
| 7 — Campaign execution | A current customer/campaign package with local CSV, EBCDIC and manifest files. |
| 8 — Customer presentation | **My Qualified Offers**, offer comparison and selection, and **My Marketing Files / Delivery Status**. |

The seeded personal-loan original is **$10,000 at 12% APR for 36 months, with no annual fee**. The seeded campaign normally qualifies both personal-loan catalog variants for these inputs. You may not see card/auto offers even though underwriting approved those products: Step 2 campaign priority and its customer-wide cooldown apply during reservation allocation. Changed catalogs or policies can change these results.

### 4. Compare and choose

From **My Qualified Offers**, select a displayed personal-loan row. The comparison shows original and personalized amount, APR, term, monthly payment and estimated borrowing cost. If only Original appears initially, return to the list and refresh after personalization completes.

Enter **`P`** to review a personalized alternative, then **Enter** again to confirm. Expected: **Selection Saved**. You can later choose **`O`** and confirm to demonstrate that the latest valid choice updates the campaign package. Exact personalized terms depend on the active model and qualified candidates; a personalized alternative is only shown when one passes the rules.

### 5. Inspect the generated files

Return to the Customer menu and choose **06 My marketing files / delivery status**. Select the package, then enter **`C`** to verify CSV or **`D`** to verify EBCDIC.

Expected: a verified filename and SHA-256 checksum. The actual local files are under:

```text
Offer_Generation_Mainframe/runtime/outbound/<package-id>/offers.csv
Offer_Generation_Mainframe/runtime/outbound/<package-id>/offers.dat
Offer_Generation_Mainframe/runtime/outbound/<package-id>/manifest.json
```

The package preserves the available choices and leads with the latest valid selection, or the highest-fit qualified offer if nothing was selected. Files update asynchronously after a selection. `EMAIL` uses a synthetic contact; the application produces local files and does not send an email.

Optional final negative check: edit Alice again and set marketing opt-in to `N`. Current offers and downloads must become unavailable, and the background worker withdraws the active outbound files. Historical decisions and selections remain for audit. Do this **after** demonstrating the successful offer and file path.

## Business case: 10,000 customers, offer/rule comparison and publication

This case changes price and all three rule stages, so the results show a real coverage-versus-customer-cost tradeoff. It uses an isolated population and does not create 10,000 active customer accounts or outbound packages.

### 1. Generate a reproducible population

Choose **02 Business → 01 Generate synthetic customers**:

| Field | Value |
| --- | --- |
| Customer count | `10000` |
| Population seed | `20260918` |

Press **Enter**. Expected: **10,000 isolated customers**, with a mix of eligible, ineligible, incomplete, suppressed and nonconsenting people. Inspect rows through **02 Inspect isolated populations**; self-reported inputs and independent bureau facts are distinct.

### 2. Create and edit an offer draft

Choose **04 Create a draft from an existing catalog offer**. Set the draft name to **`Personal loan fit demo`** and select the displayed row with ID **`DEMO-PL-1`**. It uses the associated **`DEMO-PL`** campaign.

Make these four edits from the draft menu, keeping all other fields at their baseline values:

| Draft menu | Change | Purpose |
| --- | --- | --- |
| **1 Edit offer terms / dates** | Name `Personal loan fit demo`; APR `10.00` instead of `12.00` | Test a cheaper loan with the same amount range, fee and 36-month term. |
| **2 Edit Step 1 underwriting criteria** | Min monthly income USD `2500` instead of `2000` | Test a higher income floor. |
| **3 Edit Step 2 marketing criteria** | Min banking tenure (months) `6` instead of `0` | Restrict the campaign to established relationships. |
| **4 Edit Step 3 bureau criteria** | Minimum credit score `700` instead of `680` | Test a stronger independent bureau score requirement. |

Each form spans two pages. **Enter** advances from the first page; **Enter on the last page saves**. F3 cancels an unfinished form. Minimum banking tenure is on the second marketing page. After four saves, the draft/rules should be **v5**. Consent, opt-out, suppression and identity matching remain mandatory.

Keep active dates that include today. Publication also requires the baseline campaign to remain current and active.

### 3. Run the comparison

Inside the draft choose **5 Run baseline / candidate simulation**. Select the population with **10,000 customers / seed 20260918** and set **Model / partition seed to `1717`**. Press **Enter** to start.

Refresh with **Enter** until `COMPLETED`. You may leave and reopen it through **Business → 05 Review simulation results / impact analysis**. Duration depends on the machine; it is a background job, not an instant form submission.

Expected: population denominator **10,000**, held-out test denominator **2,000**, a trained local K-means model, baseline/candidate eligibility and fit results, and per-customer reasons. The candidate loses some eligibility because its rules are stricter. Lower APR reduces borrowing cost for otherwise identical loan terms; inspect the paired statistics to separate that price effect from the changed eligible population.

With these seeds and the unchanged seeded catalog, the isolated regression produced **208 baseline-eligible customers, 173 candidate-eligible customers, 35 lost eligibility and 0 newly eligible**. These are synthetic reference results, not targets or evidence that the candidate should be published; changed policies, inputs or catalog state can change the counts.

### 4. Review the evidence

Open every report option on the completed result screen, using **F7/F8** to read all pages:

| Report | What to inspect |
| --- | --- |
| **1 Overall statistics** | Eligible counts/rates, newly eligible and lost eligibility, stage funnels and rejection reasons. |
| **2 Held-out test statistics** | Whether changes also appear among the 2,000 held-out customers. |
| **3 Cohort comparison / impact analysis** | Which groups gain or lose eligibility, group sizes and paired fit/cost changes. |
| **4 Model quality / assumptions / limitations** | K-means configuration, validation/test metrics and simulation assumptions. |
| **5 Per-customer eligibility and scores** | Specific baseline/candidate decisions, rejection reasons and personalized fit measures. |

Compare paired outcomes for customers eligible under both offers. A higher average among the remaining eligible customers does not alone establish that the new offer improved everyone's outcome. Simulated acceptance is a utility-based scenario, not measured take-up. This application does not establish real profit, default losses or real-world lending viability from synthetic results.

### 5. Publish after your review

From the completed result choose **6 Review and publish this tested offer + rules**. For this local demonstration, use:

| Field | Value |
| --- | --- |
| Review note | `Reviewed coverage, cohorts, fit and costs; local demo only.` |
| Type P to publish | `P` |

Press **Enter** only after you decide to publish. Expected: a receipt containing the new `BUS-...` offer ID, campaign ID, rule version, timestamp and review note. **Business → 06 Publication history** retains the receipt. Existing catalog offers remain intact; the experimental K-means model is not automatically deployed as the active personalization model.

To demonstrate the Customer connection, repeat the Customer case with a new external reference such as `DEMO-CUST-002`. Its income `7000`, tenure `36` and independent bureau score `780` pass the candidate's new thresholds. Look for the published offer ID in the qualified list, subject to current campaign capacity, priority and throttling. Publishing does not automatically reapply the new offer to previously processed customers.

## Interpreting a different result

| Observation | Check |
| --- | --- |
| External reference already exists | Open/update that customer, or use a new reference for a new application. |
| `NO_ELIGIBLE_OFFERS` during initial setup | Expected while marketing opt-in is `N`. Complete the bureau setup, then save consent `Y`. |
| Bureau `REVIEW` or `DECLINED` | Verify the independent report, its timestamp and the reasons; the customer-entered score does not replace it. |
| `RETRYING` or dependency errors | Confirm all three APIs and Kafka/PostgreSQL are available; inspect service logs. |
| Original offer appears before Personalized | Allow active model bootstrap and personalization/copy processing to finish, then refresh. |
| Qualified offers but no files | Review delivery task reasons, enabled channels and Step 7 contact limits. Current offer visibility and outbound delivery have separate gates. |
| New Business draft cannot be published | Run its latest version and check active dates and unchanged baseline/policy. An older simulation cannot authorize an edited draft. |

Run `scripts/test-bureau.ps1` for automated coverage of the real Java/COBOL/PostgreSQL/Kafka/3270 paths. Tests use their own schemas and fixtures; they do not clear your local customer databases.
