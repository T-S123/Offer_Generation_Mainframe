<!-- Provides one terminal-led Carl demo: customer processing, manual Business comparison, A2A exploration of the same draft, reviewed publication and observed feedback, preserving prior-run data. -->
# End-to-end demo walkthrough

This is a fresh second pass through the same Customer and Business flow, using **Carl Demo**. Keep the customers, simulations and published offers from your first run. This version uses new customer references, financial inputs, bureau facts, experiment seeds and candidate rules.

The examples assume the seeded `DEMO-PL-1` offer and `DEMO-PL` campaign are still active, their default policies remain unchanged, and the campaign has available capacity. Earlier publication adds catalog entries without replacing that baseline. Select rows by name and ID, not by their previous row numbers. If `DEMO-CARL-001` or `DEMO-CARL-002` already exists, use a new unused reference such as `DEMO-CARL-003` and substitute it consistently; do not delete databases or overwrite the earlier customer's profile.

## Start the application

The shell commands in this section are for starting the local services. **Once
the mainframe client opens, every demo action below uses its menus and fields.**
Do not paste PowerShell, JSON inspection commands or HTTP requests into the
mainframe terminal.

Before startup, keep your working `runtime/local.env` and ensure the ignored
`runtime/ai.env` contains your OpenAI API key and the chosen default cap:

~~~bash
OPENAI_API_KEY='YOUR_ACTUAL_OPENAI_API_KEY'
AI_DEFAULT_MAX_USD=10
~~~

If you already configured AI, keep that file. For first-time setup, follow the
[AI service setup](../ai-service/README.md#start-locally). The AI launcher reuses
the existing PostgreSQL connection. Configuration files must use Bash-compatible
assignments, LF line endings and UTF-8 without a BOM.

Using File Explorer, copy
`docs/demo-policies/a2a-personal-loan-policy.md` into `policy-documents`
under this project. If you already copied it, keep the same file; no replacement
is needed. This explicitly synthetic document supplies the permitted 680–740
bureau-score range. You will review and approve it inside the mainframe.
Use the configured policy directory if you changed AI_POLICY_DIR.

Stop older base and AI launchers with Ctrl+C before rebuilding. From PowerShell
in `Offer_Generation_Mainframe`, start the base application:

~~~powershell
.\scripts\infrastructure.ps1
.\scripts\build.ps1
.\scripts\build-ai.ps1
.\scripts\run.ps1
~~~

Build once initially and after source changes; subsequent launches can reuse the
built files. Keep this window open and wait for:

~~~text
All three APIs are responding. Marketing offers: 127.0.0.1:8092
Terminal: 01 Customer for an application; 02 Business for simulations.
~~~

In a **second PowerShell window** in the same folder:

~~~powershell
.\scripts\run-ai.ps1
~~~

Wait for **Six AI agents are ready** and keep that window open.

In a **third PowerShell window** in the same folder:

~~~powershell
.\scripts\run-ai.ps1 -Check
.\scripts\terminal.ps1
~~~

Maximize the console. Use **Tab** between fields, **Enter** to submit, **F3** to
return and **F7/F8** to page. **Ctrl+U** clears a field; **Ctrl+R** resets a locked
keyboard. **Ctrl+]**, then `Quit` and Enter, exits the terminal.

Select displayed rows by their names/IDs rather than assuming an earlier row
number. The optional graphical client is `.scripts	erminal.ps1 -Gui`;
use the console client if WSLg reports `Can't open display`.

If **02 Business → 08 AI-assisted simulation and offer feedback** is missing,
rebuild and restart the base application from this branch. An old running engine
does not acquire the new menu when a different JAR is built. If the menu exists
but reports AI unavailable, verify that all six agents are running.

## Customer case: application to personalized offers and marketing files

### 1. Enter a synthetic customer

Choose **01 Customer → 01 Enter my information**. Fill in both pages:

| Page 1 field | Value |
| --- | --- |
| Display name | `Carl Demo` |
| External ref | `DEMO-CARL-001` |
| Gross monthly income | `8200` |
| Monthly debt payments | `650` |
| Credit score (300–850) | `745` |
| Delinquencies (12 mo) | `0` |
| Credit utilization % | `25` |
| Personal loan amount | `12000` |
| Requested card limit | `4500` |
| Auto loan amount | `18000` |
| Vehicle value | `26000` |

