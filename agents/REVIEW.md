# Automated Code Review Standards

Audit every diff against the criteria below and against `AGENTS.md`. Judge severity by production consequence rather than syntax style.

## Severity Levels
- **Critical (Must Fix):** Unbounded concurrency against the 1-minute downstream API, missing pre-call idempotency, race conditions, cross-region data leakage (`EU`/`CA`), or silent message loss.
- **Warning (Should Fix):** Missing partial-failure tests, blocking carrier threads inside `synchronized` blocks on Virtual Threads, hardcoded time (`Instant.now()`), or scope creep (unrequested REST/JPA boilerplate).
- **Minor (Optional):** Naming clarity, record/pattern-matching simplifications, or concise Javadoc on contracts.

## Diff Audit Checklist

### 1. Concurrency & Downstream Capacity
- **Bounded Parallelism:** Confirm `EngagementDownstreamClient` calls are strictly gated by a `Semaphore` / rate limiter (`<= maxConcurrency`). Flag any unbounded `Executors.newVirtualThreadPerTaskExecutor()` usage.
- **Virtual Thread Pinning:** Flag heavy blocking I/O executed inside `synchronized` blocks instead of `ReentrantLock` or `Semaphore`.
- **Pagination & Batching:** Confirm large firm/product fan-outs (20,000–40,000 engagements) iterate page by page rather than loading unbounded lists into memory.

### 2. Idempotency & Failure Handling
- **Pre-Call Reservation:** Verify `(publishEventId, engagementId)` is claimed atomically *before* calling the 1-minute downstream client, and released/marked failed properly if a retryable error occurs.
- **Conditional Persistence:** Confirm `EngagementStateRepository` guards against out-of-order updates using `sourceUpdatedAt`.
- **Error Classification:** Verify transient errors (`Timeout`, `429`, `503`) trigger retryable handling/backoff while malformed or permanent errors route to `DeadLetterPublisher`.

### 3. Regional Isolation, Privacy & Time
- **Region Boundary:** Confirm `Region` (`EU` vs `CA`) is validated and propagated across repository, downstream client, and DLQ calls.
- **PII Hygiene:** Flag any sensitive payload fields written to logs, exception messages, or metric tags.
- **Injected Clock:** Flag any direct `Instant.now()` / `LocalDateTime.now()` calls without an injected `Clock`.

### 4. Test Quality & Scope Discipline
- **No Unrequested Bloat:** Flag unnecessary `@RestController`, Flyway, or Docker/Testcontainers dependencies outside the narrow worker scope.
- **Deterministic Concurrency Tests:** Verify tests prove peak concurrency bounds, duplicate suppression, and partial batch failures without `Thread.sleep` flakiness.