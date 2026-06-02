---
status: Accepted
date: 2026-06-02
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0157: Paper HUD + Adventure UI adapters (Wave 6b-2)

## Status

Accepted. Second sub-wave of Wave 6b (Phase 1 PoC closing). Ships
seven Paper HUD adapters in `core.adapter.paper.hud/` backed by
Adventure API (Paper-bundled). Companion sub-waves
[[adr/0156-paper-world-block-barrier-adapters]] (Wave 6b-1) and
[[adr/0158-paper-inventory-and-bridges]] (Wave 6b-3) deliver world /
inventory / bridges.

## Context

After Wave 6b-1 (ADR-0156) shipped the world / block / barrier
adapters, the HUD layer was still all `InMemory*` at priority 0 —
`InMemoryBossBarManager`, `InMemoryScoreboardManager`,
`InMemoryTabList`, `InMemoryHudGateway`. Player-facing UI
(`/astera-match`'s scoreboard, kill-feed action-bar, MatchEnded title
sequence, tab list match info) all silently no-op'd at runtime.

Phase 1 PoC smoke-test items #4 (match auto-transition with
COUNTDOWN→ACTIVE timer visible to players), #7 (status effect potion
icon — complemented by `PaperStatusEffectContainer` from ADR-0155
plus the BossBar progress meter for tick-effect remaining-duration
visualisation), and #8 (kill 10 → `MatchEnded.winningTeam` with
title display) all require the HUD layer to actually render to
clients.

## Decision

### §A — `PaperBossBar` + `PaperBossBarManager` (sub-A)

`plugin/core/.../adapter/paper/hud/PaperBossBar.kt` (268 LOC, 8
`IBossBar` overrides) + `PaperBossBarManager.kt` (144 LOC, 7
`IBossBarManager` overrides).

`PaperBossBar` is `internal` — only `PaperBossBarManager`
instantiates it. Constructor matches `createBossBar` parameter set
(id, title, progress, color, overlay, flags, server,
messageRenderer?) so the id-idempotent contract in
`createBossBar(id, …)` mutates in place when a bar with that id
already exists, and attached viewers see the update via Adventure's
listener machinery.

- Viewer state: internal `ConcurrentHashMap<PlayerId, Unit>` mirror
  (Adventure's `BossBar.viewers()` returns opaque audiences with no
  `PlayerId` recovery).
- Domain ↔ Adventure enum bridges (Color / Overlay / Flag) are
  exhaustive `when`s.
- Offline player handling: `server.getPlayer(uuid)` null ⇒ silent
  skip + DEBUG log (mirrors `PaperPlayerSpeedGateway` record-and-skip
  pattern).
- `PaperBossBarManager.scheduler` reserved (`@Suppress`) for
  Wave 6b-_polish per-viewer-locale work.

Manager `resolvePaperBar` cross-manager guard: logs WARN and no-ops
if an external (e.g. `InMemoryBossBar`) impl is passed — defensive
against wiring-bug.

### §B — `PaperScoreboard` + `PaperScoreboardManager` + `PaperTeamScoreboard` (sub-B)

Three files under `core.adapter.paper.hud/`:
- `PaperScoreboard.kt` (157 LOC, 6 `IScoreboard` overrides).
- `PaperScoreboardManager.kt` (147 LOC, 9 `IScoreboardManager`
  overrides).
- `PaperTeamScoreboard.kt` (148 LOC, 7 `ITeamScoreboard` overrides).

`PaperTeamScoreboard` kept as a separate file (not nested under
`PaperScoreboard`) because `ITeamScoreboard` is **not** a subtype of
`IScoreboard`; the Bukkit-side state is distinct (line-style uses
synthetic-row entries via `Objective.getScore(name)`, team-style
uses `Team` registration + per-team entry sets). Nesting would have
forced an artificial shared base.

Backing approach:
- Each `PaperScoreboard` / `PaperTeamScoreboard` owns its own fresh
  vanilla scoreboard from `server.scoreboardManager.newScoreboard`,
  so the "at most one objective per `DisplaySlot.SIDEBAR`" vanilla
  limit holds within each instance.
- `setTitle` ⇒ `Objective.displayName(Component)` (Paper 1.21
  Component overload, not legacy string).
- `addLine(index, ...)` ⇒ `Objective.getScore(entry).score = index`
  (sidebar sorts descending).
- `removeLine` ⇒ `Scoreboard.resetScores(entryName)`.
- `addViewer` ⇒ `Player.scoreboard = bukkitScoreboard`; `removeViewer`
  ⇒ restore `mainScoreboard`.
- Team: `Scoreboard.registerNewTeam` + `Team.displayName(Component)`,
  removal via `Team.unregister()` + `Scoreboard.resetScores`.

