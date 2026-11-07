# Filming demo: all three A2A networks

Use this script with the prepared local data. Run the scenes in order **Network 1 → Network 3 → Network 2**: publish a tested offer, personalize it for a customer, then investigate recorded activity. This is a synthetic local demo; an offer selection is not a funded loan. Model-backed scenes make paid provider calls within the displayed limits.

**What to capture on camera:** (1) a validated candidate and its publication receipt, (2) a customer reviewing and confirming the personalized terms, and (3) the Business answer that traces that same selection. Keep the new catalog ID handy between scenes. The directions below describe a desktop-width browser. On narrower windows the panels stack, so follow their headings.

## Before recording: start the services

Open three PowerShell windows. In each:

```powershell
Set-Location C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe
```

**Window 1 — base application:**

```powershell
.\scripts\infrastructure.ps1
.\scripts\run.ps1
```

Wait for `All three APIs are responding`.

**Window 2 — all three A2A networks:**

```powershell
.\scripts\run-ai.ps1
```

Wait for `Simulation (six), investigation (five), and customer personalization (five) are ready.`

**Window 3 — readiness and GUI:**

```powershell
.\scripts\run-ai.ps1 -Check
.\scripts\run-gui.ps1
```

Open **http://127.0.0.1:8080**. Keep all three windows open. If a browser tab still shows an earlier rehearsal, reload after the launchers report ready. Start from the Lending Studio landing page; if you are already in a workspace, click **Sign out** in the sidebar. These use existing build artifacts. If rebuilding is needed, stop the launchers first and run `build.ps1`, `build-ai.ps1`, and `build-gui.ps1` from `scripts`, then restart.

### Prepare a repeatable start with the presenter button

Click the circular **Presenter controls** button at the bottom right. Choose **Demo A2A**, read the confirmation, then click **Prepare A2A demo**. This replaces the current local demo history. Keep all three launch windows open; wait for the landing page to return automatically. Preparation creates the customer, approved policies, draft and cohorts through the owner APIs, without executing agents or publishing. The **A2A demo ready** dialog tells you exactly which customer and draft were created, their IDs, the starting catalog offer and both cohort seeds. Keep the customer reference for Network 2; use the customer name for Network 3 and the saved draft name for Network 1. Click **Done**, then begin **Scene 1** below. Reopen the presenter button whenever you need these prepared identities again.

Drag the button if it obstructs the screen. For a fresh customer-signup demonstration instead, choose **Clear all data → Clear demo data**. For another three-network recording, repeat **Demo A2A** after finishing; each preparation creates unused cohort IDs and current synthetic bureau evidence.

### Prepared starting data

| Item | Select/use |
| --- | --- |
| Customer | **A2A Recording Customer** |
| Customer reference | `A2A-FILM-001` |
| Customer ID | New on each preparation; select by name or use reference `A2A-FILM-001` |
| Starting profile | Version **2**, monthly income **9000**, marketing consent **Yes** |
| Draft | **A2A filming personal loan · v2** |
| Discovery population | **10,000 customers · seed 20261105** (new ID each time) |
| Final-validation population | **10,000 customers · seed 20261104** (new ID each time) |
| Analysis seed | `20261106` |
| Evidence | Two approved personal-loan demo policy documents |
| Starting publication state | No publication from this recording experiment |

The dedicated `FILM-PL-20261104` campaign has priority 5. Its published copy sorts before this baseline campaign, making the new publication available in the customer's limited campaign slot. The working default campaigns are restored. Both populations use the preparation day's assessment snapshot, within the new campaign's effective dates.

The customer has a current synthetic bureau report. UUIDs and dates change after each preparation; select by the names and seeds in this guide. Exact current IDs are also recorded in `runtime/maintenance/a2a-filming-start.json`. Do not run customer signup again before Scene 1.

**Starting the experiment reserves its validation cohort.** A second independent experiment requires a new final-validation population with a different seed. Do not try to reuse a validation population reserved by an earlier workflow, including a blocked workflow. Each successful Demo A2A preparation leaves its final-validation population unused.

## Scene 1 — NETWORK 1: Simulation and offer design