Press **Enter** for page 2.

| Page 2 field | Value |
| --- | --- |
| Vehicle age in years | `4` |
| Banking tenure months | `48` |
| Deposit balance USD | `8500` |
| Monthly spend USD | `1700` |
| Products held | `CHECKING` |
| Marketing opt-in Y/N | **`N` initially, for the controlled setup below** |
| Prescreen OPT-OUT Y/N | `N` |
| Channels in order | `EMAIL` |

Press **Enter** to review, then **Enter** to save. Record the generated **Customer ID** from the progress or profile screen.

**Customer ID and External ref are different.** `DEMO-CARL-001` is the label you entered; saving creates a separate 36-character UUID such as `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`. Use that generated ID on the bureau screen. If you did not record it, choose **Customer → 02 Open a demo customer profile**, select Carl's row, and read **ID** on his profile; do not create another customer.

Expected: all three default underwriting assessments are `ELIGIBLE`, but application progress settles on `NO_ELIGIBLE_OFFERS` because marketing consent is off. This is an intentional intermediate result, not a failed save or a bureau rejection. No marketing offers or outbound files should be available. Continue with steps 2 and 3 below to prepare the independent bureau report and then enable consent for the positive demo.

For an unprepared, randomized demo you can use `Y` immediately instead. The whole pipeline runs automatically, but a good self-reported score does **not** guarantee bureau approval: each new customer's independent synthetic report can decline or require review. Use the following setup for a repeatable positive case.

### 2. Prepare the independent bureau report in Business mode

Press **F3** until the mode selector appears. Choose:

**02 Business → 07 Active customer / campaign operations → 09 Bureau qualification / credit decision → 4 Generate / edit an independent bureau profile**.

Enter the generated Customer ID recorded above, **not `DEMO-CARL-001`**. The screen loads a generated report; replace its facts with:

| Bureau field | Value |
| --- | --- |
| File status | `MATCHED` |
| Bureau score | `775` |
| Bureau debt USD/mo | `700` |
| Utilization % | `18` |
| Delinquencies 12m | `0` |
| Inquiries 6m | `2` |
| Oldest account mo | `156` |
| Bankruptcy Y/N | `N` |
| Reported UTC | Keep the freshly generated timestamp |

If you reuse an older report, use a current nonfuture UTC timestamp instead. Press **Enter**. Expected: a new bureau version saved with source `LOCAL_EDIT`. This changes synthetic input evidence, not the qualification rules or a historical decision.

### 3. Enable consent and let Steps 1–8 run

Return to the mode selector with **F3**. Choose **01 Customer → 02 Open a demo customer profile**. Select Carl's row, or enter his Customer ID in **Or customer ID**.

On the profile, press **F4** to edit. Keep page 1 unchanged and press **Enter**. On page 2 change **Marketing opt-in to `Y`**, keeping **Prescreen OPT-OUT `N`** and **Channels `EMAIL`**. Press **Enter** to review and **Enter** to save.

The new profile version automatically starts the entire workflow. The progress screen refreshes every two seconds. Allow model and offer-copy processing to finish; the active personalization model may already be trained from the first run. Entering another customer is unnecessary.

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

For Carl's **$12,000** request, the unchanged seeded personal-loan offer uses **12% APR for 36 months, with no annual fee**. However, a personal-loan offer published during your first run may now take priority and show different original terms. Inspect the actual catalog offer ID, name and terms on the screen; do not assume that the first displayed row is `DEMO-PL-1` or that it must use 12% APR.

Step 2 campaign priority, capacity and customer-wide cooldown affect reservation allocation. Carl can therefore see a different number of personal-loan offers, or no card/auto offers, despite all three underwriting assessments passing. His fresh customer ID avoids reusing the earlier customer's contact history, but the shared campaign state remains.

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

Optional final negative check: edit Carl again and set marketing opt-in to `N`. Current offers and downloads must become unavailable, and the background worker withdraws the active outbound files. Historical decisions and selections remain for audit. Do this **after** demonstrating the successful offer and file path.

## Business case: manual comparison, then AI on the same draft