`PaperTabList` (140 LOC, 8 `ITabList` overrides):
- Header / footer via Adventure `Audience.sendPlayerListHeader/Footer(Component)`.
- Per-player tab name override via `Player.playerListName(Component)`.
- `clearHeader` / `clearFooter` ⇒ send `Component.empty()`.

### §C — `PaperHudGateway` (sub-A)

`plugin/core/.../adapter/paper/hud/PaperHudGateway.kt`, 486 LOC,
5 `IHudGateway` overrides.

Constructor `(server, messageRenderer, scheduler, bossBarManager,
eventBus)` adds `bossBarManager` + `eventBus` over the original
sketch since `BossBarSlot` layout routing requires the manager port
and the `InMemoryHudGateway` contract publishes the full
`HudShown/Updated/Cleared/ResourcePackPushRequested` event chain —
wiring-symmetric so audit subscribers don't go dark on the Paper
upgrade.

Layout routing:
- `ActionBar` ⇒ `Player.sendActionBar(Component)` + scheduler-driven
  TTL auto-clear (mirrors `InMemoryHudGateway.scheduleActionBarTtl`
  semantics — the cleared-handle pre-check prevents auto-clearing a
  handle that was explicitly replaced).
- `BossBarSlot` ⇒ routed through injected `IBossBarManager.createBossBar`
  + `show`; bar id `hud.slot{n}.{handle-slug}`. Title override via
  `PaperBossBar.adventureBar.name(component)` when the manager is the
  Paper concrete.
- `Subtitle` ⇒ `Player.showTitle(Title.title(empty, sub, Times.times(...)))`
  with `main = Component.empty()`.
- `Sidebar` / `TabList` ⇒ Phase 1 stub (one-shot INFO log + handle
  tracking only). The dedicated sub-B managers
  (`PaperScoreboardManager` / `PaperTabList`) own those surfaces
  directly; routing them via `IHudGateway.showFor` is a future
  ergonomic improvement.
- `pushResourcePack` ⇒ `Player.setResourcePack(url, hashBytes,
  prompt, required)` (Paper 1.20.4+ byte-hash overload; deprecated
  String-hash variant avoided). 40-char hex → 20-byte conversion
  inlined; throws-to-`HudError.ResourcePackPushFailed` on malformed
  input.

Offline player: every mutation returns `Result.failure(HudError.UnknownPlayer)`
(matches `PaperCameraGateway` contract). `clearFor` is lenient —
drains tracking + publishes `HudCleared` even when player is offline,
so subscribers stay consistent.

ADR-0149 §C invariant: no `player.health` / `foodLevel` / attribute /
gamemode mutation anywhere.

`HudComponent` → Adventure `Component` rendering:
- `Text` via `messageRenderer.render(...)` + `MiniMessage.deserialize`,
  with optional `HudColor` → `TextColor` tint.
- `Glyph` emits `<glyph:ref>` placeholder.
- `ProgressBar` emits `[####....]` ASCII placeholder.
- `Composite` folds; `Spacer` emits single space.
- `ICustomFontRegistry` PUA glyph wiring is a Wave 6b-γ concern —
  documented inline.

### §D — Wiring (orchestrator Phase C)

`AsteraCorePlugin.onEnable` HUD section (after the existing priority
0 InMemory `bossBarManager` / `scoreboardManager` / `tabList`):

```kotlin
val paperBossBarManager = PaperBossBarManager(server, scheduler)
services.register<IBossBarManager>(paperBossBarManager, priority = 100)
services.register<IScoreboardManager>(PaperScoreboardManager(server), priority = 100)
services.register<ITabList>(PaperTabList(server), priority = 100)
```

PaperHudGateway is wired later in the file (after `messages:
IMessageRenderer` is created at line ~381) since it depends on the
just-built `paperBossBarManager` + `eventBus`:

```kotlin
services.get<IHudGateway>() ?: InMemoryHudGateway(eventBus, scheduler).also {
    services.register<IHudGateway>(it, priority = 0)
}
services.register<IHudGateway>(
    PaperHudGateway(server, messages, scheduler, paperBossBarManager, eventBus),
    priority = 100,
)
val hudGateway: IHudGateway = services.getOrThrow<IHudGateway>()
```

`messageRenderer` resolved as `messages` (Wave 5b
`SimpleMessageRenderer` instance already in scope at the HUD-wiring
site).

`PaperScoreboardManager` / `PaperTabList` constructors accept
`messageRenderer? = null`; orchestrator passes `null` rather than
forward-referencing `messages` to avoid restructuring the existing
wiring order. Per-locale rendering still works correctly for
`PaperTabList.setHeader` because `sendPlayerListHeader` is per-Player
(renderer is called with the actual `playerId`). For scoreboards,
the first-viewer surrogate caveat in §B holds.

