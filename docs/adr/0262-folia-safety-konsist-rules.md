---
status: Accepted
date: 2026-10-26
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0262: Folia-safety Konsist rules and the ownership-annotation ratchet

## Status

Accepted. Adds rules to `tools/architecture-test/.../ArchitectureTest.kt`; existing rules are not relaxed (per
[[adr/0022-multi-plugin-portable-architecture]] lineage, restated in [[adr/0258-independent-jar-graph-and-skills-bundle]]).
Implements track T3 of [[adr/0252-folia-runtime-and-executionscope-threading-model]].

## Context

Thread-safety mistakes on Folia surface at runtime, in production, as exceptions or silent corruption. Convention alone
failed before (the boot spike shows 7 plugins dead on one scheduler call). The architecture test is the only gate that
reaches every module.

## Decision

New Konsist rules (build fails on violation):

1. **Forbidden APIs**: `BukkitScheduler` / `Bukkit.getScheduler`, `isPrimaryThread`, `BukkitRunnable`, `GlobalScope`,
   `runBlocking`.
2. **Teleport only via `TeleportGateway`** (async); direct `Entity#teleport` is forbidden outside the gateway.
3. **No `createWorld`, `WorldCreator`, `unloadWorld`** ([[adr/0255-world-slots-and-arenapaster]]).
4. **No Player mutators inside `onlinePlayers` loops** outside `kernel:folia` (a loop over all players hits other regions'
   entities); use per-entity scheduling.
5. **Ownership annotations**: every non-domain class with mutable state must carry `@InstanceOwned`, `@EntityOwned`,
   `@GlobalOwned` or `@ThreadSafe`. Rollout is a **ratchet**: start from a checked-in allowlist baseline of current
   offenders and only ever shrink it to 0 (a new offender fails; an allowlist entry that is fixed must be removed).
6. **`transaction(` only under `Io`** scope ([[adr/0259-async-persistence-and-write-behind]]).
7. **Compute purity** for `combat-compute` / `domain.compute`: no Bukkit, clock, random, thread, I/O, mutable globals
   ([[adr/0256-same-tick-region-thread-combat]], [[adr/0257-determinism-seeds-and-ordering]]).
8. **`PluginLoadOrderTest`**: Gradle jar graph and `plugin.yml` depend/softdepend must agree
   ([[adr/0258-independent-jar-graph-and-skills-bundle]]).

## Consequences

### Positive
- Folia-unsafe code cannot merge; the allowlist ratchet gives measurable progress to 0.

### Negative / Trade-offs
- False positives need explicit suppression; annotation boilerplate on many classes.

### Mitigations
- Suppressions require a comment and live in `konsist-allowlist/`; the ratchet test fails if an entry is stale.

## Alternatives Considered

- **Code review only**: proved insufficient.
- **Runtime assertions only**: found late, per environment.
- **Big-bang annotation of all classes**: blocks T1; ratchet allows incremental adoption.

## References

- [[adr/0252-folia-runtime-and-executionscope-threading-model]], [[adr/0256-same-tick-region-thread-combat]],
  [[adr/0259-async-persistence-and-write-behind]], `.claude/rules/coding-posture.md`