This is one continuous mainframe demonstration: **create a draft → run one manual
comparison → ask AI to explore that same draft → approve its scope → inspect the
tested winners → publish → check customer feedback**.

The first comparison uses your chosen APR and rule changes. Then AI varies only
bureau minimum score while preserving your edited offer terms, marketing and
underwriting. All user actions after startup occur in the terminal. Isolated
Business populations do not create active customer accounts or outbound packages.

### 1. Generate a reproducible population

Choose **02 Business → 01 Generate synthetic customers**:

| Field | Value |
| --- | --- |
| Customer count | `10000` |
| Population seed | `20260929` |

Press **Enter**. Expected: **10,000 isolated customers**, with a mix of eligible, ineligible, incomplete, suppressed and nonconsenting people. Inspect rows through **02 Inspect isolated populations**; self-reported inputs and independent bureau facts are distinct.

### 2. Create and edit an offer draft

Choose **04 Create a draft from an existing catalog offer**. Set the draft name to **`Carl personal loan comparison`** and select the displayed row with ID **`DEMO-PL-1`**. It uses the associated **`DEMO-PL`** campaign.

Make these four edits from the draft menu, keeping all other fields at their baseline values:

| Draft menu | Change | Purpose |
| --- | --- | --- |
| **1 Edit offer terms / dates** | Name `Carl personal loan comparison`; APR `9.50` instead of `12.00` | Test a cheaper loan with the same amount range, fee and 36-month term. |
| **2 Edit Step 1 underwriting criteria** | Min monthly income USD `3000` instead of `2000` | Test a higher income floor. |
| **3 Edit Step 2 marketing criteria** | Min banking tenure (months) `12` instead of `0` | Restrict the campaign to established relationships. |
| **4 Edit Step 3 bureau criteria** | Minimum credit score `720` instead of `680` | Test a stronger independent bureau score requirement. |

Each form spans two pages. **Enter** advances from the first page; **Enter on the last page saves**. F3 cancels an unfinished form. Minimum banking tenure is on the second marketing page. After four saves, the draft/rules should be **v5**. Consent, opt-out, suppression and identity matching remain mandatory.

Keep active dates that include today. Publication also requires the baseline campaign to remain current and active.

### 3. Run the comparison

Inside the draft choose **5 Run baseline / candidate simulation**. Select the population with **10,000 customers / seed 20260929** and set **Model / partition seed to `2929`**. Press **Enter** to start.

Refresh with **Enter** until `COMPLETED`. You may leave and reopen it through **Business → 05 Review simulation results / impact analysis**. Duration depends on the machine; it is a background job, not an instant form submission.

Expected: population denominator **10,000**, held-out test denominator **2,000**, a trained local K-means model, baseline/candidate eligibility and fit results, and per-customer reasons. The higher income, tenure and bureau-score floors can reduce eligibility; inspect the actual gains and losses for this new sample. Lower APR reduces borrowing cost for otherwise identical loan terms; inspect the paired statistics to separate that price effect from the changed eligible population.

The exact eligible counts for seed **20260929**, model seed **2929** and these new thresholds have not been precomputed. Record the baseline-eligible, candidate-eligible, newly eligible and lost-eligibility counts from this run. The previous walkthrough's counts belong to its earlier sample and rules; they are not expected results for Carl's comparison.

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

### 5. Continue the same experiment with AI

Keep the manual result for comparison. **Do not publish it yet.** The next steps
use A2A to explore the same edited draft, then publish one reviewed AI-tested winner
through the existing engine.

This is still the Business Simulation Engine. The manual option tests one chosen
configuration; the AI option proposes a permitted range and runs a bounded set of
configurations through that same engine.

Your edited draft is the AI starting configuration:

| Field | Value AI must preserve |
| --- | --- |
| Offer APR | 9.50 |
| Underwriting minimum monthly income | 3000 |
| Marketing minimum banking tenure | 12 months |
| Other offer terms and rule fields | Their saved draft values |
| Bureau minimum score | Currently 720; this is the only field AI may vary |

The permitted AI range will be 680–740, step 1. Locking underwriting preserves
your manual income edit; it does not restore the original catalog rules.

### 6. Review the evidence inside Business

Return with F3 to Business home. Choose:

