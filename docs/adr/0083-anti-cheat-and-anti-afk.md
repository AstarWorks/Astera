---
status: Accepted
date: 2026-05-21
deciders: Wave 5q (Y-RyuZU + Claude)
supersedes: null
superseded_by: null
---

# ADR-0083: Anti-cheat (lightweight validator + external bridge) + Anti-AFK detection

## Status

Accepted

## Context

Wave 5q was originally scoped as "anti-cheat + anti-grief rollback" in the
Wave 5g core-hardening track. Two pivots reshaped the wave between scoping
and implementation:

1. **Anti-grief rollback dropped.** Wave 5n region permissions (ADR-0081)
   already gate destructive interaction at the *permission* layer, and Wave
   5w (ADR-0089) commits to `restic`-based per-server backups for the
   true recovery case. Building a third in-process rollback layer would
   duplicate both — the user confirmed on 2026-05-20 that "item theft is
   coverable by region permission" and that backup is the right tool for
   the disaster path.

2. **Anti-AFK detection added.** PvP matches are unplayable when a team
   member parks and AFK-farms; the Wave 5w queue + leaver-penalty surface
   needs an event source that doesn't require the match plumbing to poll
   every player position itself.

That leaves three things in scope: a **light, in-process movement /
damage-rate / interact-rate validator triad**, a **bridge to an installed
external anti-cheat plugin** (Spartan / Negativity / Vulcan — the actual
choice is deferred to Wave 8f), and a **per-player AFK detector + tick
sweeper** that publishes `PlayerAfkWarned` / `PlayerAfkKicked` events for
the match plumbing to consume.

Constraints worth recording:

- **Vendor neutrality (ADR-0022, ADR-0083 §"Konsist").** Validator
  results, suspicion types, and the AFK domain must live in
  `core.domain.*` and stay Bukkit-free. Paper listeners and the
  external-plugin glue land in adapter/paper/ at Wave 6b.
- **Vanilla-Paper bootability.** Astera must boot without any external
  anti-cheat plugin installed; the default
  `IExternalAnticheatBridge` is a Noop.
- **Wave 5t admin escape hatch (ADR-0086).** Admins / GMs holding
  `astera.anticheat.bypass` skip both flag emission *and* action
  execution. The bypass check uses `IPermissionService.check`, so it
  inherits the LuckPerms-when-available behaviour Wave 5t set up.
- **Performance.** The light validators run inside Paper move /
  damage / interact listeners and therefore must be O(1) per call.
  Heavier heuristics (packet inspection, killaura, reach) belong on
  the external plugin side and arrive through the bridge as
  `AnticheatSuspicion.CustomFlag`.

## Decision

We split Wave 5q into three loosely-coupled tracks:

1. **Light validator triad** in `core.domain.anticheat.*` and
   `core.application.port.outbound.anticheat.*`. Each validator returns a
   sealed result (`MovementValidation { Ok / Suspicious / Reject }`,
   `DamageRateValidation { Ok / TooFast }`,
   `InteractValidation { Ok / RateExceeded }`). Default impls
   (`DefaultMovementValidator`, `SlidingWindowDamageRateValidator`,
   `SlidingWindowInteractValidator`) are tunable through constructor
   parameters so the Wave 5t live-config editor can swap thresholds at
   runtime.

2. **External anti-cheat bridge** as a single outbound port
   (`IExternalAnticheatBridge`). Default impl `NoopExternalAnticheatBridge`
   makes bridge calls into no-ops and `queryFlagLevel` returns 0; Wave
   6b will replace it with a Paper-side adapter for the plugin that
   wins Wave 8f.

3. **`AnticheatOrchestrator`** glues validators + bridge + permission
   service + event bus. It accepts an `AnticheatActionPolicy` so a
   server admin can wire a custom decision tree without subclassing.
   The stock policy is severity-based: external flag level ≥ 8 →
   `Ban`, ≥ 4 → `Kick`, Astera-local rate flags → `Notify`, else
   `Quiet`.

4. **Anti-AFK detector** as a separate port pair: `IAfkDetector` for
   recording activity / reading status + `AfkSweepScheduler` for
   periodic event emission. Defaults: 30s warning, 2 min kick, 1s
   sweep cadence. The sweeper de-duplicates state transitions so a
   player who oscillates between Warning and Active only triggers
   `PlayerAfkWarned` on the rising edge.

