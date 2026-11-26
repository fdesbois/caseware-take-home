# Template-Publish Fan-Out Worker

Narrow Java 25 / Spring Boot 4 worker component responsible for fanning out `TemplatePublished` events across eligible regional engagements (`EU` / `Canada`), enforcing bounded concurrency against a capacity-constrained (~1 min/call) downstream engagement service, and guaranteeing atomic idempotency and partial-failure resilience.

*(See `DESIGN.md` for the full end-to-end system architecture, AWS regional cell topology, version-pinning model, backfill calculations, and audit-defensible LLM summary pipeline.)*

## 1. Design Decisions & Trade-Offs

* **Bounded Concurrency (`Virtual Threads + Semaphore`):** Uses Java 25 Virtual Threads to avoid OS thread starvation during ~1-minute I/O calls, capped by an explicit `Semaphore(maxConcurrency)` (and circuit breaker) so downstream pods are never overwhelmed.
* **Pre-Call Atomic Idempotency:** Claims `(publishEventId, engagementId)` *before* invoking the downstream client rather than relying solely on post-call database upserts, preventing duplicate SQS deliveries from wasting downstream compute capacity.
* **Paginated Expansion vs. Single-Message Batching:** Expands eligible engagements in bounded pages and processes each engagement independently so a single failure routes only that item for retry or DLQ without aborting the batch.
* **Out-of-Order Write Protection:** Persists engagement template versions conditionally using `sourceUpdatedAt` so concurrent live events (`EngagementCreated` / `Accepted` / `Declined`) are never overwritten by slower background reads.
* **Deliberate Scope Boundaries:** Transport adapters (Spring Cloud AWS `@SqsListener`), database implementations, and HTTP clients are modeled as clean port interfaces so core orchestration and concurrency invariants are verified deterministically without infrastructure overhead.

## 2. Package Structure

```text
src/
├── main/java/com/caseware/fanout/
│   ├── model/       # Immutable records & sealed FanOutOutcome types (Region, TemplatePublishedEvent, EngagementSnapshot)
│   ├── port/        # Clean boundary interfaces (EngagementRepository, EngagementDownstreamClient, IdempotencyStore, DeadLetterPublisher)
│   ├── worker/      # TemplatePublishFanOutWorker (pagination, Semaphore concurrency, retry/DLQ routing)
│   └── config/      # FanOutProperties (@ConfigurationProperties) and Spring Boot 4 bean wiring
└── test/java/com/caseware/fanout/
    └── worker/      # Deterministic JUnit 5 tests (peak concurrency cap, idempotency, partial failures, out-of-order safety)