**Say:** “Network 1 designs offers by testing approved business-rule changes against synthetic customers. The agents propose and analyze; the application executes the rules and I approve publication.”

1. On the landing page, click **Enter Business workspace** inside the dark **Business workspace** card on the right. The left navigation contains **Offer performance**, **Simulations**, **A2A exploration**, **Investigations**, and **Policy evidence**.
2. Show **Offer performance** briefly. It should have no publication from this experiment yet. Existing manual or historical records are separate.
3. Click **Policy evidence**, the last item in the left navigation. Show both files as **Approved**. You may open **Read and review** to show their scope, then close the dialog. Do not approve an unchanged document again.
4. Explain that the approved range is **bureau minimum credit score 680–740**, with all other fields fixed. Agents cannot invent authority to change underwriting; a reset removes older experiments.
5. Click **A2A exploration** in the left navigation. Find **A2A simulations** at the top of the main pane and click **Start A2A exploration** to the right of the title.

Fill **Start an A2A experiment**:

| Field | Input |
| --- | --- |
| Saved offer draft | `A2A filming personal loan · v2` |
| Discovery population | Seed `20261105`; match the current option's ID |
| Final-validation population | Seed `20261104`; match the current option's ID |
| Analysis seed | `20261106` |
| Keep these stages unchanged | Check **Offer**, **Underwriting**, **Marketing**; leave **Bureau** unchecked |

Paste into **Objective and constraints**:

```text
Explore only /rules/bureau/minimumScore from 680 through 740,
step 1, using the approved demo policy.

Keep this saved draft's offer terms, underwriting, marketing,
and all other bureau fields fixed.

Report separate best-tested acceptance and eligibility winners,
each with at least 5 eligible customers. Both cross-metric floors
are 0%. Use the separate untouched final-validation population.

Explain synthetic-utility and small-sample limitations.
Do not publish automatically.
```

Scroll down in the experiment form and expand **Resource budget**. Replace these values explicitly; the default total is 200, not 20:

| Field | Value |
| --- | ---: |
| Total simulation limit | `20` |
| Exploration trials | `12` |
| Refinement trials | `5` |
| Validation trials | `2` |
| Minimum eligible customers | `5` |
| Model call limit | `60` |
| Token limit | `500000` |
| Maximum cost ($) | `10` |
| Eligibility floor (%) | `0` |
| Acceptance floor (%) | `0` |

Keep a future **Deadline (UTC ISO timestamp)**. Check **I approve model execution within this budget. Catalog publication requires a separate review.** Click **Start A2A workflow** at the bottom of the form.

### While Network 1 runs

In **Simulation details**, show **Resource usage** on the left and **Agent activity** on the right. Scroll down for **Saved trials → Inspect evaluations**. If you leave the screen, click **A2A exploration**, find the matching objective and start time under **Saved simulations**, and click that row to reopen it. The six roles are Orchestrator, Research, Designer, Coordinator, Analyzer, and Reflection. This can take several minutes; pause the recording while waiting rather than promising instantaneous results.

If a focused question appears, read it and answer within the stated constraints. If explicit scope approval appears, inspect it before approving. A budget/deadline pause is not completion; use **Review budget extension** only if you intentionally authorize more resources. A blocked run must explain its constraint.

**Say:** “These acceptance estimates come from synthetic utility. They are not measured customer conversion. Final validation uses a separate population that did not guide the search.”

### Publish the tested result

1. Wait for **Completed** in **Simulation details**. Scroll past the agent list to **Findings and reflection** and expand **Analysis and supporting evidence**.
2. Read the eligibility winner in the claims. Note its candidate ID and validation run ID. Both objectives can share a winner. Read the displayed results for your run.
3. Scroll below **Saved trials** to **Review a validated candidate**. In **Final-validation candidate**, match the candidate's first 16 characters and the validation run prefix. This dropdown shows IDs, not winner names.
4. In **Review note**, enter:

   ```text
   Reviewed the final-validation result, exact bureau-score change,
   and simulated-utility limitations for this local A2A demo.
   ```

