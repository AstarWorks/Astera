---
status: Accepted
date: 2026-06-02
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0147: PaperPlayerSpeedGateway — Wave 6a-4

## Status

Accepted

## Context

Wave 6a-4 (Round 19). Fourth Paper sweep sub-wave. Mirrors
`InMemoryPlayerSpeedGateway` shape with Bukkit writeback for the 5 ADR-0080
Wave 5k locomotion knobs (walkSpeed / flySpeed / allowFlight / sprinting /
no-clip).

`PaperPlayerFoodGateway` (IPlayerFoodGateway impl) was originally bundled
in this sub-wave but pulled to a separate **Wave 6a-5** because it
introduces a Bukkit `@EventHandler` listener pattern (cancelling
`FoodLevelChangeEvent` when hunger is suppressed) that Paper adapters
haven't shipped yet in Astera — the listener registration deserves its
own ADR-level treatment without diluting the Speed gateway commit.

## Decision

### §A — `PaperPlayerSpeedGateway` impl

`plugin/core/src/main/kotlin/com/astarworks/astera/core/adapter/paper/physiology/PaperPlayerSpeedGateway.kt`:

Constructor: `Server` + `IScheduler` + `IDomainEventBus`.

Identical TTL-tracking maps to `InMemoryPlayerSpeedGateway`
(`walkMul` / `walkExpiry` / `flyMul` / `flyExpiry` /
`flightAllowed` / `flightExpiry` / `sprinting` / `sprintingExpiry` /
`noClip` / `noClipExpiry`). Every mutation method does:

1. Validate args (multiplier finite + non-negative; ttlTicks > 0 or
   MAX_VALUE).
2. Update local state map.
3. Cancel prior expiry handle.
4. Bukkit writeback if online (`onlinePlayer(playerId)?.let { ... }`).
5. Schedule one-shot expiry via `IScheduler.runLaterTicks` if ttl != MAX.
6. Publish the corresponding event (`PlayerSpeedChanged` /
   `PlayerFlightToggled` / `PlayerSprintingChanged` /
   `PlayerNoClipChanged`).

Bukkit mappings:

- **WALK / FLY multiplier** → `player.walkSpeed = (BASELINE * multiplier).toFloat().coerceIn(-1f, 1f)`
  (BASELINE = 0.2 for WALK, 0.1 for FLY — Bukkit's documented vanilla
  baselines).
- **allowFlight** → `player.allowFlight = allow`. Toggling off mid-
  flight drops the player to ground; acceptable per ADR-0080 admin
  tier.
- **isSprinting** → `player.isSprinting = sprinting`. Vanilla input
  may flip back if the player isn't holding sprint key — documented
  Phase 1 PoC trade-off in `IPlayerSpeedGateway` KDoc.
- **no-clip** → `player.isCollidable = !noClip`. Bukkit's documented
  no-clip-without-GameMode-change path per `IPlayerSpeedGateway` KDoc.
  Walks through other entities; world geometry collision unchanged
  (true no-clip = `GameMode.SPECTATOR` which is `ISpectatorGateway`
  scope).

### §B — Offline-player semantics

Per `IPlayerSpeedGateway` KDoc, every mutation still updates the local
state map (so `currentMultiplier` / `isFlightAllowed` returns the
intended setting) but skips the Bukkit writeback with a DEBUG log when
the player is offline. The mutation returns `Result.success(Unit)` —
NOT `BackendUnavailable` — because the requested setting is still
"recorded" and will take effect on next login if it's still in the
state map. This matches `InMemoryPlayerSpeedGateway`'s "state is
authoritative; Bukkit write is best-effort" stance.

(Distinguishes from `IPlayerHealthGateway` which DOES return
`BackendUnavailable("player offline")` — health writes are not
recordable in absentia because Bukkit's `Player.health` is the source
of truth there.)

### §C — No Konsist guard (no silent-drop risk identified)

Unlike ADR-0144 / ADR-0146 (Konsist Rules 56 / 57 protecting
KNOCKBACK_RESISTANCE / appliedThisTick + isDead tokens), the speed
gateway has no equivalent critical invariant a refactor could silently
drop. The contract surface is the `IPlayerSpeedGateway` interface
(compile-checked) + the 4 Bukkit setter calls (`walkSpeed` / `flySpeed`
/ `allowFlight` / `isSprinting` / `isCollidable`). A refactor that
removes any of those would either fail the InMemory behavioural test
contract or change observable behaviour visibly.

### §D — Registration at priority 100

`AsteraCorePlugin.onEnable` between the existing InMemory registration
(priority 0) and the next gateway (`InMemoryPlayerFoodGateway`).

## Consequences