**08 AI-assisted simulation and offer feedback → 3 Review local policy documents**.

1. Enter **R**, then Enter, to refresh the local folder.
2. Select **a2a-personal-loan-policy.md** by its displayed row.
3. Confirm **Extraction: READY**. Choose **1 Review extracted text and metadata**.
4. Read with F7/F8. This synthetic policy permits bureau scores 680–740 for
   PERSONAL_LOAN local tests.
5. Press F3. Choose **2 Approve this exact document version**.
6. Set **Product or ALL = PERSONAL_LOAN**. Keep the displayed future expiry date,
   or enter another future date in YYYY-MM-DD format.
7. Type **A** and Enter. Confirm **Approved: true**.

If the unchanged document is already approved for PERSONAL_LOAN and is unexpired,
review it and retain that approval. Resolve incomplete extraction before proceeding.
Document edits revoke approval.

This supplies the Research agent with evidence. You do not enter a policy-search
API call in the terminal.

### 7. Reserve a fresh final-validation population

Return to Business home and choose **01 Generate synthetic customers** again:

| Field | Value |
| --- | --- |
| Customer count | 10000 |
| Population seed | 20260930 |

Record this as **AI final validation**. Do not use it in the manual comparison
or inspect its results to choose ranges before the final test.

The earlier **10000 / seed 20260929** population is discovery data for both the
manual comparison and AI exploration. **10000 / seed 20260930** is the separate,
untouched final check. Neither creates active Customer-mode accounts.

If either seed previously served as final validation for any AI workflow, use a
fresh unused pair throughout this demo. For example, choose 20261001 for discovery
and 20261002 for validation and run the manual comparison on the new discovery
population. A new population ID with an old validation seed is not reusable.

### 8. Launch A2A from the same draft

Choose **Business → 03 Open offer / rule drafts**. Select your original
**Carl personal loan comparison v5**, not a generated AI candidate or an older
published catalog offer.

Choose **7 AI explore this draft**.

This carries the selected draft directly into AI. Alternatively, **Business → 08
→ 1 Explore an existing draft** lets you select the same draft. These are two
entry points to the same feature.

Select:

1. Discovery: **10000 customers / seed 20260929**.
2. Final validation: **10000 customers / seed 20260930**.

Enter one line in each of the three intent fields:

~~~text
Explore only bureau minimum score from 680 to 740, step 1.
Keep this draft's offer terms, marketing and underwriting fixed.
Use the approved demo policy. Report both best-tested winners.
~~~

Each line fits the terminal's 73-character field. Press Enter.

### 9. Set the budget in the terminal

| Terminal field | Value |
| --- | --- |
| Simulation attempt limit | 20 |
| Wall-clock minutes | 60 |
| Model call limit | 60 |
| Token reservation limit | 500000 |
| Maximum reserved model cost USD | 10 |
| Minimum eligible support | 5 |
| Lock all underwriting? Y/N | Y |
| Acceptance winner: eligibility floor % | 0 |
| Eligibility winner: acceptance floor % | 0 |

Press Enter. This authorizes model-assisted planning within the cap. It does not
yet authorize simulations or publication.

For 20 attempts, the terminal allocates **1 baseline + up to 12 exploration +
5 refinement + 2 final-validation attempts**. Deduplication can reduce actual
trial counts. Support=5 is a demonstration setting, not a recommended production
evidence threshold. The $10 cap is a maximum reserved cost, not an estimated bill
or guarantee of completion; a budget pause preserves the workflow.

### 10. Inspect and approve the scope

At **PLANNING**, leave the option field blank and press Enter to refresh.
Orchestrator, Research, Designer and Reflection prepare the proposal.

At **AWAITING_SCOPE**:

1. Choose **1 Inspect exact scope / ranges / evidence IDs** and read all pages.
2. Confirm the only allowed field is **/rules/bureau/minimumScore**.
3. Confirm **low 680, high 740, step 1**, with nonempty evidence IDs.
4. Confirm **underwriting** is locked and the budget retains the **$10** cap.
5. Press F3. Choose **3 Approve reviewed scope and start simulations**.
6. Type **A**, then Enter.

If the proposal differs, use **4 Answer clarification / revise intent** before
approval. Do not approve an unintended field or range.

