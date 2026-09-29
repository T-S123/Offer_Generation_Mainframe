# Developer/API verification reference

For the analyst demonstration, use the [combined mainframe walkthrough](demo-walkthrough.md).
It performs a manual Business comparison and then A2A exploration on the same draft,
including scope approval, results, publication and feedback through terminal menus.

The commands below are optional engineering checks run in PowerShell outside the
mainframe. They are not steps to type into a mainframe screen and are not required
for the analyst demo. Running the workflow-creation commands here creates a
separate experiment and authorizes its own model budget.

This guide is for this Windows checkout with WSL Ubuntu-22.04. It loads a sample
policy, creates an isolated personal-loan experiment, invokes all six Java agents
using GPT-6 Astra, approves the scope, and verifies the results and A2A handoffs.

**Budget: $10 maximum model reservation for the main workflow; up to 20 simulation
attempts.** This is a cap, not an estimated bill or a guarantee of completion.
The main demo does not publish. Optional publication and feedback are separate.

## 1. Add environment variables

Use PowerShell in this directory:

~~~powershell
Set-Location 'C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe'
~~~

Prerequisites: Docker Desktop running, Ubuntu-22.04 initialized with a normal
non-root user, and Windows PostgreSQL listening on port 5432. The existing
[installation guide](../README.md#installation) covers first-time setup.
If Java 17, Maven, GnuCOBOL and the terminal clients are missing, run:

~~~powershell
.\scripts\setup.ps1
~~~

There are two ignored, local configuration files:

| File | Settings | Purpose |
| --- | --- | --- |
| runtime/local.env | DATABASE_URL, KAFKA_BOOTSTRAP_SERVERS | Existing application |
| runtime/ai.env | OPENAI_API_KEY | AI services; reuse local.env database |

Preserve your working local.env. Its expected shape is:

~~~bash
DATABASE_URL='postgresql://USER:URL_ENCODED_PASSWORD@127.0.0.1:15432/lending_intelligence_engine'
KAFKA_BOOTSTRAP_SERVERS='127.0.0.1:9092'
~~~

Use your actual PostgreSQL login. URL-encode reserved password characters.
Compose supplies Kafka and a PostgreSQL TCP relay on 15432, not PostgreSQL itself.
The database role needs permission to create the application tables and schemas.

Create ai.env only if absent, then edit it:

~~~powershell
New-Item -ItemType Directory -Path .\runtime -Force | Out-Null
if (-not (Test-Path -LiteralPath .\runtime\ai.env)) {
    Copy-Item .\ai-service\config\ai.env.example .\runtime\ai.env
}
notepad.exe .\runtime\ai.env
~~~

Add your key and change the existing default cap. Keep one assignment per setting:

~~~bash
OPENAI_API_KEY='YOUR_ACTUAL_OPENAI_API_KEY'
AI_DEFAULT_MAX_USD=10
~~~

Get an API key through the [OpenAI developer quickstart](https://developers.openai.com/api/docs/quickstart).
Your API account needs billing and access to gpt-6-astra. The model is selected by
the implementation; no OPENAI_MODEL variable is required. Keep keys local.

After saving and closing Notepad, normalize both files to LF and UTF-8 without BOM.
This command does not print their contents:

~~~powershell
foreach ($name in @('local.env', 'ai.env')) {
    $path = Join-Path (Get-Location).Path "runtime\$name"
    if (Test-Path -LiteralPath $path) {
        $text = [IO.File]::ReadAllText($path).Replace([string][char]13, '')
        [IO.File]::WriteAllText($path, $text, [Text.UTF8Encoding]::new($false))
    }
}
~~~

Optional settings: AI_DATABASE_URL for a separate database; AI_POLICY_DIR for a
WSL/Linux policy path; AI_MCP_CONFIG for a WSL/Linux MCP configuration path;
AI_ENABLE_OCR=true with Tesseract installed for scanned documents.
None is required for this text-document demo. No external MCP/research service or
real customer source is enabled by default.

Use default ports. Changing AI_BASE_PORT requires configuring the base engine too,
so put that override in local.env. Windows-only environment assignments are not
a reliable substitute for the files because the launchers run Java inside WSL.
The AI services create their six ai_* schemas and local credentials automatically.

## 2. Build and start

Stop older application instances with Ctrl+C in their launcher windows.

**Window 1: build, then run the existing application.**

~~~powershell
Set-Location 'C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe'
.\scripts\infrastructure.ps1
.\scripts\build.ps1
.\scripts\build-ai.ps1
.\scripts\run.ps1
~~~

Wait for "All three APIs are responding". Keep this window open.
Builds are needed initially and after code changes; the first build downloads
dependencies. These builds run tests with controlled models, not paid inference.

**Window 2: run the agents.**

~~~powershell
Set-Location 'C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe'
.\scripts\run-ai.ps1
~~~

Wait for "Six AI agents are ready". Keep this window open.

**Window 3: paste the remaining API demo commands here.**

~~~powershell
Set-Location 'C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe'
.\scripts\run-ai.ps1 -Check
~~~

Expected roles/ports: Orchestrator 8100, Research 8101, Designer 8102,
Coordinator 8103, Analyzer 8104, Reflection 8105. Health checks verify processes,
not your account's ability to complete model inference.

## 3. Initialize helpers

Paste this whole block into Window 3. It uses the same authenticated engine proxy
as the terminal. Tokens are read locally and are not printed.

~~~powershell
$ErrorActionPreference = 'Stop'
$demoRoot = (Get-Location).Path
$demoBase = 'http://127.0.0.1:8090/api/v1'
$demoHeaders = @{
    Authorization = 'Bearer ' + [IO.File]::ReadAllText(
        (Join-Path $demoRoot 'runtime\api-token')).Trim()
}
$demoCap = [decimal]10
$demoWorkflowIds = @()

function Invoke-DemoApi {
    param([string]$Method, [string]$Path, [object]$Body = $null)
    $request = @{
        Method=$Method; Uri="$demoBase/$Path"; Headers=$demoHeaders
        TimeoutSec=150; ErrorAction='Stop'
    }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(
            ($Body | ConvertTo-Json -Depth 100 -Compress))
    }
    Invoke-RestMethod @request
}
function New-DemoBudget {
    param([decimal]$Cap = 10)
    $b = Invoke-DemoApi GET 'ai/defaults'
    $b.maxEvaluations=20; $b.exploration=10; $b.refinement=6; $b.validation=3
    $b.maxModelCalls=60; $b.maxTokens=500000; $b.maxCostUsd=$Cap
    $b.minimumEligible=5; $b.eligibilityFloor=0; $b.acceptanceFloor=0
    $b.deadline = [DateTime]::UtcNow.AddHours(1).ToString('o')
    return $b
}
function Wait-DemoWorkflow {
    param([string]$Id, [string]$Until)
    $end = [DateTime]::UtcNow.AddMinutes(65)
    $last = ''
    do {
        $current = Invoke-DemoApi GET "ai/workflows/$Id"
        $state = $current.workflow.state
        if ($state -ne $last) { Write-Host "$Id : $state"; $last=$state }
        if ($state -eq $Until) { return $current }
        if ($state -in @('FAILED','NEEDS_INPUT','REVIEW_REQUIRED','PAUSED_BUDGET','CANCELED')) {
            $current.workflow | Select-Object state, clarification, error |
                Format-List | Out-Host
            throw "Stopped at $state. See troubleshooting. Workflow ID: $Id"
        }
        Start-Sleep -Seconds 3
    } while ([DateTime]::UtcNow -lt $end)
    throw "Polling stopped; the server may still be working. Inspect $Id."
}
function New-DemoPopulationPair {
    $used = @(Invoke-DemoApi GET 'business/populations' | ForEach-Object { $_.seed })
    do { $a=Get-Random -Minimum 1 -Maximum 2000000000 } while ($a -in $used)
    do { $b=Get-Random -Minimum 1 -Maximum 2000000000 } while ($b -eq $a -or $b -in $used)
    $discovery = Invoke-DemoApi POST 'business/populations' @{count=2000; seed=$a}
    $validation = Invoke-DemoApi POST 'business/populations' @{count=2000; seed=$b}
    return @{discovery=$discovery; validation=$validation}
}
Invoke-DemoApi GET 'health' | Format-List
Invoke-DemoApi GET 'ai/health' | Format-List
~~~

Windows localhost normally forwards into WSL. If this fails while the launchers
report healthy services, see troubleshooting before creating work.

## 4. Load, inspect and approve the sample document

The [sample policy](demo-policies/a2a-personal-loan-policy.md) is clearly marked
synthetic. Its 680–740 range demonstrates retrieval; it makes no claim about
appropriate real lending thresholds. This approval is for local testing.

~~~powershell
$policyName = 'a2a-personal-loan-policy.md'
$source = Join-Path $demoRoot "docs\demo-policies\$policyName"
$destination = Join-Path $demoRoot "policy-documents\$policyName"
New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
if (Test-Path -LiteralPath $destination) {
    if ((Get-FileHash $destination).Hash -ne (Get-FileHash $source).Hash) {
        throw 'A different local policy uses this filename. Preserve it and resolve the name first.'
    }
} else { Copy-Item -LiteralPath $source -Destination $destination }
Invoke-DemoApi POST 'ai/policies/refresh' @{} | Out-Null
$policy = @((Invoke-DemoApi GET 'ai/policies').documents |
    Where-Object { $_.name -eq $policyName })[0]
if ($null -eq $policy -or $policy.extractionStatus -ne 'READY') {
    throw 'The demo policy did not extract completely. Inspect the policy list.'
}
$extracted = Invoke-DemoApi GET "ai/policies/$($policy.id)/text?offset=0"
$extracted.text
if ($extracted.totalCharacters -gt $extracted.text.Length) {
    throw 'Read remaining text pages before approval.'
}
if ((Read-Host 'After reviewing the synthetic policy, type APPROVE') -ne 'APPROVE') {
    throw 'Policy not approved.'
}
$policy = Invoke-DemoApi POST "ai/policies/$($policy.id)/approve" @{
    hash=$policy.hash; product='PERSONAL_LOAN'; geography='US'
    expiresOn=[DateTime]::UtcNow.AddDays(30).ToString('yyyy-MM-dd')
}
$policy | Select-Object name, extractionStatus, approved, product, expiresOn
~~~

Expected: READY and approved=True. If you configured AI_POLICY_DIR, use that
directory instead. Resolve conflicts with other approved policies before proceeding.

Your own documents go in policy-documents. Supported formats include PDF,
DOCX/DOC, TXT, Markdown, HTML, RTF and ODT. Refresh, inspect extracted text and
approve. Edits revoke approval. Scanned or poor-quality sources may require OCR
or replacement; seeing a filename does not prove successful text extraction.

## 5. Create the draft and two independent populations

This uses the existing DEMO-PL-1 personal-loan offer and DEMO-PL campaign.
It does not edit that catalog offer. Review the printed baseline and dates.

~~~powershell
$catalog = Invoke-DemoApi GET 'business/catalog'
$offer = @($catalog.offers | Where-Object { $_.id -eq 'DEMO-PL-1' })[0]
$campaign = @($catalog.campaigns | Where-Object { $_.id -eq 'DEMO-PL' })[0]
if ($null -eq $offer -or $null -eq $campaign) {
    throw 'Seeded offer/campaign missing. Choose an existing PERSONAL_LOAN pair.'
}
if ($offer.data.product -ne 'PERSONAL_LOAN' -or
    'DEMO-PL-1' -notin $campaign.data.offerIds) {
    throw 'Baseline product or campaign association differs from this example.'
}
$offer | ConvertTo-Json -Depth 30
$campaign | ConvertTo-Json -Depth 30
$pair = New-DemoPopulationPair
$draft = Invoke-DemoApi POST 'business/drafts' @{
    name='A2A bureau score demo ' + (Get-Date -Format 'yyyyMMdd-HHmmss')
    baselineOfferId='DEMO-PL-1'; campaignId='DEMO-PL'
}
$pair.discovery, $pair.validation | Format-Table id, count, seed
$draft | Select-Object id, name, version
~~~

Every new experiment needs fresh validation. Reusing an old validation seed with
a new population ID is also rejected. These isolated populations do not populate
the Customer-mode list.

## 6. Trigger A2A, review scope, then run simulations

The next POST starts paid inference. The budget permits 1 baseline + 10 coarse
exploration + 6 refinement + 3 validation attempts. Deduplication can reduce the
number. Minimum eligible support=5 is for this small demo, not a production
evidence recommendation.

~~~powershell
$intent = 'For the local PERSONAL_LOAN demo, explore only /rules/bureau/minimumScore from 680 through 740 inclusive, step 1, using the approved A2A demo policy. Lock underwriting. Keep all offer terms, marketing criteria and other bureau fields unchanged. Report separate best-tested acceptance and eligibility configurations.'
$start = Invoke-DemoApi POST 'ai/workflows' @{
    requestId='demo-' + [Guid]::NewGuid().ToString('N')
    draftId=$draft.id
    populationId=$pair.discovery.id
    validationPopulationId=$pair.validation.id
    intent=$intent
    lockedStages=@('underwriting')
    budget=(New-DemoBudget -Cap $demoCap)
    seed=$pair.discovery.seed
}
$workflowId = $start.id
$demoWorkflowIds += $workflowId
Write-Host "Save this workflow ID: $workflowId"
$planned = Wait-DemoWorkflow -Id $workflowId -Until 'AWAITING_SCOPE'
~~~

Expected: PLANNING → AWAITING_SCOPE. Orchestrator, Research, Designer and
Reflection run before approval. No simulations should have run yet.

~~~powershell
$planned.workflow.scope | ConvertTo-Json -Depth 40
$planned.usage | Format-List
$ranges = @($planned.workflow.ranges)
$allowed = @($planned.workflow.allowedParameterIds)
if ($allowed.Count -ne 1 -or $allowed[0] -ne '/rules/bureau/minimumScore' -or
    'underwriting' -notin $planned.workflow.lockedStages -or
    $ranges.Count -ne 1 -or $ranges[0].low -ne 680 -or
    $ranges[0].high -ne 740 -or $ranges[0].step -ne 1 -or
    @($ranges[0].evidenceIds).Count -eq 0 -or $planned.usage.evaluations -ne 0) {
    throw 'Scope differs from the example. Inspect/clarify; do not approve it.'
}
if ((Read-Host 'After reviewing the scope, evidence and $10 cap, type APPROVE') -ne 'APPROVE') {
    throw 'Scope not approved.'
}
Invoke-DemoApi POST "ai/workflows/$workflowId/scope-confirmations" @{
    expectedVersion=$planned.version
    scopeHash=$planned.workflow.scopeHash
} | Out-Null
$finished = Wait-DemoWorkflow -Id $workflowId -Until 'COMPLETED'
~~~

Expected: EXECUTING → COMPLETED. Coordinator reviews admission, Java executes the
simulations, Analyzer explains computed metrics and Reflection reviews conclusions.

## 7. Verify results and real agent handoffs

~~~powershell
$finished.workflow.winners | ConvertTo-Json -Depth 30
$finished.workflow.analysis.decision | ConvertTo-Json -Depth 30
$finished.workflow.reflection.decision | ConvertTo-Json -Depth 30
$finished.usage | Format-List

$trials=@(); $offset=0
do {
    $page = Invoke-DemoApi GET "ai/workflows/$workflowId/evaluations?offset=$offset&limit=20"
    $trials += @($page.items)
    $offset = $page.nextOffset
} while ($null -ne $offset)
$trials | Select-Object phase, status,
    @{n='BureauScore';e={$_.configuration.rules.bureau.minimumScore}},
    @{n='Eligible';e={$_.score.eligible}},
    @{n='AcceptancePct';e={$_.score.acceptancePct}},
    @{n='EligibilityPct';e={$_.score.eligibilityPct}}, runId | Format-Table -AutoSize

$steps = @($finished.workflow.steps.PSObject.Properties | ForEach-Object { $_.Value })
$steps | Select-Object role, taskId, resultRef | Format-Table -AutoSize
foreach ($role in @('ORCHESTRATOR','RESEARCH','DESIGNER','COORDINATOR','ANALYZER','REFLECTION')) {
    if ($role -notin $steps.role) { throw "Missing completed A2A role: $role" }
}
foreach ($step in $steps) {
    $artifact = Invoke-DemoApi GET "ai/workflows/$workflowId/artifacts/$($step.resultRef)"
    [pscustomobject]@{
        Role=$step.role; TaskId=$step.taskId
        Model=$artifact.modelId; Decision=$artifact.decision.status
    } | Format-List
    if ($step.role -eq 'RESEARCH') {
        $artifact.observations | ConvertTo-Json -Depth 40
    }
}
~~~

Research should show a policy.search receipt with excerpts and evidence IDs.
Saved model IDs identify the responses. Completed role/task records verify actual
A2A delegation, beyond readiness checks. Inspect any failed trials.

Verify unchanged fields and metric denominators, then save results:

~~~powershell
$completed = @($trials | Where-Object { $_.status -eq 'COMPLETED' })
if ($completed.Count -eq 0 -or 'VALIDATION' -notin $completed.phase) {
    throw 'No completed final validation.'
}
$baseline = $finished.workflow.baseline
foreach ($trial in $trials) {
    $normalized = $trial.configuration | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $normalized.rules.bureau.minimumScore = $baseline.rules.bureau.minimumScore
    if (($normalized | ConvertTo-Json -Depth 100 -Compress) -ne
        ($baseline | ConvertTo-Json -Depth 100 -Compress)) {
        throw "An unapproved field changed in $($trial.runId)."
    }
}
foreach ($trial in $completed) {
    $s = $trial.score
    if ([Math]::Abs($s.eligibilityPct - 100.0*$s.eligible/$s.assessed) -gt 0.000001) {
        throw 'Eligibility denominator check failed.'
    }
    if ($s.eligible -gt 0 -and
        [Math]::Abs($s.acceptancePct - 100.0*$s.expectedSelections/$s.eligible) -gt 0.000001) {
        throw 'Acceptance denominator check failed.'
    }
}
if ($finished.usage.evaluations -gt 20 -or
    [decimal]$finished.usage.reservedCostUsd -gt $demoCap) {
    throw 'Demo budget exceeded.'
}
$output = Join-Path $demoRoot "runtime\demo\$workflowId"
New-Item -ItemType Directory -Path $output -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $output 'workflow.json'),
    ($finished | ConvertTo-Json -Depth 100), [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $output 'trials.json'),
    (ConvertTo-Json -InputObject @($trials) -Depth 100), [Text.UTF8Encoding]::new($false))