5. Click **Prepare publication preview**, directly below the note.
6. In **Confirm exact catalog publication**, show **Changed fields** and **Exact offer and rules**. The search must not have changed locked terms or stages.
7. Check **I reviewed this configuration and approve publication to the catalog.** Click **Confirm publication** at the bottom right of the dialog.
8. When the dialog closes, scroll back toward the top of **Simulation details**. In **Published to catalog**, copy the heading beginning with `BUS-`. Expand **Inspect publication receipt** in that panel to show the linked candidate and validation run. Call it `NEW_OFFER_ID` for the remaining scenes. Do not confuse a simulation run ID, customer offer/response ID, and catalog offer ID.

**Checkpoint:** there is a publication receipt linked to the original prediction. The customer has not accepted it merely because it was published.

## Scene 2 — NETWORK 3: Customer personalization and guidance

**Say:** “Now I am testing Network 3. It uses this customer's latest saved information and currently qualified offers. It can propose a customer-only variant within approved limits and guide me to review it.”

1. Click **Sign out** at the bottom of the left sidebar.
2. In the **Customer portal** card on the left, click **Already a customer? Log in** below the create-profile button.
3. Under **Saved customers**, click the row **A2A Recording Customer**. The row opens the profile immediately. Alternatively, paste its UUID into **Customer ID** above the list and click **Open my profile**.
4. Click **My information** in the left sidebar. Scroll to **02 Financial profile** in **Edit profile**. Show the prefilled income.
5. Change **Monthly income ($)** from `9000` to `9200`. Keep the other fields unchanged.
6. Scroll past **Your preferences** to the bottom right of the form and click **Review information**. In **Review profile changes**, check the income, scroll to the bottom if needed and click **Save updated profile**.
7. Wait for the save dialog to close. **Overview** shows **Saved profile v3** and income **$9,200.00**. Click **My offers**. If it says **Checking your offers…**, click **Refresh** until the **Qualified** personal-loan card appears. Its heading must match `NEW_OFFER_ID`. A table showing **Original** and **Personalized** terms can already be present here from standard offer creation. The A2A run starts with the next button.
8. Show that the **Personalized offer** panel is still **Not started**: saving a profile did not itself authorize agent spending.
9. Click **Find my personalized offer** in the **Personalized offer** panel near the top of **My offers**, above the loan comparison table. The two **Review** buttons beneath the table open existing offers; use the button in the top panel to film the agent workflow.

The five roles are Personalization Orchestrator, Customer Context, Offer Designer, Qualification, and Offer Guide. The owner API evaluates and issues the exact candidate. Default limits are three candidates, ten model calls, 300,000 tokens, up to the configured $15 ceiling, and 180 seconds.

If **Your answer** appears, answer the specific question and click **Send answer**. To stop, use **Cancel personalization**. Refreshing should reopen the same run for this profile version rather than authorize duplicate work.

### Review and accept

When a qualified result completes:

1. When the top panel reads **Completed**, click **Open personalized offer** below its three numbered steps.
2. Show amount, APR, monthly payment, borrowing cost, term, and expiry.
3. Click **Review current terms** immediately below the term and expiry details.
4. Inspect **Confirm your offer choice**.
5. Click **Confirm selection** at the bottom right of the dialog. Film the message **Your reviewed offer choice has been saved.** near the top of **My offers**. That is the end of the customer scene.

**Say:** “The agent did not accept on my behalf. The customer confirms the exact terms, and the backend checks current qualification again.”

If the catalog ID differs from `NEW_OFFER_ID`, stop before confirming and check the publication receipt and current qualification. Network 2 can only attribute the selection to the offer the customer actually chose. In the prepared rehearsal the two IDs matched and one confirmation was sufficient.

`NO_QUALIFIED_IMPROVEMENT` is an honest bounded result, but it does not demonstrate successful issuance and guidance. Do not describe it as a completed happy-path test. Preserve currently valid offers and inspect the returned reasons. A stale profile or expired offer requires requalification.

## Scene 3 — NETWORK 2: Performance and investigation

**Say:** “Network 2 investigates recorded evidence. The general dashboard reads the Performance API directly; agents run only when I submit an investigation.”

