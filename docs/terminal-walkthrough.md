# End-to-end terminal walkthrough

This guide covers the TN3270 Customer and Business screens, manual simulations, A2A offer design (Network 1), and A2A performance investigation (Network 2). Network 3 can issue an offer that the terminal can display and select, but its **start button and interactive guidance are currently GUI-only**; use [GUI customer personalization](../README.md#5-customer-a2a-personalization-network-3). The terminal does not have an equivalent Network 3 chat screen.

Use synthetic customer data. Local Customer/Business mode selection is not production authentication. For ownership, storage, and events, see [architecture](architecture.md).

## 1. Set up and connect

Run these commands in **PowerShell**, not inside the terminal's host fields:

```powershell
Set-Location C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe
```

Prerequisites are WSL 2 with `Ubuntu-22.04`, running Docker Desktop with WSL integration, and Windows PostgreSQL listening on `5432`. Compose starts Kafka and a PostgreSQL relay, not PostgreSQL itself. For live A2A, supply a model API key with access to the model configured in `AstraClient.java` (currently `gpt-6-astra`). Node.js is only needed if also building the GUI.

If Ubuntu is absent, run `wsl.exe --install -d Ubuntu-22.04` and complete its first-launch user setup. Then install the Java 17, Maven, GnuCOBOL, and terminal dependencies:

```powershell
.\scripts\setup.ps1
New-Item -ItemType Directory -Force runtime | Out-Null
```

In a PostgreSQL SQL console, create the database once if missing:

```sql
CREATE DATABASE lending_intelligence_engine;
```

Create `runtime/local.env` in a text editor as UTF-8 without BOM, with LF line endings and Bash assignments. Preserve existing working settings. Replace the login placeholders and URL-encode special characters in the credentials:

```bash
DATABASE_URL='postgresql://USER:URL_ENCODED_PASSWORD@127.0.0.1:15432/lending_intelligence_engine'
KAFKA_BOOTSTRAP_SERVERS='127.0.0.1:9092'
```

The relay maps `15432` to Windows PostgreSQL `5432`. Create/edit `runtime/ai.env` in the same format:

```bash
OPENAI_API_KEY='YOUR_API_KEY'
AI_DEFAULT_MAX_USD=10
AI_CUSTOMER_MAX_COST_USD=15
AI_FOLLOW_UP_DAYS=14
```

The AI services reuse `DATABASE_URL` unless `AI_DATABASE_URL` overrides it. Live A2A incurs provider charges within authorized workflow limits and may send approved policy excerpts and customer facts to the model provider. Existing unfinished workflows may resume on startup. Keep these private runtime files out of Git.

Install the two synthetic policy inputs without replacing existing evidence:

```powershell
New-Item -ItemType Directory -Force policy-documents | Out-Null
foreach ($name in @('a2a-personal-loan-policy.md', 'a2a-simulation-operating-policy.md')) {
    $destination = Join-Path 'policy-documents' $name
    if (-not (Test-Path -LiteralPath $destination)) {
        Copy-Item -LiteralPath (Join-Path 'app/data/policies' $name) -Destination $destination
    }
}
```

Use your configured directory instead if `AI_POLICY_DIR` is set. These documents must still be read and approved through the Business screen below.

Stop previous launchers before rebuilding. Open **three PowerShell windows**, each in the project directory.

**Window 1 — infrastructure, build, base runtime:**

```powershell
.\scripts\infrastructure.ps1
.\scripts\build.ps1
.\scripts\build-ai.ps1
.\scripts\run.ps1
```

Wait for `All three APIs are responding. Marketing offers: 127.0.0.1:8092` and the Customer/Business terminal message. Without `DATABASE_URL`, the limited H2-only mode cannot demonstrate the complete automatic flow.

**Window 2 — all A2A networks:**

```powershell
.\scripts\run-ai.ps1
```

Wait for `Simulation (six), investigation (five), and customer personalization (five) are ready.` Missing credentials are initialized without replacing existing ones. A local readiness result does not check model-account credit or provider access.

**Window 3 — check and connect:**

```powershell
.\scripts\run-ai.ps1 -Check
.\scripts\terminal.ps1
```

The terminal connects to `127.0.0.1:2323`. `terminal.ps1 -Gui` launches x3270 instead of console c3270 and requires working WSL graphical support. For a manual/performance-only session, replace the Window 2 command with `run-ai.ps1 -PerformanceOnly`, omit `-Check`, and skip agent execution. Full AI and performance-only launchers are mutually exclusive.

### Terminal keys and fields

| Key | Action |
| --- | --- |
| Tab / Shift+Tab | Move between editable fields |
| Home | Move to the first editable field |
| Ctrl+U | Clear the current editable field before replacing a default |
| Enter | Submit the entire current host form; it does not insert a new line |
| F3 | Back; from the main mode menu, follow the displayed exit option |
| F7 / F8 | Previous / next page when available |
| F4 | Context-specific action; on AI screens, read the full result/question |
| Ctrl+R | Reset/unlock the keyboard after typing into a protected field |
| Ctrl+] then `Quit`, Enter | Leave the console emulator |

