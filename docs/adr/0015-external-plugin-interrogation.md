---
status: Accepted
date: 2026-05-17
deciders: ryuzu
---

# ADR-0015: External plugin interrogation — vanilla + self-build by default

## Status

Accepted. Supplements [[adr/0009-soft-dep-only-paper-plugin-yml]] (which made
all external plugins soft-dep). ADR-0015 goes further: it asks whether each
plugin should be a dependency at all.

## Context

RTM's `paper-plugin.yml` declared eleven external plugins as `required`:
WeaponMechanics, Oraxen, MythicMobs, ProtocolLib, PlaceholderAPI,
FastAsyncWorldEdit, WorldGuard, Multiverse-Core, ModelEngine,
UltimateAdvancementAPI, CommandAPI. That gave RTM rich functionality with
relatively few lines of code, but also bound it to eleven independently-maintained
projects — any one of which going stale during a Minecraft version bump can
stop the whole server from booting.

[[adr/0009-soft-dep-only-paper-plugin-yml]] downgraded the requirement so any
of those could be missing. ADR-0015 takes the next step:

> For each plugin, ask: do we *actually* need this, or is building it ourselves
> on top of Paper + the Astera abstractions cheap enough to prefer?

The user explicitly asked (2026-05-17) for a per-plugin interrogation.

## Decision

**Default position: vanilla Paper API + Astera self-built abstraction.** A
plugin is adopted only when the self-build cost is provably higher than the
plugin-dependency cost.

### Per-plugin verdicts

#### Adopt (B — Phase-specific decision likely yes)

- **UltimateAdvancementAPI** — Phase 4 daily/weekly challenges and Phase 7
  battle pass want programmatic advancement creation + progress. Vanilla
  advancement JSON is static and re-deploy-required. UA-API gives runtime
  manipulation. Adopt when the static approach starts hurting.

#### Compare at Phase (C — undecided)

- **MythicMobs** — Phase 5 Place might want NPC residents / bosses. Vanilla
  mob spawning + custom NBT is doable but glue-heavy. Decision at Phase 5
  start.
- **ModelEngine vs BetterModel** — Custom 3D entity models for NPCs / monsters.
  Both candidates; Phase 5 / 6 ADR-by-comparison.

#### Decline — self-build wins (D)

- **WeaponMechanics** → self-built **`GunSpec` + `ProjectileSpec` + `IProjectileService`**.
  A gun decomposes into ammo + cooldown + projectile (initial velocity, gravity,
  drag, max lifetime, hit radius, damage, on-hit effect). Hit detection via
  Paper's `BlockIterator` + `World.getNearbyEntities`. Astera controls weapon
  granularity exactly, integrates with Easing for curved trajectories. RTM's
  WeaponMechanics dependency carried thousands of lines of all-weapon-patterns
  in exchange for control.
- **Oraxen / ItemAdder / MythicItems** → self-built **`IItemRegistry` port**
  with a `Vanilla` default impl (vanilla material + customModelData + i18n +
  PDC tags). External providers (Oraxen specifically) become optional adapter
  modules in Phase 4 when UGC marketplace wants community-uploaded custom
  textures. The core need ("ID → ItemStack vendor-neutral") is owned by Astera.
- **ProtocolLib → PacketEvents** when packet manipulation is needed. PacketEvents
  (`com.github.retrooper`) is paper-native, lightweight, and actively maintained.
  Adoption deferred to Phase 3+ via a future ADR.
- **PlaceholderAPI** — Astera already has `MessageKey` + placeholder Map. PAPI's
  cross-plugin placeholder framework only matters if other plugins read Astera
  state, which we don't pursue.
- **FastAsyncWorldEdit** — **world directory clone** beats schematic restore for
  stage reset: `cp -r world_siege_template world_siege_match_${matchId}`,
  load via Paper `WorldCreator`, unload + `rm -rf` on match end. FAWE only
  becomes useful for *in-match* mass block manipulation (e.g. attack-side
  walls being blown apart in Phase 3). Defer until that pattern emerges.
