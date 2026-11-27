# update-service: engagement message handler (Part 2)

The brief asks for a "template-publish fan-out worker". In this design ([DESIGN.md](DESIGN.md)) the publish fan-out reads engagement versions from the history table and needs no per-engagement call. The 1-minute, capacity-limited call to engagement-service therefore lives in this handler's sync path, and that's what is built here. The handler keeps each region's `engagement_history` complete from two SQS queues, one deployment per region, without exceeding the load budget. Offers and summaries are created only by the publish fan-out, which reads that history.

## How it works

| Queue | Message | Handling |
|---|---|---|
| `engagement-events` | `EngagementStateUpdateEvent`, with the current template version | Append the history row (a duplicate key is a no-op). ACCEPTED and ARCHIVED remove the engagement's offer; DECLINED removes it only if it targets the declined version or an older one. |
| `engagement-sync` | `SyncRequest`, queued nightly for engagements with no history | Skip the load if any history row exists. Otherwise take a permit, load (~1 min), release, and append a SYNCED row stamped with engagement-service's read time. |

A message for another region goes to the DLQ before any load or write.

**Observability:** a counter `updates.handler.outcomes{outcome}` (`loaded`, `skipped`, `transient`, `permanent`, `error`) and a gauge `updates.handler.loads.in.flight`. Logs carry only opaque IDs, the region and the outcome.

## Key tradeoffs

- **Offers only on publish.** Events and syncs never call summary-service: they only record history, and a decision removes the offer it made obsolete. Two rare cases wait for the product's next publish: an engagement created at the moment its product publishes, and a user accepting an older offer while a newer version exists.
- **Two listeners, one handler.** Decisions never wait behind 1-minute loads, and the rules exist once. A sealed `EngagementMessage` and a pattern-matching `switch` keep the dispatch exhaustive.
- **SQS-based idempotency, not a claim table.** Two things absorb duplicates: a visibility timeout (300 s) longer than the load timeout (120 s), and skipping the load when any history row exists. A rare concurrent duplicate costs one extra load, never a wrong result. A claim table would add a write per message and a cleanup job just to save that load.
- **Append-only history.** Late or reordered messages need no special case: the current state is the latest row by `source_updated_at`, plus the max declined version. A SYNCED row carries engagement-service's read time, on the same clock as the events, so a decision made during the load wins even if this pod's clock runs ahead. The rows are also the audit trail (summary key, actor).
- **Per-pod permits as the global cap.** 4 pods × 20 permits = the 80 concurrent loads engagement-service allows. There is no distributed limiter to run or to fail, but the replica count is fixed: adding pods means lowering `permits-per-pod`, and deploys must not surge (`maxSurge: 0`), or a fifth pod would allow 100 loads.
- **Virtual threads + a semaphore.** Listeners run on virtual threads, so a minute-long load parks cheaply. Spring Cloud AWS needs an explicit executor for this; it ignores `spring.threads.virtual.enabled`. A fair `Semaphore` wraps `load` and nothing else. The sync listener takes at most one message per permit; the events listener allows 50 messages in flight, set explicitly.
- **Failures stay per engagement.** Transient load errors (timeout, 429, 5xx) throw, so SQS redelivers them and the queue's DLQ takes over after `maxReceiveCount`. Permanent ones (404, malformed) go straight to the DLQ. There is no backoff: a sync request that reaches the DLQ still has no history, so the next nightly run queues it again; the events DLQ needs an alarm and a manual redrive.

## Layout

```text
src/main/java/com/caseware/updates/
├── model/    records and sealed types: messages, LoadResult, table rows
├── port/     the four ports; their Javadoc is the contract (no SQL in this repository)
├── handler/  EngagementMessageHandler, TransientLoadException
├── adapter/  EngagementQueueListeners: two one-line @SqsListener methods
└── config/   UpdateServiceProperties (validated at startup) and the wiring
```

## Left out

These exist as ports or in the design only: the SQL Server and HTTP adapters, summary-service, the template-publish fan-out (Flow B, which creates offers and summaries), the nightly cron, AWS infrastructure, REST endpoints, JPA and Flyway. Without the adapters the service does not start on its own. The in-memory fakes in `src/test` are the reference implementation of the port contracts.

## Run the tests

Requires Java 25; the wrapper fetches Maven.

```bash
./mvnw test -q
```

The tests need no Docker, no AWS and no sleeps: they use latches, barriers, a mutable `Clock` and thread-safe fakes. Any compiler warning fails the build (`-Xlint:all -Werror`). Test logs go to `target/surefire-reports/*-output.txt`.

| # | Invariant | Test |
|---|---|---|
| 1 | Peak loads never exceed the permits (500 requests, 20 permits) | `EngagementMessageHandlerTest` |
| 2 | A duplicate `SyncRequest` doesn't load again | `EngagementMessageHandlerTest` |
| 3 | Transient and permanent failures stay isolated; no permit leaks | `EngagementMessageHandlerTest` |
| 4 | A decision made during the load beats the SYNCED snapshot (engagement-service's clock) | `EngagementMessageHandlerTest` |
| 5 | A decision or an archive removes only the offer it made obsolete | `EngagementMessageHandlerTest` |
| 6 | A region mismatch goes to the DLQ with no load or write | `EngagementMessageHandlerTest` |
| + | Port contracts, config validation, listener wiring | `InMemoryUpdateDatabaseTest`, `UpdateServicePropertiesTest`, `SqsListenerWiringTest` |
