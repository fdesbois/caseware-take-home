# Automated Code Review Standards

Audit every diff against the criteria below and against `AGENTS.md`. Judge severity by production consequence rather than syntax style. A finding that contradicts a documented trade-off in `AGENTS.md` or the README is a discussion, not a defect.

## Severity Levels
- **Critical (Must Fix):** Unbounded concurrency against the 1-minute `EngagementClient.load`, an offer removal that can erase a newer offer (unconditional delete on a decision), a duplicate `SyncRequest` that reloads an engagement that already has history, cross-region processing (`EU`/`CA`/`US`), or silent message loss.
- **Warning (Should Fix):** Missing failure-isolation or ordering tests, blocking I/O inside `synchronized` blocks, hardcoded time (`Instant.now()`), SQL in this codebase, or scope creep (unrequested REST/JPA/Flyway/adapters, offer computation or summary calls in the handler, claim tables or idempotency stores).
- **Minor (Optional):** Naming clarity, record/pattern-matching simplifications, or concise Javadoc on contracts.

## Diff Audit Checklist

### 1. Concurrency & Downstream Capacity
- **Bounded Parallelism:** Confirm every `EngagementClient.load` call is gated by the per-pod `Semaphore` (`permitsPerPod`) and that permits are released in `finally`.
- **Listener Sizing:** Confirm the `engagement-sync` listener uses `maxConcurrentMessages = permitsPerPod` and `messageVisibilitySeconds = 300`, that the `engagement-events` listener sets its limit explicitly, and that the load timeout is shorter than the visibility timeout.
- **Virtual Threads:** Confirm blocking calls run on virtual threads and flag heavy blocking I/O inside `synchronized` blocks instead of `ReentrantLock` or `Semaphore`.

### 2. Idempotency, Ordering & Failure Handling
- **No Claim Table:** Flag any `IdempotencyStore` or claim table. Duplicates are absorbed by the visibility timeout, the "any history row → skip the load" check, and the idempotent history append on `(engagementId, sourceUpdatedAt, eventType)`.
- **Append-Only History:** Confirm the current state is the latest row by `sourceUpdatedAt` plus the max declined version, and that SYNCED rows carry engagement-service's read time (`EngagementSnapshot.readAt`), not this pod's clock.
- **Conditional Offer Removal:** Confirm ACCEPTED removes the offer only if it starts before the accepted version, DECLINED only if it targets the declined version or an older one, and ARCHIVED always, each as one atomic statement.
- **Error Classification:** Verify transient load errors (timeout, 429, 5xx) throw so SQS redelivers, permanent ones (404, malformed) go to `DeadLetterPublisher` and return normally, and anything else is counted as `error` before it propagates.

### 3. Regional Isolation, Privacy & Time
- **Region Boundary:** Confirm a message whose region differs from the configured region goes to the DLQ before any load or write.
- **PII Hygiene:** Flag any sensitive payload fields or decision types written to logs, exception messages, or metric tags. Only opaque IDs, region and outcome.
- **Injected Clock:** Flag any direct `Instant.now()` / `LocalDateTime.now()` calls without an injected `Clock`.

### 4. Test Quality & Scope Discipline
- **No Unrequested Bloat:** Flag `@RestController`, JPA, Flyway, SQL, or Docker/Testcontainers dependencies.
- **Deterministic Concurrency Tests:** Verify tests prove the permit bound, duplicate suppression, failure isolation, ordering and conditional offer removal with latches or barriers, a mutable `Clock` and in-memory fakes, never `Thread.sleep`.
