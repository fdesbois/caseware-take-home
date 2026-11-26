# AGENTS.md

Project onboarding and engineering rules. Read this file completely before planning or modifying code.

## 1. Core Philosophy & Scope Discipline
- **Narrow Component Scope:** Implement ONLY the requested `template-publish fan-out worker` and its clean port interfaces. Do NOT build REST controllers (`@RestController`), JPA/Hibernate entities, Flyway migrations, or full web application plumbing unless explicitly requested.
- **Lean Architecture:** Favor explicit domain boundaries and minimal moving parts. Reject speculative abstractions, wrapper boilerplate, and unnecessary third-party libraries.
- **Modern Java 25 & Spring Boot 4 Idioms:** Use Java 25 (`record`, `sealed` interfaces, pattern matching, `Optional`, Virtual Threads) and Spring Boot 4 / Spring Cloud AWS conventions (`@ConfigurationProperties` records, constructor injection).

## 2. Concurrency, Backpressure & Downstream Protection
- **Bounded Concurrency (Mandatory):** The downstream engagement service takes ~1 minute per call and has strictly limited capacity. NEVER spawn unbounded Virtual Threads. Always gate downstream calls with an explicit `Semaphore` (and/or rate limiter / circuit breaker) enforcing `maxConcurrency`.
- **Pre-Call Atomic Idempotency:** Never rely on in-memory check-then-write or post-call SQL upserts alone. Reserve `(publishEventId, engagementId)` atomically in `IdempotencyStore` **before** invoking `EngagementDownstreamClient` so duplicate SQS deliveries never waste a 1-minute downstream call.
- **Out-of-Order State Safety:** State updates in `EngagementStateRepository` must be conditional on `sourceUpdatedAt` so stale backfill reads never overwrite newer live events.
- **Partial Failure Isolation:** A failure on one engagement must never abort the batch or lose progress on sibling engagements. Separate transient failures (retryable with backoff) from permanent failures (routed to `DeadLetterPublisher`).

## 3. Regional Residency & Observability
- **Strict Regional Isolation (`EU` / `Canada`):** Every event, engagement lookup, and state write must carry and enforce an explicit `Region` (`EU` or `CA`). Never mix regions in a single batch or cross-region call.
- **Zero PII in Logs & Metrics:** Never log client names, firm details, or engagement document contents. Log only opaque IDs (`publishEventId`, `engagementId`, `region`) and outcome statuses.
- **Deterministic Time:** Inject `java.time.Clock` into all time-sensitive components. Never call `Instant.now()` or `System.currentTimeMillis()` directly in domain/worker logic.

## 4. Verification & Definition of Done
- **Plan First:** Output the package layout, domain types (`records` / `sealed` outcomes), and test matrix before creating files.
- **Fast, Deterministic Tests (No Docker Overhead):** Verify core invariants with fast JUnit 5 tests using virtual `Clock`, `CountDownLatch`, and thread-safe in-memory port fakes implementing atomic CAS semantics—do NOT require Docker or Testcontainers to run `./mvnw test -q`.
- **Mandatory Test Cases:**
  1. Peak concurrent calls to `EngagementDownstreamClient` strictly `<= maxConcurrency` under load.
  2. Duplicate `(publishEventId, engagementId)` delivery does not invoke the downstream client a second time.
  3. Transient vs. permanent failures isolate cleanly without aborting remaining engagements in the page.
- **Quiet, Green Builds:** Run `./mvnw test -q` before finishing. Zero compiler warnings and zero failing tests.
- **Incremental Git Checkpoints (Mandatory):** Never accumulate uncommitted work across milestones. After every green milestone (`./mvnw test -q` passing with zero warnings) — specifically:
  1. Initial Maven + Spring Boot 4 + Java 25 skeleton
  2. `DESIGN.md` finalized
  3. Domain models (`records` / `sealed` outcomes) and port interfaces
  4. `TemplatePublishFanOutWorker` implementation (bounded concurrency, pre-call idempotency, retry/DLQ routing)
  5. JUnit 5 concurrency, idempotency, and partial-failure test suite
  6. Final `README.md` and `REVIEW.md` audit pass
  Run `git status`, stage changes, commit with a concise Conventional Commit message (`docs:`, `feat:`, `test:`), and immediately run `git push -u origin HEAD`.