The 4 new domain events live in `core.domain.event`:
`AnticheatFlagRaised`, `AnticheatActionTaken`, `PlayerAfkWarned`,
`PlayerAfkKicked`. Match plumbing (Wave 5w `MatchFoundUseCase` +
leaver penalty) subscribes to `PlayerAfkKicked` and applies a
queue-join cooldown for the next match.

Two Konsist rules join the existing 30-odd baseline:
- `Anti-cheat domain must not import Bukkit Paper (ADR-0083)`
- `IAfkDetector application-layer implementations must not import Bukkit Paper (ADR-0083)`

## Consequences

### Positive

- Validator output is exhaustive `when`-able everywhere it's consumed —
  no boolean / int / exception mishmash crossing the boundary.
- Astera-local checks and external plugin checks share one event
  surface (`AnticheatFlagRaised`), so admin dashboards do not need to
  unify two formats later.
- Removing anti-grief from Wave 5q lets the wave land with three small
  domain hierarchies instead of dragging in a snapshot / rollback
  subsystem that overlaps Wave 5w.
- AFK detection is fully testable without Paper: thresholds are
  injected through constructor parameters and the sweeper is
  driven by a `tickProvider: () -> Long`.

### Negative / Trade-offs

- The light validator does not look at terrain — wall-clip detection
  is degraded to whatever the external plugin can do. Wave 5n's
  `ISpatialIndex` is the planned cure but is out of scope here.
- `IExternalAnticheatBridge` is a thin facade; the actual plugin choice
  is deferred to Wave 8f. Until then the bridge is Noop and admins
  who want stronger anti-cheat must install the external plugin
  themselves (the bridge will pick it up automatically at Wave 6b).
- `SlidingWindowDamageRateValidator` uses an approximate eviction
  (oldest sample falls out → whole window decays) to keep per-attacker
  memory O(1) rather than O(sample-count). For Phase 1 PoC this is
  intentional; precise queue tracking lands when CI shows the
  imprecision actually changes a verdict.
- `AfkSweepScheduler` walks the session-repo snapshot once per second;
  for very large servers (≫ 200 simultaneous tracked players) this
  becomes worth replacing with an event-driven promotion, but the
  current implementation is correct and trivially debuggable.

### Mitigations

- `AnticheatActionPolicy` is a `fun interface` so server operators who
  want stricter behaviour can drop a custom decider into the wiring
  without forking the orchestrator.
- `astera.anticheat.bypass` lets admins / staff bypass both flag and
  action so testing / debugging never trips the kick path.
- Konsist enforces the Bukkit isolation; reviewer subagent enforces
  the validator-O(1) discipline for further additions.

## Alternatives Considered

- **Single fat `IAnticheat` port.** Rejected — it mixes the read
  surface (suspicion / flag queries) with the write surface (validator
  results) and would force every Wave 5a damage call site to drag in
  the AFK surface too.
- **Anti-grief rollback in Wave 5q.** Rejected — duplicates Wave 5n
  region permissions and Wave 5w backups, both already committed.
- **`recordActivity` per Paper event type.** Rejected — `ActivityKind`
  sealed hierarchy is intentionally small (Movement / Click / Chat /
  SkillCast). Paper-side listeners normalise into those four; richer
  per-event tracking would explode the cross-product without
  obvious gameplay benefit.
- **Embed external anti-cheat directly in Astera.** Rejected — vendor
  lock-in and license headaches; the bridge keeps Astera free of any
  GPL'd external plugin code in the build graph.

## References

- 関連 ADR: [[adr/0022-multi-plugin-portable-architecture]],
  [[adr/0080-player-physiology-and-resource-pool]],
  [[adr/0081-world-environment-control]],
  [[adr/0086-admin-ops-permission-presence-feedback]],
  [[adr/0088-combat-aoe-environment-damage-source]],
  [[adr/0089-server-lifecycle-and-restic-backup]]
- Handoff: `/home/node/.claude/plans/wave-5g-core-hardening-giggly-sunset.md`
  §"Wave 5q"
- Konsist: `tools/architecture-test/src/test/kotlin/com/astarworks/astera/architecture/ArchitectureTest.kt`
  — `Anti-cheat domain must not import Bukkit Paper (ADR-0083)` +
  `IAfkDetector application-layer implementations must not import Bukkit Paper (ADR-0083)`
