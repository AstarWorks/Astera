---
status: Accepted
date: 2026-06-27
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0037: Wave 7b — Tournament bracket + competitive Elo rating + per-season leaderboard

## Status

Accepted

## Context

Wave 7b is the Tier 8-2 step of the Phase 3 Siege critical path
([[IMPLEMENTATION-ROADMAP]]): after the Siege content authoring foundation
(Wave 7a, [[adr/0036-siege-content-authoring]]) the server needs the
*competitive* layer — bracketed tournaments, skill-balanced ranked
matchmaking, and a ranking players can chase — to make Siege "playable as a
competition" (~75% RTM parity).

Constraints from the existing codebase:

- **ADR-0089** already committed that rating-based queueing is *additive on
  `QueueEntry`*, **not** a new `MatchmakingMode` variant. `InMemoryMatchmaker`
  carries a standing TODO ("Real per-mode sizing arrives in Wave 7b") and the
  `MatchmakingMode.Tournament` lane already exists.
- **ADR-0022** sibling-isolation: `stats ← core, items, combat, match` but
  `match ⇏ stats`. So rating (read by matchmaking, in `match`) and the
  leaderboard (a ranking view, in `stats`) must live in different plugins and
  communicate through a Core event, not a direct dependency.
- **Pre-release posture**: prefer the clean long-term shape; no compat shims.

## Decision

Ship the competitive layer as **three vertical slices across the existing
sibling-isolation matrix**, joined by one new Core event:

1. **Tournament (plugin:match)** — pure single-elimination domain
   (`Tournament` / `Round` / `BracketMatch`, `BracketAdvancer` seeds the
   bracket with byes for non-power-of-two fields and advances winners), an
   `ITournamentRepository` port (in-memory default + Postgres V46 JSONB
   overlay), and `CreateTournamentUseCase` / `ReportMatchResultUseCase`
   publishing `TournamentStarted` / `TournamentRoundAdvanced` /
   `TournamentConcluded`.
2. **Ranked rating (plugin:match)** — a bounded `Rating` value object + textbook
   Elo (`expected = 1/(1+10^((opp−self)/400))`, `new = old + K·(actual−expected)`,
   K=32) scored against the opposing team's average; `IPlayerRatingRepository`
   (in-memory + Postgres V47). `QueueEntry` gains an additive `rating: Rating?`
   and `InMemoryMatchmaker` gains a `ratingLookup` + per-mode `MatchSizeSpec`
   (1v1 / 2v2 / 3v3) and snake-draft team balancing — honoring ADR-0089 (no
   `Ranked` mode variant). `UpdateRatingOnMatchEndedUseCase` recomputes Elo on
   `MatchEnded` and publishes the new Core event **`PlayerRatingUpdated`**
   (rating carried as plain `Int`, keeping Core free of Match's `Rating` type).
3. **Leaderboard + Season (plugin:stats)** — `LeaderboardEntry` / `Season`
   domain, `ILeaderboardRepository` + `ISeasonRepository` (in-memory seeds a
   default `season-1`; Postgres V48 `seasons` + `leaderboard_rankings`).
   `RecordRatingOnPlayerRatingUpdatedListener` materialises the per-season
   ranking from `PlayerRatingUpdated`; `QueryLeaderboardUseCase` /
   `ResetSeasonUseCase` + the `/astera-leaderboard [top <n>]` command expose it.

The event boundary (`PlayerRatingUpdated`, Int rating) is what lets `match`
own rating while `stats` owns the leaderboard without violating ADR-0022.

## Consequences

### Positive
- Phase 3 Siege gains the full competitive loop (bracket → ranked match →
  Elo update → leaderboard) end-to-end, persisted across restarts.
- `match`/`stats` stay isolated; the only coupling is one additive Core event.
- ADR-0089's additive-rating commitment is fulfilled with zero `MatchmakingMode`
  churn; all prior `InMemoryMatchmaker` tests stay green (defaults preserve the
  old 2×1 sizing).

### Negative / Trade-offs
- Two rating stores of record: `player_ratings` (global, for matchmaking) vs
  `leaderboard_rankings` (per-season snapshot). A season reset starts a fresh
  ranking but does **not** reset global matchmaking rating — intentional, but a
  second source of truth to keep in mind.
- Tournament has **no in-game admin command surface yet** — the domain,
  persistence, events, and use cases ship and are registered/tested, but
  creating/reporting a bracket in-game is deferred to Wave 7c content tooling.
- Elo is single-K and team-average-based — simpler than Glicko2/TrueSkill;
  adequate for Phase 3, revisitable later (ADR-0089 named Glicko2 as a future).

### Mitigations
- The `IPlayerRatingRepository` / `IRatedMatchmaker`-style seams are additive, so
  a Glicko2 swap is a priority-100 override, not a rewrite.
- The deferred tournament command is captured in the Wave 7b handoff + RTM so it
  is not lost; the registered use cases make the command a thin adapter.

## Alternatives Considered

- **`MatchmakingMode.Ranked` variant** — rejected: contradicts ADR-0089's
  explicit "additive on QueueEntry, not a new variant" commitment and would
  force an exhaustive-`when` churn across the matchmaker.
- **Leaderboard in `match`** — rejected: leaderboard is a stats/ranking concern
  and `match` is the deeper plugin; putting it in `stats` (which already
  subscribes to match events for W/L) keeps the dependency direction legal.
- **Single combined ratings table** — rejected: matchmaking wants one global
  current rating; the leaderboard wants per-season history. Splitting the two
  keeps each query simple.

## References

- 関連 ADR: [[adr/0089-server-lifecycle-restic-backup-lobby-hud-matchmaker]]
  (additive rating commitment), [[adr/0036-siege-content-authoring]] (Wave 7a),
  [[adr/0022-multi-plugin-architecture]] (sibling isolation),
  [[adr/0058-universal-attribute-container]] (PlayerStats facade)
- 関連 docs: [[IMPLEMENTATION-ROADMAP]] (Tier 8-2 / Wave 7b),
  [[RTM-PARITY-TRACKER]] (Category 2 tournament + leaderboard rows)
