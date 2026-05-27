---
status: Accepted
date: 2026-05-27
deciders: orchestrator (Wave 5complete), astera-reviewer (Phase C close)
supersedes: null
superseded_by: null
---

# ADR-0117: PvP Server Completeness — Orthogonal Axes 2-6 Gap Close

## Status

Accepted

## Context

Wave 5wireup (ADR-0116) closed the 11 E2E loop gaps audit-found after
Round 8 of the `wave-5g-core-hardening` plan. That audit, however, only
covered **Axis 1 (player main state)** — the linear `Offline → Lobby
→ Match → PostMatch → ...` state graph. The post-Round-8 follow-up audit
discovered the plan's MECE structure was **single-axis** and missed **10+
gaps on five orthogonal axes**:

  - **Axis 2 — Communication**: `ChatChannel` sealed + per-channel send
    use case existed (Wave 5e); no top-level `/say` / `/team` / `/party`
    / `/msg` commands. Scheduled-announcement system existed (Wave 5t);
    no `/astera-announce` command surface.
  - **Axis 3 — Moderation**: `ModerationAction` sealed (Mute/Kick/Ban/
    ChatRateLimit) + `IModerationService` port existed (Wave 5h); no
    flat top-level `/astera-kick`, `/astera-ban`, etc. commands and no
    AFK-kick bridge between Wave 5q's `PlayerAfkKicked` event and the
    moderation infra. The sealed hierarchy was also missing a `Warn`
    variant — admin warnings had no canonical recording shape.
  - **Axis 4 — UI overlay**: Wave 5j shipped `IHudGateway` with
    `HudLayout.{ActionBar, BossBarSlot, Sidebar, TabList, Subtitle}`;
    no Match-side subscriber updated the per-team sidebar on score
    changes and no killfeed subscriber surfaced kills.
  - **Axis 5 — System process**: Wave 5q's AFK detector + sweep
    scheduler emitted `PlayerAfkKicked` to no subscriber; Norma's
    `VictoryConditionEvaluator` (Wave 4.5) was never wired to score
    events — admins had to manually run `/astera-match phase
    ScoreThresholdReached`.
  - **Axis 6 — Item lifecycle**: PvE loot rolls fired `LootDropped`
    events but no port materialised the drops into world entities and
    no listener walked players over them into their inventories.

The MECE failure was structural: the plan's state-graph treatment
implied "if Axis 1 is fully wired the match loop is complete", but the
PvP server's actual UX needs all six axes wired in parallel. Pre-release
posture (CLAUDE.md §"Pre-release posture") gives us room to take a
single forward-only commit that closes the orthogonal gaps without
fallback shims.

## Decision

**Add one new outbound port + one new sealed-variant extension + 4
listeners + 3 commands + 1 system-process subscriber across `:plugin:core`
and `:plugin:match`, then wire all in the existing
`AsteraCorePlugin.onEnable` / `AsteraMatchPlugin.onEnable`.**

### New Core port

- **`IItemEntityGateway`** (`core.application.port.outbound.item`):
  `spawnItemEntity(world, position, itemId, count, lifetime?) →
  ItemEntityHandle / ItemEntityError` + `removeItemEntity` +
  `findNearby(world, position, radius)` + `peek(handle)`. Phase 1
  `InMemoryItemEntityGateway` honours `lifetimeSeconds` via the
  injected `IScheduler` (one-shot despawn). Wave 6b adapter overlays
  with a Bukkit `Item`-backed impl.

### Sealed variant extension

- **`ModerationAction.Warn`** added as the 5th variant. Record-only,
  no automatic chat/connection effect. Updates the four exhaustive
  `when` sites (`InMemoryModerationService.withLifted`,
  `FakeModerationService.withLifted`,
  `ApplyModerationActionUseCase.requiredPermission`,
  `PostgresModerationService.encode/decode`). Adds DB migration **V44**
  to extend the `moderation_actions.kind` CHECK constraint.

