---
status: Accepted
date: 2026-06-03
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0169: Wave 6g-PerTeam — Lazy IMatchRepository lookup for HologramVisibilityScope.PerTeam

## Status

Accepted. Closes the `HologramVisibilityScope.PerTeam` carve-out left
open by [ADR-0168][adr-0168] §C. Together with ADR-0168, `PaperHologramGateway`
now implements all three visibility scopes (`Everyone` / `PerPlayer` /
`PerTeam`) end-to-end via Paper-native APIs with zero external packet
library.

## Context

ADR-0168 (Wave 6g-visibility) shipped `PerPlayer` end-to-end via Paper's
native `Entity.setVisibleByDefault` + `Player.showEntity` but deferred
`PerTeam` to "a small follow-up wave" because resolving `(matchId, teamId) →
Set<PlayerId>` needs `IMatchRepository.findById(matchId).teams[i].members`
and the impl is registered by `:plugin:match` whose `onEnable` runs after
`:plugin:core`'s. A constructor-time `services.getOrThrow<IMatchRepository>()`
in `AsteraCorePlugin` would NPE because the impl isn't registered yet.

ADR-0168 §C proposed introducing a separate `ITeamRosterLookup` port
(Core interface, Match-side impl, lazy lookup at use time). On
re-examination, the proposed port duplicates `IMatchRepository`'s
already-canonical "find this match, then walk its teams" path. `:plugin:core`
**already** defines the `IMatchRepository` interface (it owns the `Match`
aggregate definition); only the impl lives in `:plugin:match`. Adding a
narrower port would just be a re-export with no new semantics.

So the simpler shape: pass the same `IMatchRepository` thunk and call
`findById().teams.firstOrNull { ... }.members` inline. Lazy lookup
sidesteps the constructor-time race; the existing Core interface stays
authoritative.

## Decision

### §A — `matchRepositoryLookup: () -> IMatchRepository?` constructor param

[`PaperHologramGateway`][paper-holo] gains a sixth constructor parameter:

```kotlin
private val matchRepositoryLookup: () -> IMatchRepository? = { null },
```

Default `{ null }` preserves the pre-wave behaviour (test harnesses and
the InMemory-priority-0 wiring path that don't need PerTeam continue to
work unchanged). The Paper wiring in `AsteraCorePlugin.onEnable` passes:

```kotlin
matchRepositoryLookup = { services.get<IMatchRepository>() },
```

Each call hits the registry; resolution succeeds once `AsteraMatchPlugin`'s
`onEnable` has registered the impl. Per-call cost is one map lookup —
not worth caching (registry semantics expect `get` to be cheap; caching
would also stale-pin if the registry ever supports re-registration).

### §B — `resolveTeamRoster(scope)` helper

Private helper centralizes the `null`-chain through repo → match → team
→ members:

```kotlin
private fun resolveTeamRoster(scope: HologramVisibilityScope.PerTeam): Set<PlayerId>? {
    val matchRepo = matchRepositoryLookup() ?: return null
    val match = matchRepo.findById(scope.matchId) ?: return null
    val team = match.teams.firstOrNull { it.id == scope.teamId } ?: return null
    return team.members
}
```

Returns `null` for three distinct cases (resolver unwired, match not
found, team not found). Callers treat `null` as "visibility intent
recorded, enforcement deferred" (same posture as the pre-`start`
PerPlayer branch in ADR-0168). Returns **empty set** when the team
exists but has zero members — valid intent ("nobody sees it").

### §C — `applyVisibility(PerTeam)` symmetric to PerPlayer

The branch mirrors the PerPlayer shape from ADR-0168:

```kotlin
is HologramVisibilityScope.PerTeam -> {
    val plugin = this.plugin
    if (plugin == null) { /* warn, return */ }
    val roster = resolveTeamRoster(scope)
    if (roster == null) { /* log INFO once, return */ }
    entity.isVisibleByDefault = false
    roster.forEach { id ->
        server.getPlayer(id.uuid)?.showEntity(plugin, entity)
    }
}
```

The single-INFO-per-server-lifetime log preserves the diagnostic that
PerTeam users had under ADR-0168 §C's stub. When the resolver is wired
correctly and the match/team exist, no log fires — silent enforcement.

### §D — `viewers(handle)` resolves PerTeam via the same helper

