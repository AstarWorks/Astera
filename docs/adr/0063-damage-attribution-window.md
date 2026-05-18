---
status: Draft
date: 2026-05-19
deciders: orchestrator (Wave 5a-fix2)
supersedes: null
superseded_by: null
---

# ADR-0063: Damage Attribution Window

## Status

Draft

## Context

Wave 5a (`ca6ddab`) landed the SkillCast dispatcher, Armor domain, and the
DamageRule pipeline (`HeadshotModifier` → `ArmorReductionModifier` →
`CriticalStrikeModifier` → `KnockbackModifier`). The damage path is
canonical: `ApplyDamageUseCase` is the **single entry point** for all
damage (ADR-0030 §1), and it publishes rich `DamageDealt` / `PlayerKilled`
events.

What it does **not** publish is **assist credit**. The 2026-05-19 deep
RTM audit (ADR-0029 §"Deep audit") surfaced three combat-side gaps:

1. RTM's `DamageHistoryService` (`core/impl/.../game/damage/`) — a
   time-windowed attacker aggregator that records every damage hit per
   target and can answer "who damaged this player in the last N ticks"
   — has no Astera analogue. `DeathAttributionUseCase` does not exist
   (grep ゼロ).
2. RTM's `IGamePlayer.ITeamGamePlayer.assist: Int` counter has no
   corresponding event/listener path in Astera, even though
   `PlayerStats.assists: Long` and the SQL `assists` column already
   exist (Wave 3 V1 included them; they have just never been
   incremented).
3. (Out of scope for this ADR but in the same wave: `SkillTrigger`
   enum saturation 6 → 20 to match RTM's `model/skill/SkillTrigger.kt`.
   Pure vocabulary; no architecture impact.)

The Bukkit `EntityDamageEvent` is **not** an option for attribution
(ADR-0030 §8 commits Wave 6 to cancel and re-route all vanilla damage
events). The attribution primitive must live in the Astera combat
domain itself.

Cumulative attribution (`Map<PlayerId, Double>` per target) is rejected
because it has no expiry — a player who hit the victim once five
minutes ago would still count as an assister. The RTM
`ConfiguredDamageHistoryParameter` shape (sliding window in ticks) is
the right primitive.

A natural future home is the Universal Attribute Container (ADR-0058):
in Wave 5e, `PlayerStats.assists` will join the `IAttributeContainer`
facade migration alongside `Wallet` / `ManaPool`. Until then, the
counter lives as a regular column and is incremented via a dedicated
listener.

## Decision

Introduce a vendor-neutral `DamageHistoryWindow(durationTicks: Long)`
value class with default `100L` (5 seconds at 20 TPS) and a
ring-buffered in-memory `IDamageHistoryService` port + impl:

- **`DamageEntry(attackerId: PlayerId, amount: Double, at: Long)`** — `at`
  is the absolute server tick (`IScheduler.currentTick()`).
- **`IDamageHistoryService.recentAttackers(targetId, window, asOf)`** —
  returns distinct `PlayerId`s of attackers whose entries satisfy
  `at >= asOf - window.durationTicks`.
- **`InMemoryDamageHistoryService`** — `ConcurrentHashMap<PlayerId,
  ArrayDeque<DamageEntry>>`, cap 64 entries / target, oldest-pop on
  overflow. Cleared on `MatchEnded` (Wave 6 listener) and on player
  disconnect (Wave 5e session lifecycle).
- **`RecordDamageHistoryOnDamageDealtListener`** records every
  `DamageDealt` whose `attacker != null` (environment damage skipped).
- **`AssistAttributionUseCase`** subscribes to `PlayerKilled`, queries
  `recentAttackers(victim, window, asOf = event.atTick)`, excludes the
  killer, and publishes one `AssistCredited(player, victim, matchId?,
  at)` per distinct remaining attacker.
- **`RecordAssistOnAssistCreditedListener`** (stats plugin) increments
  `PlayerStats.assists` via the existing `find → copy → upsert` pattern
  (`RecordKillUseCase` template).

To make the window meaningful, `ApplyDamageUseCase` is updated to inject
`IScheduler` and use `scheduler.currentTick()` for both `PlayerKilled.atTick`
and the new `DamageDealt.atTick` field (the pre-existing
`atTick = System.currentTimeMillis()` was epoch-millis, not ticks — a
silent bug since Wave 5a that would have made a `100L` window meaningless).

