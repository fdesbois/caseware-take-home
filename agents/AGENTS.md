# AGENTS.md

Project onboarding and engineering rules. Read this file completely before planning or modifying code. `agents/PART2_PROMPT.md` is the source of truth for Part 2; where this file and the prompt disagree, the prompt wins.

## 1. Core Philosophy & Scope Discipline
- **Narrow Component Scope:** Implement ONLY the `update-service` engagement message handler (`EngagementMessageHandler`), its two thin `@SqsListener` methods and its port interfaces. The brief calls this component the "template-publish fan-out worker"; in this design the publish fan-out needs no per-engagement call, so the 1-minute, capacity-limited call lives in the handler's sync path. Do NOT build REST controllers (`@RestController`), JPA/Hibernate entities, Flyway migrations, SQL Server or HTTP adapters, summary-service, the publish fan-out (Flow B), the nightly cron, or AWS infrastructure.
- **No SQL anywhere:** Ports are interfaces only. Thread-safe in-memory fakes under `src/test` are the reference implementation of the port contracts.
- **Lean Architecture:** Favor explicit domain boundaries and minimal moving parts. Reject speculative abstractions, wrapper boilerplate, and unnecessary third-party libraries.
- **Modern Java 25 & Spring Boot 4 Idioms:** Use Java 25 (`record`, `sealed` interfaces, pattern-matching `switch`, `Optional`, virtual threads) and Spring Boot 4 / Spring Cloud AWS conventions (`@ConfigurationProperties` records, constructor injection).

## 2. Concurrency, Backpressure & Downstream Protection
- **Bounded Concurrency (Mandatory):** `EngagementClient.load` takes ~1 minute and the owning team allows 80 concurrent loads per region (4 pods × 20 permits). A per-pod `Semaphore` (`permitsPerPod`) wraps `load` and nothing else; permits are released in `finally` and are never held during `SummaryClient.summarize` (30–60 s). The `engagement-sync` listener's `maxConcurrentMessages` equals `permitsPerPod`.
- **Virtual Threads:** Blocking calls run on virtual threads. Never spawn unbounded work against the downstream, and never put `synchronized` around blocking I/O.
- **Idempotency without a claim table:** No `IdempotencyStore`, no claim table, nothing keyed by `publishEventId`. Duplicates are absorbed by (a) an SQS visibility timeout (300 s) longer than the load timeout (120 s), (b) skipping the load when the engagement already has any history row, and (c) an idempotent history append (duplicate primary key = no-op). A rare concurrent duplicate costs one extra load, never a wrong result.
- **Out-of-Order State Safety:** `engagement_history` is append-only; the current state is the latest row by `source_updated_at` plus the max declined version. A SYNCED row is stamped with `loadStartedAt`, so any event newer than the load wins.
- **Write only if nothing moved:** `pending_update` is written only through `PendingUpdateRepository.applyIfUnchanged(engagementId, decision, basis)`, a compare-and-set against the state and product latest version the decision was made from. A skipped stale write is counted; the change that moved the basis triggers its own evaluation.
- **Partial Failure Isolation:** One engagement's failure never affects another. `load` returns `Loaded | Transient | Permanent`: Transient (timeout, 429, 5xx) throws so SQS redelivers (the queue's DLQ takes over after `maxReceiveCount`); Permanent (404, malformed) goes to `DeadLetterPublisher` and the handler returns normally.

## 3. Regional Residency & Observability
- **Strict Regional Isolation (`EU` / `CA` / `US`):** `update-service` runs once per region. A message whose region differs from the configured region goes to the DLQ before any load or write.
- **Zero PII in Logs & Metrics:** Never log client names, firm details, or engagement contents. Logs carry only opaque IDs (`engagementId`), region and outcome.
- **Metrics:** Micrometer counters per outcome (`loaded`, `skipped`, `transient`, `permanent`, `stale_write_skipped`) and a gauge of loads in flight.
- **Deterministic Time:** Inject `java.time.Clock` into all time-sensitive components. Never call `Instant.now()` or `System.currentTimeMillis()` in domain/handler logic.

## 4. Verification & Definition of Done
- **Plan First:** Output the package layout (`com.caseware.updates.{model,port,handler,adapter,config}`), domain types (`record`s / `sealed` outcomes), and test matrix before creating files.
- **Fast, Deterministic Tests (No Docker, no AWS):** JUnit 5 with a mutable `Clock`, `CountDownLatch`, and thread-safe in-memory fakes with atomic compare-and-set semantics. No `Thread.sleep`, no Testcontainers.
- **Mandatory Test Cases:**
  1. Peak concurrent `load` calls never exceed permits (500 requests, 20 permits).
  2. A duplicate `SyncRequest` after success does not call `load` again, and the offer is still there.
  3. Transient and permanent failures are isolated: other engagements complete, permanent → DLQ, transient → throws, in-flight returns to 0 (no leaked permit).
  4. Out of order: a newer ACCEPTED handled before an older SYNCED → current state = ACCEPTED.
  5. Decision rules, parameterized: declined v5 & latest v8 → offer; declined v8 & latest v8 → none; `EmptyDiff` → none and the existing offer deleted; ARCHIVED → none; accepted an older offer while a newer version exists → new offer from the accepted version.
  6. A stale evaluation (summary held on a latch while an ACCEPTED for the same engagement completes) does not overwrite the newer result.
  7. `summarize` fails after the SYNCED row is written → the redelivery skips the load and creates the offer.
  8. Region mismatch → DLQ, no load.
  9. While one evaluation is blocked in `summarize`, other loads can still use every permit.
- **Quiet, Green Builds:** Run `./mvnw test -q` before finishing. Zero compiler warnings and zero failing tests.
- **Incremental Git Checkpoints (Mandatory):** Never accumulate uncommitted work across milestones. After every green milestone (`./mvnw test -q` passing with zero warnings):
  1. Align `agents/` with `agents/PART2_PROMPT.md`
  2. Maven wrapper + Spring Boot 4 + Java 25 skeleton
  3. Domain model (`record`s / `sealed` outcomes) and port interfaces
  4. `EngagementMessageHandler` + listeners + config
  5. JUnit 5 test suite
  6. Part 2 `README.md`

  Run `git status`, stage changes, commit with a concise Conventional Commit message (`docs:`, `build:`, `feat:`, `test:`), and immediately run `git push -u origin HEAD`. `DESIGN.md` is a separate task.
