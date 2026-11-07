# Why the application can scale

The design separates expensive work from interactive requests and gives several workloads their own processes, queues, and stores. Those mechanisms support growth, but the current checkout is a local demonstration with real bottlenecks; it is not evidence of production throughput.

| Mechanism already present | Why it helps | Code evidence |
| --- | --- | --- |
| HTTP boundaries around credit, offers, and analysis | Expensive capabilities can evolve and be provisioned separately from the screen host | [CreditServer](../services/src/main/java/com/lending/engine/credit/CreditServer.java), [OfferServer](../marketing-service/src/main/java/com/lending/offers/api/OfferServer.java) |
| Durable bureau queues, worker claims, retries, and leases | Requests survive process failures; workers can claim different jobs without duplicate ownership | [BureauQueue](../services/src/main/java/com/lending/engine/bureau/infrastructure/BureauQueue.java) |
| Outbox/inbox processing and versioned Kafka events | Qualification, offer storage, projections, and execution can progress asynchronously; consumers can reject duplicates | [ResponseEngine](../services/src/main/java/com/lending/engine/response/application/ResponseEngine.java), [OfferStore](../marketing-service/src/main/java/com/lending/offers/infrastructure/OfferStore.java), [OfferCopyStore](../services/src/main/java/com/lending/engine/storage/OfferCopyStore.java) |
| Separate customer and warehouse read copies | Reading a presentation view need not repeat the whole qualification pipeline | [StorageViews](../services/src/main/java/com/lending/engine/storage/StorageViews.java) |
| Bounded simulation and agent admission | Overload is limited instead of creating unlimited expensive jobs | [SimulationEngine](../services/src/main/java/com/lending/engine/simulation/application/SimulationEngine.java), [A2AService](../ai-service/src/main/java/com/lending/ai/A2AService.java) |
| Atomic model-budget reservations | Concurrent agent calls cannot each spend the same remaining allowance | [Store](../ai-service/src/main/java/com/lending/ai/Store.java) |
| A direct Performance API | Ordinary dashboard reads do not require model calls or wait for an investigation | [PerformanceService](../ai-service/src/main/java/com/lending/ai/PerformanceService.java) |

## Current limits

The Customer/Marketing host uses embedded H2 and a shared transaction boundary. Business experiments use a second local H2 file, one simulation worker, and three admitted in-flight/queued runs; each population is limited to 10,000 synthetic people. This is useful workload isolation, not unlimited parallel simulation.

Each A2A role currently has one worker and a bounded 16-task queue. Its JDBC store serializes operations through one connection. All three workflow runtimes share the port-8100 facade and orchestrator persistence. Launching more copies without changing ownership, leases, and idempotency is not a supported high-availability deployment. Browser sessions are also process-local.

The local Kafka deployment uses one broker; partitioning does not make that broker fault tolerant. PostgreSQL is a shared infrastructure dependency. Outbox/inbox deduplication provides recoverable at-least-once processing, not a blanket exactly-once guarantee across all systems.

## What production scaling would require

Measure request latency, queue age, model latency/cost, database contention, and failure recovery first. Then replace local aggregate persistence and browser sessions where needed, introduce pooled database access and distributed workflow ownership, provision replicated Kafka/PostgreSQL, and add authenticated ingress, observability, and capacity tests. Preserve version checks and reservation guarantees when separating any shared transaction. No production-volume or conversion claim follows from a successful 10,000-person synthetic run.

See [architecture](architecture.md) for current process boundaries and [GUI](../README.md) / [terminal](terminal-walkthrough.md) for reproducible local workflows.
