---
status: Accepted
date: 2026-10-26
deciders: ryuzu
supersedes: 0170, 0171, 0172
superseded_by: null
---

# ADR-0260: Packet-only HUD (PacketSink) and PacketEvents as a hard library dependency

## Status

Accepted. Supersedes [[adr/0170-wave-6c-stub-packetevents-foundation]] (PacketEvents as softdepend),
[[adr/0171-wave-6c-stub-packetevents-b-per-viewer-scoreboard]] and
[[adr/0172-wave-6c-stub-packetevents-c-per-viewer-tablist]] (Bukkit-scoreboard-backed per-viewer paths). Builds on
[[adr/0252-folia-runtime-and-executionscope-threading-model]].

## Context

Folia throws on `getNewScoreboard`, `registerNewObjective`, `registerNewTeam`, `setPlayerBoard` and `clearSlot`, and
disables `scoreboard`, `team`, `bossbar` commands. Sidebar, tab list and nametags built on Bukkit scoreboards therefore
cannot work. ADRs 0171/0172 already used PacketEvents for per-viewer text but kept Bukkit scoreboard objects underneath
and PacketEvents as an optional softdepend.

## Decision

1. **All sidebar, tab list and nametag output is packet-only**, on Paper as well as Folia (one code path). No Bukkit
   scoreboard/team/objective object is created.
2. A `PacketSink` port in `hud` (core) takes a per-viewer batch (`ViewerId`, packets) and writes through PacketEvents. HUD
   renderers produce *models*; the sink encodes. Identical frames to many viewers are encoded **once** and the same buffer
   written to each viewer from the compute pool ([[adr/0256-same-tick-region-thread-combat]]).
3. **Bossbar, title and actionbar use Adventure** (`Audience`/`BossBar`), which Folia supports; they are not packet-built.
4. **PacketEvents becomes a hard library dependency** bundled/shaded with core (`packetevents-spigot` from the version
   catalog), not a soft-dep: HUD cannot degrade to a Bukkit fallback because there is none. This is a *library* dependency,
   not an external plugin; [[adr/0009-soft-dep-only-paper-plugin-yml]] still holds for gameplay plugins.
5. HUD updates are lowest-priority work in the overrun policy (deferred after VFX LOD, never gameplay).

## Consequences

### Positive
- Works on Folia; one implementation for both servers; per-viewer locale/ping features of 0171/0172 are preserved.
- Encode-once fan-out reduces per-viewer cost.

### Negative / Trade-offs
- Packet-level state (fake teams/objectives) must be tracked by Astera; client-visible desyncs are our bug.
- Tighter coupling to PacketEvents versions.

### Mitigations
- `PacketSink` is a single seam; game-sim substitutes a recording sink.
- Version pinned in `gradle/libs.versions.toml` only.

## Alternatives Considered

- **Bukkit scoreboards on Paper, packets on Folia**: two code paths, divergence bugs, and the Paper path dies when production moves to Folia.
- **Keep PacketEvents soft-dep with fallback**: no viable fallback exists on Folia.
- **Hand-written NMS packets**: breaks every Minecraft bump; PacketEvents already abstracts it.

## References

- [[adr/0252-folia-runtime-and-executionscope-threading-model]], [[adr/0256-same-tick-region-thread-combat]],
  [[adr/0264-external-plugin-removal-and-single-npc-backend]] (NPC fake players use the same library)
