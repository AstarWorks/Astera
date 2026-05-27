---
status: Accepted
date: 2026-05-27
deciders: Claude (Opus 4.7)
supersedes: null
superseded_by: null
---

# ADR-0118: In-match HUD listener wireup (9 listeners)

## Status

Accepted

## Context

The Wave 5g Round 10 audit of in-match player experience surfaced a coherent
gap: the player can be in a live match, take damage, spend mana, hit
cooldowns, be afflicted by status effects, see phases tick over, watch the
match timer count down, die and respawn, switch into spectator mode — and
**none of it shows up on their screen**. The HUD primitives all existed
(`IHudGateway`, `HudLayout`, `BossBarSlot`, `Sidebar`, `TabList`, `ActionBar`,
`Subtitle`, `IFreelookGateway`) and the source events all existed
(`ResourceSpent` / `ResourceReloaded` / `ResourceRegenned`, `StatusApplied` /
`StatusEffectCancelled`, `PhaseChanged`, `MatchStarted`, `PlayerKilled` /
`PlayerRespawned`, `Match.startedAtTick`) — what was missing was the
**subscriber wiring** that translates the event stream into HUD pushes.

Wave 6b is what materialises the HUD on screen (Paper renderer). Wave 5j
(ADR-0097) shipped the in-memory recorder + the `HudShown` publication
contract so application listeners can be wired today and stay correct when
the Paper renderer lands — the listener does not know whether the rendering
is real or recorded.

The Round 10 audit identified nine listener seams:

| # | Listener | Plugin | Source event(s) | HUD region |
|---|---|---|---|---|
| 1 | `HudUpdateOnCooldownTickListener` | combat | (poll `ICooldownTracker`) | ActionBar |
| 2 | `HudUpdateOnResourcePoolChangedListener` | core | `ResourceSpent` / `Reloaded` / `Regenned` | BossBarSlot 1..3 |
| 3 | `HudUpdateOnStatusEffectChangedListener` | combat | `StatusApplied` + `StatusEffectCancelled` | Sidebar |
| 4 | `HudUpdateOnMatchPhaseChangedListener` | match | `PhaseChanged` | ActionBar |
| 5 | `HudUpdateOnMatchTickListener` | match | (poll `IMatchRepository.findActiveMatches`) | ActionBar |
| 6 | `HudUpdateOnTeamColorAssignedListener` | match | `MatchStarted` | TabList |
| 7 | `HudUpdateOnRespawnScheduledListener` | match | `PlayerKilled` + `PlayerRespawned` | ActionBar |
| 8 | `KillfeedWeaponDetailListener` | match | `PlayerKilled` | ActionBar broadcast |
| 9 | `SpectatorCameraControlListener` | match | `PlayerEnteredSpectator` (NEW) | `IFreelookGateway` |

Listener sites obey ADR-0022 sibling isolation: combat-owned events (cooldown
tracker, status effect container) land in `:plugin:combat`; the resource pool
events live in `:plugin:core`; everything match-scoped lives in `:plugin:match`.

### Pull-vs-push for two listeners

Two events did not exist when the audit ran:

- **No `CooldownStarted` / `CooldownTick` event.** `ICooldownTracker` (ADR-0082)
  is intentionally event-less — emitting `Tick` 20 times per second per
  (player, key) would flood the bus and any HUD subscriber would still throttle
  on its own cadence. Listener pulls from the tracker every 5 ticks instead.

- **No `MatchTicked` event.** Match time is implicit in
  `Match.startedAtTick + IScheduler.currentTick`; the existing schedulers
  (`BarrierStatusScheduler`, `AnomalyScheduler`, `StarGeneratorScheduler`)
  already use the pull model. Listener pulls match snapshots every 20 ticks.

### One new event

`PlayerEnteredSpectator` (`:plugin:match.domain.event.*`) is added because
Wave 5h's `EnterSpectatorUseCase` mutated session state + called
`IWorldGateway.spectatorSpawn` directly but never published — leaving
downstream subscribers (HUD, freelook, audit) with nowhere to hook in.
This is wireup, not new design — the event is the missing publication
side of an existing use case.

## Decision

Land nine HUD listeners + one new domain event (`PlayerEnteredSpectator`)
+ optional `IDomainEventBus` / `IScheduler` parameters on
`EnterSpectatorUseCase` (existing callers stay source-compatible via the
`null` defaults). Wire each listener in its plugin's
`platform/Astera*Plugin.kt` per ADR-0022 sibling isolation. Two listeners
own poll schedulers (`HudUpdateOnCooldownTickListener`,
`HudUpdateOnMatchTickListener`) — the plugin platform stores the handle
and calls `stop()` from `onDisable`. The respawn-countdown listener
(`HudUpdateOnRespawnScheduledListener`) owns per-player one-shot
`IScheduler.TaskHandle`s tracked in a `ConcurrentHashMap` keyed by
`PlayerId`; second death cancels the prior schedule.

Phase 1 `InMemoryHudGateway` records the calls and publishes `HudShown`;
the Wave 6b `PaperHudGateway` materialises the calls on screen. The
listener code does not change between the two — the gateway swap is
transparent.