Type menu numbers into **Selection/Select**, then press Enter. A field's printed label is protected; Tab into its editable area. On list screens choose the displayed row number, not the UUID. Read the bottom status/error line after every submission. Disconnecting a terminal does not stop the services or a submitted background workflow.

## 2. Create a customer

At the mode screen, enter **01 Customer**. Choose **01 Enter my information**. Fill the first form with:

| Field | Value |
| --- | --- |
| Display name | `Alex Terminal Demo` |
| External ref | `TERM-DEMO-001` (use a fresh reference if already used) |
| Gross monthly income | `8200` |
| Monthly debt | `650` |
| Credit score | `745` |
| Delinquencies 12mo | `0` |
| Credit utilization % | `25` |
| Personal loan amount | `12000` |
| Requested card limit | `4500` |
| Auto loan amount | `18000` |
| Vehicle value | `26000` |

Press Enter for the second form:

| Field | Value |
| --- | --- |
| Vehicle age | `4` |
| Banking tenure months | `48` |
| Deposit balance | `8500` |
| Monthly spend | `1700` |
| Products held | `CHECKING` |
| Marketing opt-in Y/N | `N` initially |
| Prescreen opt-out Y/N | `N` |
| Preferred channels | `EMAIL` |

Press Enter to review, then Enter to save. With unchanged seeded rules the financial inputs pass initial underwriting, but marketing consent is deliberately off, so no eligible offers are expected yet. This lets you prepare a repeatable independent bureau report before the full pipeline starts.

Return to the Customer menu with F3. Use **02 Open a demo customer profile**, select Alex's row, and record its generated **Customer ID (UUID)**. The external reference is not the UUID. **03 View / update my profile** displays current saved data; F5 shows history.

## 3. Prepare bureau facts and enable consent

Self-reported credit facts and independent bureau facts are separate. A high score typed into the customer form does not force a bureau approval.

1. Press F3 until the mode screen; choose **02 Business**.
2. Choose **07 Active customer / campaign operations**, then **09 Bureau qualification / credit decision**.
3. Choose **4 Generate / edit an independent bureau profile**.
4. Enter Alex's generated UUID and press Enter. The screen loads or generates an independent profile and displays its version.
5. Replace these fields; retain a current, non-future **Reported UTC** timestamp in ISO form, such as the current value just generated.

| Field | Value |
| --- | --- |
| File status | `MATCHED` |
| Bureau score | `775` |
| Bureau debt USD/mo | `700` |
| Utilization % | `18` |
| Delinquencies 12m | `0` |
| Inquiries 6m | `2` |
| Oldest account mo | `156` |
| Bankruptcy Y/N | `N` |

Press Enter to save. The owner API records a new bureau version with `LOCAL_EDIT` provenance. This edits synthetic source facts, not qualification results.

