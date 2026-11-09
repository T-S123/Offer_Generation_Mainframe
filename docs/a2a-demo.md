# A2A demo: exact click-and-type walkthrough

Follow this order: **Network 1 → Network 3 → Network 2**. Use the GUI throughout.

Keep this file and a blank note open beside the browser. Replace a text or number
field by clicking **inside that field**, pressing **Ctrl+A**, and typing the new
value. Do not include commas, dollar signs, quotation marks or `%` in number boxes.
For date fields, use the calendar icon and select the date described below; the
browser may display dates as `MM/DD/YYYY` even though your note uses `YYYY-MM-DD`.

Use the button names in **bold** to identify controls. Scroll inside an open dialog
to reach its bottom buttons. **×** at the upper-right corner means **Close dialog**.

## 1. Start your normal local application

Use the normal project folder below, not a folder inside `runtime`. The isolated
rehearsal instance has been removed. These steps use your existing local database
and provider configuration.

1. Start **Docker Desktop** and wait until its engine is running. Keep your Windows PostgreSQL service running.
2. Stop any older application launcher windows with **Ctrl+C**. Do not run two base or AI launchers at the same time.
3. Open **PowerShell window 1**. Enter the following commands **one line at a time**, waiting for each to finish before entering the next:

~~~powershell
Set-Location C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe
.\scripts\build.ps1
.\scripts\build-ai.ps1
.\scripts\build-gui.ps1
~~~

4. Wait for each build to finish successfully before entering the next command. If a build reports an error, stop here; do not launch an older artifact.
5. In the same **window 1**, run:

~~~powershell
.\scripts\infrastructure.ps1
.\scripts\run.ps1
~~~

6. Wait for **All three APIs are responding**. Leave this window open.
7. Open **PowerShell window 2**, run the following, and leave it open:

~~~powershell
Set-Location C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe
.\scripts\run-ai.ps1
~~~

8. Wait for **Simulation (six), investigation (five), and customer personalization (five) are ready.**
9. Open **PowerShell window 3** and run the check:

~~~powershell
Set-Location C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe
.\scripts\run-ai.ps1 -Check
~~~

10. If the check succeeds, run the next command in **window 3** and leave it open:

~~~powershell
.\scripts\run-gui.ps1
~~~

11. Open **http://127.0.0.1:8080/** in your browser. Press **Ctrl+Shift+R** once to load the rebuilt GUI.
12. Keep all three launcher windows open through preparation and all three networks.

