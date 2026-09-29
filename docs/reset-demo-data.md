# Start fresh while keeping offers and products

This procedure preserves the current catalog offer IDs, terms, product assignments
and published business rules. It also preserves their campaign definitions and
global policy so the offers remain usable. Product types are defined in Java
(PERSONAL_LOAN, CREDIT_CARD, AUTO_LOAN), not a customer table.

It removes customers and their dependent records from the active local environment,
and starts new simulation/AI history. Old data remains in the backup and old
PostgreSQL database. This is a demo reset, not permanent erasure of every copy.

**There is no single pgAdmin DELETE command for this application.** Customer
profiles and the catalog are in runtime/customer-engine.mv.db (H2), simulation
history is in runtime/business-simulations.mv.db (H2), and downstream records live
in PostgreSQL and Kafka. Deleting only PostgreSQL rows leaves profiles behind;
retaining old Kafka events can replay old work.

The procedure below is instructions only. It has not been run against your data.

## What stays and what is reset

| Preserve | Reset from the active application |
| --- | --- |
| Catalog offers and their versions, including published rules | Customer profiles, underwriting history, contacts and pipeline work |
| Product definitions | Customer suppressions, marketing runs/reservations and memberships |
| Campaign configuration and global policy | Bureau reports/requests/decisions and personalized customer offers |
| Local configuration and policy source files | Offer selections, downstream copies, outbound files and execution state |
| Backup of the old runtime and the old PostgreSQL database | Simulation populations/drafts/runs, AI workflows, outcomes and calibrations |

"Keep offers" here means the reusable catalog. Customer-specific personalized offers
contain customer data and are cleared. Catalog offers previously published by AI
remain, but old AI conversations and publication provenance are in the backup,
not the fresh AI history. Policy source files stay on disk and need fresh approval.

Use this only with the local demo services stopped. If another checkout shares
the same Kafka broker and application topic names, stop and coordinate that
shared reset first. The Kafka step removes only six named application topics,
but another checkout using those same names is affected.

## 1. Save the current catalog before stopping

While the existing engine is still running, open a separate PowerShell window:

~~~powershell
Set-Location 'C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe'
$ErrorActionPreference = 'Stop'
$resetRoot = (Get-Location).Path
$headers = @{
    Authorization = 'Bearer ' + [IO.File]::ReadAllText(
        (Join-Path $resetRoot 'runtime\api-token')).Trim()
}
$before = Invoke-RestMethod -Uri 'http://127.0.0.1:8090/api/v1/business/catalog' -Headers $headers
[IO.File]::WriteAllText((Join-Path $resetRoot 'runtime\catalog-before-reset.json'),
    ($before | ConvertTo-Json -Depth 100), [Text.UTF8Encoding]::new($false))
$before.offers | Select-Object id, version, @{n='Product';e={$_.data.product}}
~~~

Keep this PowerShell window open for the following steps.

## 2. Stop application processes, retain infrastructure for now

Ctrl+C in the AI launcher window, then Ctrl+C in the base-service launcher window.
Exit all terminal clients. Stop any separately started engine, marketing or credit
process for this checkout. Leave Docker Desktop and the local Kafka broker running
until step 6.

Do not copy or change an H2 database while its application process is running.
Do not run the reset against a different checkout with a similar folder name.

## 3. Archive runtime and create a working copy of the catalog database

This moves runtime into a unique backup directory under your Windows LocalAppData.
That backup contains private data and keys; do not upload it or commit it.

The path checks deliberately require this exact checkout. No recursive deletion
is used. The only database restored into the fresh runtime is a copy of the H2
database holding the catalog; the next step removes its customer records.