Return to **Customer → 02 Open a demo customer profile**, select Alex, then press **F4** to edit the saved profile. Existing values are prefilled. Change income `8200` to `9000`; Enter to the second page; change Marketing opt-in to `Y`; Enter to review; Enter to save. Qualification runs automatically against the new customer version. **05 Application progress** (or F2 from the customer profile) shows progress and polls while work is pending. Wait for completion; delivery/storage stages are asynchronous.

To demonstrate login/update later, reopen Alex through Customer menu 02 and verify income `9000`, consent `Y`, and the incremented profile version. F5 shows the preceding version. Reopen current data if a concurrent edit causes a version conflict.

## 4. Review, select, and inspect delivery

1. In Customer, choose **04 View my qualified offers**, or press F6 from the selected profile.
2. Select an available offer row, for example the seeded `DEMO-PL-1` personal loan. In **Compare Your Offers**, inspect the original and personalized amounts, APR, fee, term, payment, and cost.
3. Type `O` for original or `P` for personalized into the choice field; Enter opens the confirmation screen.
4. Inspect the exact terms, then press Enter again to confirm. F3 cancels. The owner API rechecks the current profile and offer authority before saving the selection.
5. Return to Customer → **06 My marketing files / delivery status**. Select a package row to inspect its manifest/status. Where available, `C` verifies the CSV and `D` verifies the EBCDIC artifact.

Packages are local files under `runtime/outbound/<package-id>/`; no actual email, SMS, or post is sent. Delivery gates and suppression are independent of financial eligibility. An offer choice is recorded demo acceptance, not loan funding. Missing/expired offers, changed policy, consent, capacity, or unfinished processing can prevent the positive example. Do not bypass those controls.

## 5. Business: manual simulation and optional publication

Use F3 to return to mode selection and choose **02 Business**. The simulation home contains:

| Menu | Function |
| --- | --- |
| 01 | Generate synthetic customers (up to 10,000) |
| 02 | Inspect isolated populations |
| 03 | Open offer / rule drafts |
| 04 | Create a draft from an existing catalog offer |
| 05 | Review simulation results / impact analysis |
| 06 | Publication history |
| 07 | Active customer / campaign operations |
| 08 | AI-assisted simulation and offer feedback |

### Populations and draft

1. Choose **01**. Set **Customer count 60-10000** to `10000` and **Population seed** to `20261102`; Enter generates a discovery population. Record its ID.
2. Repeat with count `10000`, seed `20261103`. Reserve this fresh population exclusively for A2A final validation. On later walkthroughs use new seeds/populations.
3. Choose **04**. Enter draft name `Terminal personal loan comparison`, then choose the displayed row for **Personal loan demo 1** (`DEMO-PL-1`). The terminal uses the associated baseline campaign.
4. In the draft menu choose **1 Edit offer terms / dates**. Set **APR %** to `11.00`, retaining other fields. Follow Enter through the pages; the final page saves a new version.
5. Choose **4 Edit Step 3 bureau criteria**. Set **Minimum credit score** to `720`; retain the remaining fields, and Enter through to save. Leave Step 1 underwriting and Step 2 marketing unchanged.
6. Choose **6 Inspect complete draft snapshot** to check the saved terms and rules.
7. Choose **5 Run baseline / candidate simulation**, select the discovery population by its displayed row, and set **Model / partition seed** to `20261104`. Submit.
8. While running, blank Enter refreshes. F3 can leave safely; return through menu 05 to the saved run.

A completed run offers six reports/actions: overall statistics/confidence/reasons; held-out test; cohort impact; assumptions/limitations; per-customer rows; review/publication. Inspect reports **1–5**. This is isolated synthetic data, not active customers. Simulated acceptance is utility-derived, not measured conversion. A manual run's held-out subset does not replace the fresh final-validation population used by A2A.

### Optional manual publication

Choose result option **6 Review and publish this tested offer + rules**. Enter a review note of at least 10 characters, for example `Reviewed exact terms, rules and limitations for this demo.` Type `P` in **Type P to publish**, then Enter. Record the new catalog offer/campaign IDs. This creates a new offer/campaign from the tested snapshot; it does not overwrite the original offer. Manual publication has no AI prediction record.

