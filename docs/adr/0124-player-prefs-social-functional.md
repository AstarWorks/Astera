---
status: Accepted
date: 2026-05-27
deciders: orchestrator (Wave G 5player-prefs-social)
supersedes: null
superseded_by: null
---

# ADR-0124: Player preferences + social GUI functional

## Status

Accepted

## Context

Wave 5lobby-content (ADR-0119) landed the Settings / Friends / Party GUI
**surface** (3 GuiSpec, 3 LobbyXxxShowIntentHandler skeletons) but every
click on a Settings tile or every action button on the Friends / Party
GUIs was a no-op — the application-side handlers were explicitly deferred
to "Wave G". Wave 5e (ADR-0040) had the `Friendship` + `Party` domains
plus the use-case quartet (`SendFriendRequest` / `AcceptFriendRequest` /
`RemoveFriend` for friendships; `CreateParty` / `JoinParty` / `LeaveParty`
/ `DisbandParty` for parties), and Wave 5pve-survival (ADR-0107) bolted
on the island-scoped `InvitePartyMemberUseCase` /
`AcceptPartyInvitationUseCase`.

After Round 1-11 (through ADR-0123), Round 12 of the Phase 1 PoC closing
push had three remaining gaps in the player-self preference + social
mini-domain:

1. **PlayerSettings was incomplete.** Neither `locale` nor per-channel
   volume lived on the record; players who wanted Japanese had no way to
   ask. The locale-resolution path in `IMessageRenderer` was wired to a
   per-player locale function that always returned the server default.
2. **GUI handlers were no-ops.** A click on the locale tile, the volume
   tile, or any Friends / Party action emitted a `GuiIntent` no handler
   subscribed to, and `DefaultGuiIntentRouter` logged the unmatched
   intent at WARN and dropped it.
3. **No Brigadier surface.** Even the chat-command escape hatch
   (`/astera-friend add X`, `/astera-language ja`) didn't exist —
   `AdminOpsCommands` (Wave 5admin-ops, ADR-0122) shipped admin-side
   commands only; player-self commands had no owner.

Two further design questions had to be settled in this wave:

- **Should the lobby Party invite go through `InvitePartyMemberUseCase`?**
  That use case is island-scoped (it requires an `IslandId` and creates a
  `PartyInvitation` row with TTL + async accept). The lobby has no island
  and no async-accept primitive (the anvil-modal `IGuiModalGateway` is
  Wave 10c+ scope).
- **How to localise the GUI text once a player picks Japanese?** The
  existing GUI render pipeline already routes through `IMessageRenderer`
  per-viewer; switching the locale field on `PlayerSettings` is enough
  iff the renderer keys off it. (It does — the LocaleResolver wired
  in core consults the settings repo.)

## Decision

Wave G makes the player-self preferences + social GUI surface
**end-to-end functional**:

### A. PlayerSettings extension

Add three fields to `core.domain.player.PlayerSettings`:

- `locale: LocaleCode = LocaleCode.EN`
- `musicVolume: Int = 100`
- `soundVolume: Int = 100`
- `voiceVolume: Int = 100`

Plus a `VolumeChannel` enum (`Music / Sound / Voice`) and helper methods
`volume(channel)` + `withVolume(channel, value)` that centralise the
`(channel, value)` → field mapping so the cycle-handler logic doesn't
branch on the enum at every call site. The `init` block enforces
`0..100` per-channel; `SetVolumeUseCase` surfaces the same range check
as a typed error.

`PlayerSettings.SUPPORTED_LOCALES = {EN, JA}` gates which locales the
use case + cycle handler will switch to — adding a new locale means
adding `content/languages/<code>.yaml` and extending this set.

**No DB migration.** The V8 `player_settings.settings` JSONB column has
`ignoreUnknownKeys = true`, and the new fields default-back on missing
keys so older rows decode cleanly. `PlayerSettingsJson` was extended
1:1 with the domain.

### B. Two new setter use cases

- `SetLocaleUseCase` returns `Result<Unit, SettingsError.UnsupportedLocale>`
- `SetVolumeUseCase(playerId, channel, value)` returns
  `Result<Unit, SettingsError.InvalidVolume>`

Both follow the existing `SetControlSchemeUseCase` /
`SetParticleVisibilityUseCase` pattern: read-or-default, `copy` the
field, upsert.

`SettingsError` gains two cases: `UnsupportedLocale(locale)` and
`InvalidVolume(value)`.

### C. Two new party use cases

- `KickPartyMemberUseCase(leader, target)` — leader-only; the leader
  cannot self-kick (`PartyError.NotLeader`); target must be a member.
- `TransferPartyLeaderUseCase(currentLeader, newLeader)` — leader-only;
  same-person transfer is rejected; new leader must already be a
  member (use `JoinPartyUseCase` first).

Both return `Result<Unit, PartyError>` and reuse the Wave 5e
`PartyError` hierarchy without adding new variants.