- **WorldGuard** — Astera's `Plot` + `Region` domain types own permissions
  natively. WG would create two competing region systems.
- **Multiverse-Core** — Paper's `WorldCreator` API handles per-match world
  spin-up. Multiverse's config + command surface adds more than it saves.
- **CommandAPI** — Paper's Brigadier API is mature in Paper 26. The Astera
  `ICommandHandler` inbound port abstracts the command framework; backend
  starts as Paper Brigadier direct. CommandAPI can wrap behind that port if
  the direct Brigadier experience starts hurting.

### Aggregate

From RTM's 11 required dependencies:

- **B (likely adopt at specific Phase)**: 1 — UltimateAdvancementAPI
- **C (decide at Phase start with comparison)**: 2 — MythicMobs,
  ModelEngine/BetterModel
- **D (self-build, never adopted as required)**: 8 — WeaponMechanics, Oraxen,
  ProtocolLib, PlaceholderAPI, FAWE, WorldGuard, Multiverse, CommandAPI

**Astera's `paper-plugin.yml` declares zero `softdepend`.** Every adoption
becomes a new ADR + a new Provider module + an explicit feature flag.

### New Astera-internal ports introduced as a consequence

| Port | Replaces (RTM dependency) | Phase |
|---|---|---|
| `IItemRegistry` + `IMcItemTemplate` sealed | Oraxen / ItemAdder / MythicItems | Phase 2 mid |
| `IProjectileService` + `GunSpec` / `ProjectileSpec` / `HitEffect` sealed | WeaponMechanics | Phase 2 mid |
| `ICommandHandler` + `CommandSpec` sealed | CommandAPI | Phase 2 mid |
| `IWorldDirManager` (template clone / unload / cleanup) | FAWE schematic restore | Phase 3 |
| `IPacketSink` (when needed, backed by PacketEvents) | ProtocolLib | Phase 3+ |
| `IAdvancementService` (when needed) | UA-API | Phase 4+ |

## Consequences

### Positive

- Astera boots on vanilla Paper. No "missing dependency" failure modes.
- Every external integration becomes an explicit, ADR-recorded choice.
- Astera owns the abstractions that matter most (weapon mechanics, item
  registry, command framework, region permissions) and can shape them to its
  Clean Architecture, Result<T,E>, and Konsist constraints.
- Plugin updates (or stagnation) during Paper version bumps don't block
  Astera releases.

### Negative / Trade-offs

- Self-built abstractions take more upfront work than `compile-time-depend on
  plugin X`. Phase 2 mid grows by roughly: gun service (~500 lines), item
  registry (~200), command handler abstraction (~150).
- Astera carries the maintenance burden for its own item / gun / command
  systems instead of inheriting from upstream.
- Some niceties Oraxen/WG/etc. ship free (advanced GUIs, region flags, region
  events) need to be re-invented if we ever want them.

### Mitigations

- Phase 2 mid abstractions are designed port-first, so an Oraxen Provider
  *can* slot in later for the parts where Oraxen genuinely wins (custom 3D
  textures for community submissions in Phase 4).
- ADR-0009 already requires soft-dep, so any future re-adoption preserves the
  "Astera boots without it" property.

## Alternatives Considered

- **Inherit RTM's required-everything stance**: fastest implementation, but
  fragile to Paper version bumps and binds Astera's pace to eleven other
  projects.
- **Hand-pick a smaller required set** (e.g. just WM + Oraxen): less fragile
  than RTM but still gives those two projects veto power over Astera's
  releases. The vanilla-first interrogation revealed no plugin is so
  irreplaceable that it deserves required status.

## References

- [[adr/0009-soft-dep-only-paper-plugin-yml]]
- [[adr/0013-rtm-divergence-policy]]
- [[architecture/rtm-divergence]] §"Redesign"
- 計画書 §14.3 (interrogation table)
- RTM `modules/minecraft/paper/build.gradle.kts` — lists the 11 required
  plugins for reference