ADR-0030 is **not superseded** — this is a §1 damage-path addendum.

## Consequences

### Positive

- Assist attribution becomes a first-class domain primitive, not a
  derived calculation at the consumer.
- `Wallet credit` and `mission progress` listeners that already react
  to `PlayerKilled` can be paired with `AssistCredited` listeners in
  later waves (Wave 5f Marketplace adds `RewardOnAssistListener`).
- `DamageHistoryService` is the substrate Wave 6 will use to apply the
  "last damage source" rule when the vanilla `EntityDeathEvent` is
  hijacked (ADR-0030 §8) — the listener can ask "was the last attacker
  within the window?" to decide whether the death is PvP or environment.
- The `atTick` field on every damage event becomes consistent across
  combat events (already true for `PlayerKilled` / `SkillCast` /
  `StatusApplied` / `SkillChargeStarted`).

### Negative / Trade-offs

- Per-target ring buffer at cap 64 with a 5-second window is a
  practical-but-arbitrary choice. Burst aggro (>64 hits in 5 s)
  silently drops the oldest entries — acceptable for assist credit
  (any one of many attackers still credited) but not a general damage
  log. A future audit-log domain (Wave 5e) will be the SSOT for
  long-tail damage history.
- `AssistCredited` carries `matchId: MatchId?` nullably because not all
  damage paths know the match (e.g. lobby duels). Consumers must
  null-check.
- The `Map<PlayerId, ArrayDeque<…>>` lives forever unless explicitly
  cleared. The `MatchEnded` and `PlayerDisconnected` clear hooks are
  Wave 5e / Wave 6 work; until then, a long-running server's memory
  footprint grows monotonically by ~16 KB per unique target. Acceptable
  for Phase 1 PoC.

### Mitigations

- The cap-64 ring buffer caps per-target memory regardless of attack
  rate.
- A `DamageHistoryService.clear(targetId)` API is exposed up-front so
  Wave 5e/6 listeners can wire cleanup without touching this ADR.
- ADR-0058 §5 rollout schedule already plans Wave 5e attribute-container
  migration for `PlayerStats` — `assists` joins that migration; this
  ADR is intentionally short-lived as a standalone facade.

## Alternatives Considered

- **Bukkit `EntityDamageEvent` damage-source attribution** — rejected.
  ADR-0030 §8 commits Wave 6 to cancel + re-route all vanilla damage
  events; trusting Bukkit's `getDamager()` re-introduces vendor
  coupling at exactly the layer we're isolating.
- **`Map<PlayerId, Double>` cumulative per-target aggregation** —
  rejected. No expiry means stale aggro from minutes ago credits
  assists for the next kill, against the assist semantic.
- **`IAttributeContainer` (ADR-0058) backing from day one** —
  deferred. ADR-0058 §5 rollout schedules `PlayerStats` migration for
  Wave 5e; reworking the ADR-0058 read API to handle list-valued
  attributes (history entries) is its own scope and would slow
  Wave 5a-fix2's fast-follow purpose.
- **Per-match world isolation (Wave 5d) MatchWorld-scoped history** —
  rejected for now. The damage history is a per-target invariant, not
  a per-world one (players can move between worlds during a match in
  Phase 5+). The clear API + Wave 5e session lifecycle hooks are
  enough to bound memory.

## References

- [[adr/0030-combat-depth-and-damage-event-decomposition]] §1
  (canonical damage path), §8 (Wave 6 Bukkit hijack policy)
- [[adr/0058-universal-attribute-container]] §5 (rollout schedule —
  `PlayerStats.assists` joins Wave 5e migration)
- [[adr/0029-rtm-empirical-catalog]] §"Deep audit (2026-05-19)"
- [[../RTM-PARITY-TRACKER]] Category 1 (Combat) — DamageHistoryService
  / assist counter / SkillTrigger rows
- RTM upstream:
  - `core/impl/src/main/kotlin/dev/ryuzu/rtm/core/impl/game/damage/DamageHistoryService.kt`
  - `api/core/src/main/kotlin/dev/ryuzu/rtm/api/core/game/player/IGamePlayer.kt`
