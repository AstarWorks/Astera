---
status: Accepted
date: 2026-06-08
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0172: Wave 6c-stub-packetevents-C — Per-viewer tab-list display name + latency via PlayerInfoUpdate fan-out

## Status

Accepted. Closes the Phase 1 PoC PacketEvents adoption arc opened by
ADR-0170 (foundation) and continued by ADR-0171 (scoreboard per-viewer).
Tab-list per-viewer rendering — display name and ping badge — now works
the same way: shared Player List packet carries the default-locale
baseline, then a per-viewer `WrapperPlayServerPlayerInfoUpdate` rewrites
the display name + latency for online subjects in the requesting
viewer's locale.

**Synthetic / offline-subject tab-list entries are carved out** — the
phantom-`UserProfile` UUID lifecycle (ADD_PLAYER on first sight, REMOVE
on diff) is a separate concern that requires per-viewer entry-set state
the current `PaperTabList` doesn't track. See §carve-outs.

## Context

ADR-0171 (Wave 6c-stub-B) established the seam pattern that makes PE
adoption clean:

1. `ITeamPacketSender` interface — Bukkit / Adventure types only, no PE
   import — defines the per-viewer override hook.
2. `PacketEventsTeamPacketSender` — the **only** PE-importing class for
   scoreboards, constructed inside `if (packetEventsInstalled)` so JVM
   never resolves PE symbols on vanilla Paper (JLS §5.3.4).
3. Consumer (`PaperScoreboard*`) takes a `null`-able sender and falls
   back to vanilla behaviour when absent.

The tab-list surface has the same shape: `PaperTabList` shipped in
Wave 6b with two known limitations that map 1:1 to PE-required features:

- `pushEntriesForOnlineSubjects` used vanilla `Player.playerListName`,
  which is **server-wide per subject** — a per-viewer rename leaked to
  every other client.
- `TabListEntry.pingMs` was recorded in the snapshot but never pushed
  (vanilla API has no per-row latency hook).

Both fall under "true per-viewer rows need PacketEvents" per the
existing PaperTabList KDoc. Wave 6c-stub-C wires both.

## Decision

### §A — `ITabListPacketSender` mirror of `ITeamPacketSender`

`plugin/core/.../adapter/paper/packetevents/ITabListPacketSender.kt`:

```kotlin
public interface ITabListPacketSender {
    public fun sendDisplayNameUpdate(viewer: Player, subjectUuid: UUID, displayName: Component)
    public fun sendLatencyUpdate(viewer: Player, subjectUuid: UUID, latencyMs: Int)
}
```

Bukkit / Adventure / `java.util.UUID` only — never a PE symbol. Same
isolation contract as ADR-0171 §A.

Two methods cover the Wave 6b limitations:
- `sendDisplayNameUpdate` — the per-viewer locale override.
- `sendLatencyUpdate` — the ping badge push (vanilla had no hook).

### §B — `PacketEventsTabListPacketSender` PE-backed impl

`plugin/core/.../adapter/paper/packetevents/PacketEventsTabListPacketSender.kt`:

Each method builds a single-action `WrapperPlayServerPlayerInfoUpdate`
with an `EnumSet.of(...)` action — `UPDATE_DISPLAY_NAME` for the rename,
`UPDATE_LATENCY` for the ping — and sends it via
`PacketEvents.getAPI().playerManager.getUser(viewer).sendPacket(packet)`.

The `PlayerInfo` constructor in PE 2.12.x takes 7 args
`(profile, listed, latency, gameMode, displayName?, chatSession?, listOrder)`.
PE only serialises the fields whose action is present in the EnumSet,
so the irrelevant slots get neutral defaults
(`UserProfile(subjectUuid, null)` + `false` + `0` + `GameMode.SURVIVAL`
+ `null` + `null` + `0`). The placeholder `UserProfile` is sufficient
because the client already holds the real profile from the shared
Player List packet sent at subject join.

(See ADR-0170 / ADR-0171 for the standing isolation rationale.)

### §C — `PaperTabList` sender-aware `pushEntriesForOnlineSubjects`

`PaperTabList` gains a third constructor parameter,
`tabListPacketSender: ITabListPacketSender? = null` (default null
preserves test / non-PE wiring paths).

`pushEntriesForOnlineSubjects(playerId, list)`:

- **Sender wired**: resolve the viewer `Player` from `playerId`. For
  each entry whose `name` matches an online subject, call
  `sender.sendDisplayNameUpdate(viewer, subject.uniqueId, …)` +
  `sender.sendLatencyUpdate(viewer, subject.uniqueId, entry.pingMs)`.
  Server-wide `Player.playerListName` is **not touched** — no leak.
- **Sender null** (vanilla Paper, PE absent): fall back to the original
  Wave 6b behaviour — call `subject.playerListName(Component.text(...))`,
  which works but leaks renames to every other viewer. Ping badge stays
  unpushed.

Offline subjects (`getPlayerExact(entry.name) == null`) continue to be
silently dropped in both branches — §carve-outs covers this.

### §D — Wiring in `AsteraCorePlugin.onEnable`

Mirror of ADR-0171's wiring (lines ~580-588). The new sender is
constructed inside the same `if (packetEventsInstalled)` gate:

```kotlin
val tabListPacketSender: ITabListPacketSender? =
    if (packetEventsInstalled) PacketEventsTabListPacketSender() else null
…
services.register<ITabList>(
    PaperTabList(server, messages, tabListPacketSender),
    priority = 100,
)
```

The `messages` (`IMessageRenderer`) parameter was already present but
not wired in Wave 6b's `PaperTabList(server)` call — this wave threads
it through so `displayName` resolution actually uses the viewer's
locale via the renderer rather than the naive `{name}`-substitution
fallback.

### §E — Always-push latency

`sendLatencyUpdate` is invoked for every entry on every
`setPlayerEntries` call — including when `entry.pingMs == 0`. The
TabListEntry data class defaults `pingMs` to 0 and the `init` block
allows 0 explicitly ("0 is a legitimate value (loopback)"), so we
treat the value as authoritative regardless of magnitude. A caller
that wants the vanilla auto-detected ping shown should not call
`setPlayerEntries` at all (the shared Player List packet keeps
broadcasting it).

## Consequences

### Positive

- **Per-viewer locale for online-subject tab-list rows ships.** Each
  viewer reads their own translated display name; renames stop leaking
  to other clients (the Wave 6b limitation is closed for the online-
  subject path).
- **Ping badges finally push.** `TabListEntry.pingMs` was previously
  snapshot-only; it now reaches the client when the sender is wired.
- **The seam pattern repeats 1:1 from ADR-0171.** Future per-viewer
  Paper surfaces (BossBar locale, Title locale, etc.) can drop in a
  third `IXxxPacketSender` interface + impl + gated wire-up without
  re-deriving the isolation rules.
- **`IMessageRenderer` finally reaches `PaperTabList`.** Wave 6b
  constructed it with `null` for the renderer; this wave threads it
  through so the locale path is end-to-end rather than naive
  `{name}`-substitution.
- **CLAUDE.md §4 invariant preserved.** Astera continues to boot in
  vanilla Paper — the sender is `null`, vanilla `playerListName`
  fallback engages, no new `NoClassDefFoundError` surface.

### Neutral / costs

- ~80 LOC `ITabListPacketSender` (interface + KDoc), ~90 LOC
  `PacketEventsTabListPacketSender` (impl + KDoc), ~25 LOC
  `PaperTabList` modifications, ~10 LOC `AsteraCorePlugin` wiring +
  imports. ~205 LOC total + ADR.