### D. Lobby Party invite is synchronous (PoC trade-off)

The lobby `LobbyPartyInviteIntentHandler` calls
`JoinPartyUseCase.join(target, partyId)` directly rather than going
through `InvitePartyMemberUseCase`. Justification:

- The lobby has no `IslandId` — `InvitePartyMemberUseCase` is built for
  the survival-island domain.
- Phase 1 has no `IGuiModalGateway` primitive (anvil prompt) — the
  async accept loop has no GUI to land in.
- Adding a lobby-flavoured `PartyInvitation` row + accept use case
  pairs poorly with the synchronous fan-out the GUI is designed
  around (one click → one outcome).

When `IGuiModalGateway` lands (Wave 10c+) and the lobby gets a true
invitation inbox, this handler will switch to a lobby-flavoured
invitation use case + the modal-driven accept flow. Until then,
"invite" reads as "add to my party immediately".

### E. Nine intent handlers, registered on `IGuiIntentRouter`

| Intent | Handler |
|---|---|
| `lobby.settings.show` | `LobbySettingsShowIntentHandler` (5lobby-content skeleton, wired here) |
| `settings.locale.cycle` | `LobbySettingsLocaleIntentHandler` |
| `settings.volume.adjust` | `LobbySettingsVolumeIntentHandler` |
| `settings.controls.cycle` | `LobbySettingsControlsIntentHandler` |
| `lobby.friends.show` | `LobbyFriendsShowIntentHandler` (skeleton, wired) |
| `lobby.friends.add` | `LobbyFriendAddIntentHandler` |
| `lobby.friends.accept` | `LobbyFriendAcceptIntentHandler` |
| `lobby.friends.remove` | `LobbyFriendRemoveIntentHandler` |
| `lobby.friends.pending` | `LobbyFriendsPendingIntentHandler` |
| `lobby.party.show` | `LobbyPartyShowIntentHandler` (skeleton, wired) |
| `lobby.party.invite` | `LobbyPartyInviteIntentHandler` |
| `lobby.party.kick` | `LobbyPartyKickIntentHandler` |
| `lobby.party.transfer-leader` | `LobbyPartyTransferLeaderIntentHandler` |
| `lobby.party.leave` | `LobbyPartyLeaveIntentHandler` |

Each handler routes the intent payload into a use case and renders the
typed `Result` arm into a per-viewer chat message via `IMessageRenderer`.
Where the intent's `payload[target]` is blank (the common case for
tile-clicks that lack a modal-typed name today), the handler closes the
GUI and tells the player to use the equivalent Brigadier command. Once
the modal primitive lands, the same handler keeps working — `payload`
just starts arriving populated.

The volume handler cycles `100 → 75 → 50 → 25 → 0 → 100` for the Music
bus only (the Settings GUI exposes a single volume tile in Phase 1).
The Brigadier `/astera-volume <channel> <value>` covers the other two
channels until Wave H adds three tiles.

### F. Four Brigadier command families

- `/astera-language <en|ja>` — permission `astera.settings.language`
- `/astera-volume <music|sound|voice> <0-100>` — permission `astera.settings.volume`
- `/astera-friend-add` / `-accept` / `-remove` / `-list` — permission `astera.friends.use`
- `/astera-party-invite` / `-kick` / `-transfer` / `-leave` / `-list` — permission `astera.party.use`

The Astera `CommandSpec` DSL doesn't compose deeply-nested
literal-then-literal-then-argument trees (see `CommandSpec.Literal`
kdoc) — the verb is mashed into the root command name as
`astera-friend-add`. Modelled after the Wave 5complete
`flatModCommands.kick.copy(name = "astera-kick")` pattern.

Console invocations of these commands fail with
`astera.command.player-only` because the actions are inherently
per-player.

### G. i18n parity + Wave 5e gap closure

New keys land in both `content/languages/en.yaml` and `ja.yaml`:

- `astera.command.player-only`
- `astera.settings.locale.*` (changed / unsupported / error)
- `astera.settings.volume.*` (changed / invalid / unknown-channel /
  error / command.root)
- `astera.settings.controls.changed`
- `astera.settings.language.command.root`
- `astera.friends.*` (command.*, add.*, accept.*, remove.*, pending.*)
- `astera.party.*` (command.*, invite.*, kick.*, transfer.*, leave.*)

Wave G also fills a Wave 5e gap — `FriendshipError` referenced
`astera.social.friendship.error.*` keys that were never added. ADR-0107
wired the `PartyError` side of that gap; Wave G wires the friendship
side (`not-found`, `already-exists`, `blocked`, `not-pending`,
`self-friend`).

### H. Wiring + cleanup discipline

`AsteraCorePlugin` now constructs the 11 use case instances + 14
handlers (5 settings, 5 friends, 5 party = the 9 active handlers plus
the 3 show handlers from 5lobby-content + the previously-unwired
`SetControlSchemeUseCase`) and registers them on the shared
`IGuiIntentRouter`. The `ServiceRegistry.Registration` handles are
collected into a field `waveGIntentRegistrations` and `unregister()`'d
in `onDisable()` so a `/reload` doesn't leak the lambdas' captured
service references.