1. Sign out and click **Enter Business workspace**.
2. Business entry opens **Offer performance**. In the **Observation source** dropdown choose **Local demo outcomes** (already selected in the rehearsal). Click **Refresh outcomes** at the top right beside the page title.
3. Scroll to **Published AI offers** and find **A2A filming personal loan**. Match the time and the `BUS-` ID in **All publication receipts** beneath the cards. Show the pinned prediction and the recorded counts on the publication card.
4. Show the original prediction beside recorded eligible/selected counts, source, maturity, and comparability. These are recorded demo selections, not real loan conversions.
5. Click **Investigate with A2A** near the bottom of that publication card. In the left **Ask an investigation question** panel, verify **Published offer** already says **A2A filming personal loan**. The panel on the right lists saved investigations.

Enter:

| Field | Value |
| --- | --- |
| Customer ID or reference (optional) | `A2A-FILM-001` |
| Start date (inclusive) | Leave blank. Put the first date in the question below. |
| End date (inclusive) | Leave blank. Put the last date in the same question. |
| Timezone | `America/Los_Angeles` |
| Date basis | **First eligible date** for this recorded-offer demonstration |
| Observation source | **Recorded demo outcomes** |
| Aggregate offer variant | **Offer family** |
| Evidence cutoff | Leave blank |

First assessment is the application's default. This scene deliberately selects first eligibility because native offer events record that cohort entry; a missing historical assessment denominator must not be invented. The optional check below demonstrates that limitation explicitly.

Replace both occurrences of `2026-11-06` below with the Los Angeles date when the customer qualified. If customer activity spans midnight, use the applicable start and end dates. Paste the resulting question into **Your question**. This uses the form's documented option to give dates in the question. The two dates apply to the aggregate. The individual lookup covers the customer's recorded history through the cutoff.

```text
For A2A filming personal loan, what was offer-family acceptance among customers first eligible from 2026-11-06 through 2026-11-06 inclusive, in America/Los_Angeles? Use recorded demo outcomes and combined distinct counts. Compare those counts with the original simulation prediction and explain whether a forecast gap is meaningful. Also, as of the evidence cutoff, was customer A2A-FILM-001 eligible for the exact PERSONALIZED variant of this publication? Give the recorded profile version, rule version and qualification evidence. Identify any missing evidence.
```

Review **Investigation budget**: 30 model calls, 300,000 tokens, maximum reserved cost $10. Check **I authorize agent execution within this budget.** Click **Start investigation** at the bottom of the left form, under the authorization checkbox.

The five roles are Investigation Orchestrator, Evidence Retrieval (Research), Customer Eligibility, Metrics Analyst (Analyzer), and Evidence Reviewer (Reflection). If asked for clarification, answer the displayed question. When **Investigation details** reads **Completed**:

1. Scroll below **Resource usage** and **Agent activity** to **Investigation answer**. Read the dates, timezone, date basis, and the counts printed below **Recorded acceptance**. Show the follow-up count beside the prediction and **Target mismatch**.
2. Continue to **Customer eligibility: A2A-FILM-001**. Show the publication, **Personalized**, profile version, rule version and `CP-` qualification ID. This connects the investigation to the particular variant issued in Scene 2.
3. Directly below, under **Supporting evidence**, click **Open customer decision evidence**. In **Saved agent evidence**, point to `variant`, `profileVersion`, `qualificationId` and `terms`. Close with **×** at the top right. Next to that button, **Open measurement evidence** shows the saved cohort, cutoff and original prediction. Close it to finish on the answer. To reopen the result later, click **Investigations** in the sidebar and select the dated question under **Saved investigations**.

**Checkpoint:** the answer addresses both aggregate performance and individual eligibility, cites evidence, and preserves missing-data limitations. It must not run simulations, change rules, publish, or rewrite the original prediction.

**Say:** “One recorded customer's selection is not a validated population forecast. These new observations can be provisional. Synthetic utility and observed acceptance may have incompatible definitions, so the system must not invent a forecast gap.”

Optional follow-up: ask for the same period using **First assessment date**. If assessed-customer history is unavailable, the correct answer says so. Another useful question is whether the agent can change the rules; it should require a separate Network 1 experiment and publication review.

## Finish the recording

Show the saved investigation, then stop the GUI, AI, and base launchers with Ctrl+C in their respective windows. Stop infrastructure without deleting stored data:

```powershell
.\scripts\infrastructure.ps1 -Stop
```

## If filming later: refresh synthetic bureau evidence

For a new recording, prefer **Presenter controls → Demo A2A**. To preserve an in-progress recording instead, this is only needed when its retained report is too old. While base services are running, use a fourth PowerShell window in the project directory. This appends a new **synthetic** report version with unchanged facts and a current timestamp; it does not overwrite the historical record. The GUI currently has no bureau editor.

```powershell
$customerId = (Get-Content runtime/maintenance/a2a-filming-start.json -Raw | ConvertFrom-Json).customerId
$headers = @{ Authorization = 'Bearer ' + (Get-Content runtime/api-token -Raw).Trim() }
$uri = "http://127.0.0.1:8090/api/v1/bureau/profiles/$customerId"
$current = Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -ContentType 'application/json' -Body '{}'
$current.facts.reportedAt = [DateTime]::UtcNow.ToString('o')
$body = @{ expectedVersion = $current.version; source = 'LOCAL_EDIT'; facts = $current.facts } | ConvertTo-Json -Depth 5
Invoke-RestMethod -Method Put -Uri $uri -Headers $headers -ContentType 'application/json' -Body $body | Select-Object customerId, version, source
```

Then make and save the intended customer profile edit in Scene 2 to requalify. If dates have passed the campaign/offer effective period, create a new valid draft and cohorts instead of silently rewriting the published historical offer.

## Presenter controls verification

The presenter controls were tested on November 7, 2026, in a separate database, H2 directory and Kafka broker. **Demo A2A** returned the exact customer and draft identities in its completion dialog, prepared both unused cohorts and approved policies, and left agent workflows unpublished and unstarted. The seeded customer's profile opened as v2 with income 9000. **Clear all data** removed a test customer, both cohorts, a completed manual simulation and its publication; it restored six default catalog offers and returned a logged-in customer to the landing page. A new customer was then created successfully through the GUI. The original recording database and both H2 files were unchanged.

These checks validate reset and fixture preparation; they do not rerun paid A2A workflows. The earlier three-network rehearsal results remain below.

## What the live rehearsal showed

These are checkpoints from November 6, 2026. On camera, read your own run's IDs, dates, rates and terms.

| Scene | Verified in the GUI |
| --- | --- |
| **Network 1 — Simulation and offer design** | The six roles completed 16 evaluations. The published candidate changed the draft's bureau minimum score from 720 to 680. Final validation found **186 eligible out of 10,000 (1.86%)** and **81.51% simulated acceptance** among those eligible. The publication preview listed only the bureau-score field. |
| **Network 3 — Customer personalization and guidance** | Saving income of **$9,200** produced profile **v3**. After clicking the personalization button, all five roles completed. The customer reviewed **$12,000 at 7.5% APR for 36 months**, **$373.27/month**, and **$1,437.89 borrowing cost**, then confirmed once. This candidate improved the terms within the existing rules. The owner approval recorded zero rule changes. |
| **Network 2 — Performance and investigation** | The dashboard and dated investigation both showed **1 distinct selector / 1 eligible customer = 100% provisional acceptance**, with **0 mature** enrollments in the 14-day window. Observed eligibility was unavailable because the assessed count was missing. All five specialist steps completed. The individual evidence showed **Eligible**, **PERSONALIZED**, **profile v3**, rule **DRS-2026-001**, and the same qualification ID and terms Network 3 issued. The journal held the decision record but lacked its exact assessment timestamp. |

**Two details that mattered during testing.** An earlier customer run stopped on `Unsupported parameter: underwriting.maximumDtiPct`. The local AI build now keeps customer policy fields separate from simulation parameters. The same profile completed on the corrected build. An earlier investigation that referred only to “the selected dates” stopped when no dates reached the workflow. The pasted question in Scene 3 worked with both calendar fields blank and both dates written explicitly.

**Recording starting point.** The original three-network rehearsal used a disposable copy, and the recording database was preserved. For repeat recordings, use **Demo A2A** to recreate the documented starting state with current dates and fresh IDs. After filming, stopping the services preserves records; choosing either presenter reset replaces them.