Expected: **EXECUTING → COMPLETED**. Blank Enter refreshes. Coordinator reviews
execution feasibility; Java runs the simulations; Analyzer interprets computed
results and Reflection reviews the conclusions.

The AI BASELINE trial evaluates your edited v5 configuration, including APR 9.50
and income floor 3000. The earlier manual comparison contrasted v5 with the original
catalog baseline. Judge AI's search against its own BASELINE and validation
results. The AI terminal manages its search/model seed separately from the manual
2929 seed; these are not identical runs.

To leave and resume: **Business → 08 → 2 Resume a workflow / inspect results**,
then select this intent. Do not create another workflow just to refresh it.

### 11. Read winners and all simulations in the terminal

The workflow screen shows two headline metrics:

- **Acceptance winner:** best supported tested acceptance among eligible customers.
- **Eligibility winner:** best supported tested eligibility among assessed customers.

The same candidate may win both. An unavailable winner remains unavailable;
there is no promised target such as 80%.

Use these menu options instead of PowerShell inspection commands:

| Information | Terminal action |
| --- | --- |
| Both winners, candidate IDs and run IDs | **2 Inspect analysis, limitations and workflow**; page to winners / acceptance and winners / eligibility |
| Analyzer conclusions | In option **2**, read analysis / decision / summary and claims |
| Reflection's review | In option **2**, read reflection / decision / summary and claims |
| A2A handoffs | In option **2**, inspect steps; completed records contain role, taskId and resultRef |
| Scope and policy citation IDs | **1 Inspect exact scope / ranges / evidence IDs** |
| All trials, including failures | **5 All simulation results and trial status**, then F7/F8 |
| A trial's exact terms, rules and scores | In option **5**, select a row and page through its details |
| Simulation attempt usage | Trials used counter on the workflow screen |
| Configured resource limits | Budget in the scope/workflow report |
| Exact winner configuration | **6 Preview a tested winner for publication**, then inspect the preview |

In trial details, read **configuration / rules / bureau / minimumScore** and match
candidate IDs to the two winners. Winning trials must be **VALIDATION** and
**COMPLETED**. Confirm APR, underwriting, marketing and other fields still match
v5. Record each winner's bureau score and both rates.

The trial list labels acceptance **A** and eligibility **E**:

- Acceptance = 100 × expected selections / eligible customers.
- Eligibility = 100 × eligible customers / assessed customers.

These are synthetic **SIMULATED_UTILITY** results, not real conversion forecasts
or proof of causal effects. "Best tested" is bounded by your simulation budget.

A completed workflow normally contains **ORCHESTRATOR, RESEARCH, DESIGNER,
COORDINATOR, ANALYZER and REFLECTION** in its saved steps. Reflection can occur
more than once. These are paginated field/value reports; technical labels are
displayed data, not commands to type.

The terminal does not expose every low-level API artifact or the complete
token/cost accounting response. Full research artifact downloads and detailed
usage inspection are optional developer checks. They are not needed to launch,
review, publish or check the offer through the mainframe.

### 12. Publish the AI-tested winner

From the **AI workflow** choose **6 Preview a tested winner for publication**.
Use this AI publication path, rather than publishing the earlier manual trial.

| Field | Value |
| --- | --- |
| Acceptance A / Eligibility E | A |
| Review note | Reviewed Carl AI trials and locked underwriting; demo only. |

Choose **E** instead if you prefer the eligibility winner after reviewing the
tradeoff.

1. Press Enter to create a preview.
2. Choose **1 Inspect complete terms, rules, exact changes and report**.
3. Page through configuration, changedPaths, prediction, run ID, expiry and hash.
   Confirm only the bureau score differs from the AI starting draft.
4. Press F3.
5. Clear **Inspect option** if it still contains 1. Type **P** in **Type P to
   publish**, then Enter.

Inspection is required. Previews expire after 15 minutes; create and review a
fresh one if needed. Publication goes through the existing engine into the active
catalog, creating an offer and campaign while retaining existing catalog entries.

The receipt is a paginated report. Record **receipt / offerId** and **receipt /
campaignId**. Find the publication later in **Business → 06 Publication history**
or **Business → 08 → 4 Published offer performance**.