No `onDisable` cleanup required: BossBar / Scoreboard / TabList own
no scheduler tasks (auto-clear handles per `IHudGateway.clearFor`
are scheduler-owned and cancelled on the scheduler's own shutdown).

### §E — Out-of-scope (Wave 6b-stub or later)

- **PacketEvents (Wave 6b-stub)** is the gating dependency for
  several Phase 1 limitations documented inline in the source:
  - Per-viewer locale on shared `Scoreboard` text (vanilla packets
    are server-global per board). Currently uses first-viewer
    surrogate.
  - Tab list ping badges + non-online-player entries (Bukkit cannot
    synthesize player-list entries for offline / virtual subjects).
  - Per-viewer-locale BossBar (Adventure BossBar is a single instance
    shared across viewers).
- `ICustomFontRegistry` PUA glyph rendering for `Glyph` / `Spacer` /
  `ProgressBar` — Wave 6b-γ.
- `IHudGateway.showFor(layout = Sidebar/TabList)` ergonomic routing
  through the dedicated managers — currently stubbed; future
  improvement.

### §F — `ISpectator` port intentionally omitted

The plan called for `PaperSpectator` to wrap `Bukkit.GameMode.SPECTATOR`
+ camera follow. No `ISpectator` port exists in the codebase yet
— `Bukkit.GameMode` lookups happen ad-hoc in current use cases. A
proper `ISpectatorGateway` port introduction is itself non-trivial
and out of scope for Wave 6b-2 (which is bounded to "ship Paper impls
of existing HUD ports"). Defer to a separate wave that pairs port
introduction + adapter together.

## Consequences

### Positive

- Phase 1 PoC smoke-test items #4 / #7 / #8 prerequisites land. BossBar
  / Scoreboard / TabList / ActionBar / Title can all render to real
  clients; HudGateway `showFor` calls in lobby / match use cases stop
  silently no-op'ing.
- Adventure API is used consistently (Component / MiniMessage /
  Title / BossBar). Future MiniMessage-tagged i18n keys "just work".
- ADR-0149 §C invariant preserved (HUD never touches player HP /
  damage paths).
- Total LOC delta: ~1490 across 7 files, 2 sub-agents in parallel.

### Neutral / costs

- Vanilla Scoreboard line / title cap: 40 chars (conservative,
  legibility-driven). Designers asking for longer rows ⇒ revisit.
- `PaperHudGateway` Sidebar / TabList layout routing is Phase 1
  stub. Callers should use the dedicated managers directly until the
  ergonomic routing lands.
- 4 Phase 1 PoC limits are gated on PacketEvents (Wave 6b-stub):
  per-viewer locale on shared scoreboard / BossBar, tab list ping
  badges, virtual tab list entries.

### Negative

- `PaperHudGateway` constructor grew from the original sketch (5 →
  5 params) to add `bossBarManager + eventBus`. Acceptable since both
  are already in scope at the wiring site; the InMemory equivalent
  has the same shape.
- `ISpectator` port omission means `PaperSpectator` doesn't ship.
  Phase 1 PoC accepts the gap — spectator gameplay can be triggered
  by current `Bukkit.GameMode.SPECTATOR` lookups in use cases until
  the port introduction wave.

## Verification

- `./gradlew :plugin:core:compileKotlin` green.
- `./gradlew :tools:architecture-test:test` green (no new Konsist
  rules required; all 7 files live in already-permitted
  `core.adapter.paper.hud.*` package).
- `./gradlew check` BUILD SUCCESSFUL.

Manual integration check (Wave 6b smoke test prerequisites):
1. Cast a status effect skill → `PaperStatusEffectContainer`
   mirrors to vanilla potion icon (ADR-0155) AND the BossBar
   progress meter shows tick-effect remaining duration if the
   HUD use case calls `IBossBarManager.createBossBar`.
2. `/astera-match start` → scoreboard sidebar shows team scores
   in real time; tab list footer shows match phase + timer.
3. `MatchEnded` ⇒ subtitle / title sequence displays winning team.
4. Send action bar via `IHudGateway.showFor(ActionBar, ttl)` →
   client sees the message + auto-clear at ttl expiry.

## References

- [[adr/0022-multi-plugin-architecture]] — sibling-isolation
  (all 7 files in core)
- [[adr/0026-paper-runtime-topology]] — Wave 6 Paper sweep umbrella
- [[adr/0031-hud-port-architecture]] — IBossBar / IScoreboard /
  ITabList port shape this wave concretes
- [[adr/0149-astera-internal-hp-death-model]] — §C invariant
  preserved by all 7 adapters
- [[adr/0153-bukkit-event-adapter]] — Adventure API precedent
  (PaperBroadcaster MiniMessage rendering pattern)
- [[adr/0156-paper-world-block-barrier-adapters]] — Wave 6b-1
  precedent
- [[adr/0158-paper-inventory-and-bridges]] — companion sub-wave 6b-3
  (Inventory + Broadcaster + RewardBridge)