~~~powershell
$expectedRoot = 'C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe'
$resolvedRoot = (Resolve-Path -LiteralPath $resetRoot).ProviderPath.TrimEnd('\')
if ($resolvedRoot -ne $expectedRoot) { throw 'Wrong checkout; reset stopped.' }
$runtime = Join-Path $resolvedRoot 'runtime'
if ((Resolve-Path -LiteralPath $runtime).ProviderPath.TrimEnd('\') -ne
    (Join-Path $expectedRoot 'runtime')) { throw 'Unexpected runtime path.' }
if ((Get-Item -LiteralPath $runtime).Attributes -band [IO.FileAttributes]::ReparsePoint) {
    throw 'Runtime is a link/junction; resolve its ownership before resetting.'
}
if (-not (Test-Path -LiteralPath (Join-Path $runtime 'customer-engine.mv.db'))) {
    throw 'The expected customer/catalog H2 database is missing.'
}
$backupBase = [IO.Path]::GetFullPath(
    (Join-Path $env:LOCALAPPDATA 'OfferGenerationMainframe\backups'))
$backup = [IO.Path]::GetFullPath((Join-Path $backupBase (
    (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))))
if (-not $backup.StartsWith($backupBase + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Unexpected backup destination.'
}
New-Item -ItemType Directory -Path $backup -Force | Out-Null
if ((Get-Item -LiteralPath $backup).Attributes -band [IO.FileAttributes]::ReparsePoint) {
    throw 'Backup destination is a link/junction.'
}
$archiveRuntime = Join-Path $backup 'runtime'
if (Test-Path -LiteralPath $archiveRuntime) { throw 'Backup destination already exists.' }
Move-Item -LiteralPath $runtime -Destination $archiveRuntime
New-Item -ItemType Directory -Path $runtime | Out-Null
foreach ($name in @('customer-engine.mv.db', 'local.env', 'ai.env')) {
    $old = Join-Path $archiveRuntime $name
    if (Test-Path -LiteralPath $old) {
        Copy-Item -LiteralPath $old -Destination (Join-Path $runtime $name)
    }
}
Write-Host "Keep this backup path: $backup"
~~~

The original H2 file is untouched in the archive. Outbound files, old simulation
databases and AI runtime credentials/logs are archived too. New service credentials
will be generated on startup; do not restore the old AI state separately.

## 4. Remove customer rows from the working H2 copy

This is **H2 SQL, not PostgreSQL SQL**. Run it using the already-built Java 17
application JAR inside WSL. The SQL file deletes dependent rows in order inside
a transaction and retains only OFFER, CAMPAIGN and POLICY catalog revisions.
It preserves migration checksums.

~~~powershell
. .\scripts\wsl-path.ps1
$linuxProject = ConvertTo-EngineLinuxPath -Path $resetRoot
$h2Url = "jdbc:h2:file:$linuxProject/runtime/customer-engine;IFEXISTS=TRUE"
wsl.exe -d Ubuntu-22.04 --exec java -cp "$linuxProject/services/target/customer-engine-1.0.0.jar" org.h2.tools.RunScript -url $h2Url -user sa -script "$linuxProject/docs/reset-customers-keep-catalog.h2.sql" -showResults
if ($LASTEXITCODE -ne 0) {
    throw 'H2 reset failed. Keep services stopped; the original is in the backup.'
}
~~~

Expected: remaining_customers = 0, with retained OFFER, CAMPAIGN and POLICY
revision counts. Do not use RunScript -continueOnError or delete migration tables.
The source is [reset-customers-keep-catalog.h2.sql](reset-customers-keep-catalog.h2.sql).

## 5. Create a fresh PostgreSQL database in pgAdmin

Keep the existing database as your PostgreSQL backup. In pgAdmin, connect the
Query Tool to the maintenance database named postgres and run this by itself
with autocommit enabled, not inside BEGIN/COMMIT:

~~~sql
CREATE DATABASE lending_intelligence_engine_fresh;
~~~

If that name already exists, choose a new unused name; do not reuse or empty an
unknown database. Set the new database's owner to the same application login used
in local.env (pgAdmin: new database → Properties → Definition → Owner).
If your pgAdmin connection already uses that login, ownership is already correct.
An administrator may need to create the database or change its owner.

Edit the copied configuration:

~~~powershell
notepad.exe .\runtime\local.env
notepad.exe .\runtime\ai.env
~~~

In local.env change only the database name at the end of DATABASE_URL:

~~~bash
DATABASE_URL='postgresql://USER:URL_ENCODED_PASSWORD@127.0.0.1:15432/lending_intelligence_engine_fresh'
KAFKA_BOOTSTRAP_SERVERS='127.0.0.1:9092'
~~~

Keep your existing login/password and the actual connection settings. In ai.env,
remove/comment a separate AI_DATABASE_URL override so AI uses the new DATABASE_URL,
or point it to another new empty AI database. Never leave it pointing to the old
AI database. Preserve OPENAI_API_KEY.

For this local reset, remove custom AI_CREDENTIAL_FILE overrides if present so
the launcher generates credentials in the fresh runtime. An external operational
outcome source must stay disconnected during reset or it can re-import old data.

After saving/closing both editors:

~~~powershell
foreach ($name in @('local.env','ai.env')) {
    $path = Join-Path $runtime $name
    if (Test-Path -LiteralPath $path) {
        [IO.File]::WriteAllText($path,
            [IO.File]::ReadAllText($path).Replace([string][char]13, ''),
            [Text.UTF8Encoding]::new($false))
    }
}
~~~

No DROP SCHEMA or TRUNCATE in pgAdmin is required. Application startup creates
the new downstream and AI schemas. Their old contents remain in the old database.

## 6. Clear stale Kafka events for this application

All application producers and consumers must still be stopped. This step deletes
the six default local application topics and their retained event history. It does
not remove other topics or Docker volumes. If you customized Kafka topic names or
the broker connection, stop and adapt the list to that configuration first.

~~~powershell
$topics = @(
    'bureau.qualification.requested.v1',
    'bureau.qualification.completed.v1',
    'bureau.qualification.rejected.v1',
    'marketing.qualification.updated.v1',
    'bureau.decision.published.v1',
    'offers.storage.updated.v1'
)
docker.exe compose --project-directory $resetRoot ps
$existing = @(docker.exe compose --project-directory $resetRoot exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list)
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the local Kafka broker.' }
$topics | ForEach-Object { Write-Host "Application topic to clear: $_" }
if ((Read-Host 'Confirm all app services are stopped; type RESET-TOPICS') -ne 'RESET-TOPICS') {
    throw 'Kafka reset not confirmed.'
}
foreach ($topic in $topics) {
    if ($topic -in $existing) {
        docker.exe compose --project-directory $resetRoot exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic $topic
        if ($LASTEXITCODE -ne 0) { throw "Could not delete $topic; keep services stopped." }
    }
}
$deadline = [DateTime]::UtcNow.AddSeconds(60)
do {
    $now = @(docker.exe compose --project-directory $resetRoot exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list)
    if ($LASTEXITCODE -ne 0) { throw 'Kafka verification failed.' }
    $remaining = @($topics | Where-Object { $_ -in $now })
    if ($remaining.Count -eq 0) { break }
    Start-Sleep -Seconds 2
} while ([DateTime]::UtcNow -lt $deadline)
if ($remaining.Count -gt 0) {
    throw 'Topic deletion is still pending. Do not restart the application yet.'
}
~~~

Services recreate the topics on startup. Do not start another checkout that still
points to the old database and publishes to these topics.

## 7. Restart and verify zero customers and unchanged catalog

Start the base launcher in one window, wait for readiness, then start AI in another:

~~~powershell
Set-Location 'C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe'
.\scripts\run.ps1
~~~

~~~powershell
Set-Location 'C:\Users\tanek\code\AWS\Case-Study-1\Offer_Generation_Mainframe'
.\scripts\run-ai.ps1
~~~

Back in the original reset window, using the new token:

~~~powershell
$headers = @{
    Authorization='Bearer ' + [IO.File]::ReadAllText(
        (Join-Path $runtime 'api-token')).Trim()
}
$customers = Invoke-RestMethod -Uri 'http://127.0.0.1:8090/api/v1/customers?offset=0&limit=1' -Headers $headers
$customers | ConvertTo-Json -Depth 10
if ($customers.total -ne 0) { throw 'Customer reset verification failed.' }
$after = Invoke-RestMethod -Uri 'http://127.0.0.1:8090/api/v1/business/catalog' -Headers $headers
$before = [IO.File]::ReadAllText(
    (Join-Path $archiveRuntime 'catalog-before-reset.json')) | ConvertFrom-Json
foreach ($kind in @('offers','campaigns')) {
    $oldJson = ConvertTo-Json -InputObject @($before.$kind | Sort-Object id) -Depth 100 -Compress
    $newJson = ConvertTo-Json -InputObject @($after.$kind | Sort-Object id) -Depth 100 -Compress
    if ($oldJson -ne $newJson) { throw "Retained $kind do not match the backup." }
}
.\scripts\run-ai.ps1 -Check
Write-Host 'Verified: zero customers; original offers and campaigns retained.'
~~~

Open the terminal and confirm the Customer list is empty and the Business catalog
still contains your offers. Create a new customer or follow the
[A2A demo](a2a-demo-walkthrough.md). Reapprove policy documents and use fresh
populations; old workflow IDs belong to the archived environment.

If any verification fails, stop the services and keep the backup and old database.
Do not combine an old H2 file with a new PostgreSQL database during normal operation.
Restoring the previous environment requires matching its H2 snapshot, database,
configuration and event-processing state; the deleted Kafka event log is not
included in this backup.

Validation: the H2 SQL was executed on a disposable in-memory fixture using the existing application JAR and current customer/marketing DDL. All seeded customer/dependent rows were removed, catalog offer versions and published-rule payloads were retained, and migration metadata was unchanged. The full reset was not run on the active environment.