### New Core listeners

- **`SpawnItemEntityOnLootDroppedListener`** subscribes to `LootDropped`
  and spawns one entity per `(itemId, count)` drop at the killer's
  last-known position (sourced from new
  `IPlayerLocationLookup` port + default
  `PlayerMovedLocationTracker` impl that subscribes to `PlayerMoved`).
- **`AutoPickupOnPlayerMovedListener`** subscribes to `PlayerMoved`,
  scans `IItemEntityGateway.findNearby` (1.5-block default radius),
  and pipes successes through `IInventoryMutator.give` + publishes a
  new Core `LootPickedUp` event.
- **`KickPlayerOnAfkKickedListener`** subscribes to `PlayerAfkKicked`
  and records a `ModerationAction.Kick` with `SYSTEM_ISSUER_ID =
  PlayerId(UUID(0, 0))` (bypasses permission gate — system action).

### New Match listeners

- **`UpdateMatchSidebarListener`** subscribes to `MatchStarted` +
  `EntityPickedUpItem` and re-renders the per-team Norma scoreboard
  via `IHudGateway.showFor(player, HudLayout.Sidebar(...))`.
- **`KillfeedOnPlayerKilledListener`** subscribes to `PlayerKilled`
  and renders three HUD surfaces (victim ActionBar, killer Subtitle,
  match-wide ActionBar broadcast).
- **`NormaVictoryConditionEvaluatorListener`** subscribes to
  `EntityPickedUpItem`, re-runs the existing Wave 4.5
  `VictoryConditionEvaluator`, and auto-fires
  `SwitchPhaseUseCase.execute(matchId, ScoreThresholdReached)` when
  a unique winner emerges. Eliminates the admin-manual
  `/astera-match phase` step from the Norma win path.

### New commands

Core registers under their own top-level names (the platform layer
materialises each as `/astera-<verb>`):

- `/astera-chat {say|team|party|msg}` (Axis 2) — driven by existing
  `SendChatMessageUseCase`. `team` resolves via the cross-plugin
  lazy `IMatchTeamLookup` provider (Match plugin registers the impl).
- `/astera-announce <messageKey> [chat|actionbar|title]` (Axis 2) —
  driven by existing `IScheduledAnnouncementSystem.schedule(... .Once)`.
  Admin-gated by `astera.admin.announce`.
- `/astera-{kick,mute,unmute,ban,tempban,unban,warn}` (Axis 3) — flat
  alternates to the Wave 5h `/astera mod {...}` tree, mirroring
  EssentialsX muscle memory.

Match plugin adds `/astera-match {abort,cancel,restart}` sub-commands
beside the existing `start/end/entry/phase/list/replay`.

### Konsist rule (50 → 51)

`item entity domain stays pure (ADR-0117)` — `core.domain.item.*` must
not import Bukkit / kotlinx.serialization / persistence / koin /
`IDomainEventBus` / `IBroadcaster` / `IScheduler` /
`IItemEntityGateway`. Guards the Phase 1 PoC posture that the
`ItemEntityHandle` value class stays a pure UUID wrapper; lifetime
state lives in the gateway impl, not the handle.

## Consequences

### Positive

- **PvP server feature parity**: all six orthogonal axes wired in a
  single forward-only commit. Players can chat in any of four
  channels, admins can moderate inline, AFK detection feeds the
  audit trail, sidebars and killfeeds update without manual /reload,
  PvE drops actually hit the ground and walk-pickup just works.
- **No fallback shims**: pre-release posture honoured. `Warn` variant
  added cleanly with exhaustive `when` updates at all 4 sites; no
  `@Deprecated`, no dual-decoder, no parallel "warning" infra.
- **Minimum new port count = 1** (`IItemEntityGateway`); every other
  axis reused existing ports / domain types / use cases. Wave 5e /
  5h / 5j / 5q / 5t / 5w / 5world-events all benefit by becoming
  *actually wired*.
