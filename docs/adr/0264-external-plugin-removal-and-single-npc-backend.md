---
status: Accepted
date: 2026-10-26
deciders: ryuzu
supersedes: 0015, 0082, 0085
superseded_by: null
---

# ADR-0264: External plugin removal (MythicMobs, WeaponMechanics, Oraxen, Citizens), MinimalMobAdapter and one NPC backend

## Status

Accepted. Supersedes [[adr/0015-external-plugin-interrogation]] (its remaining external-plugin posture; the packet section
was already superseded by ADR-0090), [[adr/0085-pve-mob-foundation]] (MythicMobs-capable two-tier mobs) and
[[adr/0082-entity-spawning-and-npc-two-tier]] (Minimal core / Citizens edge). Builds on
[[adr/0009-soft-dep-only-paper-plugin-yml]] and [[adr/0252-folia-runtime-and-executionscope-threading-model]].

## Context

Folia support requires every runtime plugin to declare `folia-supported`, and the major external plugins do not (they use
the Bukkit scheduler and global scoreboards). Astera already treated them as soft-deps and ships its own item, skill and
mob content. Keeping adapters for plugins that cannot load on the target server is dead weight.

## Decision

1. **Already done (commit 3369b171, 2026-10-26)**: WeaponMechanics, Oraxen and MythicMobs integrations were removed:
   version-catalog entries, `MythicMobsMobAdapter`, `MobBackendId` mythic variant, the `IMobAdapter` / `IMobSpawner`
   plugin hooks, Gradle module registrations in `settings.gradle.kts`, the related `ArchitectureTest` allowances, and the
   contributor docs (`add-a-mob-yaml.md`, `add-an-item-yaml.md`). `MinimalMobAdapter` is now the only mob backend and
   `MobSpawnerRouter` routes only to it.
2. **Citizens is removed** (T12). It is not Folia-capable and conflicts with region ownership.
3. **PvE mobs**: `MinimalMobAdapter` (vanilla entities owned by their region, spec-driven behaviour, deterministic A*
   pathing per the bot-AI bar) is the single PvE backend.
4. **NPCs**: one `NpcAdapter` with two body kinds: region-owned **Display / Mannequin** bodies (world-resident, scheduled via
   the entity scheduler) and **packet-only fake players** through PacketEvents (no world entity; used for lobby/crowd
   NPCs; see [[adr/0260-packet-only-hud-and-packetevents-hard-dependency]]).
5. **PacketEvents is the only external dependency** and it is a library, not a plugin
   ([[adr/0260-packet-only-hud-and-packetevents-hard-dependency]]). Astera boots on vanilla Paper/Folia with no other plugin.

## Consequences

### Positive
- No unmaintained or non-Folia plugin on the critical path; fewer adapters, smaller surface, simpler CI.
- One mob backend and one NPC backend to reason about for ownership.

### Negative / Trade-offs
- Losing MythicMobs-authored mob content and Oraxen/WeaponMechanics assets as import paths; everything is Astera content.
- We own NPC and mob behaviour quality.

### Mitigations
- Pre-release posture ([[adr/0258-independent-jar-graph-and-skills-bundle]] era): no compatibility shims; content is
  YAML under `content/`.
- `MinimalMobAdapter` is extended by content, not by plugins.

## Alternatives Considered

- **Keep adapters behind soft-dep**: dead code on Folia; maintenance with no users.
- **Fork Citizens/MythicMobs for Folia**: ongoing upstream-merge cost; ownership model differs.
- **Packet-only NPCs for everything**: cannot be hit/interacted without server entities; hence the two body kinds.

## References

- commit `3369b171`, [[adr/0009-soft-dep-only-paper-plugin-yml]], [[adr/0252-folia-runtime-and-executionscope-threading-model]],
  [[adr/0260-packet-only-hud-and-packetevents-hard-dependency]]