- Konsist 58 rules unchanged (no new architectural invariant; the new
  PE-importing class lives in `core.adapter.paper.packetevents.*` like
  ADR-0170 / ADR-0171's siblings).
- One `Player.sendPacket` per entry per viewer per `setPlayerEntries`
  call — for `N` entries × `V` viewers, `2 × N × V` packets per push
  (display-name + latency). Phase 1 PoC volumes (handful of viewers,
  ≤20 entries) make this negligible; Phase 3 may want to batch the
  display-name + latency into one combined-action packet if tab list
  refresh becomes hot.

### Negative

- **Synthetic / offline-subject entries are still silently dropped.**
  The §carve-outs path requires per-viewer phantom-UUID lifecycle
  state. Today's `PaperTabList` only tracks `entries: Map<PlayerId,
  List<TabListEntry>>` — no diff of "this entry was visible before,
  now it isn't" per viewer, so a `WrapperPlayServerPlayerInfoRemove`
  would not know which UUIDs to remove. A follow-up wave introduces
  the diff + phantom UUID derivation (probably
  `UUID.nameUUIDFromBytes("$viewerUuid:$entryName".toByteArray())`)
  and pushes ADD_PLAYER / REMOVE accordingly.
- **`pingMs = 0` overrides the vanilla auto-detected ping.** When a
  caller passes `setPlayerEntries(viewer, listOf(TabListEntry("x",
  null)))` with the default `pingMs = 0`, the viewer sees "0ms" rather
  than "x's real ping". Acceptable for Phase 1 PoC since the typical
  caller explicitly sets pingMs, but documented in §E for future
  reference.

## Carve-outs

The following are out of scope and deferred to a follow-up wave (most
likely Wave 6c-stub-packetevents-D):

- **Synthetic / offline-subject entries.** Phantom `UserProfile` UUIDs +
  ADD_PLAYER / REMOVE lifecycle per viewer.
- **Per-viewer header / footer overrides.** Current header/footer
  rendering already uses `IMessageRenderer` per-viewer at the call
  site (vanilla `sendPlayerListHeader(Component)` is per-recipient),
  so no PE involvement is needed for them today.
- **Tab-list scoreboard collapse.** Some servers display the tab list
  with a sidebar-style team-prefix coloring; PE could do this via
  the same per-viewer team-update packets ADR-0171 introduced. Not
  in current scope.

## Verification

- `./gradlew :plugin:core:compileKotlin` BUILD SUCCESSFUL — PE 2.12.x
  `PlayerInfo` constructor signature (`UserProfile, Boolean, Int,
  GameMode, Component?, RemoteChatSession?, Int`) resolved correctly
  after the initial 7-arg shape mismatch (captured for the handoff:
  next session reuses this signature).
- `./gradlew check` BUILD SUCCESSFUL — full plugin suite green.
- 58 Konsist rules green — no rule change.
- Manual smoke (post-merge):
  1. Boot vanilla Paper (no PacketEvents) → `PaperTabList(server,
     messages, null)`. `setPlayerEntries` invokes
     `subject.playerListName(...)` — current Wave 6b behaviour,
     leaks renames.
  2. Boot with PacketEvents installed →
     `PaperTabList(server, messages, PacketEventsTabListPacketSender())`.
     Set two viewers' tab lists with the same online subject but
     different `displayName` keys → confirm each viewer sees their
     own rendering, server-side `playerListName` unchanged.
  3. Set `pingMs = 250` for an entry → confirm the 5-bar ping icon
     drops accordingly for the requesting viewer; other viewers see
     the vanilla auto-detected ping.

## Alternatives Considered

- **Implement synthetic-entry injection in this wave.** Would balloon
  scope (~300 LOC of diff state + phantom UUID lifecycle on top of
  the ~200 LOC this wave ships). Deferred to keep the PR reviewable
  and to gather a clean implementation requirement from the first
  consumer.
- **Drop the vanilla `playerListName` fallback.** Cleaner contract
  (sender-or-nothing) but breaks dev-time non-PE usage. Phase 1 PoC
  has no PE-mandatory rule for the tab list, so keep the fallback.
- **Combine `UPDATE_DISPLAY_NAME` + `UPDATE_LATENCY` into a single
  multi-action packet per entry.** PE supports `EnumSet.of(a, b)`
  multi-action sends; would halve the per-entry packet count. Phase 1
  PoC volumes don't justify the cognitive cost (the two-method
  interface is clearer); revisit if `setPlayerEntries` becomes hot.
- **Push the `pingMs` only when nonzero.** Avoids the "0 means
  loopback or unset?" ambiguity for callers, but introduces silent
  divergence between "the snapshot says X" and "the viewer sees Y".
  Always-push keeps the snapshot truthful.

## References

- [[adr/0170-wave-6c-stub-packetevents-foundation]] — PE adoption +
  isolation pattern this wave reuses.
- [[adr/0171-wave-6c-stub-packetevents-b-per-viewer-scoreboard]] —
  prior wave's `ITeamPacketSender` seam that this ADR mirrors.
- `plugin/core/src/main/kotlin/com/astarworks/astera/core/adapter/paper/hud/PaperTabList.kt`
  — consumer.
- [PacketEvents 2.x `WrapperPlayServerPlayerInfoUpdate`](https://github.com/retrooper/packetevents/blob/2.0/api/src/main/java/com/github/retrooper/packetevents/wrapper/play/server/WrapperPlayServerPlayerInfoUpdate.java)
  — wrapper API.