### 13. Return to Customer, then check the same offer's feedback

Repeat the Customer steps above with:

| Field | Value |
| --- | --- |
| Display name | Carl Followup |
| External reference | DEMO-CARL-002, or a fresh unused DEMO-CARL reference |
| Financial and bureau fields | Same values as Carl Demo's tables |
| Initial marketing consent | N; prepare the new bureau profile, then change to Y |

Use the new customer's UUID for their bureau report. Income 8200, tenure 48 and
bureau score 775 satisfy the preserved income/tenure criteria and every permitted
bureau threshold up to 740.

Look for the AI publication's exact catalog offer ID. Older campaigns can still
take priority; inspect marketing reasons if it is absent. Select an available
alternative for the matching offer and inspect marketing files as before.

Return to **02 Business → 08 AI-assisted simulation and offer feedback → 4
Published offer performance → select that publication → 1 Current selections /
eligible and the saved estimate**.

Read eligible count, selections, current rate, saved simulated estimate, source
and maturity. This read does not start another model workflow.

A new offer can have no enrollments yet, so observed acceptance is unavailable.
Recent customer selections are **DEMO**, with provisional follow-up. One local
interaction does not supply a mature two-week real cohort.

The same screen also offers:

- **2 Ask why:** a budgeted research, engine-diagnostics, analysis and reflection workflow.
- **3 Explore edits:** a successor using the published offer, fresh populations and inherited locks.

Those are additional workflows requiring explicit budgets. Stop at current
performance for this $10-capped primary demo. A later authorized feedback/revision
demo can use those same menus without HTTP or PowerShell; its model budget must
be considered separately before submission.

## Interpreting a different result

| Observation | Check |
| --- | --- |
| External reference already exists | For this second-pass demo, keep the earlier customer and use an unused `DEMO-CARL-...` reference. |
| `NO_ELIGIBLE_OFFERS` during initial setup | Expected while marketing opt-in is `N`. Complete the bureau setup, then save consent `Y`. |
| Bureau `REVIEW` or `DECLINED` | Verify the independent report, its timestamp and the reasons; the customer-entered score does not replace it. |
| `RETRYING` or dependency errors | Confirm all three APIs and Kafka/PostgreSQL are available; inspect service logs. |
| Original offer appears before Personalized | Allow active model bootstrap and personalization/copy processing to finish, then refresh. |
| Qualified offers but no files | Review delivery task reasons, enabled channels and Step 7 contact limits. Current offer visibility and outbound delivery have separate gates. |
| New Business draft cannot be published | Run its latest version and check active dates and unchanged baseline/policy. An older simulation cannot authorize an edited draft. |
| Older published offer appears instead of this run's offer | Compare catalog IDs and inspect campaign priority, capacity and marketing exclusion reasons. Prior publications remain active; a new customer does not reset shared campaign state. |
| AI menu is missing | Rebuild and restart the base engine from this branch; the running process may still use an older JAR. |
| AI menu reports unavailable | Start scripts/run-ai.ps1 in its own service window after the base services are ready. |
| Policy is not READY or approved | In AI home option 3, refresh, inspect extraction and approve the exact current document for PERSONAL_LOAN. |
| NEEDS_INPUT before scope approval | Read the clarification using workflow option 2; reply using option 4 and re-review the proposed scope. |
| PAUSED_BUDGET | Inspect the error. Option 8 can extend an expired deadline while preserving the $10 cap and existing limits. Do not automatically increase the cost cap. |
| FAILED or REVIEW_REQUIRED | Read error, analysis and reflection using option 2. Do not treat a stopped workflow as completed or publish it. |
| Validation population rejected | Generate a fresh unused seed; a new ID with a previously used validation seed is not sufficient. |
| Publication says inspect first | Use AI preview option 1, read all pages, return with F3, clear Inspect option, then type P in the publication field. |
| Current acceptance is unavailable or provisional | Check eligible enrollments, source and follow-up maturity. A saved utility estimate does not manufacture observed selections. |

Run `scripts/test-bureau.ps1` for automated coverage of the real Java/COBOL/PostgreSQL/Kafka/3270 paths. Tests use their own schemas and fixtures; they do not clear your local customer databases.
