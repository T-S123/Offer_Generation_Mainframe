# Source evidence and design boundaries

Inspected on 27 September 2026. Repository: Offer_Generation_Mainframe, main, commit 232eda9. This file records the original architecture research, before implementation. Current implementation and verification are documented in [the AI guide](../../../ai-service/README.md); source pointers below remain tied to the inspected baseline.

## Existing implementation anchors

Line numbers refer to the inspected commit; they are evidence pointers, not permanent API contracts.

| Finding | Source and relevant lines |
|---|---|
| Business Simulation has 9 offer fields and 11 fields in each of three stages: 42 editable controls | [SimulationTerminal.java](../../../services/src/main/java/com/lending/engine/terminal/SimulationTerminal.java), lines 57 and 69–82 |
| Rule domains, score-zero sentinel, stage restrictions and fixed hidden fields | [BusinessRules.java](../../../services/src/main/java/com/lending/engine/simulation/domain/BusinessRules.java), lines 18–43 |
| Offer amount/APR/term/date validation | [MarketingRules.java](../../../services/src/main/java/com/lending/engine/marketing/domain/MarketingRules.java), lines 17–28 |
| One simulation worker and three admission permits | [SimulationEngine.java](../../../services/src/main/java/com/lending/engine/simulation/application/SimulationEngine.java), line 39 |
| Synthetic population limit 60–10,000; frozen drafts and optimistic versioning | [SimulationEngine.java](../../../services/src/main/java/com/lending/engine/simulation/application/SimulationEngine.java), lines 45–57 |
| Simulation execution sends matched baseline/candidate rows to Java analysis and records population/draft hashes | [SimulationEngine.java](../../../services/src/main/java/com/lending/engine/simulation/application/SimulationEngine.java), lines 59–60 |
| Amount terms use requested customer principal rather than sweeping an assigned principal | [SimulationEngine.java](../../../services/src/main/java/com/lending/engine/simulation/application/SimulationEngine.java), lines 75–76 |
| Report sums simulated acceptance probabilities over eligible rows; eligibility percentage already exists | [BusinessAnalysis.java](../../../marketing-service/src/main/java/com/lending/offers/domain/BusinessAnalysis.java), lines 45–53 |
| Isolated cohort fitting, partitions and paired comparison | [BusinessAnalysis.java](../../../marketing-service/src/main/java/com/lending/offers/domain/BusinessAnalysis.java), lines 31–39 and 47 |
| Utility uses affordability, cost, amount match and term fit; it is not observed conversion | [Personalizer.java](../../../marketing-service/src/main/java/com/lending/offers/domain/Personalizer.java), lines 19–28 |
| Selection validates the current exact terms/version, deduplicates request identity and commits selection plus journal in one transaction; status is SELECTED_DEMO_ONLY | [OfferStore.java](../../../marketing-service/src/main/java/com/lending/offers/infrastructure/OfferStore.java), lines 71–74 |
| Storage preserves source evidence and ordered revisions; selection snapshots and cursor APIs exist | [StorageJournal.java](../../../marketing-service/src/main/java/com/lending/offers/infrastructure/StorageJournal.java), lines 31–52 and 79–85 |
| Business publication requires completed/latest draft and explicit review; a matching prior fingerprint recovers its receipt | [SimulationEngine.java](../../../services/src/main/java/com/lending/engine/simulation/application/SimulationEngine.java), line 83 |
| Publication checks baseline catalog/campaign/policy, validates capacity/dates, and creates a new offer/campaign/rules/receipt through the marketing store transaction | [MarketingEngine.java](../../../services/src/main/java/com/lending/engine/marketing/application/MarketingEngine.java), lines 41–49 |
| Existing business endpoint routing | [SimulationApi.java](../../../services/src/main/java/com/lending/engine/simulation/api/SimulationApi.java), lines 20–28 |
| Existing runtime/service/database wiring | [Main.java](../../../services/src/main/java/com/lending/engine/Main.java), lines 26–88 |
| Actual rule execution remains COBOL behind Java adapters | [LIRL01C.cbl](../../../app/cbl/LIRL01C.cbl) and [CobolBusinessPolicy.java](../../../services/src/main/java/com/lending/engine/simulation/infrastructure/CobolBusinessPolicy.java) |
| Sibling A2A example uses custom tasks/send with skill_id and input, and extracts result.answer | [orchestrator_server.py](../../../../a2a/app/a2a/orchestrator_server.py), lines 164–184, 262–264 and 309 |

The formula S/E, immutable field masks, outer validation boundary, historical enrollment cohort, agent services, durable search scheduler, outcome model, AI APIs, approval tokens and additive idempotent run admission are **proposed**, not claims about existing capabilities.

## Primary references

These sources informed protocol and integration choices. SDK versions must be pinned and interoperability checked when implementation begins.

- [A2A specification](https://a2a-protocol.org/latest/specification/): v1 interfaces, messages, parts, tasks, artifacts, discovery and security declarations.
- [A2A key concepts](https://a2a-protocol.org/latest/topics/key-concepts/), [task lifecycle](https://a2a-protocol.org/latest/topics/life-of-a-task/) and [streaming/asynchronous interaction](https://a2a-protocol.org/latest/topics/streaming-and-async/): stateful agent collaboration.
- [Official A2A Java SDK](https://github.com/a2aproject/a2a-java): Java client/server implementation starting point.
- [GPT-6 Astra](https://developers.openai.com/api/docs/models/gpt-6-astra): requested model and supported API capabilities.
- [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs): schema-constrained responses and function arguments.
- [OpenAI libraries](https://developers.openai.com/api/docs/libraries): official Java client.
- [MCP Java client](https://java.sdk.modelcontextprotocol.io/latest/client/): controlled Java tool-server integration.
- [OpenAI remote MCP](https://developers.openai.com/api/docs/guides/tools-connectors-mcp): optional model-side MCP support.

No source above supplies bank-approved parameter recommendations. The Research agent must retrieve applicable internal policy and evidence at runtime. Technical validation limits are not recommended lending thresholds.

## Artifact validation

[validation.json](validation.json) records structural and layout checks for the seven-view editable drawing. Matching PNG previews were generated and visually inspected. JSON examples contain synthetic identities and illustrative numbers; they are not captured production messages. No application tests, model calls, simulations or publications were run.

The diagram builder and its content file are documentation tooling only. Application source, existing migrations and fixtures remain unchanged.
