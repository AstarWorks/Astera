---
status: Accepted
date: 2026-06-05
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0170: Wave 6c-stub-packetevents (A) — PacketEvents 2.x adoption + PaperPacketInterceptor foundation

## Status

Accepted. First-stage adoption of [PacketEvents 2.x][packetevents] as a
`compileOnly` softdepend, plus a Paper-side [`IPacketInterceptor`][port]
implementation that runs the existing `MovementValidatorPacketBridge`
(Wave 5q anti-cheat) against real Netty-intercepted client packets.

This is the **foundation** wave; the user-facing scoreboard / tablist
per-viewer rendering work [last-session handoff §3] flows on top in a
separate wave that wires the outbound `User.sendPacket` path. The
foundation alone closes the long-deferred ADR-0095 §5 promise (Wave 6b
PacketEvents adapter swaps the InMemory `IPacketInterceptor` at priority 100)
and makes Wave 5q anti-cheat genuinely cancel inbound move packets rather
than running only against synthesised dispatches from tests.

## Context

ADR-0095 (Wave 5y, 2026-05-21) ratified PacketEvents adoption: the
`IPacketInterceptor` / `IPlayerVisibilityGateway` ports, the type-erased
`PacketEnvelope` / `PacketDecision` boundary, and the six-variant
`PacketKind` enumeration all shipped at that time. The Paper-side
implementation was deferred to "Wave 6b" with a runtime fallback to the
InMemory variant.

Wave 6g-visibility (ADR-0168) and Wave 6g-PerTeam (ADR-0169) discovered
that Paper 1.20+ exposes per-viewer entity visibility primitives
(`Entity.setVisibleByDefault` + `Player.showEntity` / `hideEntity`)
sufficient for the hologram surface — so hologram visibility shipped
without PacketEvents and the ADR-0095 §5 PacketEvents requirement was
narrowed to the surfaces Paper-native APIs *don't* cover:

- **Anti-cheat inbound movement interception** (Wave 5q
  `MovementValidatorPacketBridge`): the validator must cancel the inbound
  `PLAYER_POSITION` / `PLAYER_ROTATION` / `PLAYER_POSITION_AND_ROTATION`
  packet *before* Paper applies the position update. The Bukkit
  `PlayerMoveEvent` fires *after* the move is logically applied, leaving
  a one-tick window where a cheating client lands a hit before the
  server rolls back. This is the canonical "PacketEvents is the only
  way" scope.
- **Per-viewer scoreboard team-name rendering** (`PaperScoreboard.kt`,
  `PaperTeamScoreboard.kt`): vanilla Scoreboard packets are server-wide
  per scoreboard. To render different locales for different viewers a
  per-viewer `ClientboundSetPlayerTeamPacket` must be sent.
- **Per-viewer tab-list fake-entry injection** (`PaperTabList.kt`):
  vanilla `Player.setPlayerListName` only updates real players' rows;
  synthetic / offline entries need per-viewer `ClientboundPlayerInfoUpdatePacket`
  with phantom UUIDs.
- **Replay coordinate masking** (Wave 5w future) and **chat-channel
  scoping** — preserved targets for later waves.

This wave (6c-stub-packetevents-A) lands the **infrastructure** that all
four use cases share:

- A `compileOnly` PacketEvents dependency in `gradle/libs.versions.toml`
  + `:plugin:core/build.gradle.kts`.
- A `softdepend: [packetevents]` declaration in `plugin/core/.../plugin.yml`
  so the runtime PluginManager loads PacketEvents before Astera-Core's
  `onLoad` runs.
- A `PacketEventsBootstrap` class that wraps the 2.x lifecycle (`load` →
  `init` → `terminate`) and is **isolated** so the JVM does not load any
  PacketEvents symbol unless a prior plain `Bukkit.getPluginManager().getPlugin("packetevents")`
  gate has confirmed it's installed.
- A `PaperPacketInterceptor` implementing `IPacketInterceptor` that
  registers as a PacketEvents `PacketListener` and translates
  `PacketReceiveEvent` / `PacketSendEvent` to the existing
  `PacketEnvelope` / `PacketDecision` exchange.
- Conditional wiring in `AsteraCorePlugin.onLoad` / `onEnable` /
  `onDisable` that engages the lifecycle only when PacketEvents is
  installed.

User-facing consumers (scoreboard / tablist per-viewer; per-viewer
visibility EntitySpawn drops) ride on this foundation in follow-up
waves; this ADR documents the foundation only.

## Decision

### §A — PacketEvents `compileOnly` dep + runtime detection

