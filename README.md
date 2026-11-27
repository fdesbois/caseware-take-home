# update-service: engagement message handler (Part 2)

The brief asks for a "template-publish fan-out worker". In this design ([DESIGN.md](DESIGN.md)) the publish fan-out reads engagement versions from the history table and needs no per-engagement call. The 1-minute, capacity-limited call to engagement-service therefore lives in this handler's sync path, and that's what is built here. The handler keeps each engagement's pending update current from two SQS queues, one deployment per region, without exceeding the load budget.

## How it works

| Queue | Message | Handling |
|---|---|---|
| `engagement-events` | `EngagementStateUpdateEvent`, with the current template version | Append the history row (a duplicate key is a no-op), then `evaluatePending`. |
| `engagement-sync` | `SyncRequest`, queued nightly for engagements with no history | Skip the load if any history row exists. Otherwise take a permit, load (~1 min), release, and append a SYNCED row stamped with engagement-service's read time. Then always `evaluatePending`. |

`evaluatePending` reads the current state and the product's latest version, then applies the pure `OfferRules`:
- **No offer** when there is no state, the engagement is ARCHIVED, `latest ≤ applied` or `latest ≤ max(declined)`.
- **Otherwise** it summarizes `applied → latest`: `EmptyDiff` means no offer, `Ready` means an offer.

The result is written with a compare-and-set. A message for another region goes to the DLQ before any load or write.

**Observability:** a counter `updates.handler.outcomes{outcome}` (`loaded`, `skipped`, `transient`, `permanent`, `stale_write_skipped`, `error`) and a gauge `updates.handler.loads.in.flight`. Logs carry only opaque IDs, the region and the outcome.

## Key tradeoffs

- **Two listeners, one handler.** Decisions never wait behind 1-minute loads, and the rules exist once. A sealed `EngagementMessage` and a pattern-matching `switch` keep the dispatch exhaustive.
- **SQS-based idempotency, not a claim table.** Two things absorb duplicates: a visibility timeout (300 s) longer than the load timeout (120 s), and skipping the load when any history row exists. A rare concurrent duplicate costs one extra load, never a wrong result. A claim table would add a write per message and a cleanup job just to save that load.
- **Append-only history.** Late or reordered messages need no special case: the current state is the latest row by `source_updated_at`, plus the max declined version. A SYNCED row carries engagement-service's read time, on the same clock as the events, so a decision made during the load wins even if this pod's clock runs ahead. The rows are also the audit trail (summary key, actor).
- **Compare-and-set writes.** `applyIfUnchanged` writes only if the state and latest version the decision used are still current. Otherwise it skips and counts the write, because the change that moved them runs its own evaluation. The basis is the whole state, not only `source_updated_at`: a late, older DECLINED can raise the max declined version, and two rows can share an instant.
- **Per-pod permits as the global cap.** 4 pods × 20 permits = the 80 concurrent loads engagement-service allows. There is no distributed limiter to run or to fail, but the replica count is fixed: adding pods means lowering `permits-per-pod`, and deploys must not surge (`maxSurge: 0`), or a fifth pod would allow 100 loads.
- **Virtual threads + a semaphore.** Listeners run on virtual threads, so a minute-long load parks cheaply. Spring Cloud AWS needs an explicit executor for this; it ignores `spring.threads.virtual.enabled`. A fair `Semaphore` wraps `load` and nothing else, so the 30–60 s `summarize` never holds a permit. The sync listener takes at most one message per permit, so a message waiting on a slow summary keeps its slot; during the backfill most summaries are already cached, and the in-flight limit can be raised if it runs slow. The events listener allows 50 messages in flight, set explicitly.
- **Failures stay per engagement.** Transient load errors (timeout, 429, 5xx) throw, so SQS redelivers them and the queue's DLQ takes over after `maxReceiveCount`. Permanent ones (404, malformed) go straight to the DLQ. There is no backoff: a sync request that reaches the DLQ still has no history, so the next nightly run queues it again; the events DLQ needs an alarm and a manual redrive.

## Layout

```text
src/main/java/com/caseware/updates/
├── model/    records and sealed types: messages, LoadResult, SummaryResult, PendingDecision, table rows
├── port/     the six ports; their Javadoc is the contract (no SQL in this repository)
├── handler/  EngagementMessageHandler, OfferRules (pure), TransientLoadException
├── adapter/  EngagementQueueListeners: two one-line @SqsListener methods
└── config/   UpdateServiceProperties (validated at startup) and the wiring
```

## Left out

These exist as ports only: the SQL Server and HTTP adapters, summary-service, the template-publish fan-out (Flow B), the nightly cron, AWS infrastructure, REST endpoints, JPA and Flyway. Without the adapters the service does not start on its own. The in-memory fakes in `src/test` are the reference implementation of the port contracts.

## Run the tests

Requires Java 25; the wrapper fetches Maven.

```bash
./mvnw test -q
```

The tests need no Docker, no AWS and no sleeps: they use latches, barriers, a mutable `Clock` and thread-safe fakes. Any compiler warning fails the build (`-Xlint:all -Werror`). Test logs go to `target/surefire-reports/*-output.txt`.

| # | Invariant | Test |
|---|---|---|
| 1 | Peak loads never exceed the permits (500 requests, 20 permits) | `EngagementMessageHandlerTest` |
| 2 | A duplicate `SyncRequest` doesn't load again; the offer stays | `EngagementMessageHandlerTest` |
| 3 | Transient and permanent failures stay isolated; no permit leaks | `EngagementMessageHandlerTest` |
| 4 | A newer ACCEPTED beats an older SYNCED, even with the pod clock ahead | `EngagementMessageHandlerTest` |
| 5 | Decision rules (parameterized); `EmptyDiff` deletes the offer | `OfferRulesTest`, `EngagementMessageHandlerTest` |
| 6 | A stale evaluation doesn't overwrite the newer result | `EngagementMessageHandlerTest` |
| 7 | After a failed `summarize`, the redelivery skips the load | `EngagementMessageHandlerTest` |
| 8 | A region mismatch goes to the DLQ with no load | `EngagementMessageHandlerTest` |
| 9 | `summarize` holds no permit | `EngagementMessageHandlerTest` |
| + | Port contracts, config validation, listener wiring | `InMemoryUpdateDatabaseTest`, `UpdateServicePropertiesTest`, `SqsListenerWiringTest` |
