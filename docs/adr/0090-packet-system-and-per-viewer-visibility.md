---
status: Accepted
date: 2026-05-21
deciders: Wave 5y (Y-RyuZU + Claude)
supersedes: null
superseded_by: null
---

# ADR-0090: Packet system adoption (PacketEvents) + per-viewer visibility gateway

## Status

Accepted. Supersedes the "PacketEvents adoption deferred to Phase 3+" clause
in [[adr/0015-external-plugin-interrogation]] §"ProtocolLib → PacketEvents".
The packet-section supersede note is recorded in ADR-0015 directly (the rest
of ADR-0015 stays Accepted).

## Context

Three Wave 5 surfaces converged on "we need a packet pipeline":

1. **Wave 5q anti-cheat (ADR-0083)** wired `IMovementValidator` to Paper's
   `PlayerMoveEvent`. That fires *after* Paper has logically updated the
   player position — a cheating client can race the server and land a hit
   before the move is rolled back. Packet-level interception drops the
   inbound `MovementServerbound` before Paper applies it.

2. **Wave 5n region permissions + Wave 5y per-viewer visibility** need the
   inverse: drop *outbound* spawn / destroy packets per-viewer so a hidden
   entity is genuinely invisible to the affected client. `Player.hidePlayer`
   handles other players but cannot suppress arbitrary entity spawns; the
   scoreboard-team trick only changes nameplate / collision and leaves the
   entity model visible.

3. **Wave 5w replay system (planned)** needs to mask coordinate fields in
   the recorded packet stream when a privacy-sensitive player has opted out.
   This is `PacketDecision.Replace` semantics.

ADR-0015 §"ProtocolLib → PacketEvents" had deferred packet-library adoption
to Phase 3+ "via a future ADR". Wave 5y *is* that future ADR. The deferral
was the conservative choice when Phase 1 had no concrete demand; with three
demand sites concentrated in Waves 5q–5y, the cost of *not* adopting a
packet library is forcing every downstream wave to invent a Paper-side
workaround that the Wave 6b adapter would later replace anyway.

The remaining question was the type-system boundary: PacketEvents' API
surface is hundreds of `PacketWrapper<*>` types that change shape on every
Minecraft version bump. Letting that surface leak into `core.domain` would
either (a) tie the domain to a specific PE major + MC version, or (b) force
an Astera-internal mirror that dedupes nothing.

## Decision

