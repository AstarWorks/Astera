---
status: Accepted
date: 2026-06-08
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0173: Wave 6c-stub-packetevents-D — Synthetic / offline-subject tab-list entries via phantom PlayerInfoUpdate lifecycle

## Status

Accepted. Completes the tab-list per-viewer surface opened by ADR-0172
(Wave 6c-stub-C), which carved out synthetic / offline-subject rows. Those
rows now render: an offline `TabListEntry` is injected as a per-viewer
phantom row via `WrapperPlayServerPlayerInfoUpdate` (`ADD_PLAYER` +
`UPDATE_LISTED`), and torn down via `WrapperPlayServerPlayerInfoRemove` when
it drops out of the next `setPlayerEntries` diff.

## Context

ADR-0172 wired per-viewer display-name + latency for tab-list rows whose
`name` matches an **online** player, and explicitly deferred synthetic /
offline rows (§carve-outs):

- `PaperTabList.pushEntriesForOnlineSubjects` did
  `getPlayerExact(name) ?: continue` — any non-online entry was silently
  dropped, recorded only in the `currentEntries` snapshot.
- ADR-0172 §Negative pinned the reason for the deferral: per-viewer
  phantom-UUID lifecycle state ("this entry was visible before, now it
  isn't" per viewer) that the Wave 6c-C `PaperTabList` didn't track. It even
  named the likely derivation
  `UUID.nameUUIDFromBytes("$viewerUuid:$entryName")`.

This wave closes that gap. Use cases: NPC rows, server-info badges, and
offline players a server wants listed for context — all of which the shared
Player List packet never spawns a client row for.

## Decision

### §A — `ITabListPacketSender` gains ADD / REMOVE

`plugin/core/.../adapter/paper/packetevents/ITabListPacketSender.kt`:

```kotlin
public fun sendAddEntry(viewer: Player, entryUuid: UUID, name: String,
                        displayName: Component, latencyMs: Int)
public fun sendRemoveEntry(viewer: Player, entryUuid: UUID)
```

Bukkit / Adventure / `java.util.UUID` only — never a PE symbol (same
isolation contract as ADR-0171/0172 §A). `entryUuid` is always a
caller-derived **phantom** UUID, never a real player's.

### §B — `PacketEventsTabListPacketSender` implements both

- `sendAddEntry`: one `WrapperPlayServerPlayerInfoUpdate` with
  `EnumSet.of(ADD_PLAYER, UPDATE_LISTED, UPDATE_DISPLAY_NAME, UPDATE_LATENCY)`
  and `PlayerInfo(UserProfile(entryUuid, name), listed = true,
  latencyMs.coerceAtLeast(0), GameMode.SURVIVAL, displayName, null, 0)`.
- `sendRemoveEntry`: `WrapperPlayServerPlayerInfoRemove(entryUuid)`.

**Why `listed = true`** — a row only renders when the entry is both
`ADD_PLAYER`'d *and* marked listed; an unlisted ADD spawns the profile
without a visible row.

**Why one combined-action ADD** — `ADD_PLAYER` carries the full
`PlayerInfo`, so folding `UPDATE_DISPLAY_NAME` + `UPDATE_LATENCY` into the
same packet refreshes the row's text + ping on every (idempotent) re-send,
with no separate UPDATE round-trip.

**Why `UserProfile(entryUuid, name)`** — a synthetic row has no real
profile, so the wave mints one (UUID = the phantom UUID, name = the entry
name). No skin property → default head. Contrast ADR-0172's
`UserProfile(uuid, null)`, which was valid only because the client already
held the real profile for an *online* subject.

### §C — `PaperTabList` online/offline branch + per-viewer phantom diff

- New state `phantomUuids: MutableMap<PlayerId, Set<UUID>>` — phantom UUIDs
  currently injected for each viewer.
- `pushEntries` (renamed from `pushEntriesForOnlineSubjects`): per entry —
  - **online subject** → ADR-0172 path (`sendDisplayNameUpdate` +
    `sendLatencyUpdate`, or vanilla `playerListName` fallback);
  - **offline / synthetic** + sender + online viewer → derive
    `phantomTabListUuid(viewer.uniqueId, entry.name)`, `sendAddEntry(...)`,
    collect into the desired set.
- Reconcile: `previous − desired` → `sendRemoveEntry` each, then store the
  desired set. ADD is re-sent on every push (idempotent), so the tracked set
  drives **removals only** — which makes reconnect self-healing (the join
  listener re-pushes) **without a quit hook**.
- `phantomTabListUuid(viewerUuid, name) =
  UUID.nameUUIDFromBytes("$viewerUuid:$name".toByteArray())` — pure,
  unit-tested. Per-viewer scoping keeps one viewer's REMOVE from tearing down
  another viewer's row.

### §D — No wiring change

The same `PacketEventsTabListPacketSender` instance constructed inside
`AsteraCorePlugin`'s `if (packetEventsInstalled)` gate (ADR-0172 §D) gains the
two methods; `PaperTabList` already receives it. `AsteraCorePlugin` is
**untouched** this wave.

## Consequences

### Positive

- Synthetic / offline tab-list rows finally render — per-viewer locale + ping,
  isolated (one viewer's phantom never leaks to another). The last ADR-0172
  tab-list carve-out is closed.
- The ADR-0172 seam extends cleanly: same interface, same gated impl, no new
  Konsist rule, no wiring churn.
- Reconnect self-heals via idempotent ADD; no quit listener required.

### Neutral / costs

- ~165 LOC (interface + impl + `PaperTabList` branch/diff + pure test). Konsist
  58 rules unchanged (the new PE-importing code stays in
  `core.adapter.paper.packetevents.*`; the interface stays PE-symbol-free).
- One ADD packet per offline entry per viewer per push, plus REMOVEs on diff.
  Phase 1 PoC volumes (handful of viewers, ≤20 entries) make this negligible.
- `phantomUuids` retains a `Set<UUID>` per viewer; a viewer who never returns
  leaves a small stale entry (bounded by viewer count).

### Negative

- Phantom rows have **no skin** (default steve/alex head). Cosmetic;
  PoC-acceptable.
- Duplicate offline entries sharing a `name` collapse to one phantom row (the
  derived UUID is name-scoped) — matches the online path's lack of de-dup.
- **Vanilla Paper (no PE) still drops offline rows** — there is no client
  baseline to ADD to. Documented; the PacketEvents softdepend is the only way
  to inject synthetic rows.

## Carve-outs (still out of scope)

- **Per-viewer header / footer overrides** — already per-recipient via vanilla
  `sendPlayerListHeader(Component)`; no PE needed (ADR-0172 §carve-outs).
- **Tab-list scoreboard collapse** (sidebar-style team-prefix coloring).
- **Phantom row skins** — would need a texture property on the synthetic
  `UserProfile`.
- **Quit-time phantom-state eviction** — `phantomUuids` self-corrects on
  re-push, so eviction is a memory nicety, not a correctness requirement.

## Verification

- `./gradlew :plugin:core:compileKotlin` BUILD SUCCESSFUL — PE 2.12.2
  `WrapperPlayServerPlayerInfoUpdate.Action.{ADD_PLAYER, UPDATE_LISTED}` and
  `WrapperPlayServerPlayerInfoRemove(java.util.UUID...)` resolve (verified
  against the resolved `packetevents-api-2.12.2.jar` before implementation).
- `./gradlew check` BUILD SUCCESSFUL — full plugin suite green; 58 Konsist
  rules unchanged.
- `TabListPhantomUuidTest` — `phantomTabListUuid` stable per (viewer, name),
  distinct across viewers and across names.
- Manual smoke (post-merge, PacketEvents installed):
  1. `setPlayerEntries(viewer, listOf(TabListEntry("NPC-Sentry", someKey, 80)))`
     where `NPC-Sentry` is **not** online → the viewer sees a phantom row
     rendered in their locale with an 80 ms ping badge; a second viewer with a
     different key sees their own rendering; neither leaks to the other.
  2. Push again **without** that entry → the phantom row disappears (REMOVE
     fired); other rows unaffected.
  3. Vanilla Paper (no PE) → offline entry still dropped (unchanged); online
     entries fall back to `playerListName`; no `NoClassDefFoundError`.

## Alternatives Considered

- **Separate ADD then UPDATE packets.** Extra round-trip; the combined-action
  ADD refreshes display-name + latency in place.
- **Track phantom state, ADD once + UPDATE thereafter.** Needs a quit hook to
  re-ADD on reconnect; always-ADD is self-healing and matches the online
  path's always-resend.
- **Server-wide synthetic rows (shared scoreboard-style).** Breaks per-viewer
  locale isolation — the whole point of the PE seam.
- **Random (type-4) phantom UUIDs.** Non-reproducible; REMOVE would need a
  separate lookup table. Name-based derivation makes the UUID a pure function
  of (viewer, name).

## References

- [[adr/0172-wave-6c-stub-packetevents-c-per-viewer-tablist]] — prior wave;
  this ADR fills its synthetic-row carve-out.
- [[adr/0171-wave-6c-stub-packetevents-b-per-viewer-scoreboard]] —
  `ITeamPacketSender` seam.
- [[adr/0170-wave-6c-stub-packetevents-foundation]] — PE adoption + isolation
  pattern.
- `plugin/core/src/main/kotlin/com/astarworks/astera/core/adapter/paper/hud/PaperTabList.kt`
  — consumer.
- [PacketEvents 2.x `WrapperPlayServerPlayerInfoUpdate`](https://github.com/retrooper/packetevents/blob/2.0/api/src/main/java/com/github/retrooper/packetevents/wrapper/play/server/WrapperPlayServerPlayerInfoUpdate.java)
  / `WrapperPlayServerPlayerInfoRemove` — wrapper API.