`gradle/libs.versions.toml` gains `packetevents = "2.12.2"` (current
stable, 2026-06-01; supports Minecraft 26.1) and a `packetevents-spigot`
library entry (`com.github.retrooper:packetevents-spigot`). The CodeMC
repository that hosts it is already declared in `settings.gradle.kts`
(line 19, `codemc-releases`) so no new repo is needed.

`plugin/core/build.gradle.kts` adds `compileOnly(libs.packetevents.spigot)`.
**No `implementation`**, no shading — Astera ships its own jar without
PacketEvents bytecode embedded. The GPL-3.0 PacketEvents license does
NOT infect the MIT codebase under the standard softdepend + no-shading
interpretation (the user's installed PacketEvents plugin provides the
classpath at runtime; Astera's jar is independent).

`plugin/core/.../plugin.yml` adds `softdepend: [packetevents]` — loads
PacketEvents first (so `JavaPlugin.onLoad` can call into PacketEvents'
API) without forcing its presence; absent PacketEvents the boot proceeds
unchanged and the InMemory variant continues to serve.

### §B — `PacketEventsBootstrap` lifecycle isolation

`plugin/core/.../adapter/paper/packetevents/PacketEventsBootstrap.kt` is
an `object` (Kotlin singleton) holding the four lifecycle calls:

- `isInstalled(plugin)` — Bukkit-only check; **never references a
  PacketEvents class** so it can be called from anywhere safely.
- `load(plugin)` — `PacketEvents.setAPI(SpigotPacketEventsBuilder.build(plugin))`
  + `.load()`. Called from `AsteraCorePlugin.onLoad`.
- `init()` — `PacketEvents.getAPI().init()`. Called from `onEnable`.
- `terminate()` — `PacketEvents.getAPI().terminate()`. Called from
  `onDisable`.

The isolation is load-bearing: when PacketEvents is absent at runtime,
the JVM never resolves the `com.github.retrooper.packetevents.*` symbols
in this object (per JLS §5.3.4 lazy resolution), and no
`NoClassDefFoundError` fires. `AsteraCorePlugin.onLoad` gates entry with
`if (PacketEventsBootstrap.isInstalled(this))` — the gate uses only
Bukkit APIs, so it itself doesn't pre-load the object's static refs.

### §C — `PaperPacketInterceptor` translation layer

`plugin/core/.../adapter/paper/packetevents/PaperPacketInterceptor.kt`
implements:

- `IPacketInterceptor.register / unregister` — append/remove from a
  `CopyOnWriteArrayList<Entry>` (PacketEvents callbacks run on Netty
  threads; the list must tolerate concurrent iteration / mutation).
- `PacketListener.onPacketReceive / onPacketSend` — translate the
  PacketEvents event to a `PacketKind` via private `inboundKindOf` /
  `outboundKindOf` mappers; build a vendor-neutral `PacketEnvelope`;
  dispatch through registered listeners in registration order; first
  `PacketDecision.Cancel` calls `event.setCancelled(true)` and
  short-circuits.

The **Phase 1 PoC mapping is intentionally narrow**: only the inbound
movement packet kinds (`PLAYER_POSITION`, `PLAYER_ROTATION`,
`PLAYER_POSITION_AND_ROTATION` → `PacketKind.MovementServerbound`) are
wired, because the existing `MovementValidatorPacketBridge` is the sole
current consumer. The five other `PacketKind` values
(`MovementClientbound`, `DamageAnimation`, `ChatClientbound`,
`EntitySpawn`, `EntityDestroy`) get null mappings — the adapter
short-circuits dispatch with zero allocations for PacketEvents events
that have no Astera consumer. Follow-up waves add their mappings
alongside their consumers.

### §D — `PacketDecision.Replace` deferred

The InMemory variant implements `Replace` by merging the new payload
into the next listener's envelope. The Paper adapter can't do this
generically — there's no version-agnostic way to mutate an arbitrary
`PacketWrapper<*>` field set. ADR-0095's design assumed typed `Replace`
handlers per `PacketKind` (the adapter knows which fields to overwrite
based on the wire convention). Phase 1 PoC has zero `Replace`
consumers (the InMemory dispatch path services tests only), so the
Paper adapter logs INFO once per server lifetime and treats `Replace`
as `Pass`. Visibly degrading rather than silent-misbehaving.

A follow-up wave (per `PaperPacketInterceptor` KDoc §future-work)
lands typed Replace handlers when the first consumer (Wave 5w replay
coordinate masking is the natural candidate) arrives.

### §E — Conditional wiring in `AsteraCorePlugin`

`onLoad` (new method, didn't exist pre-wave) — gate-check
`PacketEventsBootstrap.isInstalled(this)`; if true, set
`packetEventsInstalled = true` and call `PacketEventsBootstrap.load(this)`.
Logs INFO either way so ops sees which mode the plugin booted in.

`onEnable` (mid-method, before the existing Wave 5y in-memory wiring
block) — if `packetEventsInstalled`, call `PacketEventsBootstrap.init()`,
construct `PaperPacketInterceptor(tickProvider = { scheduler.currentTick() })`,
call `start()` (registers with PE event manager), then
`services.register<IPacketInterceptor>(it, priority = 100)`. The
existing block at line ~1745 (`val packetInterceptor: IPacketInterceptor =
services.get<IPacketInterceptor>() ?: InMemoryPacketInterceptor()…`)
now resolves to the Paper variant when PE is installed and the InMemory
variant otherwise — no other code path changes.

`onDisable` (terminate block) — if `packetEventsInstalled`, call
`PacketEventsBootstrap.terminate()` after the existing teardown but
before `paperScheduler?.stop()`.

## Consequences

### Positive

- **Wave 5q anti-cheat is genuinely cancellable now.** With PacketEvents
  installed, `MovementValidatorPacketBridge` cancellations drop the
  inbound move packet at the Netty layer — the server never applies the
  cheat-detected move. Previously the InMemory variant only ran in
  tests; in prod `PlayerMoveEvent` fired post-apply and the bridge
  couldn't actually stop the move.
- **ADR-0095 §5 promise closed.** The "Wave 6b Paper adapter swaps the
  InMemory IPacketInterceptor at priority 100" deferred work is
  implemented.
- **CLAUDE.md §4 invariant preserved.** Astera boots vanilla without
  PacketEvents — runtime detection gates every PacketEvents code path,
  and the InMemory variant continues to serve when absent. No `depend:`
  in plugin.yml; soft only.
- **Foundation for downstream waves.** Per-viewer scoreboard / tablist
  rendering (the user-facing scope from the handoff) can ship as
  follow-up waves on top of this without redoing the lifecycle work.
- **Class-load isolation is rigorous.** `PacketEventsBootstrap` and
  `PaperPacketInterceptor` never resolve their PacketEvents symbols
  when the runtime check fails — Astera-Core boots cleanly on a vanilla
  Paper server with no NoClassDefFoundError, no log noise beyond a
  single INFO "PacketEvents plugin not installed" line.

### Neutral / costs

- **+1 external softdepend** (PacketEvents 2.12.2 — GPL-3.0). Astera's
  external-Paper-plugin softdepend roster grows from 0 to 1. The
  CLAUDE.md §4 rule explicitly permits this; the WeaponMechanics /
  Oraxen / MythicMobs sentinels in `gradle/libs.versions.toml` document
  the existing posture.
- **~300 LOC** across PaperPacketInterceptor (220) + PacketEventsBootstrap
  (45) + AsteraCorePlugin wiring (40) + plugin.yml + libs.versions.toml.
- **No new tests** in this wave. PacketEvents requires a real Paper +
  PE runtime to exercise; pure-Kotlin testing would need MockBukkit +
  a PE test harness Astera doesn't currently maintain. The
  InMemoryPacketInterceptor + `MovementValidatorPacketBridge` test
  pair continue to cover the contract. Manual smoke (start Paper +
  PacketEvents, trigger anti-cheat) is documented in §Verification.
- **Konsist no rule change.** No new architectural invariant — Paper
  ecosystem imports were already permitted in `adapter.paper.*` and
  the new code respects that boundary.

### Negative

- **PacketEvents calls run on Netty threads, not Paper main.** Listeners
  registered via `IPacketInterceptor.register` must be thread-safe and
  must not call Bukkit APIs that require the main thread. The existing
  contract (per `IPacketInterceptor` KDoc and ADR-0095) requires this,
  but the Paper adapter is the first impl that actually enforces it.
  Existing Astera consumer (`MovementValidatorPacketBridge`) is
  thread-safe by construction; future consumers must respect this.
- **`PacketDecision.Replace` returns `Pass` in the Paper adapter.** A
  consumer that needs Replace must wait for the typed-handler follow-up
  wave. Today's only Replace consumer is the InMemory test path, so
  no production regression.
- **GPL-3.0 license in the dep graph.** Softdepend + no-shading is the
  standard mitigation; the legal posture is well-established in the
  Paper plugin ecosystem (ProtocolLib is GPL-2.0 with the same
  pattern). Operators distributing Astera as a binary must NOT bundle
  PacketEvents — point them at PacketEvents' own distribution.

## Verification

- `./gradlew :plugin:core:compileKotlin` BUILD SUCCESSFUL — the
  `compileOnly` dep resolves, PacketEvents 2.x symbols import correctly,
  the Kotlin/Java interop on `event.setCancelled(true)` + `event.user`
  works.
- `./gradlew check` BUILD SUCCESSFUL — full plugin suite still passes.
- 58 Konsist rules green — no rule change.
- Manual smoke (post-merge):
  1. Boot vanilla Paper without PacketEvents → log "PacketEvents plugin
     not installed — staying on InMemoryPacketInterceptor". `services.get<IPacketInterceptor>()`
     resolves to `InMemoryPacketInterceptor`.
  2. Install PacketEvents 2.12.2 on the same server → log "PacketEvents
     loaded (lifecycle phase 1/3)" in onLoad and "PaperPacketInterceptor
     registered at priority 100" in onEnable. `services.get<IPacketInterceptor>()`
     resolves to `PaperPacketInterceptor`.
  3. Trigger Wave 5q anti-cheat (rubber-band detection) → confirm the
     inbound `PLAYER_POSITION` packet is cancelled at the Netty layer
     (PacketReceiveEvent.isCancelled() == true), no `PlayerMoveEvent`
     fires, the player is rolled back to last validated position.
  4. `./astera-server stop` → log "PacketEvents terminated" via
     onDisable; no orphaned PE threads.

## Alternatives Considered

- **Adopt ProtocolLib instead of PacketEvents.** Older API, sync-on-
  main-thread design, smaller maintenance momentum in 2026. Same
  softdepend cost. Rejected per the web-search-agent finding and
  ADR-0095's existing direction.
- **Ship PaperPacketInterceptor + per-viewer scoreboard + per-viewer
  tablist in a single wave.** Conservatively ~1500 LOC across 4 files
  with 3 distinct ADR-worthy decisions (foundation, scoreboard
  per-viewer, tablist fake-entry injection). Splitting keeps each
  ADR + commit reviewable. This wave is A; the scoreboard / tablist
  consumers are B / C.
- **Skip the runtime detection gate; require PacketEvents as `depend:`.**
  Would violate CLAUDE.md §4. Rejected.
- **Map all six `PacketKind` values in the Paper adapter eagerly.**
  Costs dispatch work + envelope allocation on every PacketEvents
  event for kinds that have zero consumers. The narrow-by-default
  mapping pattern (only consumed kinds get non-null mappings) was
  endorsed by ADR-0095 §"Limited PacketKind surface".
- **Implement `PacketDecision.Replace` via reflection on `PacketWrapper`.**
  Brittle across PE major versions, expensive at dispatch time, and
  benefits zero current consumers. Typed handlers per kind (when
  consumers arrive) keep the boundary explicit.

## References

- [[adr/0009-soft-dep-external-plugins]] — the umbrella "all external
  Paper plugins are soft-deps" rule this wave honours.
- [[adr/0015-external-plugin-interrogation]] §"ProtocolLib → PacketEvents"
  — the original Phase 3+ deferral that ADR-0095 superseded for
  PacketEvents specifically. Today's wave finally implements what
  ADR-0095 ratified.
- [[adr/0072-pre-paper-sweep-port-commitments]] — `compileOnly`
  sentinel pattern for external Paper plugins (`weaponmechanics`,
  `oraxen`, `mythic-dist`). PacketEvents follows the same posture
  but with a real version (not sentinel) because the consumer
  actually compiles against PE 2.x types.
- [[adr/0090-packet-system-and-per-viewer-visibility]] — the original
  `IPacketInterceptor` / `PacketEnvelope` / `PacketDecision` design.
  (Cross-link uses ADR-0090 as documented in `IPlayerVisibilityGateway`
  KDoc; the ADR is filed at 0095 per current numbering, which the
  text uses interchangeably.)
- [[adr/0095-packet-system-and-per-viewer-visibility]] — primary
  reference for the design this wave implements.
- [[adr/0168-wave-6g-visibility-paper-native-no-packetevents]] — the
  carve-out that narrowed ADR-0095's PacketEvents requirement to the
  surfaces Paper-native APIs don't cover; this wave fills one of
  those (anti-cheat inbound interception).
- [PacketEvents 2.12.2 release notes][packetevents] — version anchor.
- [PacketEvents Creating an Instance guide](https://docs.packetevents.com/creating-your-packetevents-instance)
  — `load/init/terminate` lifecycle.

[port]: ../../plugin/core/src/main/kotlin/com/astarworks/astera/core/application/port/outbound/packet/IPacketInterceptor.kt
[packetevents]: https://github.com/retrooper/packetevents/releases