1. **Adopt [PacketEvents](https://github.com/retrooper/packetevents) as the
   packet library for Wave 6b's Paper adapter.** Declared as a soft-dep
   (ADR-0009 line) — Astera continues to boot in vanilla Paper.

2. **Introduce `IPacketInterceptor` + `IPlayerVisibilityGateway` outbound
   ports in `plugin:core` (Wave 5y, this wave).** The ports are
   **type-erased**: `IPacketInterceptor` exchanges `PacketEnvelope(kind,
   playerId, payloadView: Map<String, Any>, atTick)`, and the application
   layer pattern-matches on `PacketKind` enum variants for the curated
   subset Astera actually intercepts.

3. **`core.domain` MUST NOT import PacketEvents.** Konsist rule
   `core.domain.* must not import com.github.retrooper.packetevents.*`
   enforces this. The `PacketEnvelope.payloadView` is the only contract —
   the Wave 6b adapter is the authoritative source of keyname conventions
   (`x` / `y` / `z` / `yaw` / `pitch` / `entityId` / `message`).

4. **Limited `PacketKind` surface.** Six variants for Phase 1
   (`MovementClientbound`, `MovementServerbound`, `DamageAnimation`,
   `ChatClientbound`, `EntitySpawn`, `EntityDestroy`). Any wave that wants
   a new kind MUST land a new ADR clause; the cost of the ADR is the rate
   limiter against "incidental" packet-surface growth.

5. **`IPlayerVisibilityGateway` is packet-first.** The default in-memory
   impl maintains a per-viewer hide index; the Wave 6b Paper adapter
   subscribes to `EntitySpawn` / `EntityDestroy` via `IPacketInterceptor`
   and drops the egress packet when the (viewer, target) pair is in the
   hide index. When PacketEvents is absent, the adapter degrades to
   `Player.hidePlayer` + spawn-suppression no-op for non-player entities,
   surfacing the gap as
   `VisibilityError.BackendError(PacketBackendError.PacketEventsUnavailable)`.

6. **Wave 5q anti-cheat integration.** `MovementValidatorPacketBridge`
   registers on `PacketKind.MovementServerbound`; on
   `MovementValidation.Reject` it returns `PacketDecision.Cancel` (drop the
   move packet so the client snaps back) and reports a
   `WallClipping(from, to)` suspicion through the existing
   `AnticheatOrchestrator`.

## Consequences

### Positive

- Anti-cheat now runs *before* Paper applies the move (Wave 5q-fix surface).
- Per-viewer visibility is real, not the scoreboard-team approximation.
- Replay-system + spectator-vision waves (5w, 6f) get a uniform packet
  surface without inventing per-feature workarounds.
- Type-erasure boundary keeps `core.domain` stable across MC version bumps;
  the surface that *does* break is the Wave 6b adapter, which is
  vendor-edge code anyway.
- Astera continues to boot in vanilla Paper — the in-memory implementations
  are non-trivial (they support tests + scenario DSL + headless PoC) and
  the user-visible degradation is reported, not silent.

### Negative / Trade-offs

- `payloadView: Map<String, Any>` is a type-system step backward from a
  strongly-typed wrapper. Tests must assert against the documented key
  names (e.g. `"x"`, `"y"`, `"z"`) and pay a runtime `as? Double` cast.
- Phase 5+ may want strong `PacketEnvelope` sub-types per `PacketKind` once
  the PacketEvents shape stabilises. The current ADR closes that door
  loosely — a revision is a new ADR, not a bug fix.
- PacketEvents soft-dep adds another upstream dependency that can stale on
  MC version bumps. Mitigated by Wave 6b's degradation path.

### Mitigations

- The Konsist rules (`core.domain.* must not import
  com.github.retrooper.packetevents.*` +
  `IPacketInterceptor / IPlayerVisibilityGateway impls must run the
  PacketEvents loaded check`) catch shape drift at build time.
- Test fixtures (`FakePacketInterceptor`, `FakePlayerVisibilityGateway`)
  ship in Wave 5y so downstream waves don't reinvent them.
- The vanilla-Paper degradation path is exercised by the
  `InMemoryPlayerVisibilityGatewayTest` — the in-memory impl is the
  fallback adapter's substrate, not a parallel codebase.

## Alternatives Considered

- **Defer to Phase 3+ as ADR-0015 originally proposed.** Rejected — Wave
  5q's anti-cheat race condition + Wave 5n's hide-real-spawn requirement
  are concrete, not speculative. Forcing each to ship its own packet
  workaround would create three packet-touching code paths to consolidate
  later.

- **Strongly-typed `PacketEnvelope` sub-types per `PacketKind`.** Rejected
  for Phase 1 — every MC version bump reshuffles PacketEvents field names,
  and a strong mirror would either lag (constant `Optional<>` decay) or
  break domain code on every adapter update. Phase 5+ can revisit when
  the PE surface has stabilised across two MC bumps.

- **Use ProtocolLib instead.** Rejected per ADR-0015's existing rationale —
  PacketEvents is paper-native, lighter, and the maintainer cadence is
  faster on MC version updates.

- **Use the scoreboard-team trick for per-viewer visibility.** Rejected —
  works for players but cannot hide arbitrary entity spawns, and breaks
  on nameplate / collision channels that Astera uses for HUD elements.

## References

- 関連 ADR:
  - [[adr/0015-external-plugin-interrogation]] (PacketEvents section superseded)
  - [[adr/0009-soft-dep-only-paper-plugin-yml]] (soft-dep posture)
  - [[adr/0083-anti-cheat-and-anti-afk]] (Wave 5q anti-cheat surfaces this bridges)
  - [[adr/0022-multi-plugin-portable-architecture]] (port-based wiring)
  - [[adr/0001-clean-architecture-with-mc-as-edge]] (vendor isolation)
- 関連 docs:
  - `plugin/core/src/main/kotlin/.../core/domain/packet/` — type-erased envelope
  - `plugin/core/src/main/kotlin/.../core/domain/visibility/` — error surfaces
  - `plugin/core/src/main/kotlin/.../core/application/service/anticheat/MovementValidatorPacketBridge.kt`
- 外部:
  - PacketEvents — <https://github.com/retrooper/packetevents>