### Positive

- 5 Wave 5k locomotion knobs now Bukkit-visible in Paper deployments
  (admin /speed, mana-as-speed potion, sprint-skill, walk-through-walls
  admin mode).
- TTL semantics + event shape identical to InMemory — HUD / analytics /
  achievement subscribers impl-agnostic.
- Pattern reuse: 5 mutation methods follow the same shape (validate →
  state update → cancel old expiry → Bukkit write → schedule new
  expiry → publish event), reducing review surface.
- Walk/fly baseline scaling means multiplier semantics match player
  expectations (1.0 = vanilla, 2.0 = double-vanilla) regardless of
  axis.

### Negative / Trade-offs

- **No Konsist guard** — the contract surface is too small to warrant
  one (5 method bodies, each calling 1 Bukkit setter). A future-
  refactor risk is theoretical but not concrete enough to justify the
  rule.
- **No-clip approximation only walks through entities** — vanilla
  world-block collision still applies. ADR-0080 documents this; true
  no-clip is ISpectatorGateway scope.
- **Local state vs Bukkit truth diverge on offline writes** — if an
  admin sets `walkSpeed = 2.0` on an offline player, `currentMultiplier`
  returns 2.0 immediately but `player.walkSpeed` won't reflect it
  until next login. Acceptable per the "state recorded, write
  best-effort" stance.
- **flightExpiry / sprintingExpiry / noClipExpiry guard their no-op
  branches** via `playerId in flightAllowed` etc. — same pattern as
  InMemory. If a subsequent mutation overwrites the flag before the
  expiry fires, the expiry handle is cancelled in step 3 so no race.

### Mitigations

- Walk/fly baseline + coerceIn keep `Player.walkSpeed` within Bukkit's
  documented [-1, 1] range even with `multiplier = 100` configuration
  errors.
- Per-axis expiry maps are independent so a WALK ttl doesn't affect a
  FLY expiry (matches InMemory).

## Alternatives Considered

- **Alt A: bundle PaperPlayerFoodGateway in this wave**: rejected.
  Food gateway needs a Bukkit `@EventHandler` listener to cancel
  `FoodLevelChangeEvent` when hunger is suppressed — a new pattern
  for Paper adapters in Astera (no existing Paper adapter registers
  Bukkit listeners directly; the convention is via `BukkitEventAdapter`
  Wave 6a-6). Pulling Food to its own sub-wave (Wave 6a-5) lets the
  Listener wiring decision get proper ADR treatment.
- **Alt B: skip TTL state tracking and rely on Bukkit reads**:
  rejected. Bukkit `Player.walkSpeed` returns the absolute Float, not
  the Astera-multiplier. Reverse-engineering "current multiplier"
  from `walkSpeed / 0.2` loses the source-of-truth: if vanilla code
  (other plugin) wrote walkSpeed, Astera's view diverges. Local state
  is authoritative; Bukkit is the writeback target.
- **Alt C: validate ttlTicks > 0 in InMemory too**: out of scope —
  this ADR just mirrors InMemory's existing validation.

## References

- 関連 ADR:
  [[adr/0022-multi-plugin-layout]],
  [[adr/0080-player-physiology-resource-pool]] (Wave 5k — established
  IPlayerSpeedGateway 5-knob interface),
  [[adr/0144-paper-knockback-gateway]] / [[adr/0145-paper-player-health-gateway]] /
  [[adr/0146-paper-damage-gateway]] (Wave 6a-1/2/3 — sibling Paper
  adapter shape this ADR follows)
- Related files:
  `plugin/core/src/main/kotlin/.../adapter/paper/physiology/PaperPlayerSpeedGateway.kt`
  (new ~280 LOC),
  `plugin/core/src/main/kotlin/.../platform/AsteraCorePlugin.kt:1082+`
  (registration at priority 100 after InMemory),
  `plugin/core/src/main/kotlin/.../application/service/InMemoryPlayerSpeedGateway.kt`
  (behavioural reference)
- Future work:
  - **Wave 6a-5: `PaperPlayerFoodGateway`** — port the 6-method
    interface + add the Bukkit `FoodLevelChangeEvent` listener for
    suppression. Will introduce the "Paper adapter that registers a
    Bukkit listener" pattern (separate from Wave 6a-6 BukkitEventAdapter
    which translates Bukkit events to DomainEvents).
  - Wave 6a-6: `BukkitEventAdapter` (15 ADR-0134 allowlist event
    translations + death-event Path 2 hijack per ADR-0137).
  - Phase 2+: per-player vanilla `walkSpeed` baseline override if
    server starts using non-vanilla baselines (currently hardcoded
    0.2 / 0.1).
