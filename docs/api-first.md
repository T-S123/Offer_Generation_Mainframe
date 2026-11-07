# Why the application follows API-first principles

The GUI, terminal, and A2A workflows use application APIs for domain operations. Customer creation, qualification, experiments, publication, personalization, and selection are not browser-only behaviors. Their request/response shapes and version checks are part of explicit contracts.

## Contracts and consumers

| Boundary | Contract | Consumers |
| --- | --- | --- |
| Customer/assessment | [openapi.yaml](../contracts/openapi.yaml) | TN3270 client and browser gateway |
| Marketing qualification/catalog | [marketing.openapi.yaml](../contracts/marketing.openapi.yaml) | Operational screens, automation, and publication use cases |
| Credit and bureau requests | [credit.openapi.yaml](../contracts/credit.openapi.yaml), [bureau.openapi.yaml](../contracts/bureau.openapi.yaml) | Durable qualification workers and operational clients |
| Business experiments/analysis | [business.openapi.yaml](../contracts/business.openapi.yaml), [business-analysis.openapi.yaml](../contracts/business-analysis.openapi.yaml) | Manual screens and Network 1 |
| Exact offers and selection | [marketing-offers.openapi.yaml](../contracts/marketing-offers.openapi.yaml), [offer-source.openapi.yaml](../contracts/offer-source.openapi.yaml) | Customer clients and source-authority callbacks |
| Local presenter maintenance | [presenter.openapi.json](../contracts/presenter.openapi.json) | Confirmed, idempotent reset commands through the loopback gateway |
| Browser adapter | [gui.openapi.json](../contracts/gui.openapi.json) | React client via `/web-api/` |
| Performance/investigation | [performance.openapi.json](../contracts/performance.openapi.json) | Direct dashboard reads and Network 2 |
| Customer personalization | [personalization.openapi.json](../contracts/personalization.openapi.json) | Customer GUI and Network 3 |
| Events and legacy records | [qualification event contract](../contracts/decision-response.asyncapi.yaml), [storage event contract](../contracts/offer-storage.asyncapi.yaml), [COBOL record contract](../contracts/cobol-records.md) | Kafka consumers and COBOL batch adapters |

The remaining files in `contracts/` define additional execution, storage, schema, and bureau-event boundaries. They are operational contracts retained alongside the six reader guides.

## How this works in practice

A customer edit goes from a form through the browser gateway or terminal HTTP client to the Customer API. The owner validates the submitted profile and expected version, persists it, and initiates qualification. Both clients see the same rules and conflicts. Similarly, an agent proposes a candidate; an explicit owner command validates/qualifies/issues it, and a separate customer selection command records acceptance.

The browser gateway holds service credentials server-side, maps a local demo session to an allowed customer or Business operation, and translates transport concerns. Agents have scoped A2A envelopes, typed results, bounded tools, and explicit execution budgets. Neither frontends nor prompts become an alternative database-write path. The presenter reset is a separate operational command: the browser supplies an action and request ID; a local maintenance coordinator owns worker shutdown and data reset. Demo fixture creation still uses owner APIs.

Contracts include more than field names: immutable versions, idempotency where supported, concurrency conflicts, reviewed publication, exact-term selection, and source/maturity definitions matter. Events describe downstream facts; commands ask the owner to perform a change. Optional read-only MCP tools do not replace publication, candidate issuance, or customer acceptance commands.

## Keeping contracts aligned

From the project directory in PowerShell:

```powershell
node .\scripts\sync-gui-contract.mjs --check
.\scripts\build-gui.ps1
```

The synchronizer checks the generated GUI contract, including its referenced AI-facing definitions, against owner contracts; `build-gui.ps1` also runs client tests and gateway tests. If an intentional source-contract change needs regeneration, use `node .\scripts\sync-gui-contract.mjs`, review the generated diff, and rerun `--check`. Do not hand-edit generated files as the sole implementation of a new endpoint. Base and AI checks run through `build.ps1` and `build-ai.ps1` respectively.

These mechanisms support API-first development; they do not prove that every historical change began with a specification, nor replace semantic integration tests. The Performance dashboard requires its API but no agent run. Live A2A additionally depends on the model provider and configured permissions. See [architecture](architecture.md) and the [GUI walkthrough](../README.md) for deployment and setup.