Add corresponding message keys to `content/languages/{en,ja}.yaml`
(`astera.hud.cooldown.*`, `astera.hud.resource.*`, `astera.hud.status.*`,
`astera.hud.phase.*`, `astera.hud.match.timer`, `astera.hud.tablist.*`,
`astera.hud.respawn.countdown`, `astera.killfeed.weapon.*`) so the
i18n-parity Konsist test stays green.

## Consequences

### Positive

- Player-facing in-match state is now wired end-to-end through every HUD
  surface (ActionBar / BossBar / Sidebar / TabList). When Wave 6b lands
  the Paper renderer, the player sees cooldowns / mana / status effects /
  phase / timer / team color / respawn countdown / killfeed weapon names
  / spectator camera control with zero listener changes.
- Closes nine real wire gaps surfaced by Round 10 in-match-experience
  audit — none of these listeners existed before, the HUD primitives sat
  unused for matches.
- Pull-model for cooldown + match-timer keeps the event bus from flooding;
  matches the existing scheduler-poll pattern used by Barrier / Anomaly /
  StarGenerator.
- `PlayerEnteredSpectator` event makes spectator transitions observable
  for downstream listeners (HUD freelook today; future audit / metrics).
- Phase 1 PoC posture: forward-only commits, no fallback shim, no
  `@Deprecated`. `EnterSpectatorUseCase`'s new optional parameters
  preserve the existing test surface without a dual-decoder.

### Negative / Trade-offs

- Two poll schedulers (every 5 ticks for cooldown, every 20 ticks for
  match timer) add ~1.3 wakeups/sec/match × max concurrent matches.
  Negligible at Phase 1 scale (≤ 32 players, ≤ 4 matches) but the
  cooldown poll touches every online player every tick interval — if
  the player count crosses ~256 the listener should switch to "only
  poll players who have an active key" via a tracker callback.
- Message keys reference unimplemented placeholder substitution
  (`{remaining}` / `{max}` / `{item-name}` / `{seconds}` / `{phase}` /
  `{time}` / `{team}`). The Wave 6b renderer must implement substitution
  for these placeholders — the keys are reserved + documented today so
  the renderer has a target list.
- The `KillfeedWeaponDetailListener` lives alongside the existing
  `KillfeedOnPlayerKilledListener` rather than replacing it (the
  existing listener stays untouched per the wireup-only scope). The two
  push to the same HUD surface with different message keys — at Phase 1
  player counts (≤ 32) the cost is negligible.

### Mitigations

- Konsist + 51 architecture tests gate the wiring — Phase B compile +
  Phase C `./gradlew check` confirmed all green at landing.
- Each listener takes its dependencies through constructor injection so
  the priority-0 InMemory defaults and the Wave 6b priority-100 Paper
  overrides both work without listener changes (the gateway swap is
  transparent via `ServiceRegistry`).
- Poll-listener handles are stored on the plugin platform and cancelled
  from `onDisable` so reload / shutdown does not leak tasks.

## Alternatives Considered

- **One `MatchHudCoordinator` collecting every event.** Considered and
  rejected: would have crossed `:plugin:combat` / `:plugin:core` /
  `:plugin:match` boundaries, breaking ADR-0022 sibling isolation. The
  per-event listener split keeps each subscriber in the plugin that
  owns its source event.
- **Synthesizing `CooldownStarted` / `CooldownTick` events.** Considered
  and rejected: would have required the tracker to publish on every tick
  for every (player, key) pair (event flood) or required listeners to
  subscribe to a coarse-grained "any tracker mutation" event (no better
  than polling, and less efficient). Pull from the tracker is cleaner.
- **Synthesizing `MatchTicked` events.** Same shape as cooldown — the
  match aggregate is a snapshot, not a stream, and pull from the
  snapshot is what the existing match schedulers already do.
- **Skipping `PlayerEnteredSpectator` and reading session state from a
  scheduler.** Considered and rejected: would have re-introduced the
  "polling for state change" pattern. Adding the missing publication
  side of the use case is the right shape — same pattern as every other
  use case in the codebase.

## References

- Related ADRs:
  - [[adr/0022-multi-plugin-architecture]] — sibling isolation defines
    where each listener lands.
  - [[adr/0080-resource-pool-foundation]] — `ResourceSpent` /
    `ResourceReloaded` / `ResourceRegenned` source events for §2.
  - [[adr/0082-cooldown-tracker]] — `ICooldownTracker` (event-less by
    design; §1 pulls from it).
  - [[adr/0097-hud-gateway-foundation]] — `IHudGateway` + `HudLayout`
    primitives this wave subscribes through.
  - [[adr/0110-camera-music-gateway-foundation]] — `IFreelookGateway`
    used by §9.
  - [[adr/0117-pvp-server-completeness-orthogonal-axes]] — Wave
    5complete's `KillfeedOnPlayerKilledListener` (one of three killfeed
    listeners; §8 sits alongside it).

- Wave handoff: `/home/node/.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §Round 10.
