---
status: Accepted
date: 2026-06-03
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0166: Wave 6d-stub-warmup — PaperBlockStateGateway warmUp driver

## Status

Accepted. Small follow-up wave wiring an existing hook
([ADR-0160 §B][adr-0160]) that shipped without a driver because the
required upstream (`wave-6c` merge into main) was still pending at
authoring time.

## Context

Wave 6c (ADR-0160 §B) introduced `PaperBlockStateGateway.warmUp(WorldId)`:
a hook that rehydrates the in-memory `tracker: Map<BlockKey, BlockState>`
from the persistent `IBlockChangeJournalRepository` (Postgres V45). The
hook exists so explosion-broken blocks survive `/restart` — without
hydration, the tracker boots empty and the gateway falls back to
seeded-default behaviour for every position until a fresh damage event
re-records its state, breaking the durability invariant across restarts.

The handoff after Wave 6c noted the hook was un-called: the gateway
exposed `warmUp` (Wave 6c §B), but `AsteraCorePlugin.onEnable` did not
enumerate loaded worlds to invoke it. The driver was deferred because
the call site needed `wave-6c` merged into main first — a circular wait
between the hook's introduction (on the wave-6c branch) and the driver
call (which would have introduced an unresolvable import on main).

After this session's merge sequence landed `wave-6c` through `wave-7a`
into main (`e0c8618` HEAD), the dependency is satisfied and this small
wave closes the gap.

## Decision

### §A — Driver call in `AsteraCorePlugin.onEnable`

Capture the `PaperBlockStateGateway` instance into a local val (was
inline-constructed inside `services.register<IBlockStateGateway>`), then
iterate `server.worlds` and call `gateway.warmUp(WorldId(world.name))`
for each:

```kotlin
val paperBlockStateGateway = PaperBlockStateGateway(...)
services.register<IBlockStateGateway>(paperBlockStateGateway, priority = 100)
server.worlds.forEach { world ->
    paperBlockStateGateway.warmUp(WorldId(world.name))
}
```

Plus a single INFO log line summarising the world count covered.

### §B — Boot-time roster only

The loop covers worlds loaded at `onEnable` time. Worlds loaded later
(multiverse plugins, dynamic load) **re-warm on first damage event**
through the existing `journalSave` path — the journal is the source of
truth, the tracker is a hot cache that fills on demand. Adding a
`WorldLoadEvent` listener to call `warmUp` on dynamic loads would be a
small follow-up, but the Phase 1 PoC ships with the lobby world
loaded at boot; dynamic loading is a Phase 4+ concern.

### §C — `WorldId(world.name)` convention

Mirrors `BukkitEventAdapter.kt:272` (`world = WorldId(world.name)`) —
WorldId carries the world's name (a String matching
`Bukkit.getWorld(String name)`'s lookup key), not the UUID. The gateway
itself uses `server.getWorld(world.value)` (line 397) which resolves
by name, so the loop must pass the name as well.

## Consequences

### Positive

- **Explosion-broken blocks now survive `/restart`.** The Wave 6c §B
  journal stops being a write-only sink in production — the loaded
  state actually returns on next boot.
- The wiring matches the `KDoc` contract on
  `PaperBlockStateGateway.warmUp` ("Call once per loaded world from
  `AsteraCorePlugin.onEnable` after the gateway is registered") that
  was previously documentation-only.
- A single INFO log line on boot confirms hydration happened; debug-level
  per-world counts are already in the gateway.

### Neutral / costs

- Dynamic world loads (added after boot) are not covered. Acceptable
  for Phase 1 PoC; a `WorldLoadListener` is the natural follow-up if
  multiverse becomes scope.
- 17 LOC of plugin wiring + 1 import (`WorldId`).

### Negative

- None — this is the call the gateway has been waiting for.

## Verification

- `./gradlew :plugin:core:compileKotlin` BUILD SUCCESSFUL.
- `./gradlew check` BUILD SUCCESSFUL.
- 58 Konsist rules green (no new rule introduced).
- Manual smoke (Phase 1 dev tooling): start Paper, break a block,
  trigger persistence via Wave 6c §B journal, `/restart`, confirm the
  tracker holds the broken state — verifies the warmUp loop ran on
  the second boot.

## Alternatives Considered

- **Lazy first-touch hydration only.** The gateway already falls back
  to seeded defaults + journal-save on first damage. Rejected because
  the *first* damage event after restart would compute against a stale
  default (e.g. a previously broken block treated as full-durability)
  until journalSave rewrites it — visible flicker.
- **`WorldLoadEvent` listener instead of boot loop.** Would cover
  dynamic loads but adds Bukkit listener registration + scheduler
  marshaling. Phase 1 PoC has zero dynamic worlds. Add it when
  multiverse joins the roadmap.
- **No driver, accept the gap.** Was the position last session
  (deferred because dep wasn't merged). Now that the dep is merged,
  the cost of leaving the hook un-driven is the production durability
  bug.

## References

- [[adr/0160-paper-side-production-hardening]] §B — introduced
  `PaperBlockStateGateway.warmUp(WorldId)` + Postgres V45
  `IBlockChangeJournalRepository`.
- `plugin/core/src/main/kotlin/com/astarworks/astera/core/adapter/paper/world/PaperBlockStateGateway.kt:151-171`
  — the warmUp hook this wave drives.
- `plugin/core/src/main/kotlin/com/astarworks/astera/core/adapter/paper/event/BukkitEventAdapter.kt:272`
  — `WorldId(world.name)` precedent.

[adr-0160]: ./0160-paper-side-production-hardening.md
