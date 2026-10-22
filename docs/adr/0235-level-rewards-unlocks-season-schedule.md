---
status: Accepted
date: 2026-10-23
deciders: cx1
supersedes: null
superseded_by: null
---

# ADR-0235: Permanent level rewards and scheduled season rollover

## Context

ADR-0210 connects lifetime XP but level changes grant no rewards. Competitive
season reset exists without a timer. Stats cannot depend on Economy (ADR-0022).

## Decision

- `content/progression/level-rewards.yaml` maps account levels to STAR, scarce
  GOLD, and title/cosmetic/optional item entitlement IDs. GEM is never granted.
  Entitlements are permanent `title:`, `cosmetic:` or `item:` IDs, queryable through
  Core's `IPlayerUnlockView` and visible on the existing lobby profile card.
  Item unlocks record ownership; they do not create physical inventory items.
- Stats reconciles eligible levels on every XP grant and player join. V65 stores
  unique `(player_id, level)` claims and `(player_id, unlock_id)` entitlements.
  The Postgres reward repository credits the existing Postgres currency ledger
  inside the same database transaction as the claim and unlocks. In-memory uses
  the Core ledger port with a lazy provider, since Economy enables after Stats.
  Reconciliation recovers XP committed before a crash without paying twice.
- Chat, title and CurrencyChanged notifications follow commit. They are best
  effort: a crash can omit a notification, never duplicate wallet credit.
- `seasons.yaml` holds stable window IDs and ordered, non-overlapping UTC
  start/end instants plus the next season label. On LOBBY/ALL, a scheduled check
  runs one tick after boot and every minute. Each overdue window invokes
  `ResetSeasonUseCase.executeScheduled`; V65 records its key in the same
  transaction as closing/opening seasons. A database advisory transaction lock
  serializes competing lobby instances and manual resets. Repeated checks or
  restarts do not reset twice; missed windows catch up chronologically.
- Reset retains existing semantics: a new competitive leaderboard partition,
  preserving old standings, lifetime XP, wallets and permanent unlocks.

## Consequences

Postgres is required for durability across process restarts, as with XP itself.
Changing a claimed tier does not pay the difference; changing a window's stable
ID creates a new reset. Invalid progression content fails installation instead
of silently disabling progression. Seasonal announcements go to online players
on the lobby backend which performed the reset; chat/HUD are not a durable outbox.
