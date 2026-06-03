---
status: Accepted
date: 2026-06-03
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0168: Wave 6g-visibility — Paper-native per-viewer hologram visibility, no PacketEvents

## Status

Accepted. Implements `HologramVisibilityScope.PerPlayer` end-to-end in
[PaperHologramGateway][paper-holo] using Paper-native APIs
(`Entity#setVisibleByDefault` + `Player#showEntity` / `Player#hideEntity`).
**Carves out** the per-viewer entity-visibility clause of [ADR-0095][adr-0095]
without forcing PacketEvents adoption for this surface. ADR-0095's other
clauses (inbound movement interception for anti-cheat, replay coordinate
masking) remain genuine PacketEvents targets and are unaffected by this
ADR.

## Context

The Wave 6 Paper sweep left two ports stubbed under the same rationale
"needs PacketEvents":

1. **`PaperHologramGateway.logScopeStub`** (Wave 6f / ADR-0163 §visibility-
   stub) — `HologramVisibilityScope.PerPlayer` / `PerTeam` were recorded
   in the shadow map but every tracker still received the spawn packet.
2. **`PaperHudGateway` / `PaperTabList` / `PaperScoreboard` per-viewer
   locale + team-name** (Wave 6c-stub-packetevents) — referenced
   PacketEvents in multiple TODOs.

Last-session handoff (2026-06-03) ranked these together: *"Wave 6g-visibility
+ Wave 6c-stub-packetevents — single PacketEvents dep decision unblocks
both."* The decision being deferred was: **adopt PacketEvents under
softdepend, or stay vanilla?**

A research pass this session (`web-search-agent`, 2026-06-03) surfaced two
load-bearing facts that change the equation:

### Fact 1 — Paper 1.20+ exposes `Entity.setVisibleByDefault` + `Player.showEntity(plugin, entity)`

Paper documents these as the canonical per-viewer visibility primitives.
Under the hood the implementation does exactly what PacketEvents would do
(suppress / send the `ClientboundAddEntityPacket` per viewer), but the
surface is first-class Bukkit API.

Caveats:
- `Player.showEntity` state is **per-session** — re-applied on reconnect.
- Off-main calls require server-thread marshalling (same as the rest of
  the gateway).

