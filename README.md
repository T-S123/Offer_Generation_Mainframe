# Lending Studio: end-to-end GUI walkthrough

This guide follows the current local application: create and edit a customer, qualify and select offers, run manual and A2A Business experiments, publish a reviewed offer, investigate recorded performance, and request customer-specific A2A personalization. Use synthetic data. Customer login is a local profile selector; Business entry is a demo mode, not password authentication.

To record the three networks using the prepared local customer and cohorts, follow the [A2A filming script](docs/a2a-demo.md). The six core guides are listed below.

| Guide | Contents |
| --- | --- |
| [GUI walkthrough](README.md) | This guide: setup, buttons, inputs, and expected results |
| [Terminal walkthrough](docs/terminal-walkthrough.md) | Setup, TN3270 menus, fields, keys, and A2A |
| [Scalability](docs/scalability.md) | Scaling mechanisms and current bottlenecks |
| [Domain-driven design](docs/domain-driven-design.md) | Domain ownership and separation of responsibilities |
| [API-first design](docs/api-first.md) | Contracts, clients, commands, events, and verification |
| [Architecture](docs/architecture.md) | Current deployment, eight customer stages, all three A2A networks, and source map |

## Presenter controls for repeat demonstrations

A circular presentation-screen button appears at the bottom right of every page. Drag it to a convenient position; its position is remembered in this browser. You can also focus it and use the arrow keys. Click it to choose:

- **Clear all data → Clear demo data:** removes the local customer, selection, simulation, publication, agent and outcome history, clears queued application events, and restores the working default catalog and rules. The browser returns to the landing page. Click **Create a customer profile**, or enter **01 Customer → 01 Enter my information** in the terminal.
- **Demo A2A → Prepare A2A demo:** replaces the current demo state with **A2A Recording Customer** (reference `A2A-FILM-001`, profile v2, income 9000), approved bundled demo policies, the v2 personal-loan draft, and two unused 10,000-customer cohorts. It returns to the landing page and opens **A2A demo ready**, listing the new customer name, reference and UUID, saved draft name/version/ID, starting catalog offer, and both cohort seeds. Click **Done** to begin; reopening the presenter button shows these details again. This is the starting draft and default catalog offer; AI publication and customer-specific variants are created during the demo. Continue at **Scene 1** in the [A2A filming script](docs/a2a-demo.md).

Both actions ask for confirmation and affect everyone using this local installation. Keep the base, AI and GUI launcher windows open while preparation runs. The launcher windows pause and resume their own workers; the browser displays progress. No model call or catalog publication is made by preparation.

Use the standard local launchers below with a single local PostgreSQL database and Kafka broker. Stop older launchers and rebuild once to enable this feature. **Clear all data** works without AI running; **Demo A2A** requires all three networks and their configured provider key. External databases, split AI databases and custom service ports are not supported by presenter reset. Access uses the local demo gateway's loopback, origin and CSRF protections.

Configuration, credentials, policy source files and backups are retained. Source files are retained without carrying their old approval records forward; Demo A2A approves only the exact bundled fixture policies. The reset does not stop PostgreSQL or Docker. If preparation fails, the dialog shows the failure. Check `runtime/presenter/reset.log`, restart any launcher that exited, then retry the chosen action. A failure after clearing begins keeps the application paused until retry succeeds.

## 1. Prepare the local runtime

Run shell commands in **PowerShell**, from the project directory. Do not enter them in a browser field or TN3270 screen.

```powershell
Set-Location C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe
```

Prerequisites:

- WSL 2 with the distribution named `Ubuntu-22.04`.
- Docker Desktop running with its WSL 2 backend.
- A Windows PostgreSQL server listening on port `5432`, with a login allowed to create the application's tables and schemas. Docker Compose supplies Kafka and a TCP relay; it does **not** install PostgreSQL.
- Windows Node.js `22.12.0` or newer and `npm.cmd`, as required by `frontend/package.json`.
- For live A2A: an OpenAI API key with access to the model configured in `ai-service/.../AstraClient.java` (currently `gpt-6-astra`). A green agent health check does not prove provider access or sufficient credit.

If WSL is absent, run `wsl.exe --install -d Ubuntu-22.04`, complete Ubuntu's first-launch user setup, then return here. Install the Linux build and terminal dependencies once:

```powershell
.\scripts\setup.ps1
```

In PostgreSQL's SQL console, create the database once if it is absent:

```sql
CREATE DATABASE lending_intelligence_engine;
```