If startup reports missing database settings or a provider key, use
[Prepare the local runtime](../README.md#1-prepare-the-local-runtime), then return
here. Keep the existing working `runtime/local.env` and `runtime/ai.env`; do not
replace them with example credentials. Agent readiness checks do not test provider
credit or model access.

## 2. Prepare the starting customer, draft and populations

**Prepare A2A demo replaces the current local demo history. Run it once at the
start of a fresh recording. Do not run it between networks or while resuming one.**

1. Click the floating circular **Presenter controls** button with the presentation-screen icon. It starts at the bottom right; if you previously dragged it, use its saved position.
2. In the dialog, click **Demo A2A**. Do not choose **Clear all data**.
3. In the confirmation dialog, click **Prepare A2A demo** at the bottom right.
4. Wait through **Preparing demo**. Leave the browser and all launcher windows open. Do not click the preparation action again while it is running.
5. Wait for the landing page and the **A2A demo ready** dialog.
6. Under **Customer created**, copy **Customer ID** into your note as `CUSTOMER_ID`. This is the UUID, not the reference `A2A-FILM-001`.
7. Check the ready dialog against this table:

| Location in ready dialog | Required value |
| --- | --- |
| Customer created → Name | A2A Recording Customer |
| Customer created → Reference | A2A-FILM-001 |
| Customer created → Saved profile | Version 2 |
| Offer draft created · Network 1 → Draft | A2A filming personal loan · v2 |
| Discovery cohort seed | 20261105 |
| Validation cohort seed | 20261104 |
| Starting catalog offer | ID DEMO-PL-1 |

8. Click **Done**, below the prepared details.

Do not create another customer or draft. Do not generate additional populations.
The numeric seeds above stay fixed even when you record on a different day;
customer, draft and population IDs are generated again by preparation.

## 3. NETWORK 1 — Simulation and offer design

### 3A. Check the prepared policies

1. On the landing page, click **Enter Business workspace** in the dark **Business workspace** card on the right.
2. In the left sidebar, click **Policy evidence**, the last navigation item.
3. Find the two prepared document rows whose names end in:
   - `a2a-personal-loan-policy.md`
   - `a2a-simulation-operating-policy.md`
4. For the prepared copies, usually shown with the `presenter/` prefix, check **Extraction = READY** and **Approval = Approved**. Other unrelated documents can have different statuses.
5. If both prepared copies are approved, continue to **3B**. Do not approve unchanged copies again.
6. If a prepared copy says **Review needed**, click **Refresh documents** at the top right. If it still needs review, follow **Policy approval recovery** at the end of this file before continuing.

### 3B. Open and fill the experiment form

1. Click **A2A exploration**, the third item in the left sidebar.
2. Click **Start A2A exploration** at the upper right of **A2A simulations**.
3. In the **Start an A2A experiment** dialog, fill these fields from top to bottom:

| Field or group | Exact action |
| --- | --- |
| Saved offer draft | Open the dropdown; select **A2A filming personal loan · v2**. |
| Discovery population | Select the option containing **10000 customers · seed 20261105**. Ignore its trailing generated ID. |
| Final-validation population | Select the option containing **10000 customers · seed 20261104**. Do not reverse these populations. |
| Analysis seed | Replace the value with **20261106**. |
| Keep these stages unchanged | Check **Offer**, **Underwriting**, and **Marketing**. Leave **Bureau** unchecked. |

4. In the large **Objective and constraints** box below the stage checkboxes, paste this entire block:

~~~text
Explore only /rules/bureau/minimumScore from 680 through 740,
step 1, using the approved personal-loan policy.

Keep this saved draft's offer terms, underwriting, marketing,
and all other bureau fields fixed.

Report separate best-tested acceptance and eligibility winners,
each with at least 5 eligible customers. Both cross-metric floors
are 0%. Use the separate untouched final-validation population.

Explain synthetic-utility and small-sample limitations.
Do not publish automatically.
~~~

### 3C. Set and verify the budget

1. Scroll down **inside the dialog** to the expandable **Resource budget · up to … · … evaluations** row.
2. Click that row to expand it.
3. In **Total simulation limit**, replace the value with **20**.
4. Wait until **Balancing phase allocations…** disappears before editing other budget fields.
5. Fill or verify every field below by its label; their order on screen can differ from this table:

| Field | Value |
| --- | ---: |
| Total simulation limit | 20 |
| Exploration trials | 12 |
| Refinement trials | 5 |
| Validation trials | 2 |
| Minimum eligible customers | 5 |
| Model call limit | 60 |
| Token limit | 500000 |
| Maximum cost ($) | 10 |
| Eligibility floor (%) | 0 |
| Acceptance floor (%) | 0 |

6. Leave **Deadline (UTC ISO timestamp)** at the generated future value. If you left the form open until that time passed, click **×**, reopen **Start A2A exploration**, and fill **3B–3C** again.
7. Check that the allocation line reads **Allocation: 1 baseline + 12 exploration + 5 refinement + 2 validation = 20 of 20 evaluations.** If it differs, correct the three phase boxes to **12 / 5 / 2**. They sum to **19**; the baseline supplies the twentieth evaluation.
8. Below the budget, check **I approve model execution within this budget. Catalog publication requires a separate review.**
9. Click **Start A2A workflow** at the bottom right of the dialog **once**.
10. Wait for the dialog to close and **Simulation details** to appear.

### 3D. Wait and handle any requested input

1. Look at the status badge below **Simulation details**. Let the run continue until it says **Completed**. Do not expect it to use every allowed trial.
2. Keep this screen open. It refreshes automatically; **Refresh** on the right of the status row checks it manually.
3. To inspect completed agent work, click **Evidence** on a row in **Agent activity**, to the right of **Resource usage**. Close **Saved agent evidence** with **×** to return.
4. If **Your input is needed** appears above **Resource usage**, read its question. Click the **Your answer** box, type the relevant answer below, then click **Send answer and continue**. Clicking a suggested answer only fills the box; you must still submit it.

| If the question asks about… | Type in **Your answer** |
| --- | --- |
| Permitted changes or policy range | `Change only /rules/bureau/minimumScore from 680 through 740 inclusive, step 1. Keep offer terms, underwriting, marketing, and all other bureau fields fixed. Use the approved PERSONAL_LOAN policy.` |
| Which objective, denominator or floors | `Report separate best-tested acceptance and eligibility winners. Use at least 5 eligible customers for each, eligibility floor 0%, acceptance floor 0%, and the untouched final-validation population. Treat acceptance as simulated utility.` |
| Permission to publish | `Do not publish automatically. Complete the experiment and leave publication for my separate preview and confirmation.` |

5. If **Review the proposed scope** appears, check that it contains only the permitted bureau-score change above, then click **Approve this scope and continue** beneath it.
6. If the run pauses for another material question, answer that specific question; do not paste an unrelated table answer. If it asks for more spending, use **Budget pause recovery** below.
7. If you navigate away, click **A2A exploration**, then the saved row beginning **Explore only /rules/bureau/minimumScore…** under **Saved simulations**. Do not start a second run.
8. Continue only when the status says **Completed**. **Blocked**, **Failed**, and **No supported candidate** are not successful results; use the displayed error and the recovery table below.

### 3E. Select the eligibility winner and publish it

1. On the completed **Simulation details** page, scroll to **Findings and reflection** and expand **Analysis and supporting evidence** to read the result.
2. For the exact IDs, scroll to the bottom of the page and expand **Inspect complete workflow state**.
3. In that JSON, find the top-level **`"winners"`** object, then its **`"eligibility"`** object. Copy **`candidateId`** and **`runId`** from that object into your note as `WINNER_CANDIDATE_ID` and `WINNER_RUN_ID`. Use **winners**, not **discoveryWinners**, and **eligibility**, not the first object in the file. Copy the values without quotation marks.
4. Click **Inspect complete workflow state** again to collapse it. Scroll upward to **Review a validated candidate**, immediately below **Saved trials**.
5. Open **Final-validation candidate**. Select the option whose first segment matches the first **16 characters** of `WINNER_CANDIDATE_ID` and whose **run** segment matches the first **12 characters** of `WINNER_RUN_ID`.
6. In **Review note**, paste:

~~~text
Reviewed the final-validation result, exact bureau-score change,
and simulated-utility limitations for this local A2A demo.
~~~

7. Click **Prepare publication preview**, below the note.
8. In **Confirm exact catalog publication**, scroll to **Changed fields**. Verify it lists only **`/rules/bureau/minimumScore`**.
9. Under **Exact offer and rules**, verify **rules → bureau → minimumScore** is within **680–740**. The offer name should be **A2A filming personal loan**, its **illustrativeAprPct** should remain **9.5**, and **rules → underwriting → minimumIncomeUsd** should remain **3000**. Do not approve unexpected additional changes.
10. Scroll to the bottom of the dialog and check **I reviewed this configuration and approve publication to the catalog.**
11. Click **Confirm publication** at the bottom right. Wait for the dialog to close.
12. Scroll toward the top of **Simulation details** to **Published to catalog**. Copy the full ID beginning **BUS-** into your note as `NEW_OFFER_ID`. This is the new publication, not the prepared **DEMO-PL-1** catalog offer.
13. Click **Inspect publication receipt** beneath **View offer performance** to inspect the saved receipt, then collapse it again.
14. Record today's **Los Angeles date** in your note as `START_DATE`, including the year. Use the date of this publication, even if you continue Network 3 tomorrow. To obtain it exactly, open a **fourth PowerShell window** and run:

~~~powershell
[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow, 'Pacific Standard Time').ToString('yyyy-MM-dd')
~~~

Keep this fourth window available for date commands; do not type commands into the three running launcher windows.

## 4. NETWORK 3 — Customer personalization and guidance

### 4A. Save the prepared customer's updated income

1. Click **Sign out** at the bottom of the left sidebar.
2. On the landing page, click **Already a customer? Log in**, below **Create a customer profile** in the left **Customer portal** card.
3. Under **Saved customers**, click **A2A Recording Customer**. Alternatively, paste the UUID saved as `CUSTOMER_ID` into **Customer ID**, then click **Open my profile**. Do not put `A2A-FILM-001` in the UUID box.
4. Check that the customer overview shows **Saved profile v2** before making the one update in this scene.
5. Click **My information**, the second item in the left sidebar.
6. On **Edit profile**, scroll to **02 Financial profile**. In **Monthly income ($)**, replace **9000** with **9200**. Leave every other field unchanged.
7. Scroll to the bottom of the page and click **Review information** at the lower right.
8. In **Review profile changes**, check **Monthly income ($)** is **9200**. Scroll inside the dialog to the bottom and click **Save updated profile** once.
9. Wait for the dialog to close. Check the overview shows **Saved profile v3** and **$9,200.00** income. Do not save the same edit again; another save creates another version.

### 4B. Run the customer network

1. Click **My offers**, the third item in the left sidebar.
2. If the page says **Checking your offers…**, wait a few seconds, then click **Refresh** at the upper right. Repeat only while qualification is still processing.
3. In the personal-loan card below the top panel, check **Qualified** and confirm its **BUS-…** catalog ID matches `NEW_OFFER_ID`. Do not proceed with **DEMO-PL-1** in place of the new publication.
4. Near the top of the page, find the **Personalized offer** panel with **Saved profile v3** and status **Not started**.
5. In that panel, click **Find my personalized offer** once. Do not click **Review original** or **Review personalized** in the comparison table below it.
6. Wait while the top panel says **Personalization pending**. The panel polls automatically; do not cancel it merely because a result is not immediate.
7. If an input question appears inside this panel, use its **Your answer** box and click **Send answer**. If it asks whether other profile facts changed and none did, type: `No other profile facts changed. Use my saved profile v3 and the existing preferences, within the approved personalization policy.` Do not invent changes to qualify for an offer.
8. Continue only after the top panel says **Completed** and shows three numbered steps. If it says **No qualified improvement**, **Stale profile**, **Offer changed**, or **Failed**, read its reason and use the recovery table; do not continue as though a new variant was issued.

### 4C. Review the exact offer and confirm selection

1. In the **completed top panel**, click **Open personalized offer**, below the three numbered steps.
2. Read the displayed **Amount**, **APR**, **Monthly payment**, **Borrowing cost**, **Term**, **Annual fee**, and **Valid until**. Use the returned values; do not try to enter the numbers from a previous recording.
3. Click **Review current terms** immediately below those details.
4. In **Confirm your offer choice**, check the badge says **Personalized**. Compare **Amount usd**, **Apr pct**, **Annual fee ($)**, and **Term (months; cards use 0)** with the offer you just opened. This dialog contains terms, not customer or catalog identifiers.
5. Click **Confirm selection** at the bottom right once. Wait while it says **Confirming…**.
6. After the dialog closes, look below the **My offers** page heading for **Your reviewed offer choice has been saved.** Wait for that confirmation before continuing.
7. In your fourth PowerShell window, run the date command from **3E** again. Copy the result into your note as `END_DATE`.

If both scenes occurred on the same Los Angeles calendar day, `START_DATE` and
`END_DATE` will be identical. If you paused overnight, keep the earlier publication
date as `START_DATE` and the selection date as `END_DATE`. Do not substitute the
fixed population seeds for these dates.

## 5. NETWORK 2 — Performance and investigation

### 5A. Open the recorded performance card

1. Click **Sign out** at the bottom of the left sidebar.
2. Click **Enter Business workspace** in the dark right-hand landing-page card.
3. On **Offer performance**, find **Observation source** near the top right, below **Refresh outcomes**. Select **Local demo outcomes**.
4. Click **Refresh outcomes** at the upper right. Wait for **Loading outcomes** to clear.
5. Under **Published AI offers**, find **A2A filming personal loan**. After this fresh-demo sequence there should be one AI publication.
6. Scroll to **All publication receipts** below the cards and check **Catalog offer** contains `NEW_OFFER_ID`. Return to that publication's card above.
7. Check **Recorded acceptance** includes a recorded selection in its **selected / eligible** line. If selection confirmation just occurred and the counts have not refreshed, click **Refresh outcomes** again after a few seconds.
8. At the bottom right of that publication card, click **Investigate with A2A**. Do not click **New experiment** or **View tested run**.

### 5B. Enter the compound question and its measurement scope

On **Investigations**, use the **Ask an investigation question** form in the left
column. **Saved investigations** is the separate right column.

1. Leave **Published offer** at the preselected **A2A filming personal loan**. Entering from its card carries the exact publication ID into this form.
2. In **Your question**, directly below that dropdown, paste:

~~~text
For the selected A2A filming personal loan publication and the date range
entered in this form, what was offer-family acceptance among customers
first eligible during that period in America/Los_Angeles?

Use recorded demo outcomes and combined distinct counts. Compare those
counts with the original simulation prediction and explain whether a
forecast gap is meaningful.

Also, as of the evidence cutoff, was customer A2A-FILM-001 eligible for
the exact PERSONALIZED variant of this publication? Give the recorded
profile version, rule version and qualification evidence. Identify any
missing evidence.
~~~

3. Fill the remaining scope fields, scrolling downward through the same form:

| Field | Exact value or action |
| --- | --- |
| Customer ID or reference (optional) | Type **A2A-FILM-001**. This field accepts the reference. |
| Start date (inclusive) | Use the calendar control to choose the date saved as **START_DATE**. |
| End date (inclusive) | Choose **END_DATE**. Include the year; end must not be before start. |
| Date basis | Open the dropdown and select **First eligible date**. Change it from the default **First assessment date**. |
| Timezone | Type **America/Los_Angeles** exactly. |
| Observation source | Select **Recorded demo outcomes**. |
| Aggregate offer variant | Select **Offer family**. The individual PERSONALIZED question is handled separately. |
| Evidence cutoff (optional ISO instant) | Leave empty. |

4. Under **Investigation budget**, replace or verify:

| Field | Value |
| --- | ---: |
| Maximum model calls | 30 |
| Maximum tokens | 300000 |
| Maximum reserved cost ($) | 10 |

5. Check **I authorize agent execution within this budget.**, below the budget fields.
6. Click **Start investigation** directly beneath the checkbox once.
7. Wait for **Investigation details**, then its **Completed** status. This screen refreshes automatically.
8. If **Your input is needed** appears above **Resource usage**, answer only the requested missing scope in **Your answer** and click **Send answer and continue**. For dates, use the reply template in **6B**. For variant scope, type: `Use OFFER_FAMILY for aggregate acceptance, and the exact PERSONALIZED variant for customer A2A-FILM-001 as of the evidence cutoff.`

### 5C. Open the answer and underlying records

1. On the completed **Investigation details** page, scroll below **Resource usage** and **Agent activity** to **Investigation answer**.
2. Check the displayed date range equals your `START_DATE` through `END_DATE`, with **First eligible**, **America/Los_Angeles**, and the demo source.
3. Read **Recorded acceptance**, **Recorded eligibility**, **Frozen predicted acceptance**, and **Frozen predicted eligibility**. Use the displayed counts; do not substitute a simulated rate for recorded acceptance.
4. Continue downward to **Customer eligibility: A2A-FILM-001**. Check the publication is `NEW_OFFER_ID`, the variant is **Personalized**, and the returned profile version is **3** for this fresh sequence. Read the rule version and qualification ID.
5. Under **Supporting evidence**, click **Open customer decision evidence**.
6. In **Saved agent evidence**, inspect **variant**, **profileVersion**, **qualificationId**, and **terms** in the saved result. Scroll inside the dialog if needed, then click **×** at the top right.
7. Click **Open measurement evidence**, also under **Supporting evidence**. Inspect the saved date range, date basis, cohort counts, evidence cutoff and original prediction; close with **×**.

A new recording can show **provisional** observations, a missing assessed count,
and **Target mismatch**. Keep those recorded limitations. They do not mean you
entered the form incorrectly, and they do not establish a validated forecast gap.
If the exact customer decision says **Not recorded**, inspect its evidence rather
than reporting the customer as ineligible.

## 6. Optional NETWORK 2 scene — ask, pause for dates, then resume

### 6A. Start a separate investigation with the dates omitted

1. From the completed **Investigation details** screen, click **← All investigations** at the upper right. This returns to the new-investigation form. Clicking the already-selected sidebar item alone does not close a saved result.
2. In the left **Ask an investigation question** form, select **A2A filming personal loan** under **Published offer**.
3. Replace **Your question** with this exact block:

~~~text
Investigate offer-family acceptance for a date range I will provide.
Ask me for the start and end dates before calculating. Then report the
recorded counts and explain whether comparison with the original
simulation prediction is meaningful.
~~~

4. In **Customer ID or reference (optional)**, keep **A2A-FILM-001**.
5. Clear **both** **Start date (inclusive)** and **End date (inclusive)**. Click inside each date field and press **Ctrl+A**, then **Backspace**; check that no date remains. Use the calendar control's **Clear** action if your browser provides it instead. Values from the previous form may still be present.
6. Set **Date basis = First eligible date**, **Timezone = America/Los_Angeles**, **Observation source = Recorded demo outcomes**, and **Aggregate offer variant = Offer family**.
7. Leave **Evidence cutoff (optional ISO instant)** empty.
8. Set the budget to **30** model calls, **300000** tokens, and **10** maximum reserved cost.
9. Check **I authorize agent execution within this budget.**, then click **Start investigation** once.
10. Wait for **Your input is needed** and a question asking for the date range. Do not open a second investigation while waiting.

### 6B. Supply the requested dates

1. In the **Your answer** box beneath the date question, paste the following. Replace **START_DATE** and **END_DATE** with the full `YYYY-MM-DD` dates in your note **before submitting**:

~~~text
Use START_DATE as the start date and END_DATE as the end date, both
inclusive, in America/Los_Angeles. Use First eligible date, recorded
demo outcomes, and OFFER_FAMILY scope. Proceed with those dates.
~~~

2. Verify that neither placeholder remains. If both dates in your note are the same, repeat that same date in both positions.
3. Click **Send answer and continue** beneath the text box. A suggested-answer button, if shown, does not submit the reply by itself.
4. Wait until the status is **Completed**.
5. Scroll to **Investigation answer** and verify it uses the dates you supplied. If a different material question appears first, answer it in the same panel and submit it before waiting again.

## 7. Stop, resume, or prepare another recording

- **Stop while preserving this recording:** press **Ctrl+C** in window 3 (GUI), then window 2 (AI), then window 1 (base engine). In window 1, run:

~~~powershell
.\scripts\infrastructure.ps1 -Stop
~~~

- **Resume this recording:** start the three launchers as in section 1, without rebuilding unchanged code. Do not run **Prepare A2A demo**. Reopen **A2A exploration → Saved simulations** or **Investigations → Saved investigations**, or log back into the same customer.
- **Start another fresh recording:** start the services, then repeat **Presenter controls → Demo A2A → Prepare A2A demo**. Copy the new customer ID. Reuse the documented seeds only with the newly prepared populations; the previous final-validation population cannot be reused for a new experiment.

## Recovery steps

### Policy approval recovery

Use this only if a prepared policy still says **Review needed** after **Refresh documents**.

1. In the fourth PowerShell window, generate an expiry date 30 days from today's Los Angeles date:

~~~powershell
[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow, 'Pacific Standard Time').AddDays(30).ToString('yyyy-MM-dd')
~~~

2. In **Policy evidence**, click **Read and review** at the right end of the affected prepared document's row.
3. Read the extracted text. Use **Next text** below it to reach additional pages.
4. Below the text, set **Applicable product** to **PERSONAL_LOAN**.
5. In **Approval expiry**, use the calendar control to enter the generated future date.
6. Check **I have read this document and approve its use as US policy evidence for the selected product.**
7. Click **Approve this document version**. The dialog closes after successful approval.
8. Check that row now says **Approved**. Repeat only for the other prepared document if it also needs review, then return to **3B**.

If the prepared policy is missing, removed, or extraction is not READY, do not
approve a different document in its place. Inspect the preparation error first.

### Budget pause recovery for the Network 1 run

1. On the saved run's details page, scroll to the bottom and click **Review budget extension**.
2. In **Extend the workflow budget**, read the current limits. Raising a limit authorizes additional usage; skip this recovery if you do not want that extra spending.
3. Leave **Total simulation limit** at its current value (**20** for this run). The phase and ranking fields are read-only during an extension: verify **Exploration = 12**, **Refinement = 5**, **Validation = 2**, **Minimum eligible = 5**, and both floors **0**. Do not try to rebalance them here.
4. If model/token/cost limits caused the pause, set **Model call limit = 100**, **Token limit = 1000000**, and **Maximum cost ($) = 20**, provided each is at least the current limit. If a current limit already exceeds those values, retain it instead.
5. Keep the dialog's newly generated future **Deadline (UTC ISO timestamp)**.
6. Click **Approve extension and resume** at the bottom. If the error requests a different exhausted limit, read that specific request before authorizing more; do not repeatedly submit unchanged values.

### Other interruptions

| What you see | Exact next action |
| --- | --- |
| **Invalid phase allocation** before starting | Stay in **Start an A2A experiment**. Expand **Resource budget**, set total **20**, wait for balancing, then set phases **12 / 5 / 2**. Check the allocation line and approval checkbox before retrying **Start A2A workflow**. Do not reset the database. |
| Total changes but phase boxes do not | Stop GUI and AI with **Ctrl+C**, rebuild using **build-ai.ps1** and **build-gui.ps1**, restart those launchers, and press **Ctrl+Shift+R**. Reopen the experiment form. |
| **Final validation was already reserved** | If you already started this experiment, close the new form and reopen its row under **Saved simulations**. For an entirely new recording, use section 2; do not swap discovery and validation to evade the restriction. |
| Preparation fails or remains paused | Keep the GUI window open. Read `runtime/presenter/reset.log`; restart a launcher only if it exited. After resolving the displayed cause, reopen **Presenter controls → Demo A2A → Prepare A2A demo**. Do not start an experiment until **A2A demo ready** appears. |
| Missing or different customer / draft / starting profile | Check section 2's ready summary. For a new recording, prepare once before Network 1. Do not reset in the middle of a recording you want to keep. |
| New BUS- offer is absent after the profile save | In **My offers**, wait and click **Refresh**. Check the saved version is **v3**. If qualification reports a failure, read it; do not select **DEMO-PL-1** as a substitute. |
| **Stale profile**, **Offer changed**, or expired offer | Reopen **My information** and **My offers** to load current data. A new saved profile version permits a new personalization request; if you deliberately save another version, record that version and use it in your investigation checks instead of v3. |
| **No qualified improvement** or personalization **Failed** | Read the top panel's reason. Keep existing valid offers available; do not claim Network 3 issued a variant. If the failure is a provider or time-limit problem, resolve that cause before starting a new saved-profile request. |
| Provider authentication, quota, rate-limit or timeout error | Read the exact error and the relevant launcher log under `runtime/ai`, `runtime/ai/feedback`, or `runtime/ai/personalization`. Agent readiness alone cannot guarantee provider success. Do not continue to publication or acceptance without the required successful state. |
| Recorded selection still missing | Return to **My offers** for the same customer and check whether confirmation succeeded. On **Offer performance**, click **Refresh outcomes** after the source has processed it. In the investigation, use the publication-to-selection date range and **First eligible date**. |
| Page lost after navigation | Reopen the saved run from **Saved simulations** or **Saved investigations**. Do not press a new-run button to resume an existing run. |