References:
- [PaperMC Display Entities docs](https://docs.papermc.io/paper/dev/display-entities/)
- [Paper 1.21 Player Javadoc — `showEntity(Plugin, Entity)`](https://jd.papermc.io/paper/1.21.4/org/bukkit/entity/Player.html)
- [PaperMC Discussion #10567 — `hideEntity`/`setVisibleByDefault` semantics](https://github.com/PaperMC/Paper/discussions/10567)

### Fact 2 — Adventure `Component` per-viewer rendering covers HUD locale without packet manipulation

`Player#sendActionBar(Component)`, `Player#sendPlayerListHeaderAndFooter(...)`,
`BossBar#addViewer(player)`, `Player#showTitle(Title)` all accept a
per-call Component. Building the Component per-viewer with the viewer's
locale before send is idiomatic Adventure — no packet pipeline needed.

The Wave 6c-stub-packetevents TODOs in `PaperScoreboard.kt` / `PaperTabList.kt`
that genuinely need PacketEvents (per-viewer team-name rendering, tab-list
fake-entry injection) are a **separate concern**. Those surfaces *can't*
be solved by Paper-native APIs because the underlying packets (`ClientboundSetPlayerTeamPacket`
mutation, `ClientboundPlayerInfoUpdatePacket` injection of phantom entries)
are not exposed at the Bukkit layer. Adopting PacketEvents for those will
remain a separate decision.

This ADR is therefore narrowed to **Wave 6g-visibility only**:
implement `HologramVisibilityScope.PerPlayer` via Paper-native, document
the carve-out from ADR-0095, defer `HologramVisibilityScope.PerTeam` to a
follow-up wave (separate concern: cross-plugin team-roster resolver).

## Decision

### §A — `applyVisibility(entity, scope)` replaces `logScopeStub`

[`PaperHologramGateway`][paper-holo] gains a private `applyVisibility(Entity,
HologramVisibilityScope)` method invoked from both `spawn` (after the entity
spawns) and `updateVisibility` (after the shadow-map write). Per-scope
behaviour:

- `Everyone` → `entity.isVisibleByDefault = true`. Used both as the spawn
  default (no-op) and to *clear* a prior PerPlayer / PerTeam scope on
  `updateVisibility(Everyone)`.
- `PerPlayer(playerIds)` → `entity.isVisibleByDefault = false`, then
  `server.getPlayer(id.uuid)?.showEntity(plugin, entity)` per listed player.
  Offline-at-spawn players are tracked in `live[handle].visibilityScope`
  and re-applied on join (§C).
- `PerTeam(matchId, teamId)` → logs INFO at first occurrence per server
  lifetime; behavior remains "every tracker sees the entity" until the
  follow-up wave wires the team-roster resolver (§D).

### §B — `start(plugin: JavaPlugin)` lifecycle hook

The gateway previously held no `JavaPlugin` reference. Adding one to the
constructor would force every call site (including the existing test
fixtures and the InMemory-priority-0 wiring path) to plumb one through.
Instead, the gateway gains a `start(plugin)` method called from
`AsteraCorePlugin.onEnable` right after `register(gateway, priority = 100)`.
The plugin reference is stored in a `private var plugin: JavaPlugin?` and:

- consumed by `applyVisibility` for `Player.showEntity` / `hideEntity`
- passed to `pluginManager.registerEvents` for the join-reapply listener (§C)

If `applyVisibility(PerPlayer)` is invoked before `start` (e.g. unit tests),
the gateway logs WARN and leaves the entity globally visible — the
visibility *intent* is recorded in the shadow map, so a later
`updateVisibility(scope)` after `start` resyncs.

### §C — `JoinReapplyListener` for reconnect handling

Bukkit's `Player.showEntity` state is per-session. A player who reconnects
sees every hologram (including those that should be hidden from them) unless
`showEntity` is reapplied. A private `inner class JoinReapplyListener` is
registered in `start(plugin)`:

- `@EventHandler(priority = MONITOR, ignoreCancelled = true)` on
  `PlayerJoinEvent`.
- Walks the `live` shadow map, filters to `PerPlayer` scopes containing the
  joining player, resolves each `entityId → Entity` via `server.getEntity`,
  and calls `player.showEntity(plugin, entity)`.
- Skips PerTeam (carve-out §D).
- No cleanup on `PlayerQuitEvent` needed — Bukkit drops the per-session
  visibility state automatically.

`onDisable` of the host plugin invokes `HandlerList.unregisterAll(plugin)`
automatically; no explicit teardown method on the gateway.

### §D — PerTeam deferred to follow-up wave

`HologramVisibilityScope.PerTeam(matchId, teamId)` requires resolving
`(MatchId, TeamId) → Set<PlayerId>`. `IMatchRepository` (which owns the
`Match.teams[i].members` roster) lives in `:plugin:core` interface but its
impl in `:plugin:match`. AsteraMatchPlugin loads *after* AsteraCorePlugin,
so a constructor-time `getOrThrow<IMatchRepository>()` in the Core wiring
order fails. The clean fix is a small follow-up wave that:

- introduces an `ITeamRosterLookup`-shaped port (or extends an existing
  one) with `rosterOf(MatchId, TeamId): Set<PlayerId>?`
- registers an in-memory impl in `AsteraMatchPlugin.onEnable` at priority 0
- has `PaperHologramGateway` lazily look it up via `services.get<...>()`
  at `applyVisibility` call time (post-Match-load, so resolution succeeds)

This ADR keeps the PerTeam path stubbed (single INFO log per server
lifetime, identical posture to the PerPlayer stub it replaces) so the
gap is visible in ops but doesn't crash. Implementation lands in the
follow-up wave.

### §E — ADR-0095 carve-out, not supersede

ADR-0095 covers three packet-related surfaces:

1. anti-cheat inbound movement interception (Wave 5q)
2. per-viewer outbound entity visibility (Wave 5n / 5y)
3. replay coordinate masking (Wave 5w future)

This ADR addresses **only #2**, and only the subset that Paper-native
APIs cover (entity visibility — not the per-viewer team-name / tablist-
fake-entry sub-cases). ADR-0095's status stays `Accepted`. The KDoc on
[`IPlayerVisibilityGateway`][ipvg] continues to describe PacketEvents as
the canonical packet-level strategy; this ADR documents that the
hologram path opts out of that strategy on the strength of `setVisibleByDefault`
+ `showEntity` being sufficient for its narrower scope.

When a future wave implements the general `PaperPlayerVisibilityGateway`
(for non-hologram entities), it inherits the same trade-off and can copy
the pattern. PacketEvents adoption stays on the table for #1 and #3 where
no Paper-native equivalent exists.

## Consequences

### Positive

- **Wave 6g-visibility ships without any new dependency.** No PacketEvents,
  no ProtocolLib, no plugin softdepend slot. Astera continues to boot in
  vanilla Paper with no external plugins (CLAUDE.md §4 invariant
  preserved).
- **PerPlayer is real now.** The `logScopeStub` INFO line is gone for
  PerPlayer; ops sees actual per-viewer enforcement.
- **PerTeam carve-out is honest.** The stub log still fires for PerTeam,
  but the message now references the resolver gap (not the missing packet
  library — that's no longer the blocker).
- The pattern (gateway holds `JavaPlugin` reference + Listener inner
  class for reconnect reapply) is reusable for the eventual general
  `PaperPlayerVisibilityGateway` when it lands.

### Neutral / costs

- ~120 LOC added to `PaperHologramGateway`: 60 LOC `applyVisibility` +
  20 LOC `start` + 25 LOC `JoinReapplyListener` + KDoc updates + imports.
- 17 LOC AsteraCorePlugin wiring change (capture gateway as `val`, call
  `start(this)`).
- Two `var text: String?` deprecation warnings on TextDisplay.text are
  pre-existing and unrelated to this wave; surfacing them again here
  is noise but acceptable.

### Negative

- Reconnect-reapply is an O(holograms × PerPlayer-scope filter) walk on
  every `PlayerJoinEvent`. Phase 1 PoC volumes (handful of holograms)
  make this negligible; if hologram counts grow into the hundreds the
  listener should switch to an inverse index `playerId → Set<HologramHandle>`
  rebuilt on `applyVisibility`.
- PerTeam stays stubbed. A consumer that *only* uses PerTeam still sees
  the old "every tracker visible" behaviour. The follow-up wave is small
  (port + resolver + lazy lookup); not blocked by anything other than
  scope-control on this ADR.

## Verification

- `./gradlew :plugin:core:compileKotlin` BUILD SUCCESSFUL.
- `./gradlew check` BUILD SUCCESSFUL.
- 58 Konsist rules green (no rule change).
- `InMemoryHologramGatewayTest` still passes — InMemory variant continues to
  return correct `viewers()` per scope; PaperHologramGateway's behavioural
  contract was already pinned by the InMemory tests.
- Manual smoke (Phase 1 dev tooling):
  1. Spawn a hologram with `PerPlayer(setOf(playerA))`.
  2. Confirm playerA sees it, playerB does not.
  3. Reconnect playerA.
  4. Confirm playerA still sees it post-reconnect (verifies
     `JoinReapplyListener`).
  5. `updateVisibility(handle, Everyone)`.
  6. Confirm both playerA and playerB now see it (verifies the
     `Everyone` carry-back path).
  7. `updateVisibility(handle, PerTeam(matchId, teamId))`.
  8. Confirm INFO log fires once per server lifetime + every tracker
     still sees the entity (carve-out §D).

## Alternatives Considered

- **Adopt PacketEvents under softdepend, implement
  `PaperPlayerVisibilityGateway` end-to-end.** Adds 1 external Paper-plugin
  softdepend (GPL-3.0 license forces softdepend-only, no shading), a new
  packet API surface to wrap behind `IPacketGateway`, and ~600 LOC of
  adapter + test. Rejected for this wave because the Paper-native primitives
  cover the hologram scope perfectly and PacketEvents wasn't needed for the
  use case driving the wave. Keep PacketEvents on the table for the
  anti-cheat (movement interception) + replay (coordinate masking) +
  scoreboard/tablist per-viewer team-name use cases where it's actually
  required.
- **Adopt ProtocolLib instead of PacketEvents.** Older API, sync-on-main-
  thread design, smaller maintenance momentum in 2026. Same softdepend cost.
  Rejected for the same scope-control reason.
- **Direct NMS via Paper internals.** Forbidden by Konsist `core.adapter.api.*`
  Bukkit-free rule (this code lives in `core.adapter.paper.hologram.*` so
  Bukkit imports are fine, but NMS access also breaks across Paper minor
  versions and is fragile). Rejected.
- **Implement `PaperPlayerVisibilityGateway` first, route hologram visibility
  through `IPlayerVisibilityGateway`.** Cleaner architectural fit (no
  duplicated logic if other entities need per-viewer visibility later),
  but a larger wave (~400 LOC + reach into Match plugin for the EntityHandle
  translation). Right call when the second consumer lands; not justified
  yet by hologram alone. The pattern this ADR establishes (Paper-native
  visibility primitives + reconnect-reapply listener) transfers cleanly.
- **Solve PerTeam in this wave.** Would force the cross-plugin team-roster
  resolver design into the same diff. Rejected to keep this ADR's scope
  tight; the follow-up wave is small.

## References

- [[adr/0022-multi-plugin-architecture]] §sibling-isolation — the rule
  that blocks `:plugin:core` from directly importing `:plugin:match`
  types (forces the deferred-PerTeam resolver pattern).
- [[adr/0095-packet-system-and-per-viewer-visibility]] — the ADR this
  wave carves out from. Per-viewer entity visibility no longer needs
  PacketEvents; other clauses (movement / replay) stay PacketEvents
  candidates.
- [[adr/0163-paper-sweep-round-4-hologram]] §visibility-stub — Wave 6f's
  documented stub posture this wave fills in.
- [Paper docs — Display entity visibility](https://docs.papermc.io/paper/dev/display-entities/)
- [Paper Javadoc — `Player#showEntity(Plugin, Entity)`](https://jd.papermc.io/paper/1.21.4/org/bukkit/entity/Player.html)

[paper-holo]: ../../plugin/core/src/main/kotlin/com/astarworks/astera/core/adapter/paper/hologram/PaperHologramGateway.kt
[adr-0095]: ./0095-packet-system-and-per-viewer-visibility.md
[ipvg]: ../../plugin/core/src/main/kotlin/com/astarworks/astera/core/application/port/outbound/visibility/IPlayerVisibilityGateway.kt