Write-Host "Verified. Saved results: $output"
~~~

Acceptance = expected selections / eligible; eligibility = eligible / assessed.
A null acceptance rate means no eligible customers. Rates vary with the sample
and baseline; there is no required 80% answer. The same candidate can win both
objectives. These are SIMULATED_UTILITY results, not calibrated real forecasts.
"Best tested" does not mean a global optimum.

## 8. Trigger or inspect through the mainframe

In a fourth PowerShell window:

~~~powershell
Set-Location 'C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe'
.\scripts\terminal.ps1
~~~

Choose **02 Business → 08 AI-assisted simulation and offer feedback**.

To inspect the existing API demo, choose **2 Resume a workflow / inspect results**
and its displayed row. Option 1 shows scope, 2 shows workflow/report data and 5
shows trials. Enter with a blank choice refreshes; F7/F8 page; F3 returns.

For a new UI-only experiment:

1. Business → 01: generate two fresh populations with different seeds.
   Business → 04: create a personal-loan draft.
2. Business → 08 → 3: refresh policy documents with R, select the sample, use
   option 1 to read text and option 2 to approve PERSONAL_LOAN with a future
   expiry. Type A to confirm.
3. AI → 1: select draft, discovery population and distinct validation population.
4. Enter these three intent lines:

~~~text
Explore only bureau minimum score from 680 to 740, step 1.
Keep underwriting, marketing and offer terms unchanged.
Use the approved A2A demo policy. Report both best-tested winners.
~~~