You can leave the manual result unpublished and use the same draft for A2A.

## 6. A2A simulation and offer design: Network 1

From Business home choose **08 AI-assisted simulation and offer feedback**. The “Six Java agents” header describes the simulation network; the full launcher also runs five investigation and five customer-personalization agents.

### Read and approve local policy evidence

1. Choose **3 Review local policy documents**. Use `R` to refresh the index if the new files are missing.
2. Select the row for `a2a-personal-loan-policy.md`. Choose **1** to read extracted text, paging with F7/F8. F3 returns to the document menu.
3. Choose **2** to approve. Set **Product or ALL** to `PERSONAL_LOAN`, keep a future **Approval expires YYYY-MM-DD**, type `A` in **Type A to approve**, and Enter.
4. Repeat for `a2a-simulation-operating-policy.md`.

Approval binds the document hash, product, and expiry. Changed bytes or expired approval require another review. These demo inputs authorize a bounded synthetic bureau-score search; they are not production lending policy.

### Start a bounded run

Return to the draft through Business **03**, then choose **7 AI explore this draft**. Alternatively AI home **1 Explore an existing draft** lets you select it. Choose the discovery population `20261102`, followed by the distinct, unused final-validation population `20261103`.

The intent editor has numbered 72-character lines. Tab between lines. F7/F8 save and change pages; **Enter submits the entire request**, not a new line. F4 reads a full question without losing the draft. Enter these four lines in separate fields:

```text
Change only /rules/bureau/minimumScore from 680 to 740, step 1.
Use approved demo policy. Keep all other fields unchanged.
Rank acceptance and eligibility separately; minimum support is 5.
Both cross-metric floors are 0%. Do not publish automatically.
```

Press Enter to open the budget form. Replace defaults as follows:

| Budget field | Enter |
| --- | --- |
| Simulation attempt limit | `20` |
| Execution minutes | `30` |
| Model call limit | `60` |
| Token reservation limit | `500000` |
| Maximum reserved model cost USD | `10` |
| Minimum eligible support | `5` |
| Lock all underwriting? Y/N | `Y` |
| Acceptance winner: eligibility floor % | `0` |
| Eligibility winner: acceptance floor % | `0` |

Enter starts paid model planning and execution within the submitted budget. The terminal allocates these 20 evaluations as 1 baseline, 12 exploration, 5 refinement, and 2 validation. Its A2A analysis seed is set by the terminal request; it does not ask for the manual run's partition seed. Support 5 is a demo setting, not strong statistical evidence.

The workflow screen exposes:

| Option | Use |
| --- | --- |
| 1 | Inspect exact scope, ranges, and evidence IDs |
| 2 | Inspect analysis, limitations, and workflow |
| 3 | Execution plan / scope authorization |
| 4 | Read question / send reply, or revise a stopped request |
| 5 | All simulation results and trial status |
| 6 | Preview a tested winner for publication |
| 7 | Cancel further work |
| 8 | Extend a paused budget / deadline |
| 9 / F4 | Read the full AI result / question |

Blank Enter refreshes. Answer a pending question through option 4 using the multiline editor. The initial submission normally authorizes execution; if status is explicitly `AWAITING_SCOPE`, read option 1, then option 3 and type `A` to approve. Extend a paused budget only after inspecting usage. A blocked run needs its stated issue resolved; a request may legitimately finish without a supported winner.

### Review and publish a validated winner

For a completed validated run, choose option **6**. Choose `A` for the acceptance winner or `E` for the eligibility winner and enter a meaningful review note. Enter prepares a time-limited preview.

Use preview option **1** to inspect its complete exact offer/rules and changes, with F7/F8 for pages. F3 returns to the preview. Type `P` into the publication confirmation field and press Enter. A changed or expired preview must be recreated. No publication happens merely because a workflow completes.

Record the catalog offer ID and the separate AI publication/workflow IDs from the receipt. The original estimate remains frozen for later comparisons.

## 7. Recorded performance and A2A investigation: Network 2

