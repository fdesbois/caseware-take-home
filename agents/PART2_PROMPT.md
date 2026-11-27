> **Historical input, superseded.** The first implementation prompt for Part 2. The design later moved offers and summaries to the publish fan-out, so the handler no longer evaluates offers or calls summary-service. `agents/AGENTS.md` and the root `README.md` describe the final component.

# Part 2: engagement message handler (update-service)

You are implementing **Part 2** of the Caseware take-home (`context/Staff_Java_Developer_-_Take-Home_Test 2.pdf`). Read `agents/AGENTS.md`, `agents/REVIEW.md` and, if present, `DESIGN.md` first. **Where they conflict with this prompt, this prompt wins**: your first commit aligns `agents/AGENTS.md` and `agents/REVIEW.md` with it (no claim table or `IdempotencyStore`, nothing keyed by `publishEventId`, the type names below replace the old ones).

## Decided context (do not redesign)
- `update-service` runs once per region (EU, CA, US) on EKS and owns an RDS SQL Server database with three tables:
  - `product_header_version(product_id PK, version, published_at)`: latest version per product (`product_id` = template + branch). Versions only increase; a withdrawal is published as a new version.
  - `engagement_history(PK engagement_id, source_updated_at, event_type; firm_id, product_id, applied_version, declined_version, summary_key, actor_id)`: append-only; `event_type` ∈ CREATED, ACCEPTED, DECLINED, SYNCED, ARCHIVED. Current state = latest row by `source_updated_at`.
  - `pending_update(engagement_id PK, firm_id, product_id, from_version, to_version, summary_key, offered_at)`: the current offer, deleted once it no longer applies.
- Two SQS queues, each with its own DLQ:
  - `engagement-events`: `EngagementStateUpdateEvent(engagementId, firmId, productId, region, type, appliedVersion, declinedVersion?, summaryKey?, actorId?, sourceUpdatedAt)`, published by engagement-service with the full state.
  - `engagement-sync`: `SyncRequest(engagementId, region)`, published nightly by a cron for engagements with no history row, at most ~100k per run (one day of loads), so the ~800k backfill spreads over ~8 nights.
- `EngagementClient.load` takes ~1 minute, and the owning team allows **80 concurrent loads per region** (4 pods × 20 permits).
- `SummaryClient.summarize(productId, from, to)` returns `Ready(summaryKey)` or `EmptyDiff`, and can take 30–60 s.

## Build: one handler, two thin listeners
- **Listeners**: two `@SqsListener` methods (Spring Cloud AWS, Boot 4 compatible), one per queue, each one line calling `EngagementMessageHandler.handle(EngagementMessage)`. `engagement-sync`: `maxConcurrentMessages` = permits per pod, `messageVisibilitySeconds = 300`.
- **Handler**: `sealed interface EngagementMessage permits EngagementStateUpdateEvent, SyncRequest`, dispatched with a pattern-matching `switch`:
  - Event: append the history row (duplicate primary key = no-op), then `evaluatePending`.
  - Sync request: skip the load if history already has any row for this engagement (the cron only queues engagements with none, so a row means an event or an earlier sync already gave us the state). Otherwise take a permit, `loadStartedAt = clock.instant()`, `load`, release the permit, append a SYNCED row with `source_updated_at = loadStartedAt`. Then **always** `evaluatePending` (even when the load was skipped, so a redelivery after a later failure still creates the offer).
- **`evaluatePending(engagementId)`**: read the current state and the product's latest version, then a pure, separately tested decision function:
  - No offer (delete the row): no state, ARCHIVED, `latest ≤ applied`, or `latest ≤ max(declined_version)`.
  - Otherwise `summarize(applied, latest)`: `EmptyDiff` → delete; `Ready` → upsert offer `applied → latest`.
