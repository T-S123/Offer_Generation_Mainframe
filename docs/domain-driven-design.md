# Why the application follows domain-driven design

The code organizes behavior around lending responsibilities and keeps the authoritative decisions in their owning domain/application services. GUI screens, terminal fields, agent prompts, and database adapters do not independently decide eligibility or acceptance.

| Domain/context | Responsibility and authority | Implementation |
| --- | --- | --- |
| Customer and underwriting | Customer identity, versioned profiles, assessment history, input integrity, and underwriting orchestration | [Model](../services/src/main/java/com/lending/engine/domain/Model.java), [CustomerRules](../services/src/main/java/com/lending/engine/domain/CustomerRules.java), [CustomerEngine](../services/src/main/java/com/lending/engine/application/CustomerEngine.java) |
| Marketing qualification/catalog | Campaigns, catalog revisions, qualification snapshots, consent/suppression, capacity reservations, and reviewed publication | [MarketingEngine](../services/src/main/java/com/lending/engine/marketing/application/MarketingEngine.java) |
| Bureau and credit | Independent bureau facts, durable credit requests, and versioned credit decisions | [BureauEngine](../services/src/main/java/com/lending/engine/bureau/application/BureauEngine.java), [CreditEngine](../services/src/main/java/com/lending/engine/credit/CreditEngine.java) |
| Offer generation and selection | Exact original/personalized variants, financial comparisons, source revalidation, and explicit customer choices | [OfferEngine](../marketing-service/src/main/java/com/lending/offers/application/OfferEngine.java), [OfferSource](../services/src/main/java/com/lending/engine/offers/OfferSource.java) |
| Business experimentation | Frozen synthetic populations, versioned drafts/rules, paired comparisons, and tested publication receipts | [SimulationEngine](../services/src/main/java/com/lending/engine/simulation/application/SimulationEngine.java) |
| Performance investigation | Immutable predictions, evidence scope, deterministic measurements, coverage/maturity, and evidence-backed explanations | [OutcomeService](../ai-service/src/main/java/com/lending/ai/OutcomeService.java), [PerformanceMeasurements](../ai-service/src/main/java/com/lending/ai/PerformanceMeasurements.java) |
| Customer personalization | Approved limits and exact-candidate qualification; agent proposals remain subordinate to owner checks | [CustomerPersonalizationPolicy](../services/src/main/java/com/lending/engine/offers/CustomerPersonalizationPolicy.java), [PersonalizationWorkflowService](../ai-service/src/main/java/com/lending/ai/PersonalizationWorkflowService.java) |

## Concrete design properties

**Domain language carries identity.** A customer profile version, Risk ID, qualification, offer variant, rule version, simulation run, and publication receipt are distinct concepts. For example, eligibility for a product family does not prove eligibility for a particular personalized variant.

**Rules and infrastructure are separated.** [Ports](../services/src/main/java/com/lending/engine/application/Ports.java) describes persistence and underwriting dependencies. JDBC and HTTP implement transport/storage concerns. COBOL policy programs accept fixed copybook inputs; batch adapters own file I/O. The browser and TN3270 terminal call APIs rather than duplicating those rules.

**Invariants live at the write boundary.** Profile updates check expected versions and preserve history. Publication requires a tested version and explicit review. Selection checks current authority and exact terms. Consent, suppression, and approved personalization limits remain enforced even if an agent proposes otherwise.

**Events connect responsibilities.** Versioned qualification and storage events feed downstream offer generation, read copies, and execution. A stale projection is not sufficient authority to accept an offer; the owner revalidates the command.

## Scope of the claim

DDD does not require every context to be a microservice. Customer and Marketing qualification intentionally share a local transaction to prevent profile/finalization races. The three A2A networks have distinct roles and workflow policies but share a facade and workflow store. This is a modular implementation with explicit boundaries, not a claim that every service is independently deployable or that every class uses a pure domain model. See [architecture](architecture.md) for these tradeoffs.
