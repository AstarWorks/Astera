---
status: Accepted
date: 2026-05-31
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0138: Lobby UX — chat display + tab list population

## Status

Accepted

## Context

Round 18 audit α (Explore agent #1) flagged 5 lobby-UX gaps. **Phase A
verification revealed 3 were FALSE alarms** — the underlying feature was
already wired but the agent missed the wiring location:

- ❌ FALSE — "Achievement unlock celebration missing":
  `NotifyOnAchievementUnlockedListener` (`plugin/stats/.../application/eventbus/listeners/`)
  IS wired at `AsteraStatsPlugin.kt:357-364`. It sends chat via
  `IPlayerGateway.sendMessage` + `IMessageRenderer` and pushes a
  `HudLayout.Subtitle` through `IHudGateway`. Wave 6b Paper popup adapter
  will carry the subtitle payload through to the screen unchanged.
- ❌ FALSE — "Settings backend handlers deferred (Wave G)":
  `LobbySettingsLocaleIntentHandler.kt`,
  `LobbySettingsVolumeIntentHandler.kt`,
  `LobbySettingsControlsIntentHandler.kt` ALL implement actual
  side-effects (call `SetLocaleUseCase` / `setVolume` / `setControl`,
  read from `IPlayerSettingsRepository`, send confirmation chat). Not
  no-ops.
- ❌ FALSE — "Reconnect countdown wiring unclear":
  `ReconnectCountdownHudListener` IS wired at `AsteraMatchPlugin.kt:992`
  (Wave 5match-hud Wave 5e).

**True remaining gaps verified by direct grep**:

1. ✅ TRUE — `ChatMessageSent` has 2 subscribers
   (`AuditLogListener.handleChatMessageSent`, `ReplayCaptureEngine`) —
   both audit/persistence concerns. **No subscriber surfaces the message
   to other players.** Player typing `/astera-chat say "hello"` produces
   a domain event that is audited but never displayed to anyone.
2. ✅ TRUE — `ITabList` port + `InMemoryTabList` are registered at
   `AsteraCorePlugin.onEnable:481-484`, but **no listener populates
   roster entries on player join/quit**. Tab list remains vanilla
   Minecraft default — no per-player ping, no display-name override, no
   rank prefix.

This ADR closes both. Wave AB uses **only Core-vendor-neutral ports**
(`IPlayerGateway` + `IPlayerProfileService` + `IPlayerSessionRepository` +
`ITabList`); no Paper dependency. Wave 6b's `PaperPlayerGateway` /
`PaperTabListAdapter` will carry the payloads through to Bukkit / Adventure.

## Decision

Add 2 new listeners in `plugin/core/src/main/kotlin/.../application/eventbus/listeners/`:

### `ChatDisplayListener` (new package `chat/`)

Subscribes `ChatMessageSent`. Per-channel routing:

- `ChatChannel.Global` → `IPlayerSessionRepository.all().map { it.playerId }`
  → `IPlayerGateway.sendMessage(each, "<senderDisplayName> $content")`.
  Sender DOES receive their own message back (mirrors most MC chat UX).
- `ChatChannel.Private(recipient)` → single `sendMessage(recipient, ...)`.
- `ChatChannel.Team` / `ChatChannel.Party` → WARN log + skip. Routing
  requires `IMatchTeamLookup` / `IPartyRepository` lookups that vary by
  feature plugin; Phase 1 PoC keeps this listener Core-only. Wave 6b or a
  follow-up Round wires the channel-aware routing.

Sender display name resolves via `IPlayerProfileService.find(sender)?.displayName`;
fallback to the 8-char UUID prefix if profile missing. Wave 6b's
`PaperChatRenderer` will substitute Bukkit display name + MiniMessage
formatting (this listener stays vendor-neutral).

Per-recipient `sendMessage` failure logs WARN + continues to the next
recipient (one offline player should not drop the broadcast).

### `UpdateTabListOnPlayerJoinedListener` (existing `hud/` dir)

Subscribes both `PlayerJoinedServer` and `PlayerQuitServer`. On either
event:

- Read `sessions.all()` → resolve each playerId's display name via
  `IPlayerProfileService.find(...)?.displayName` (fallback 8-char UUID
  prefix).
- For every online player, call `ITabList.setPlayerEntries(viewerId,
  rosterEntries)` — per `ITabList.setPlayerEntries` KDoc, this is an
  atomic replacement of the per-player list.

`TabListEntry.pingMs = 0` for Phase 1 (no `IPlayerPingProvider` port
yet). Wave 6b's `PaperPingProvider` will populate from
`Player.getPing()`; a follow-up refactor of this listener injects the
port. O(N²) for N online players acceptable for Phase 1 PoC cap of ~100.

### Wiring

Both listeners wired in `AsteraCorePlugin.onEnable` right after the
existing `HintOnPlayerJoinedListener` (ADR-0139) block. Both reuse
already-instantiated `playerGateway`, `sessionRepo`, `profileService`,
`tabList`, `eventBus`.

Unit tests (`ChatDisplayListenerTest` + `UpdateTabListOnPlayerJoinedListenerTest`)
verify per-channel routing + roster refresh + UUID-prefix fallback.

## Consequences

### Positive

- Chat finally visible to other players (single most-impactful Phase 1
  PoC UX gap closed).
- Tab list shows live roster on join/quit; no longer vanilla MC default.
- Pure Core-port wiring — Wave 6b Paper adapters benefit automatically
  (no domain change needed when the Paper rendering ships).
- Uses existing `NotifyOnRecipeCraftedListener` pattern
  (`IPlayerGateway` + `IMessageRenderer`) so future maintainers
  recognise the shape.
- 3 documented false-positives in §Context save future audits from
  re-flagging non-issues.

### Negative / Trade-offs

- Team/Party chat routing not implemented (logs WARN + skips). Phase 1
  PoC tolerates this — Team/Party chat requires active matches/parties
  which are themselves Phase 1 PoC features. Real-world chat-volume in
  Phase 1 will be Global + Private.
- Display name falls back to 8-char UUID prefix when profile missing —
  ugly but explicit. Players without a profile are rare (first-time
  profile creation flow auto-fires per ADR-0116).
- `UpdateTabListOnPlayerJoinedListener.refreshAll()` is O(N²) for N
  online. Phase 1 cap ~100 → 10,000 entry sets per join — bounded;
  Wave 6b can optimise via per-player delta packets if needed.

### Mitigations

- Team/Party WARN log includes channel type + sender — operators can
  identify whether players are blocked by the limitation.
- Tab list refresh is bounded by InMemoryTabList being a `MutableMap`
  write per player; no I/O on the hot path.

## Alternatives Considered

- **Alt A: ChatDisplayListener via `IBroadcaster`**: rejected.
  `IBroadcaster` publishes `DomainEvent` objects (per its KDoc) — not a
  player-message-sender. Misuse would conflate domain event fan-out
  with player I/O. `IPlayerGateway.sendMessage` is the correct path.
- **Alt B: Team/Party routing in this wave via injected
  IMatchTeamLookup / IPartyRepository**: rejected. Adds 2 ports to the
  constructor and requires multi-plugin wiring (IMatchTeamLookup lives
  in plugin/match). Sibling-isolation per ADR-0022 forbids Core →
  Match imports. Better to land Team/Party in a follow-up wave inside
  Match plugin (subscribes to ChatMessageSent + routes accordingly).
- **Alt C: TabList per-player delta updates instead of full refresh**:
  rejected for Phase 1. Adds state tracking (last-known-roster per
  viewer) for marginal CPU savings at Phase 1 player counts. Wave 6b
  Paper impl can optimise via Bukkit `PlayerInfoUpdate` packet selectors.
- **Alt D: Skip Wave AB entirely (3 false-positives, only 2 real
  gaps)**: rejected. The 2 real gaps are both Phase 1 UX blockers
  (invisible chat / empty tab list); the ~4h wave cost is justified.

## References

- 関連 ADR:
  [[adr/0022-multi-plugin-layout]],
  [[adr/0031-tab-list-port]] (Wave 5b — `ITabList` port introduction),
  [[adr/0118-chat-channels-and-audit]] (Wave 5e — `ChatMessageSent`
  event + `ChatChannel` sealed; this ADR wires the player-visibility
  consumer),
  [[adr/0125-content-depth-recipes]] (Wave H — established the
  `IMessageRenderer` + `IPlayerGateway.sendMessage` pattern that
  `ChatDisplayListener` follows),
  [[adr/0130-polish-quick-wins]] (Wave M — established the listener
  pattern under `application/eventbus/listeners/hud/` that
  `UpdateTabListOnPlayerJoinedListener` follows),
  [[adr/0139-onboarding-rotating-tip]] (Round 18 sibling — same
  PlayerJoinedServer subscribe layer)
- Related files:
  `plugin/core/src/main/kotlin/.../application/eventbus/listeners/chat/ChatDisplayListener.kt`,
  `plugin/core/src/main/kotlin/.../application/eventbus/listeners/hud/UpdateTabListOnPlayerJoinedListener.kt`,
  `plugin/core/src/main/kotlin/.../platform/AsteraCorePlugin.kt`
  (wiring around line ~2032),
  `plugin/core/src/test/kotlin/.../application/eventbus/listeners/chat/ChatDisplayListenerTest.kt`,
  `plugin/core/src/test/kotlin/.../application/eventbus/listeners/hud/UpdateTabListOnPlayerJoinedListenerTest.kt`
- Round 18 audit α false-positive corrections documented here:
  - "Achievement unlock celebration missing" — FALSE; exists as
    NotifyOnAchievementUnlockedListener
  - "Settings handlers no-op" — FALSE; all 3 implemented
  - "Reconnect countdown wiring unclear" — FALSE; wired at
    AsteraMatchPlugin.kt:992
- Future work:
  - Team/Party chat routing — follow-up wave inside `plugin/match`
    (subscribes ChatMessageSent + routes via IMatchTeamLookup) and
    `plugin/core` (party routing via IPartyRepository) — both can
    coexist with this Global+Private path.
  - `IPlayerPingProvider` port — Wave 6b PaperPingProvider populates
    `TabListEntry.pingMs` from `Player.getPing()`.
  - PaperChatRenderer — Wave 6b substitutes MiniMessage formatting +
    Bukkit display name in `ChatDisplayListener.format` output.
