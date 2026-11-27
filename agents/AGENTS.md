# AGENTS.md

Project onboarding and engineering rules. Read this file completely before planning or modifying code. It describes the final design (offers only on publish); `PART2_PROMPT.md` is the first implementation prompt, kept as history. Where they disagree, this file wins.

## 1. Core Philosophy & Scope Discipline
- **Narrow Component Scope:** Implement ONLY the `update-service` engagement message handler (`EngagementMessageHandler`), its two thin `@SqsListener` methods and its port interfaces. The handler keeps `engagement_history` complete and removes offers that a decision or an archive made obsolete. Offers and summaries are created only by the publish fan-out (Flow B). The brief calls this component the "template-publish fan-out worker"; in this design the 1-minute, capacity-limited call lives in the handler's sync path. Do NOT build REST controllers (`@RestController`), JPA/Hibernate entities, Flyway migrations, SQL Server or HTTP adapters, summary-service, the publish fan-out (Flow B), the nightly cron, or AWS infrastructure.
- **No SQL anywhere:** Ports are interfaces only. Thread-safe in-memory fakes under `src/test` are the reference implementation of the port contracts.
- **Lean Architecture:** Favor explicit domain boundaries and minimal moving parts. Reject speculative abstractions, wrapper boilerplate, and unnecessary third-party libraries.
- **Modern Java 25 & Spring Boot 4 Idioms:** Use Java 25 (`record`, `sealed` interfaces, pattern-matching `switch`, `Optional`, virtual threads) and Spring Boot 4 / Spring Cloud AWS conventions (`@ConfigurationProperties` records, constructor injection).

## 2. Concurrency, Backpressure & Downstream Protection
- **Bounded Concurrency (Mandatory):** `EngagementClient.load` takes ~1 minute and the owning team allows 80 concurrent loads per region (4 pods × 20 permits; deploy with `maxSurge: 0`). A per-pod `Semaphore` (`permitsPerPod`) wraps `load` and nothing else, and permits are released in `finally`. The `engagement-sync` listener's `maxConcurrentMessages` equals `permitsPerPod`; the `engagement-events` listener sets its own limit explicitly.
- **Virtual Threads:** Blocking calls run on virtual threads. Never spawn unbounded work against the downstream, and never put `synchronized` around blocking I/O.
- **Idempotency without a claim table:** No `IdempotencyStore`, no claim table. Duplicates are absorbed by (a) an SQS visibility timeout (300 s) longer than the load timeout (120 s), (b) skipping the load when the engagement already has any history row, and (c) an idempotent history append (duplicate primary key = no-op). A rare concurrent duplicate costs one extra load, never a wrong result.
- **Out-of-Order State Safety:** `engagement_history` is append-only; the current state is the latest row by `source_updated_at` plus the max declined version. A SYNCED row is stamped with engagement-service's read time (`EngagementSnapshot.readAt`), the same clock as the events, so a decision made during the load wins.
- **Conditional offer removal:** The handler never creates offers. ACCEPTED removes the offer only if it starts before the accepted version, DECLINED only if it targets the declined version or an older one, ARCHIVED always. Each removal is one atomic conditional statement, so a redelivered decision never erases a newer offer.
- **Partial Failure Isolation:** One engagement's failure never affects another. `load` returns `Loaded | Transient | Permanent`: Transient (timeout, 429, 5xx) throws so SQS redelivers (the queue's DLQ takes over after `maxReceiveCount`); Permanent (404, malformed) goes to `DeadLetterPublisher` and the handler returns normally. Any other exception is counted as `error` and rethrown.

## 3. Regional Residency & Observability
- **Strict Regional Isolation (`EU` / `CA` / `US`):** `update-service` runs once per region. A message whose region differs from the configured region goes to the DLQ before any load or write.
- **Zero PII in Logs & Metrics:** Never log client names, firm details, engagement contents or decision types. Logs carry only opaque IDs (`engagementId`), region and outcome.
- **Metrics:** Micrometer counters per outcome (`loaded`, `skipped`, `transient`, `permanent`, `error`) and a gauge of loads in flight.
- **Deterministic Time:** Inject `java.time.Clock` into any time-sensitive component. Never call `Instant.now()` or `System.currentTimeMillis()` in domain/handler logic.

## 4. Verification & Definition of Done
- **Plan First:** Output the package layout (`com.caseware.updates.{model,port,handler,adapter,config}`), domain types (`record`s / `sealed` outcomes), and test matrix before creating files.
- **Fast, Deterministic Tests (No Docker, no AWS):** JUnit 5 with a mutable `Clock`, `CountDownLatch`/`CyclicBarrier`, and thread-safe in-memory fakes with atomic conditional deletes. No `Thread.sleep`, no Testcontainers.
- **Mandatory Test Cases:**
  1. Peak concurrent `load` calls never exceed permits (500 requests, 20 permits).
  2. A duplicate `SyncRequest` after success does not call `load` again.
  3. Transient and permanent failures are isolated: other engagements complete, permanent → DLQ, transient → throws, unexpected → `error`, in-flight returns to 0 (no leaked permit).
  4. A decision made during a load beats the SYNCED snapshot (engagement-service's clock).
  5. A decision or an archive removes only the offer it made obsolete.
  6. A redelivered acceptance does not erase the offer a later publish built from it.
  7. Region mismatch → DLQ, no load, no write.
- **Quiet, Green Builds:** Run `./mvnw test -q` before finishing. Zero compiler warnings and zero failing tests.
- **Incremental Git Checkpoints (Mandatory):** Never accumulate uncommitted work across milestones. After every green milestone, run `git status`, commit with a concise Conventional Commit message (`docs:`, `build:`, `feat:`, `fix:`, `test:`), and push. Update this file and `REVIEW.md` in the same PR as any design change.