### I. Konsist

No new architecture rule. The wave introduces a typed enum
(`VolumeChannel`) and two error cases (`UnsupportedLocale` /
`InvalidVolume`) — both are pure domain / application additions and
trip none of the existing Konsist guards. Count stays at **53**.

## Consequences

### Positive

- The Settings / Friends / Party GUI surfaces are end-to-end clickable
  with real outcomes. Phase 1 PoC closing's "GUI skeleton" backlog
  shrinks by 14 handler stubs.
- Players have a chat-command escape for every GUI action — useful for
  ops scripts, tests, and accessibility.
- Locale change works server-wide today (the existing
  `IMessageRenderer` per-viewer locale resolver picks it up from
  `PlayerSettings.locale`). Japanese players see Japanese GUI titles +
  command descriptions on next render.
- `PlayerSettings` JSONB extension proved out the "add field +
  ignoreUnknownKeys" no-migration playbook for future settings.
- Wave 5e's dangling `astera.social.friendship.error.*` keys finally
  exist in en/ja — `FriendshipError`'s `messageKey` references now
  resolve cleanly.

### Negative / Trade-offs

- **Lobby party invite is synchronous.** A non-leader can't reject a
  pull-in. Mitigated by leader-only enforcement on `kick` /
  `transfer-leader` / synchronous-invite together — the social cost
  of being added to the wrong party is low at PoC scale (≤10 active
  players).
- **Volume mix-down isn't implemented.** Setting `musicVolume = 25`
  persists the choice but does not (yet) attenuate the client's
  music packets. The audio backend is Wave 7+ scope.
- **Friend-list / party-member dynamic binding is not wired here.**
  The friend tile region (Slots 0..44 of `FriendsListGuiSpec`) and
  member tile region (Slots 0..8 of `PartyManagerGuiSpec`) still
  render the YAML default — the per-viewer `IGuiBindingResolver` does
  not yet know about `player.friends.0..8` or `player.party.members.0..7`.
  Wave H or a follow-up extends `DefaultGuiBindingResolver`.
- **Cycle UX has no preview.** Volume cycle wraps `100→75→50→25→0→100`
  and the player only sees the new value in chat; the tile itself
  doesn't re-render. The in-place GUI refresh primitive is Wave 10c+.

### Mitigations

- The Brigadier command path covers every gap the GUI doesn't —
  `/astera-volume voice 60` is always available.
- The synchronous lobby invite is contained behind the GUI
  intent + the chat-command path; both rate-limit naturally
  (one click / one command = one add). Spam pressure isn't a
  threat at Phase 1 scale.
- The audio-mix gap is documented inline on `VolumeChannel` so
  the Wave 7 implementer can find it.

## Alternatives Considered

- **Reuse `InvitePartyMemberUseCase` for the lobby invite.** Rejected
  per §D — the use case requires an `IslandId` and the lobby has none.
  Threading a sentinel `IslandId.LOBBY` through the use case would
  pollute the survival domain.
- **Per-bus volume as `Map<VolumeChannel, Int>` instead of three
  fields.** Considered for extensibility. Rejected: the field-based
  shape is cleaner for kotlinx.serialization round-trip (no map-key
  enum encoding question), reads better at call sites
  (`settings.musicVolume` vs `settings.volume[Music]`), and the
  `withVolume` helper hides the enum-to-field switch from callers
  who don't care.
- **Single `/astera` root command with nested `friend add` /
  `party invite` subcommands.** Rejected because the `CommandSpec`
  DSL doesn't compose literal-then-literal-then-argument trees
  cleanly. The Wave 5complete `flatModCommands` precedent (each
  verb as a top-level command) was followed.
- **Add a `FriendshipError.NotPending` placeholder in the en/ja yaml
  rather than fix the Wave 5e gap properly.** Rejected — adding the
  whole `friendship.error.*` block was the same amount of work and
  closes the gap permanently.

## References

- 関連 ADR: [[adr/0040-party-friendship-aggregate]] (Wave 5e domain),
  [[adr/0107-pve-survival-party-island-foundation]] (island-scoped
  invite use case),
  [[adr/0119-lobby-intent-handlers-and-content-wireup]] (skeleton
  GuiSpec + Show handlers),
  [[adr/0122-admin-operations-completeness]] (Brigadier command pattern),
  [[adr/0022-multi-plugin-decomposition]] (sibling-isolation —
  Friend/Party stays in Core).
- 関連 docs: [[architecture/principles]] §Phase 1 PoC posture
  (backward-compat-free).
- RTM: §プレイヤーデータ "設定", §社交 "フレンド" / "パーティ",
  §UI "ロビーGUI" — Wave G closes the "handler functional" axis
  across all three.
