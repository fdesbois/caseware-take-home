# Pending Template Updates: Design

## 1. Scope / Context

**Problem.** An engagement file is created from a product template (zipped JSON) and stays on that version. Each template update has to be accepted or declined, engagement by engagement. Users need to see at a glance which engagements have an update pending, with a human-readable summary.

**Scale.** 4,000 firms · 800,000 active engagements · 40 products · median firm ~40 engagements, largest ~40,000 · ~1 publish per product per week ≈ 173/month.

**Hard constraints**
- Reading an engagement's template version means loading it: **~1 min each**, on a system another team owns with limited capacity.
- The template DB is shared and holds no engagement data. Engagements live in customer DBs, and some firms must keep their data in the **EU or Canada**.
- All systems are live: no maintenance window, and **existing** engagements must be covered.
- Versions branch, get withdrawn, and pile up before a decision. A firm that declined v5 may later be offered v7, which contains v5.
- All-in cost must stay under $X/month, including inference. A summary shown in March must hold up in a review in November.

**Assumptions**
- A1. `product_id` = template + market branch, so each product has one increasing sequence of versions.
- A2. A withdrawal is published as a new version (v8 restores v6's content).
- A3. engagement-service can list engagement IDs without loading them. Each of its events carries the engagement's current template version, not just the change, and never its content.
- A4. The engagement team grants **80 concurrent loads per region**.
- A5. Regions are EU, CA and US.
- A6. Templates are not firm-specific, so one summary serves every firm and region.

**Left out:** applying updates, notifications, admin endpoints, cross-branch moves, per-firm summaries, summary-service failover (an outage only delays offers).

## 2. Goals / Non-Goals

- Every active engagement, new or existing, shows a pending update when it has one, listed per firm.
- At most one offer per engagement, from its applied version to the latest, with a validated summary.
- Correct when messages are duplicated, reordered or lost. A gap is detected, never silent.
- Client data stays in its region; every decision traces to the exact summary shown; the load budget is never exceeded.
- **Non-goals:** offers within seconds of a publish (§5); owning decisions (engagement-service stays the system of record).

## 3. Design

See `Caseware Architecture.drawio.png`

### Architecture

- `*` marks an existing service. Each one adds a single event through a **transactional outbox**: the event is saved in the same transaction as the change it reports.
- **update-service** (one per region) owns version history and offers. **summary-service** (global, no database) owns summaries. The only thing that leaves a region is `(product, from, to)`.

### Data ownership & model

| Data | Owner | Where |
|---|---|---|
| Template versions, JSON diffs | template-service | Global |
| Engagements, decisions (system of record) | engagement-service | Region |
| Summaries | summary-service | Global S3, written once |
| History and offers (derived, can be rebuilt) | update-service | `update-db`, per region |

| `update-db` table | Key | Content |
|---|---|---|
| `product_header_version` | `product_id` | Latest version. Updated only forward. |
| `engagement_history` | `engagement_id, source_updated_at, event_type` | Rows are only ever added: CREATED / ACCEPTED / DECLINED / SYNCED / ARCHIVED, each with `applied_version`, `declined_version`, `summary_key`, `actor_id`. The latest row is the current state. |
| `pending_update` | `engagement_id` | The current offer: `from`, `to`, `summary_key`. Deleted once it no longer applies (decided, archived or `EMPTY_DIFF`). |

**Version rules**
- An update is pending when the engagement isn't archived, `latest > applied`, and `latest > max(declined)`.
- **Pile-up:** if v5 to v7 all land before a decision, there is one offer `v4 → v7`, replaced in place. Replacing an offer doesn't count as declining it.
- **Declined:** the engagement keeps its applied version, and offers stop until a version newer than the declined one arrives. v7 then comes as `v4 → v7`, which includes v5's changes.
- **Withdrawal (A2):** for an engagement on v6, `v6 → v8` is `EMPTY_DIFF`, so the offer is removed and no decision is recorded. For one on v7, `v7 → v8` reads "withdraws v7".

**Correctness**
- **Duplicates:** the history primary key, the forward-only version upsert, and offer writes that are safe to repeat.
- **Order:** the latest `source_updated_at` wins. Sync rows carry the time their load *started*, so a decision made during the 1-minute load wins.
- **Races:** an offer is written only if the history row and product version it was computed from are still current (compare-and-set). Otherwise the newer change re-evaluates it.
- **Loss:** outboxes, alarms on the dead-letter queues (DLQs), and the nightly sync of engagements with no history.

### Summary generation & evaluation
- **One summary per `(product, from, to, promptVersion)`**, shared by every firm and region. Cost follows the number of version pairs (~20 per publish), not engagements. Each summary is written once, so every region shows the same text.
- **Pipeline:** get the JSON diff. If it's empty, return `EMPTY_DIFF` without calling the LLM. Otherwise, Claude on Bedrock (pinned model, versioned prompt, template data only) returns `{text, changeType, diffPaths[]}` for each bullet.
- **Validator (code):** every cited path exists, every changed path is cited, and each change type matches the diff. After two failures, the summary falls back to a labelled plain rendering of the diff, so an offer is never blocked.
- **Audit:** S3 Object Lock keeps the text, bullets, diff hash, model, prompt version and validator result for the audit period. Summaries are never regenerated; a new prompt or model writes new keys. Each decision row keeps `summary_key` and `actor_id`.
- **Evaluation:** offline, a gold set reviewed by the content team and then the pilot firm must pass before any prompt or model change ships. Online, we track the pass rate, the fallback rate and a reviewed weekly sample.

### Key tradeoffs
- **A derived store**, not an extension of engagement-service. We own the build and can see across firms; the cost is a sync and a risk of drift.
- **A code validator**, not an LLM judge. It's free, deterministic and auditable; the gold set covers tone.
- **SQS idempotency** (a visibility timeout, plus skipping the load if history exists), not a claim table. **Two queues**, so decisions never wait behind 1-minute loads.

## 4. Flows

**A. Review, decide, and other engagement events**

```mermaid
sequenceDiagram
  autonumber
  actor U as User
  participant UI as Web UI
  participant UP as update-service
  participant ES as engagement-service
  participant Q as engagement-events
  participant DB as update-db
  U->>UI: open the firm's engagements
  UI->>UP: GET /firms/{firmId}/pending-updates
  UP->>DB: offers by firm_id
  UI->>UP: GET /engagements/{id}/pending-update
  UP-->>UI: offer + summary text (via summary-service)
  U->>UI: accept or decline
  UI->>ES: decision (engagementId, toVersion, summaryKey)
  ES->>ES: process decision + outbox row, same transaction
  ES-->>Q: EngagementStateUpdateEvent (full state)
  Q->>UP: at-least-once delivery
  UP->>DB: append history row (duplicate = no-op)
  UP->>UP: evaluatePending
  UP->>DB: delete or replace the offer (compare-and-set)
```

- CREATED and ARCHIVED events join at step 9. Accepting an older offer while a newer version exists gives a new offer from the accepted version.

**B. Template publish (including withdrawal)**

```mermaid
sequenceDiagram
  autonumber
  participant TS as template-service
  participant SNS as SNS
  participant Q as product-events (per region)
  participant UP as update-service
  participant DB as update-db
  participant SS as summary-service
  participant S3 as summary bucket
  participant LLM as Claude (Bedrock)
  TS->>TS: publish vN + outbox row, same transaction
  TS-->>SNS: ProductVersionUpdateEvent (product, vN, reason)
  SNS-->>Q: fan-out to EU, CA, US
  Q->>UP: deliver
  UP->>DB: forward-only upsert of product_header_version
  UP->>DB: engagements of the product, grouped by applied version
  loop each applied version, largest group first, 8 in parallel
    UP->>SS: GET /summaries/{product}/{from}/{vN}
    alt summary already in the bucket
      SS->>S3: read
    else first request for this pair
      SS->>TS: JSON diff (from, vN)
      SS->>LLM: structured summary, diff paths per bullet
      SS->>SS: validate paths, coverage and change types
      SS->>S3: write-once
    end
    SS-->>UP: summary key, or EMPTY_DIFF
    UP->>DB: upsert the group's offers (delete on EMPTY_DIFF)
  end
```

- A late or duplicate event (v7 arriving after v8) changes nothing.

**C. Nightly sync (backfill and completeness check)**

```mermaid
sequenceDiagram
  autonumber
  participant CJ as nightly CronJob
  participant QS as engagement-sync
  participant ES as engagement-service
  participant DB as update-db
  participant UP as update-service
  CJ->>QS: any message left?
  alt queue not empty
    CJ-->>CJ: skip tonight
  else queue empty
    loop pages of active engagement IDs, no load
      CJ->>ES: list engagement IDs
      CJ->>DB: which IDs have no history row?
      CJ->>QS: SyncRequest per missing ID, at most 100k per run
    end
  end
  QS->>UP: deliver, 20 per pod, 80 per region
  UP->>DB: any history row? then skip the load
  UP->>ES: load engagement (~1 min, semaphore)
  UP->>DB: append SYNCED (source_updated_at = load start)
  UP->>UP: evaluatePending, as in flow A
```

- The first runs are the backfill; later runs check completeness (`sync.missing` should be 0).
- 100k is about one day of loads, so each run starts with an empty queue. An engagement stuck in the DLQ still has no history, so the next run queues it again.

## 5. Risks

**Challenged requirement: "within seconds of a template being published."** Instead, an offer appears with its validated summary, within 5 minutes at p95. Updates are weekly and decisions take days; an unexplained offer can't be decided or defended, and seconds would mean showing unvalidated text.

**Riskiest component: whether `engagement_history` is complete.** A missing or stale row silently shows "no update". Users can't see it, and it's the worst thing an audit could find. Defences: the Correctness rules, events that carry the full state, the `sync.missing` alert, and spot-checks during the pilot.

| Failure | Mitigation |
|---|---|
| Sync loads slow down engagement-service | A hard cap (§7) and at most 100k per night. To pause, scale the sync listener to 0. |
| The LLM or summary-service is down, or output fails validation | SQS retries, then the plain rendering of the diff. Offers arrive late at worst, never wrong, and nothing is lost. |
| A message for the wrong region, or one that keeps failing | Sent to the DLQ after 5 attempts, with an alarm. Engagements still missing are queued again by the next sync. |

## 6. Metrics

| Quantity | Arithmetic | Result |
|---|---|---|
| Backfill | 800k × 1 min ÷ 80 | ≈ 7 days (~8 nightly runs) |
| Backfill compute | 800k × ~$0.001 per load (1 vCPU·min + 4 GB·min) | ≈ $0.8k one-time |
| Publish fan-out | ~20 version groups (max 52), 8 summaries in parallel × ~30 s | 1.5–3.5 min |
| Summaries | 173 publishes × 20–52 pairs | 3.5k–9k / month |
| LLM | 20k input + 1.5k output tokens at $4 / $20 per million ≈ $0.11 | $0.4–1.0k / month (≈ $390k if summarised per engagement) |
| Storage | 800k × ~10 rows/yr × 150 B; 9k × 100 KB/month | ≈ 1.2 GB/yr in RDS; ≈ 1 GB/month in S3 |
| Messaging | Events and sync requests | < 1 msg/s, < $10 / month |

**Run cost ≈ $5–6k/month:** 3 × RDS SQL Server Multi-AZ ≈ $4.3k (mostly licences), LLM $0.4–1.0k, EKS ≈ $0.3k, SQS/SNS/S3 < $0.1k. If $X is tight, cut the database tier (Web or Express edition, or a shared instance) before the model. 

| SLO / alert | Target |
|---|---|
| Publish → offer visible | p95 ≤ 5 min |
| Decision → offer updated | p99 ≤ 1 min (age of the oldest queued message) |
| Pending-list API | p99 ≤ 300 ms |
| Completeness | Alert if `sync.missing` > 0 after the backfill |
| engagement-service | In-flight loads ≤ the cap; alert if more than 5% of loads fail |
| Summaries | Validator pass rate ≥ 95%, fallback ≤ 5%, weekly sample ≥ 95% approved |

Alerts also fire on any DLQ message and on the daily LLM budget. Logs and metrics carry only opaque IDs, the region and the outcome.

## 7. Config

| Parameter | Value |
|---|---|
| Region | EU / CA / US; messages for other regions go to the DLQ |
| Sync permits per pod × pods | 20 × 4 = 80 (A4) |
| Load timeout / visibility timeout | 120 s / 300 s |
| Sync cap / schedule | 100k per run / 02:00 local time, runs never overlap |
| SQS `maxReceiveCount` | 5, then the DLQ |
| Summary parallelism / validator retries | 8 per publish / 2 |
| Model ID / prompt version | Pinned / versioned; changing either writes new keys |
| Flags | `pending-updates.ui` per firm; emitters per region |

## 8. Rollout

1. **Deploy, hidden.** All stacks go live with the UI flag off, versions seeded, and emitters turned on region by region. Summaries are generated but not shown, to build the gold set.
2. **Turn on the nightly sync.** Its first runs are the backfill, and offers appear as engagements sync. This step is done when `sync.missing` = 0.
3. **Pilot firm** (one per region if possible). Turn the UI on for 2–3 publishes; the firm's auditors check the wording.
4. **Everyone.** Turn the UI on for all firms.

- **No downtime:** every change is additive. Rollback means turning the UI flag off, and update-db can be rebuilt by the sync.
- **Evolution:** versioned events only gain fields, prompt and model changes must pass the gold set, and a new region is one more regional stack.

## 9. Rejected

- **Re-check every engagement on every publish.** 3.46M loads a month needs at least 80 loads in parallel forever and ~4 h per publish, which breaks the capacity limit and freshness. Stacked per-version summaries also misstate the net change.
- **engagement-service stores the version in each customer DB.** Strongest consistency, but the capacity-limited engagement team would build it, ~4,000 live databases would need schema changes, and there would be no view across firms.