- **State machine is now MECE on all 6 axes**: the wave's
  documentation captures the structural fix so future audits can
  exhaustively check feature coverage by axis rather than by
  single-state-graph traversal.

### Negative / Trade-offs

- **Migration cost**: a 6th `ModerationAction` variant would push the
  CHECK constraint out again; the variant set is now committed shape
  for Phase 1. If a future moderation action genuinely needs a
  different shape (e.g. "Quarantine" with isolation-world coordinates)
  the migration cost is one V-bump.
- **In-memory `IItemEntityGateway` is O(N entities) for `findNearby`**.
  Phase 1 PoC scope; Wave 6b's Paper adapter uses Bukkit's chunk
  index for O(1)-ish lookup.
- **Auto-pickup races**: two concurrent `PlayerMoved` events from the
  same player can both call `findNearby` before either despawns;
  losers see an empty set on the second call (idempotent failure —
  no double-give). This matches Bukkit's own player-move pickup
  semantics.
- **AFK kick bridge writes a moderation record but does not boot the
  player from the server** until Wave 6b wires the connection
  enforcement listener. Symmetric with the rest of Wave 5h's
  "domain decision now, Paper enforcement later" pattern.

### Mitigations

- The new `IPlayerLocationLookup` port is a deliberately small surface
  (one method) — Wave 6b's Paper overlay (priority 100) can read
  `IMcServer.findPlayer(id).location` directly and avoid the
  `PlayerMoved` event lag.
- The `MatchScoreChanged`-style event was intentionally not added; the
  Norma victory listener piggybacks on `EntityPickedUpItem` which is
  the only event the Wave 5c star/deposit pipeline emits today. If
  Annihilation mode needs its own victory listener it can subscribe
  to `NexusDamaged` symmetrically.

## Alternatives Considered

- **No `Warn` variant — record-only via `IAuditLogRepository`**:
  rejected because admin tooling (`/astera mod history`) couldn't
  surface warnings without forking a parallel query path. Adding the
  5th sealed variant is the future-proof shape.
- **Add `position: Vec3` field to `LootDropped`**: rejected because
  it would break the replay-determinism contract (Wave 5pve-content
  designed `LootDropped` as a logical roll event, not a spatial one).
  The new `IPlayerLocationLookup` port keeps `LootDropped` pure.
- **One mega-listener per axis**: rejected because each axis already
  has multiple events worth listening for separately, and Konsist's
  `application` rule encourages narrow single-responsibility listeners
  (easier unit testing, smaller blast radius).
- **Skip the flat `/astera-{kick,...}` commands and rely on `/astera
  mod kick`**: rejected because the muscle-memory argument is real.
  PvP-server users come from EssentialsX where `/kick` is one word.

## References

- 関連 ADR:
  - [[adr/0079-server-administration]] (Wave 5h: original moderation
    domain — Warn variant + V44 migration extends)
  - [[adr/0083-anti-cheat-and-anti-afk]] (Wave 5q: AFK detector +
    sweep scheduler — new bridge subscribes here)
  - [[adr/0086-admin-and-server-management]] (Wave 5t: scheduled
    announcement system — `/astera-announce` wires here)
  - [[adr/0097-custom-hud-foundation]] (Wave 5j: IHudGateway —
    sidebar + killfeed listeners use this)
  - [[adr/0111-world-events-and-inventory-mutation-foundation]] (Wave
    5world-events: PlayerMoved + IInventoryMutator — auto-pickup
    chain wires these)
  - [[adr/0116-e2e-gameplay-loop-wireup]] (Wave 5wireup: closed
    Axis 1 gaps; this ADR closes Axes 2-6)
- 関連 docs: `wave-5g-core-hardening-giggly-sunset.md §Round 8
  Player experience MECE map`
- `docs/RTM-PARITY-TRACKER.md` — annotated with 6-axes MECE close.