- **Write only if nothing moved**: `PendingUpdateRepository.applyIfUnchanged(engagementId, decision, basis)` where `basis` = (latest history `source_updated_at`, product latest version). If either moved, skip the write and count it: the change that moved it triggers its own evaluation. (The two listeners can process the same engagement concurrently.)
- **Downstream capacity**: a `Semaphore` wraps `load` only; a permit is never held during `summarize`. Blocking calls run on virtual threads; no `synchronized` around blocking I/O. Permits are released in `finally`.
- **Failures**: `load` returns a sealed `Loaded(snapshot) | Transient(code) | Permanent(code)`.
  - Transient (timeout, 429, 5xx): throw, so SQS redelivers; the DLQ takes over after `maxReceiveCount`.
  - Permanent (404, malformed): `DeadLetterPublisher.publish(message, code)`, then return normally.
- **Region**: message region ≠ configured region → DLQ, no load, no write.
- **Idempotency**: no claim table, no extra store. SQS visibility timeout (300 s) > load timeout (120 s), plus the "any history row → skip the load" check. A rare concurrent duplicate costs one extra load, never a wrong result.
- **Observability**: Micrometer counters per outcome (`loaded`, `skipped`, `transient`, `permanent`, `stale_write_skipped`) and a gauge of loads in flight. Logs carry only opaque IDs, region and outcome.
- **Time**: inject `Clock`; never call `Instant.now()`.
- **Ports** (interfaces only; thread-safe in-memory fakes in tests): `EngagementHistoryRepository`, `ProductVersionRepository`, `PendingUpdateRepository`, `SummaryClient`, `EngagementClient`, `DeadLetterPublisher`.
- **No SQL anywhere in this code.** The in-memory fakes are the reference implementation of the port contracts:
  - append is idempotent on `(engagementId, sourceUpdatedAt, eventType)`;
  - the current state is the latest row by `sourceUpdatedAt` plus the max declined version;
  - `hasAnyEntry(engagementId)` reports whether any row exists;
  - `applyIfUnchanged` is a compare-and-set.
- **Config**: validated `@ConfigurationProperties` record (region, permitsPerPod > 0, loadTimeout < visibility timeout).

## Out of scope (ports only; list them in the README)
SQL Server and HTTP adapters, summary-service, the template-publish fan-out (Flow B), the nightly cron, AWS infrastructure, REST endpoints, JPA, Flyway, and `DESIGN.md` (separate task).

## Tests (JUnit 5; no Docker, no AWS, no `Thread.sleep`; use `CountDownLatch`, a mutable `Clock`, fakes)
1. Peak concurrent `load` calls never exceed permits (500 requests, 20 permits).
2. A duplicate `SyncRequest` after success does not call `load` again, and the offer is still there.
3. Transient and permanent failures are isolated: other engagements complete, permanent → DLQ, transient → throws, in-flight returns to 0 (no leaked permit).
4. Out of order: a newer ACCEPTED handled before an older SYNCED → current state = ACCEPTED.
5. Decision rules, parameterized: declined v5 & latest v8 → offer; declined v8 & latest v8 → none; `EmptyDiff` → none and the existing offer deleted; ARCHIVED → none; accepted an older offer while a newer version exists → new offer from the accepted version.
6. A stale evaluation (summary held on a latch while an ACCEPTED for the same engagement completes) does not overwrite the newer result.
7. `summarize` fails after the SYNCED row is written → the redelivery skips the load and creates the offer.
8. Region mismatch → DLQ, no load.
9. While one evaluation is blocked in `summarize`, other loads can still use every permit.

## Process
- Print the plan first (packages `com.caseware.updates.{model,port,handler,adapter,config}`, types, test matrix), then implement milestone by milestone: align `agents/` → Maven wrapper + Spring Boot 4 + Java 25 skeleton → model + ports → handler + listeners → tests → README.
- After each milestone: `./mvnw test -q` green with zero compiler warnings, then a Conventional Commit and `git push -u origin HEAD`.
- Rewrite `README.md` as the short Part 2 README.
  - Open with one paragraph: the brief asks for a "template-publish fan-out worker". In this design the publish fan-out reads engagement versions from the history table and needs no per-engagement call, so the 1-minute, capacity-limited call lives in this handler's sync path, and that's what is built here.
  - Then the key tradeoffs (two listeners and one handler, SQS-based idempotency instead of a claim table, append-only history, compare-and-set writes, per-pod permits as the global cap, virtual threads + semaphore), what's left out, and how to run the tests. Link to `DESIGN.md`.
