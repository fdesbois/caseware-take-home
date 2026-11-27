# Pending Template Updates: Design

## 1. Scope / Context

**Problem.** An engagement file is created from a product template (zipped JSON) and stays on that version. Users accept or decline each update per engagement, so they need to see at a glance which engagements have one pending, with a human-readable summary.

**Scale.** 4,000 firms · 800,000 active engagements · 40 products · median firm ~40 engagements, largest ~40,000 · ~1 publish per product per week ≈ 173/month.

**Hard constraints**
- Reading an engagement's template version means loading it: **~1 min each**, on a system another team owns with limited capacity.
- The template DB is shared and has no engagement data; engagements live in customer DBs, some required to stay in the **EU or Canada**.
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

**Delivery approach**
- **History first, offers last:** history is complete (events, then backfill) before any offer exists.
- **Pilot early:** one firm (ideally per region) gets the UI before the first offer; its auditors later judge the wording.
- **Gold set from the product replay:** offers start with a replay of each product's latest version; the content team reviews its summaries into a **gold set** every prompt or model change must pass.

## 3. Design

### Architecture

![Architecture](Caseware%20Architecture.drawio.png)

- Existing services (`*`) publish their event right after each commit, with retries; lost events are covered under Correctness.
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
| `product_header_version` | `product_id` | Latest version, updated only forward; filled by the product replay at go-live. |
| `engagement_history` | `engagement_id, source_updated_at, event_type` | Rows are only ever added: CREATED / ACCEPTED / DECLINED / SYNCED / ARCHIVED, each with `applied_version`, `declined_version`, `summary_key`, `actor_id`. The latest row is the current state. |
| `pending_update` | `engagement_id` | The current offer: `from`, `to`, `summary_key`. Written only by flow B; removed by a decision, an archive or an `EMPTY_DIFF`. |

**Version rules**
- An update is pending when the engagement isn't archived, `latest > applied`, and `latest > max(declined)`.
- **Pile-up:** v5 to v7 landing before a decision give one offer `v4 → v7`, replaced in place; replacing is not declining.
- **Declined:** the engagement keeps its applied version, and offers stop until a version newer than the declined one arrives. v7 then comes as `v4 → v7`, which includes v5's changes.
- **Withdrawal (A2):** on v6, `v6 → v8` is `EMPTY_DIFF`: the offer is removed, no decision recorded. On v7, `v7 → v8` reads "withdraws v7".

**Correctness**
- **Duplicates:** the history primary key, the forward-only version upsert, and offer writes that are safe to repeat.
- **Order:** the latest `source_updated_at` wins. Sync rows carry engagement-service's read time (its own clock), so a decision made during the 1-minute load wins.
- **Races:** flow B writes each offer with a compare-and-set on the engagement's history; if a decision landed meanwhile, it recomputes that engagement.
- **Loss:** events are sent after commit with retries (no outbox). The nightly job re-queues engagements without history and any missed publish; a lost decision is re-emitted when the user acts on the stale offer (idempotent decision endpoint); dead-letter queues (DLQs) have alarms.

### Summary generation & evaluation
- **One summary per `(product, from, to, promptVersion)`**, shared by every firm and region. Cost follows the number of version pairs (~20 per publish), not engagements. Each summary is written once, so every region shows the same text.
- **Pipeline:** get the JSON diff. If it's empty, return `EMPTY_DIFF` without calling the LLM. Otherwise, Claude Sonnet on Bedrock (pinned model, versioned prompt, template data only) returns `{text, changeType, diffPaths[]}` for each bullet.
- **Validator (code, not an LLM judge: free, deterministic, auditable):** every cited path exists, every changed path is cited, and each change type matches the diff. After two failures, the summary falls back to a labelled plain rendering of the diff, so an offer is never blocked.
- **Audit:** S3 Object Lock keeps text, bullets, diff hash, model, prompt version and validator result for the audit period; never regenerated (a new prompt or model writes new keys). Decision rows keep `summary_key` and `actor_id`.
- **Evaluation:** offline, the gold set (§2) must pass before any prompt or model change ships. Online, we track the pass rate, the fallback rate and a reviewed weekly sample.

### Key tradeoffs
- **Offers only on publish:** events and syncs only record history; decisions remove obsolete offers. Rare gaps (an engagement created mid-publish or recovered by the nightly sync, an older offer accepted) wait for the next publish.
- **A derived store**, not an extension of engagement-service: we own the build and see across firms, at the cost of a sync and drift risk.
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
  ES->>ES: commit the decision
  ES-->>Q: EngagementStateUpdateEvent (current version), retried
  Q->>UP: at-least-once delivery
  UP->>DB: append history row (duplicate = no-op)
  UP->>DB: remove the offer the decision made obsolete
```

- CREATED and ARCHIVED join at step 9 (ARCHIVED removes the offer). Decisions remove only offers they make obsolete, so a redelivered one can't erase a newer offer.

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
  participant LLM as Claude Sonnet (Bedrock)
  TS->>TS: commit vN
  TS-->>SNS: ProductVersionUpdateEvent (product, vN, reason), retried
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
    UP->>DB: upsert the group's offers, compare-and-set (delete on EMPTY_DIFF)
  end
```

- A late or duplicate event (v7 arriving after v8) changes nothing.

**C. Nightly sync (backfill and completeness check)**