### Produce recorded demo evidence

1. Return to Customer, open Alex's profile, F4 edit, and change income `9000` to `9200`. Review and save to requalify against the newly published catalog.
2. Wait for current qualification and select the row bearing the **new catalog offer ID from the AI publication**, not merely another personal loan.
3. Review original or personalized terms and explicitly confirm the choice as in section 4. If the exact offer is absent, inspect its qualification outcome rather than attributing another offer's selection to it.
4. Return to **Business → 08 → 4 Published offer performance**. Select the AI publication row.

Choose **1 Current selections / eligible and the saved estimate** for recorded performance without running agents. Counts may be unavailable or provisional. Native terminal observations are `DEMO`. A single choice does not establish conversion accuracy; maturity defaults to a 14-day follow-up plus reconciliation checks. A catalog-terms utility prediction and offer-family observations are diagnostic comparisons unless the backend declares them comparable.

### Ask a compound question

Choose **2 Ask why: research, eight-stage diagnostics and reflection**. This starts the separate investigation workflow, despite the shared terminal entry point. In its multiline request editor enter a question like this, replacing dates with your actual demo period and the UUID with Alex's:

```text
For 2026-11-02 through 2026-11-03 inclusive, use first assessment
in America/Los_Angeles time and recorded DEMO outcomes.
What is overall acceptance for this published offer family?
Use combined selected and eligible counts, not daily averages.
Was customer <paste the actual UUID> eligible for the exact
personalized personal-loan variant? Identify the variant and
supporting evidence, and tell me which history is not recorded.
```

Keep each line within the editor width. The selected publication supplies the offer context; add the exact variant ID if known. Enter the request, review the displayed model-call/token/cost/time budget, and submit only the limits you authorize. Some budget labels are shared with the simulation screen; Network 2 does not run simulation trials.

Read full results through F4/option 9 and answer any clarification through option 4. The default date basis is first assessment and the default timezone is `America/Los_Angeles`. The GUI additionally provides structured date, variant, and source controls. Missing historical evidence means **not recorded**, not ineligible. Native demo evidence can lack an assessed denominator or the exact historical variant trace, even when offer selections exist.

From the publication's feedback menu, **4 Inspect saved investigations and successor experiments** reopens prior work. **3 Explore edits using a fresh baseline and validation population** starts a separate Network 1 successor experiment; it does not let investigation agents change the catalog.

## 8. Stop and resolve common problems

Leave c3270 with Ctrl+], `Quit`, Enter. Stop AI, then the base services with Ctrl+C in their own PowerShell windows. Stop infrastructure without deleting stored data:

```powershell
.\scripts\infrastructure.ps1 -Stop
```

| Symptom | Action |
| --- | --- |
| Protected-field / locked keyboard | Ctrl+R, then Tab into the editable field |
| Connection refused | Keep `run.ps1` running and wait for its API/terminal readiness message |
| Missing automatic stages | Set `DATABASE_URL`, start PostgreSQL and infrastructure, then restart the base services |
| Unexpected bureau decline | Inspect independent bureau facts and the decision reasons; self-reported facts are not bureau authority |
| AI unavailable | Start full `run-ai.ps1`, run `-Check`, and inspect logs under `runtime/ai/`; readiness does not validate provider credit |
| Policy unavailable/expired | Refresh the index and inspect/approve the exact file version for the product |
| Stale draft/profile/preview | Reopen current state; edited drafts require new tests and publication previews |
| No recorded denominator | Preserve unavailable/maturity labels; do not replace missing counts with invented data |

The default base build runs unit tests and skips opt-in database integrations. `scripts/test-bureau.ps1` uses the configured PostgreSQL/Kafka runtime; run it deliberately. These instructions were checked against source, not a guarantee of a specific live-model result. No reset is needed for this walkthrough. The relocated maintenance SQL is [reset-customers-keep-catalog.h2.sql](../scripts/reset-customers-keep-catalog.h2.sql); it is an H2 customer-state operation, not a complete PostgreSQL/Kafka reset, and should not be run as part of this demo.
