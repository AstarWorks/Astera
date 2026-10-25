---
status: Accepted
date: 2026-10-26
deciders: ryuzu
supersedes: 0022
superseded_by: null
---

# ADR-0258: Independent jar graph, "skills only" bundle, core module carve

## Status

Accepted. Supersedes [[adr/0022-multi-plugin-portable-architecture]] (7-plugin sibling-isolation matrix), re-basing the
rule onto the graph below. Keeps ADR-0022's principle (jars are independent, wired through `ServiceRegistry` ports) and
[[adr/0009-soft-dep-only-paper-plugin-yml]] (external plugins are soft). Related: [[adr/0252-folia-runtime-and-executionscope-threading-model]].

## Context

The 7-plugin layout (core, items, combat, match, stats, economy, persistence) is too coarse for the Folia design: instances,
PvE, lobby, social, admin and proxy are new concerns, and Core had grown into a catch-all. Founder requirement: **each jar
can be updated or plugged in on its own**, and a **"skills only" bundle (core + items + combat) must run on a plain survival
server** with no match, lobby or Postgres.

## Decision

### Core is carved (T1) into modules inside the one `Astera-Core` jar

`kernel` (runtime, `ExecutionScope`, `IScheduler`, `IComputePool`; `kernel:folia` adapters), `mc-api` (vendor-neutral
abstractions), `paper-adapter`, `content` (loaders, bake), `vocab` (ItemSpec/SkillId/DamageProfile vocabulary), `effects`,
`hud`, `entity`, `platform`. Unsealing of events and carving feature jars (T1b) follows.

### Jar graph

| Jar | Role | Hard depend | Softdepend (via ServiceRegistry ports) |
|---|---|---|---|
| core | kernel/runtime/mc-api/adapter/content/vocab/effects/hud/entity/platform | - | - |
| instances (new) | GameScope, GameInstance, slots, directory ([[adr/0253-gamescope-gameinstance-strand-and-handoff]], [[adr/0255-world-slots-and-arenapaster]]) | core | - |
| items | item content, materializer, skill-trigger listener | core | - |
| combat | damage/skill/status ([[adr/0256-same-tick-region-thread-combat]]) | core | items |
| match | match lifecycle on the strand | core, instances | combat, items |
| pve | mobs, waves, `MinimalMobAdapter` | core, instances | combat |
| lobby | sharded lobby, queue UI | core, instances | match |
| progression | XP/levels/norma/missions (successor of stats) | core | match, combat, items |
| social | friends/party | core | instances |
| admin | ops commands, metrics | core | all |
| proxy | Velocity bridge, transfer ([[adr/0263-homogeneous-backends-and-optional-proxy-scale-out]]) | core | instances |
| stats | statistics | core | match, combat |
| economy | currency/wallet/ledger | core | progression, match |
| persistence | Postgres impls, priority-override of in-memory ([[adr/0259-async-persistence-and-write-behind]]) | core | all |

Hard `depend` is only core and instances. Everything else is `softdepend`, resolved at runtime via `ServiceRegistry`
(priority-based) and the event `-api` packages ([[adr/0254-event-bus-v2-routing]]). The Gradle project graph and the
`plugin.yml` `depend/softdepend` lists must agree: a `PluginLoadOrderTest` fails the build otherwise.

### Skills bundle

`core + items + combat` is a supported deployment: `OpenWorld` default resolver, in-memory repositories, no Postgres, no
match. Items bind skills; combat resolves them; nothing requires instances.

### Konsist

The sibling-isolation matrix in `module-layout.md` is regenerated from this table; feature jars may reference core, their
softdepends' `-api` packages, and instances only where hard-depended.

## Consequences

### Positive
- Any jar can be updated independently (rolling restart granularity); skills bundle works on survival.
- Clearer ownership per track, less merge contention.

### Negative / Trade-offs
- More jars to build, version and deploy; more plugin.yml to keep consistent.
- `-api` package discipline required to avoid sibling reach-through.

### Mitigations
- `PluginLoadOrderTest` + Konsist keep graph and plugin.yml in sync automatically.
- The Gradle convention plugins share boilerplate for new jars.

## Alternatives Considered

- **Keep 7 plugins, add instance logic inside match**: couples combat/skills to match; blocks skills-only.
- **One monolith jar**: no independent update, no plug-in, defeats rolling restarts.
- **Hard depend between features**: breaks independent loading; rejected in favour of softdepend + ports.

## References

- [[adr/0022-multi-plugin-portable-architecture]] (superseded), [[adr/0009-soft-dep-only-paper-plugin-yml]],
  [[adr/0262-folia-safety-konsist-rules]], [[adr/0264-external-plugin-removal-and-single-npc-backend]]
