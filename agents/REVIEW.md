# Automated Code Review Standards

Audit every diff against the criteria below, against `AGENTS.md` and against `PART2_PROMPT.md` (which wins on conflict). Judge severity by production consequence rather than syntax style.

## Severity Levels
- **Critical (Must Fix):** Unbounded concurrency against the 1-minute `EngagementClient.load`, a permit held during `summarize`, a stale evaluation able to overwrite a newer `pending_update` (write without compare-and-set), a duplicate `SyncRequest` that reloads an engagement that already has history, cross-region processing (`EU`/`CA`/`US`), or silent message loss.
- **Warning (Should Fix):** Missing failure-isolation or ordering tests, blocking I/O inside `synchronized` blocks, hardcoded time (`Instant.now()`), SQL in this codebase, or scope creep (unrequested REST/JPA/Flyway/adapters, claim tables or idempotency stores).
- **Minor (Optional):** Naming clarity, record/pattern-matching simplifications, or concise Javadoc on contracts.

## Diff Audit Checklist

### 1. Concurrency & Downstream Capacity
- **Bounded Parallelism:** Confirm every `EngagementClient.load` call is gated by the per-pod `Semaphore` (`permitsPerPod`), that permits are released in `finally`, and that no permit is held during `summarize`.
- **Listener Sizing:** Confirm the `engagement-sync` listener uses `maxConcurrentMessages = permitsPerPod` and `messageVisibilitySeconds = 300`, and that the configured load timeout is shorter than the visibility timeout.
- **Virtual Threads:** Confirm blocking calls run on virtual threads and flag heavy blocking I/O inside `synchronized` blocks instead of `ReentrantLock` or `Semaphore`.

### 2. Idempotency, Ordering & Failure Handling
- **No Claim Table:** Flag any `IdempotencyStore`, claim table or `publishEventId` key. Duplicates are absorbed by the visibility timeout, the "any history row → skip the load" check, and the idempotent history append on `(engagementId, sourceUpdatedAt, eventType)`.
- **Always Evaluate:** Confirm a `SyncRequest` runs `evaluatePending` even when the load was skipped, so a redelivery after a failed `summarize` still creates the offer.
- **Append-Only History:** Confirm the current state is the latest row by `sourceUpdatedAt` plus the max declined version, and that SYNCED rows are stamped with `loadStartedAt`.
- **Conditional Persistence:** Confirm `pending_update` is written only through `applyIfUnchanged` with the basis (state, product latest version) the decision was made from, and that skipped writes are counted (`stale_write_skipped`).
- **Pure Decision Rules:** Confirm the offer rules (no state, ARCHIVED, `latest ≤ applied`, `latest ≤ max(declined)`, `EmptyDiff`) live in a pure function with its own tests.
- **Error Classification:** Verify transient load errors (timeout, 429, 5xx) throw so SQS redelivers, while permanent ones (404, malformed) go to `DeadLetterPublisher` and return normally.

### 3. Regional Isolation, Privacy & Time
- **Region Boundary:** Confirm a message whose region differs from the configured region goes to the DLQ before any load or write.
- **PII Hygiene:** Flag any sensitive payload fields written to logs, exception messages, or metric tags. Only opaque IDs, region and outcome.
- **Injected Clock:** Flag any direct `Instant.now()` / `LocalDateTime.now()` calls without an injected `Clock`.

### 4. Test Quality & Scope Discipline
- **No Unrequested Bloat:** Flag `@RestController`, JPA, Flyway, SQL, or Docker/Testcontainers dependencies.
- **Deterministic Concurrency Tests:** Verify tests prove the permit bound, duplicate suppression, failure isolation, ordering and stale-write protection with `CountDownLatch`, a mutable `Clock` and in-memory fakes, never `Thread.sleep`.