```mermaid
sequenceDiagram
  autonumber
  participant CJ as nightly CronJob
  participant TS as template-service
  participant QP as product-events
  participant QS as engagement-sync
  participant ES as engagement-service
  participant DB as update-db
  participant UP as update-service
  CJ->>TS: latest version of each product (~40)
  CJ->>DB: compare with product_header_version
  opt a publish was missed
    CJ->>QP: re-queue ProductVersionUpdateEvent (flow B)
  end
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
  UP->>DB: append SYNCED (source_updated_at = engagement-service read time)
```

- First runs = backfill, then a completeness check (`sync.missing` should be 0). 100k ≈ one day of loads; a DLQ'd engagement is still missing, so the next run re-queues it.
- The product check starts at rollout step 4; its first run, on an empty `product_header_version`, is the product backfill.

## 5. Risks

**Challenged requirement: "within seconds of a template being published."** Instead, an offer appears with its validated summary, within 5 minutes at p95. Updates are weekly and decisions take days; an unexplained offer can't be decided or defended, and seconds would mean showing unvalidated text.

**Riskiest component: completeness of `engagement_history`.** A missing or outdated row hides a pending update without any visible error. Mitigations: the Correctness rules (§3), version-carrying events, the `sync.missing` alert, and pilot spot-checks.

| Failure | Mitigation |
|---|---|
| Sync loads slow down engagement-service | A hard cap (§7) and at most 100k per night. To pause, scale the sync listener to 0. |
| The LLM or summary-service is down, or output fails validation | SQS retries, then the plain rendering of the diff. Offers arrive late at worst, never wrong, and nothing is lost. |
| A message for the wrong region, or one that keeps failing | Sent to the DLQ after 5 attempts, with an alarm. Engagements still missing are queued again by the next sync. |

## 6. Metrics

| Quantity | Arithmetic | Result |
|---|---|---|
| Backfill | 800k × 1 min ÷ 80 | ≈ 7 days (~8 nightly runs) |
| One-time | 800k loads × ~$0.001; replay 40 × 20–52 summaries × $0.055 | ≈ $0.8k + $0.1k |
| Publish fan-out | ~20 version groups (max 52), 8 summaries in parallel × ~30 s | 1.5–3.5 min |
| Summaries | 173 publishes × distinct applied versions (≤ 52) | ≤ 9k / month, likely 0.5k–3.5k |
| LLM (Sonnet) | 20k input + 1.5k output tokens at $2 / $10 per million ≈ $0.055 | ≤ $0.5k / month (≈ $190k if summarised per engagement) |
| Storage | 800k × ~10 rows/yr × 150 B; 9k × 100 KB/month | ≈ 1.2 GB/yr in RDS; ≈ 1 GB/month in S3 |

**Run cost ≈ $0.7–1.2k/month (assumed $X = $2k):** `update-db` on RDS SQL Server Express, single-AZ, per region ≈ $0.1–0.25k (no licence; fits 10 GB for years), LLM ≤ $0.5k, EKS ≈ $0.3k, SQS/SNS/S3 < $0.1k. Multi-AZ Standard would cost ≈ $4.3k in licences; `update-db` is rebuildable, so recovery is a point-in-time restore. 

| SLO / alert | Target |
|---|---|
| Publish → offer visible | p95 ≤ 5 min |
| Decision → offer removed | p99 ≤ 1 min (oldest message age) |
| Pending-list API | p99 ≤ 300 ms |
| Completeness | Alert if `sync.missing` > 0 after the backfill |
| engagement-service | In-flight loads ≤ the cap; alert if more than 5% of loads fail |
| Summaries | Validator pass rate ≥ 95%, fallback ≤ 5%, weekly sample ≥ 95% approved |

Plus alarms on any DLQ message and the daily LLM budget. Logs and metrics carry only opaque IDs, region and outcome.

## 7. Config

| Parameter | Value |
|---|---|
| Region | EU / CA / US; messages for other regions go to the DLQ |
| Sync permits per pod × pods | 20 × 4 = 80 (A4) |
| Load timeout / visibility timeout / `maxReceiveCount` | 120 s / 300 s / 5, then the DLQ |
| Sync cap / schedule | 100k per run / 02:00 local time, runs never overlap |
| Summary parallelism / validator retries / model and prompt | 8 per publish / 2 / pinned and versioned (a change writes new keys) |
| Flags | `pending-updates.ui` per firm; emitters per region |

## 8. Rollout

1. **Engagement events.** Deploy all stacks (UI off), then turn on engagement events, region by region.
2. **Engagement sync.** Turn on the nightly sync; its first runs are the backfill. Done when `sync.missing` = 0.
3. **Pilot firm.** UI on for one firm, with no offers yet.
4. **Product backfill.** Turn on the `product-events` consumer and the product check; its first run replays each product's latest version into flow B, so every engagement gets its offer and the gold set gets its summaries.
5. **Validate and open.** Once the pilot signs off on the offers and the gold set is reviewed, UI on for all firms.

- Every change is additive (no downtime); rollback is the UI flag; update-db can be rebuilt by the sync and the replay. Events only gain fields; a new region is one more stack.

## 9. Rejected

- **Re-check every engagement on every publish:** 3.46M loads/month, ≥ 80 in parallel forever and ~4 h per publish; per-version summaries also misstate the net change.
- **engagement-service stores the version per customer DB:** strongest consistency, but the capacity-limited team builds it, ~4,000 live DBs need schema changes, and there's no cross-firm view.