Create `runtime/local.env` in a text editor, preserving an existing working file. Use UTF-8 **without a BOM**, LF line endings, and Bash assignments. Substitute your own credentials; percent-encode special characters in the URL username/password.

```bash
DATABASE_URL='postgresql://USER:URL_ENCODED_PASSWORD@127.0.0.1:15432/lending_intelligence_engine'
KAFKA_BOOTSTRAP_SERVERS='127.0.0.1:9092'
```

Port `15432` is the Compose relay to Windows PostgreSQL `5432`. Create the directory first if necessary:

```powershell
New-Item -ItemType Directory -Force runtime | Out-Null
```

For full A2A, create or edit `runtime/ai.env` in the same encoding. The AI services reuse `DATABASE_URL`; set `AI_DATABASE_URL` only if intentionally using another database.

```bash
OPENAI_API_KEY='YOUR_API_KEY'
AI_DEFAULT_MAX_USD=10
AI_CUSTOMER_MAX_COST_USD=15
AI_FOLLOW_UP_DAYS=14
```

These are configured spending ceilings, not promised actual charges. Business workflows also show their own budgets before starting. Customer personalization uses the displayed per-profile ceiling. Saved unfinished workflows may resume when agents start. Runtime files hold credentials and data; keep them private and out of Git.

Install the included synthetic research inputs **without replacing existing local policy files**:

```powershell
New-Item -ItemType Directory -Force policy-documents | Out-Null
foreach ($name in @('a2a-personal-loan-policy.md', 'a2a-simulation-operating-policy.md')) {
    $destination = Join-Path 'policy-documents' $name
    if (-not (Test-Path -LiteralPath $destination)) {
        Copy-Item -LiteralPath (Join-Path 'app/data/policies' $name) -Destination $destination
    }
}
```

If you set `AI_POLICY_DIR`, use that directory instead. These documents are demo inputs, not real lending policy. Their approval is a separate Business action below. Other supported inputs include PDF, DOC/DOCX, TXT, Markdown, HTML, RTF, and ODT. Scanned documents need OCR configuration and review; unreadable text is not usable evidence. Approved excerpts and customer facts used by A2A can be sent to the configured model provider.

## 2. Build and start: three PowerShell windows

Stop any previous engine, AI, and GUI launchers with **Ctrl+C in their own windows** before rebuilding. Do not run two base launchers against the same H2 files. Each window below starts in the project directory.

**Window 1 — build, then base services:**

```powershell
.\scripts\infrastructure.ps1
.\scripts\build.ps1
.\scripts\build-ai.ps1
.\scripts\build-gui.ps1
.\scripts\run.ps1
```

Build scripts stop on failures. `build.ps1` compiles COBOL and packages the base/marketing services; `build-ai.ps1` packages and tests AI; `build-gui.ps1` installs locked Windows dependencies, checks contracts/tests, builds browser assets, and packages the gateway. Wait for:

```text
All three APIs are responding. Marketing offers: 127.0.0.1:8092
Terminal: 01 Customer for an application; 02 Business for simulations.
```

**Window 2 — all three A2A networks:**

```powershell
.\scripts\run-ai.ps1
```

The launcher initializes missing credentials while retaining existing ones. Wait for:

```text
Simulation (six), investigation (five), and customer personalization (five) are ready.
```

**Window 3 — readiness check and GUI:**

```powershell
.\scripts\run-ai.ps1 -Check
.\scripts\run-gui.ps1
```

Open **http://127.0.0.1:8080**. All three launcher windows must remain open. Use `run-gui.ps1 -Port 8085` and the printed URL if `8080` is occupied. After later source changes, rebuild the affected components while stopped; otherwise a normal restart needs only infrastructure and the three run commands.

For a dashboard/manual-only session, replace the Window 2 command with `run-ai.ps1 -PerformanceOnly`. It serves recorded performance without an API key or agent workers. Do not run it alongside the full AI launcher. Omit the all-agent `-Check` in this mode; interactive investigations and customer personalization need the full network.

Navigation is on the left on wide screens and across the top on narrow screens; horizontally scroll the narrow navigation to reveal later tabs. Forms and dialogs may require scrolling to reach their action buttons.

## 3. Create a customer and open the saved profile

On the landing page, use the **Customer portal** card on the left (above Business on a narrow phone). Click **Create a customer profile**. Enter this synthetic example:

| Section | Field | Enter/select |
| --- | --- | --- |
| About you | Full name | `Alex GUI Demo` |
| About you | Customer reference (optional) | `GUI-DEMO-001` (use a fresh reference if taken) |
| Financial profile | Monthly income ($) | `8200` |
| Financial profile | Monthly debt payments ($) | `650` |
| Financial profile | Self-reported credit score | `745` |
| Financial profile | Delinquencies in the last 12 months | `0` |
| Financial profile | Credit utilization (%) | `25` |
| What you are looking for | Personal loan amount ($) | `12000` |
| What you are looking for | Requested card limit ($) | `4500` |
| What you are looking for | Auto loan amount ($) | `18000` |
| What you are looking for | Vehicle value ($) | `26000` |
| What you are looking for | Vehicle age (years) | `4` |
| Your banking relationship | Banking tenure (months) | `48` |
| Your banking relationship | Deposit balance ($) | `8500` |
| Your banking relationship | Monthly spending ($) | `1700` |
| Your preferences | Receive marketing offers | **No initially**, for the controlled bureau preparation below |
| Your preferences | Opt out of prescreening | **No** |
| Your preferences | Products you already hold | Check **Checking** only |
| Your preferences | Preferred contact channels | Check **Email** only |

At the bottom, click **Review information**. Inspect the dialog, then **Create customer**. The GUI logs into the new profile automatically. On **Overview**, find **Your information** and copy the generated **Customer ID**. It is a UUID, not the `GUI-DEMO-001` reference.

Under the unchanged seeded policies, these financial inputs pass initial underwriting. Marketing consent is deliberately off, so `NO_ELIGIBLE_OFFERS` is expected at this point. Unknown numeric values are allowed but remain unknown; entering `0` means zero.

### Prepare a repeatable independent bureau report

The browser does not have a bureau-profile editor. For this controlled positive example, use the existing import command in a **fourth PowerShell window**, while the services keep running. This imports synthetic facts through the owner API; it does not change rules or recorded decisions.

