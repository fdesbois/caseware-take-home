# AI usage

How AI tools (Claude Code, Devin) were used to build this submission, and where their output was corrected. The git history and the pull requests show the loop: an agent drafts, another agent reviews against `REVIEW.md`, then each finding is triaged by hand (fixed, documented as a trade-off, or rejected with a reason in the thread).

## Files

| File | Role |
|---|---|
| `PROMPT.md` | Prompt that drove Part 1: three architecture options, then `DESIGN.md` (historical) |
| `PART2_PROMPT.md` | First implementation prompt for Part 2 (historical, superseded) |
| `AGENTS.md` | Engineering rules every coding agent reads before changing code |
| `REVIEW.md` | Review checklist for AI and human reviewers, by severity |

## Where AI helped

- Exploring three architectures with back-of-the-envelope math before committing to one.
- Arithmetic (backfill, cost, summary volume), diagrams, and the test scaffolding (latches, barriers, fakes).
- Reviews on every pull request, which caught real bugs: two clock domains in the history ordering, and a redelivered acceptance able to erase a newer offer.
- Arguing back when useful: a single queue for events and sync was proposed and dropped, because live decisions would wait behind 1-minute loads.

## Where its output was corrected or rejected

| AI proposal | Decision | Why |
|---|---|---|
| Two history tables (log + current state) | One append-only table | The latest row wins at read time; no conditional updates |
| A claim table for idempotency before each load | Dropped | The SQS visibility timeout plus "skip if history exists" cost at most one extra load |
| An audit table of every product version | One row per product | Only the latest version is needed |
| A "withdrawn" version state | A withdrawal is a new version | No extra state; an empty diff removes the offer |
| A transactional outbox in engagement-service | Publish after commit + nightly reconciliation | That service's database layout is unknown; the nightly job repairs gaps |
| Offer evaluation on every engagement event | Offers only on publish, plus a one-time product replay | Simpler handler, no summary calls on the event path; rare gaps wait for the next publish |
| RDS SQL Server Standard, Multi-AZ | Express, single-AZ | Licences alone exceeded the assumed $2k budget; the store can be rebuilt |
| Review findings: atomic claim, region passed to every port, summarize timeout | Rejected, with reasons on the PRs | Production hardening beyond the brief, or contradicting a documented trade-off |

## Where AI should not be trusted here

- **Numbers:** prices, capacity (the 80-load budget) and volumes are assumptions until checked; the AI stated list prices confidently.
- **Concurrency claims tested at the wrong level:** a handler-level test passed while the listener configuration still limited throughput.
- **Stale rules:** a reviewer flagged deliberate trade-offs as critical because `AGENTS.md` lagged the design. Rules change in the same PR as the design.
- **Audit-facing text:** LLM summaries are gated by a deterministic validator, stored immutably, and measured against a human-reviewed gold set.

## Guiding other engineers

- Keep the rules (`AGENTS.md`) and the review checklist (`REVIEW.md`) in the repository, and update them with every design change.
- Ask the agent for the plan and the test matrix before code; every concurrency claim needs a deterministic test.
- Run an AI review on every PR, then triage each finding explicitly; never merge on the review alone.
- Keep the arithmetic and assumptions next to any AI-produced number in a design.