5. Set attempts=20, calls=60, tokens=500000, cost=10, minutes=60,
   minimum eligible support=5, both floors=0, underwriting lock=Y.
   The UI allocates phase slots automatically, so its split differs from the API.
6. At AWAITING_SCOPE, option 1 displays scope. F3 returns; option 3 and A approve.
   Refresh until completion.

A second workflow authorizes another budget. Inspect the existing workflow if you
only want to view the API experiment in the terminal.

## 9. Optional publication and feedback

This changes the active local catalog. Skip it if you only want the A2A demo.
In the same Window 3 session, prepare and inspect the acceptance winner:

~~~powershell
$finished = Invoke-DemoApi GET "ai/workflows/$workflowId"
$winner = $finished.workflow.winners.acceptance
if ($finished.workflow.state -ne 'COMPLETED' -or $null -eq $winner) {
    throw 'No completed acceptance winner is available.'
}
$preview = Invoke-DemoApi POST "ai/workflows/$workflowId/publication-previews" @{
    candidateId=$winner.candidateId
    note='Reviewed local synthetic A2A demonstration; bureau-score change only.'
}
$preview | ConvertTo-Json -Depth 50
~~~

Read the complete terms, rules, changed paths, prediction and hash. The preview
expires in 15 minutes. Confirm only if you want to publish that exact offer:

~~~powershell
if ((Read-Host 'Type PUBLISH to publish the displayed preview') -ne 'PUBLISH') {
    throw 'Offer not published.'
}
$publication = Invoke-DemoApi POST "ai/publication-previews/$($preview.previewId)/confirm" @{
    previewHash=$preview.previewHash; confirm=$true
}
$publicationId = $publication.receipt.offerId
$publication.receipt | Format-List
$performance = Invoke-DemoApi GET "ai/publications/$publicationId/performance?sourceMode=DEMO"
$performance.answer
$performance.comparison | ConvertTo-Json -Depth 40
~~~

A newly published offer normally has no eligible enrollments, so acceptance is
unavailable, not an invented 40%. The saved estimate remains unchanged.

To produce local customer activity, follow the [customer demo](demo-walkthrough.md#customer-case-application-to-personalized-offers-and-marketing-files)
with a fresh external reference, then choose an eligible offer matching the new
catalog offer ID. That guide controls consent and bureau facts. Local activity
remains DEMO; follow-up defaults to 14 days plus reconciliation time. It cannot
stand in for a mature real customer cohort.

For a researched "Why?", allocate only the unused part of this demo's $10 cap:

~~~powershell
$spent=[decimal]0
foreach ($id in $demoWorkflowIds) {
    $snapshot = Invoke-DemoApi GET "ai/workflows/$id"
    if ($snapshot.workflow.state -notin @('COMPLETED','REVIEW_REQUIRED','FAILED','CANCELED')) {
        throw 'Finish or cancel earlier work before allocating the remaining demo budget.'
    }
    $spent += [decimal]$snapshot.usage.reservedCostUsd
}
$remaining=$demoCap-$spent
if ($remaining -le 0) { throw 'The $10 demo cap is exhausted.' }
$investigation = Invoke-DemoApi POST "ai/publications/$publicationId/investigations" @{
    requestId='why-' + [Guid]::NewGuid().ToString('N')
    text='Why might selections differ from the saved simulation? Check coverage, maturity, source and eligible denominator first. Investigate supported engine diagnostics, label hypotheses and do not change underwriting.'
    budget=(New-DemoBudget -Cap $remaining); sourceMode='DEMO'
}
$investigationId=$investigation.id
$demoWorkflowIds += $investigationId
$feedback = Wait-DemoWorkflow -Id $investigationId -Until 'COMPLETED'
$feedback.workflow.analysis.decision | ConvertTo-Json -Depth 40
$feedback.workflow.reflection.decision | ConvertTo-Json -Depth 40
~~~

With insufficient data, a clarification or "insufficient evidence" conclusion is
appropriate. There is no configured real customer source. A two-week real forecast
comparison needs operational data and comparable mature outcomes.

For a corrective experiment: generate fresh populations, then **AI → 4 Published
offer performance → select publication → 3**. Underwriting locks carry forward.
New requested fields need approved evidence. Use only the remaining budget to stay
inside $10 total; this is another workflow with its own scope approval, validation,
publication preview and confirmation. See [API contracts](../ai-service/API.md)
for the structured successor request.

## Troubleshooting

- Missing API key: edit ai.env, normalize LF/no BOM, restart AI. A configured key
  does not prove account billing, model access or quota.
- Startup failure: base services must start first. Check the PostgreSQL relay,
  login/CREATE privileges, runtime/ai/*.log, runtime/credit-service.log and
  runtime/marketing-service.log.
- NEEDS_INPUT: read workflow.clarification. Reply through terminal option 4 or
  the pre-execution message example below.
- PAUSED_BUDGET: inspect usage and error. The next request reserves a conservative
  allowance, so a cost pause can occur before displayed usage reaches $10.
  Do not automatically increase it. If only time expired, terminal option 8 can
  extend the deadline while preserving cost=10 and current calls/tokens.
- FAILED or REVIEW_REQUIRED: inspect error, analysis and reflection; preserve
  the ID. A stopped workflow is not a successful demo.
- No supported winner: inspect rules and eligible support. A larger new sample
  needs fresh validation and a separately considered budget.
- VALIDATION_LEAKAGE: generate fresh seeds, including for discovery.
- STALE_VERSION or SCOPE_HASH: reload and re-review before approving.
- Catalog changed / policy expired: resolve the source change and research a
  new plan. Do not change the active baseline during an experiment.

For a pre-execution clarification:

~~~powershell
$current = Invoke-DemoApi GET "ai/workflows/$workflowId"
$current.workflow.clarification
$reply = Read-Host 'Enter your clarification after reading the question'
Invoke-DemoApi POST "ai/workflows/$workflowId/messages" @{
    expectedVersion=$current.version; text=$reply
} | Out-Null
$planned = Wait-DemoWorkflow -Id $workflowId -Until 'AWAITING_SCOPE'
~~~

Repeat the scope-review block afterward. For a feedback investigation, use
$investigationId and wait for COMPLETED instead. Scope changes after execution
require a successor.

If Windows localhost fails, check from WSL:

~~~powershell
wsl.exe -d Ubuntu-22.04 --exec curl --fail --silent --show-error --max-time 10 --noproxy '*' http://127.0.0.1:8090/api/v1/health
~~~

If WSL works but Windows fails, use the mainframe UI while resolving localhost
forwarding/VPN/firewall settings. Do not expose the service publicly.

To stop future workflow admissions deliberately:

~~~powershell
Invoke-DemoApi POST "ai/workflows/$workflowId/cancel" @{} | Out-Null
~~~

An already admitted simulation may finish. Ctrl+C stops each launcher; Ctrl+],
then Quit, exits the terminal. Stopping does not clear data. See
[reset instructions](reset-demo-data.md) to start without customers while keeping
the offer catalog.

This guide is checked against the implementation. No live key, paid model call or
active catalog publication was used to prepare it.