Previously returned `emptyList()` for the PerTeam case (matched
`InMemoryHologramGateway`'s stub). Now:

```kotlin
is HologramVisibilityScope.PerTeam ->
    resolveTeamRoster(scope)
        ?.filter { id -> server.getPlayer(id.uuid) != null }
        ?: emptyList()
```

When the resolver is unwired (test mode), still returns empty —
contract-aligned with InMemory. When the resolver is wired, returns the
**online** subset of the roster, parallel to PerPlayer's existing
filtered result. No production code currently calls `viewers()`, so the
behavioural divergence between InMemory (always empty) and Paper
(roster when wired) doesn't affect any caller; the alignment improves
when consumers eventually depend on it.

### §E — `JoinReapplyListener` extended to PerTeam

ADR-0168's `JoinReapplyListener` only re-applied PerPlayer visibility on
reconnect. Extended to a `when` over all three scopes:

```kotlin
val isViewer = when (val scope = hologram.visibilityScope) {
    is HologramVisibilityScope.Everyone -> false
    is HologramVisibilityScope.PerPlayer -> playerId in scope.playerIds
    is HologramVisibilityScope.PerTeam -> resolveTeamRoster(scope)?.contains(playerId) ?: false
}
```

The `Everyone` branch returns `false` because `isVisibleByDefault` stays
`true` for that scope — no reapply needed. PerTeam null-roster degrades
to `false` (not-a-viewer), so a join during a window where the resolver
isn't yet wired correctly does no harm; a later `updateVisibility(scope)`
after wiring resyncs the entity.

## Consequences

### Positive

- **All three `HologramVisibilityScope` variants now ship end-to-end** via
  Paper-native APIs. Zero external packet library required for the
  hologram visibility surface — completes the carve-out from ADR-0095's
  PacketEvents requirement that ADR-0168 began.
- The lazy-lookup pattern is reusable for any other Core-side adapter
  that needs a Match-plugin-registered service (e.g. future Paper-side
  scoreboard team-name resolution if it wants to use the same roster).
- No new port added. Reuses the canonical `IMatchRepository` interface
  that already lives in Core, avoiding port duplication.

### Neutral / costs

- ~40 LOC added to `PaperHologramGateway` (resolveTeamRoster helper +
  PerTeam branch in applyVisibility + viewers + JoinReapplyListener
  extension) + 1 new import (`IMatchRepository`) + 1 new constructor
  param.
- AsteraCorePlugin wiring grows by 4 lines (the thunk closure).
- The lazy thunk captures `services` in a closure — a tiny memory cost
  per gateway instance, acceptable.

### Negative

- The PerTeam resolution path silently degrades to "globally visible"
  if the match is found but no team matches `teamId`. A typo'd team id
  in a content YAML would manifest as "the hologram is visible to
  everyone" rather than "the hologram fails to spawn" — log once but no
  crash. Acceptable for Phase 1 PoC; a typed lookup result (e.g. a
  sealed `RosterResolution { Found(set), MatchNotFound, TeamNotFound }`)
  could distinguish later if needed.

## Verification

- `./gradlew :plugin:core:compileKotlin` BUILD SUCCESSFUL.
- `./gradlew check` BUILD SUCCESSFUL.
- 58 Konsist rules green (no rule change).
- `InMemoryHologramGatewayTest` still passes (InMemory variant unaffected
  by the new constructor parameter — default `{ null }` keeps behaviour
  identical).
- Manual smoke (Phase 1 dev tooling):
  1. Start a match with `Team(id=TeamId("red"), members=setOf(playerA, playerB))`.
  2. `spawn(hologram(visibilityScope = PerTeam(matchId, TeamId("red"))))`.
  3. Confirm playerA + playerB see the hologram; playerC (not on a team)
     does not.
  4. Reconnect playerA. Confirm visibility persists (verifies
     `JoinReapplyListener` extension).
  5. `updateVisibility(handle, PerTeam(matchId, TeamId("blue")))`.
  6. Confirm playerA + playerB lose visibility, blue-team members gain
     it.
  7. Negative: `spawn(...PerTeam(matchId, TeamId("nonexistent")))` →
     INFO log fires once, entity stays globally visible (no crash).

## Alternatives Considered

- **Introduce a separate `ITeamRosterLookup` port** (ADR-0168 §C's
  proposal). Rejected on re-examination: duplicates `IMatchRepository`'s
  existing `findById(matchId).teams.firstOrNull { ... }.members` path
  with no new semantics. One port serving two consumers (the Match
  plugin's own use cases + the hologram gateway) is cleaner than two
  ports with identical resolution under the hood.
- **Cache the resolver result.** Per-`applyVisibility` call cost is one
  `services.get` + one `findById` + one `teams.firstOrNull` walk — all
  cheap. Caching would either stale-pin (if the match roster mutates
  between cache fill and read) or need invalidation hooks that the
  ServiceRegistry doesn't currently expose. Defer caching to a future
  wave that has profiler evidence the path is hot.
- **Make the resolver mandatory (no `{ null }` default).** Would force
  every test harness + the InMemory wiring path to plumb a fake repo
  even when they don't use PerTeam. The default-`{ null }` shape
  preserves opt-in for the feature.
- **Pre-resolve on `spawn` and cache per-handle.** Captures stale rosters
  if the team roster mutates after spawn — a player who joins the team
  mid-match wouldn't see the hologram. The on-demand resolver always
  reflects current state.

## References

- [[adr/0022-multi-plugin-architecture]] §sibling-isolation — the rule
  that drives the lazy-lookup pattern (Core can't import Match impls).
- [[adr/0095-packet-system-and-per-viewer-visibility]] — the per-viewer
  visibility surface this lineage completes the carve-out from. Other
  ADR-0095 clauses (anti-cheat movement, replay coordinates) remain
  genuine PacketEvents targets.
- [[adr/0163-paper-sweep-round-4-hologram]] §visibility-stub — Wave 6f's
  documented stub posture this lineage closes.
- [[adr/0168-wave-6g-visibility-paper-native-no-packetevents]] — the
  ADR this completes. PerPlayer shipped there; PerTeam ships here.

[paper-holo]: ../../plugin/core/src/main/kotlin/com/astarworks/astera/core/adapter/paper/hologram/PaperHologramGateway.kt
[adr-0168]: ./0168-wave-6g-visibility-paper-native-no-packetevents.md