```powershell
Set-Location C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe
$customerId = Read-Host 'Paste the generated Customer ID from Overview'
$report = @{
    customerId = $customerId
    expectedVersion = 0
    facts = @{
        fileStatus = 'MATCHED'
        creditScore = 775
        monthlyDebtUsd = 700
        utilizationPct = 18
        delinquencies12m = 0
        inquiries6m = 2
        oldestAccountMonths = 156
        bankruptcy = $false
        reportedAt = [DateTime]::UtcNow.ToString('o')
    }
}
$manifest = Join-Path (Get-Location) 'runtime/gui-demo-bureau.ndjson'
[IO.File]::WriteAllText($manifest, (($report | ConvertTo-Json -Depth 4 -Compress) + "`n"), [Text.UTF8Encoding]::new($false))
.\scripts\run.ps1 -BureauProfiles $manifest
```

Expected: a successful import with bureau version `1`. `expectedVersion = 0` is for the fresh customer with no previous bureau report. A conflict protects existing evidence; do not keep importing or guess a version. For an existing report, use the [terminal bureau editor](docs/terminal-walkthrough.md#3-prepare-bureau-facts-and-enable-consent), which loads its current version.

For a less controlled demo, you may skip this import and enable consent immediately. The automatically generated independent bureau profile can approve, decline, or require review even when the self-reported score is high.

### Edit and log back in

1. In the GUI, select **My information**. The saved name, amounts, preferences, and reference should already be filled in.
2. Change **Monthly income ($)** from `8200` to `9000` and **Receive marketing offers** to **Yes**. Leave the other values unchanged.
3. Click **Review information**, inspect the changes, then **Save updated profile**. A new profile version is saved and qualification restarts.
4. Select **Overview** to see the new version and processing status. Wait for offers; use **Refresh** if needed. Copy/offer delivery is asynchronous.
5. Click **Sign out**. On the landing page click **Already a customer? Log in**. Select **Alex GUI Demo** from **Saved customers**, or enter its UUID in **Customer ID** and click **Open my profile**.
6. Reopen **My information** and verify income is `9000` and consent is **Yes**. If another edit caused a version conflict, reopen the saved profile before trying again.

## 4. Review and select a qualified offer

Select **My offers** in the customer navigation. Each available offer card identifies its catalog offer and compares **Original** and **Personalized** terms: amount/limit, APR, fee, term, estimated payment, and borrowing cost.

1. Pick an available personal-loan card, such as `DEMO-PL-1` under the default catalog.
2. Click **Review personalized** at the bottom of the card, or **Review original** if you prefer the original terms.
3. Inspect **Confirm your offer choice**. Click **Confirm selection** only for the exact displayed terms.
4. Look for the saved-selection confirmation. This records a demo choice, not a funded loan or a real outbound message.

An expired or changed offer requires fresh review. No current offers can also mean incomplete inputs, changed policy, missing consent, suppression/capacity limits, or an unfinished pipeline. The GUI does not override these conditions. The terminal additionally exposes customer-correlated outbound file status; the GUI currently does not have that delivery-file screen.

## 5. Customer A2A personalization (Network 3)

A small **Your offer assistant** launcher sits at the bottom right. After a returning customer saves a profile update and qualification finishes, it opens automatically. Initial signup and later logins leave manual access available: open the launcher, describe your goal, and click **Send**.

The five agents compare current eligible offers and saved profile history, ask useful model-written questions, and review qualified candidates with the customer. A reply resumes the same A2A task; the answer box closes while the agent thinks. There are at most **six input exchanges total**, including candidate feedback. Questions may bundle related preferences. A clear request can use zero exchanges; skipping optimization requires a model assessment of at least 0.9 that no useful permitted improvement remains. Confidence is not a calibrated financial probability.

When ready, use **See personalized offer in My offers**. A separate **Personalized · this session** card appears beside normal offers. Review its exact terms and explicitly confirm selection. Unselected quotes survive refresh but disappear on logout or the eight-hour session expiry. Selected terms remain recorded across logins. The shared catalog is never changed.

Owner APIs still enforce current qualification and approved term limits, including bounded rule tightening (DTI at most 5 points lower; tenure at most 12 months higher). Customer expectations inform preferences, not saved facts or future eligibility. Supported fit, payment and borrowing-cost calculations are disclosed; observed acceptance, population eligibility and bank-profit superiority are not established.

Limits per saved profile and login session: six candidates, 32 model calls, 600,000 tokens, the configured $15 maximum, and 600 seconds of active time (human waits extend the deadline). No improvement, a budget stop, cancellation or changed qualification leaves the other offers available. Repeated clicks and refreshes reuse the same workflow.

## 6. Enter Business and run a manual experiment

Sign out, then click **Enter Business workspace** in the landing page's **Business workspace** card. **Performance briefing** opens first and summarizes active AI publications with Network 2. Select **Offer performance** for the recorded counts and publication receipts.

### Generate discovery and validation cohorts

1. Select **Simulations** in the navigation, then the **Populations** tab.
2. Click **Generate population** near the section heading. Set **Customer count** to `10000` and **Population seed** to `20261102`. Click **Generate population** in the dialog.
3. Repeat with count `10000`, seed `20261103`. Reserve this second cohort exclusively for A2A final validation; do not run a manual comparison against it.
4. Identify populations by their displayed seed and ID. For repeated demos, choose new seeds and create fresh populations. Synthetic experiment rows do not become active customer profiles.

### Create, edit, and compare

1. In **Simulations**, click **New draft** near the page heading.
2. Enter **Experiment name**: `GUI personal loan comparison`.
3. For **Baseline catalog offer**, choose **Personal loan demo 1** (`DEMO-PL-1`). For **Campaign containing this offer**, choose **Personal loan demonstration** (`DEMO-PL`). Click **Create draft**.
4. In the draft's **Offer** tab, set **APR (%)** to `11.00`. Keep the amount bounds, dates, fee, and term as loaded.
5. In **Bureau**, set **Minimum credit score (0 disables)** to `720`. Keep other fields and the Underwriting/Marketing tabs unchanged.
6. Click **Save draft version** at the bottom. The saved version increases. Dirty fields must be saved before running.
7. Click **Run a comparison** below the editor. Choose the discovery population with seed `20261102`; enter **Analysis seed** `20261104`; click **Start comparison**.
8. Wait for completion. Inspect **Baseline vs. candidate**, **Held-out test**, **Cohort impact**, assumptions, and customer rows. The held-out subset within a manual run is distinct from the untouched population reserved for A2A final validation.

Compare eligible/assessed counts as well as rates. The reported simulated acceptance is a utility-derived score, not measured customer conversion. Exact figures depend on the frozen catalog, rules, population, and analysis seed; this guide does not promise a winning percentage.

### Optional manual publication

On the completed result, click **Review publication**. Expand **Inspect exact tested offer and rules**, enter a review note such as `Reviewed eligibility, costs and limitations for this local demo.`, check **I reviewed these terms, rules, and the comparison results.**, and click **Confirm publication**. Save the offer/campaign IDs from the receipt. Publishing creates a new catalog offer/campaign; it does not overwrite the baseline.

You may leave the manual result unpublished and continue with the same saved draft below. Manual publication does not create an AI prediction record for the AI performance cards.

## 7. A2A simulation and offer design (Network 1)

### Approve the demo research evidence

Select **Policy evidence** in the Business navigation. Click **Refresh documents** near the heading. For each of the two demo documents:

1. Click **Read and review** in its table row.
2. Read the extracted text, using **Next text** as needed.
3. Choose **Applicable product** `PERSONAL_LOAN` and an **Approval expiry** in the future (for example `2027-11-02` when following this guide in November 2026).
4. Check the statement that you have read and approve this exact version, then click **Approve this document version**.

Only approve the document you actually inspected. Editing its bytes invalidates that approval. Missing files mean the copy step or `AI_POLICY_DIR` needs attention, not that the agent should invent policy.

### Start the experiment

Reopen the saved draft under **Simulations → Offer drafts** and click **Explore this draft with A2A**. Alternatively select **A2A exploration → Start A2A exploration** and choose the draft in **Saved offer draft**.

In **Start an A2A experiment**:

- **Discovery population:** seed `20261102`.
- **Final-validation population:** the untouched seed `20261103`.
- **Analysis seed:** `20261104`.
- **Keep these stages unchanged:** check **Offer**, **Underwriting**, and **Marketing**; leave **Bureau** unchecked.
- **Objective and constraints:** paste the following request.

```text
Using the saved personal-loan draft, explore only /rules/bureau/minimumScore from 680 through 740, step 1, within the approved demo policy. Keep offer terms, underwriting, marketing and all other bureau fields fixed. Report separate best-tested acceptance and eligibility configurations, with at least 5 eligible customers. Both cross-metric floors are 0%. Use a fresh final-validation population. Explain small-cohort and simulated-utility limitations. Do not publish automatically.
```

Expand **Resource budget** and enter:

| Field | Value |
| --- | --- |
| Total simulation limit | `20` |
| Exploration trials | `12` |
| Refinement trials | `5` |
| Validation trials | `2` |
| Minimum eligible customers | `5` |
| Model call limit | `60` |
| Token limit | `500000` |
| Maximum cost ($) | `10` |
| Eligibility floor (%) / Acceptance floor (%) | `0` / `0` |
| Deadline (UTC ISO timestamp) | Keep the freshly loaded future deadline; if stale, enter a new future UTC instant |

The 20 evaluations cover one baseline plus the three trial allocations. The minimum support of 5 is a demo gate, not strong statistical evidence. Check **I approve model execution within this budget. Catalog publication requires a separate review.**, then **Start A2A workflow**. These inputs authorize planning and simulation immediately; do not expect a mandatory second execution confirmation.

Follow **Conversation**, **Agent activity**, **Resource usage**, **Findings and reflection**, and **Saved trials**. Network 1 can use zero to three input rounds across planning, execution and review. Agents group useful questions into a numbered prompt; answer the whole bundle and click **Send answer and continue**. The draft survives polling and rejected sends. A successful reply closes the editor, saves the answer and resumes the same workflow. Clear requests can proceed without questions. Simulations require a narrow, reviewed plan; after three rounds agents resolve remaining choices conservatively and display **Assumptions used**. Completed workflows support **Discuss these results** within the remaining model budget without changing prior trials. See [the conversational demo](docs/a2a-demo.md) for vague and fully specified test requests.

If the workflow explicitly enters scope approval, inspect **Review the proposed scope** and click **Approve this scope and continue**. A blocked run needs its stated constraint resolved. A paused budget can be extended only deliberately through **Review budget extension → Approve extension and resume**. Model-assessed task clarity is not a calibrated outcome probability; the 75–80% clarification-frequency goal requires live evaluation across representative requests.

### Publish the reviewed A2A result

For a completed workflow with final-validation results, scroll to **Review a validated candidate**. Select a **Final-validation candidate**, enter a meaningful **Review note**, and click **Prepare publication preview**. In **Confirm exact catalog publication**, inspect exact terms, rules, changes, and the preview expiry. Check the publication approval box and click **Confirm publication**.

No supported winner is a possible result; never substitute an unvalidated trial just to finish the demo. Record the published catalog offer ID and the separate AI publication/workflow IDs. Return to **Offer performance**.

## 8. Record an outcome, then investigate performance (Network 2)

The published offer now participates in future qualification. It is not automatically selected by existing customers.

1. Sign out of Business and log into **Alex GUI Demo**. Make a real synthetic profile edit, for example income `9000` to `9200`, review, and save to requalify against the current catalog.
2. Wait for **My offers** to include the **new catalog offer ID from the AI publication receipt**. If it is absent, inspect current qualification rather than selecting a different offer and calling it this publication's outcome.
3. Review original or personalized terms on that offer and **Confirm selection**. This is recorded demo evidence. One customer's choice is not evidence of population-level conversion accuracy.
4. Return to Business → **Offer performance**, choose **Local demo outcomes**, and click **Refresh outcomes**. Read original predicted eligibility/acceptance beside recorded eligibility/selection counts, maturity, coverage, and comparability. No recorded denominator means unavailable, not 0%.

The existing follow-up window defaults to 14 days, with reconciliation/maturity checks. New observations can be provisional. A utility prediction for catalog terms and selections for an offer family do not automatically support a forecast-error subtraction. Keep the diagnostic labels; the original prediction stays frozen.

For an interactive question, click **Investigate with A2A** on the publication card, or select **Investigations** in navigation and choose the publication under **Published offer**. Enter:

```text
What was the overall recorded acceptance rate during the selected dates? Use combined selected and eligible counts, not an average of daily percentages. Also tell me whether customer GUI-DEMO-001 was eligible for this exact personalized personal-loan offer, and show the evidence and missing-data limitations.
```

Set **Customer ID or reference (optional)** to the generated UUID to avoid ambiguity. Select **Start date (inclusive)** and **End date (inclusive)** covering the demo. Leave **Date basis** at **First assessment date**, **Timezone** at `America/Los_Angeles`, and **Observation source** at **Recorded demo outcomes**. Choose **Offer family** for the aggregate comparison; the customer's exact personalized variant is a separate question. Leave **Evidence cutoff** blank to use the current evidence cutoff.

Review the investigation budget (the form initially offers 30 calls, 300,000 tokens, $10, and a 30-minute execution window), check **I authorize agent execution within this budget.**, and click **Start investigation**. Answer clarification if the exact variant/date cannot be resolved. Open the figures and supporting evidence in the result. Missing historical decisions must read as **not recorded**, not **ineligible**. The native demo feed may not contain all assessment/admission history, so a date-range eligibility denominator may remain unavailable even when offer-family selections exist.

An investigation cannot edit rules, rerun simulations, publish, or rewrite a prediction. Start a new Network 1 experiment if the findings justify changed rules.

## 9. Stop, troubleshoot, and verify changes

Stop GUI, then AI, then base services with **Ctrl+C** in their launcher windows. Stop Kafka/relay without deleting data:

```powershell
.\scripts\infrastructure.ps1 -Stop
```

| Symptom | Check/action |
| --- | --- |
| GUI unavailable | Keep the base launcher running, then run `run-gui.ps1`; use its printed URL. The launcher includes the Windows/WSL IPv4 setting. |
| Old buttons or styling | Stop/rebuild the GUI with `build-gui.ps1`, restart its gateway, then reload the browser. |
| Steps 2–7 disabled | Configure `DATABASE_URL` in `runtime/local.env`; start PostgreSQL and infrastructure, then restart the base launcher. |
| AI unavailable | Start the full AI launcher; check `run-ai.ps1 -Check` and `runtime/ai/`, `runtime/ai/feedback/`, `runtime/ai/personalization/` logs. Performance-only mode intentionally has no agent workers. |
| Provider/model failure | Inspect account/model access and the configured key; a local health check does not call the model. |
| Policy blocked | Read, approve, and retain the exact current document version for the correct product and expiry. |
| Stale profile/draft/preview | Reload current data, rerun an edited draft if needed, and prepare a new preview. Do not bypass version checks. |
| No recorded performance | Confirm an AI-linked publication exists and its offer has genuine recorded demo activity; preserve missing-data/maturity labels. |
| Duplicate launcher or occupied port | Use the already-running group or stop its own launcher; do not delete database files or credentials. |

Documentation is checked against the source; these steps do not promise live-model output or a particular acceptance rate. For code changes, the documented checks are `build.ps1`, `build-ai.ps1`, and `build-gui.ps1`. The default base build skips opt-in database integrations; `scripts/test-bureau.ps1` uses your configured PostgreSQL/Kafka runtime and should be run deliberately. No reset is required for this walkthrough.
