---
status: Accepted
date: 2026-10-23
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0248: Play gate, mode picker, visible queue and "Play vs bots"

## Status

Accepted

## Context

On the live lobby the founder could not tell where to join a game, and after joining nothing seemed to happen:

- Queueing was a menu tile (`gui-tile-queue`), a far-away NPC on the queue island, or a lane pad 50 blocks south of the spawn.
  The picker (`QueueModeListGuiSpec`) listed one hard-coded lane.
- All seven lanes are PvP with a minimum of two players. A solo player waits forever, and the only feedback was one chat
  line (`QueueMatchScheduler.tickLane`). Nothing was written to the server log.
- The founder triggered `moved too quickly!` (dy = 13.32) over and over in the lobby.

## Decision

### 1. One obvious join point: the Play gate

- `LobbyIslandGenerator.playGate()` builds a quartz arch two blocks in front of the spawn tile (the player spawns facing
  it). The `play-npc` villager (`content/lobby-npcs/play-npc.yaml`, spot `(0.5, 65, 4.5)`) stands under it. Right- or
  left-click emits `lobby.queue.show`.
- A big text display above the NPC reads **"▶ Play / プレイ"** (+ a one-line hint). It is a managed, non-persistent
  helper entity (`PaperManagedEntities.mark`, claimed in `IManagedEntityRegistry` under owner key `play-gate`), so a
  restart or a repaste never leaves a duplicate. The NPC's name tag carries the same text.
- `lobby.yaml` `marker_version` 3 -> 4 repastes the lobby once.
- The old queue NPC and the lane pads stay as secondary entry points; they feed the same queue.
- **Menu / compass.** `gui-tile-queue` is now a green emerald "▶ Play / プレイ" and stays the first main-menu tile
  (slot 1). The compass lore says Play is the first entry. The crafting-grid result button and `Q` on the compass open
  the same picker.
- **Tutorial.** The "queue" step's marker points at the Play gate (not a lane pad) and its hint tells the player to
  right-click the Play guide. Queueing from the picker completes the step (`MatchQueueJoined`).

### 2. Mode picker

`lobby.queue.show` opens a 27-slot chest built per open (`QueueModeListGuiSpec.build`), so counts are current. One
column per lane (max nine):

- row 1, the lane tile: name (game mode title), one-line description (`astera.play.lane.<id>.desc`, generic fallback),
  `teams x players, starts with at least N`, `queued now: X` (stack count = queued, min 1), and a hint. Click =
  **Queue** with real players (`lobby.queue.join`, payload `lane`).
- row 2, the **Play vs bots** tile of the same lane (`lobby.queue.bots`): queues the player, then asks the bot runner.
- row 3: "Leave queue" while queued.

Joining moves a player already queued elsewhere to the chosen lane, refuses players in a match, queues parties together
(`LaneQueueJoiner`). The old `modeKey` payload is still accepted.

### 3. Vs bots over Redis (shared with ADR-0247)

- DTOs and codec live in Core so both sides share them: `core.application.bots.{BotRequest, BotStatus, BotState,
  BotMatchWire}`. Port `IBotRequestGateway` (`core.application.port.outbound.bots`), Redis impl `LettuceBotRequestGateway`.
- Request on `astera:bots:request`: `{"requestId","lane","count","requestedBy"}`. `lane` = queue lane id, `requestedBy` =
  the player's user name. `count = max(minPlayers, teams) - queued humans`, at least 1 (norma: one bot, annihilation: two),
  so the lane can start; the runner is expected to queue its bots in that lane.
- Status on `astera:bots:status`: `{"requestId","state":"spawning|queued|failed","bots":[...],"reason"}`, relayed to
  the player as chat. No status within 10 s (`BotMatchRequester`) -> "Bots are unavailable"; after `spawning` the runner
  gets 60 s more. The player stays queued for real players in every failure case. Unknown request ids are ignored.
- Redis URI: `ASTERA_BOTS_REDIS_URI`, else `ASTERA_REDIS_URI`. Without it (or if unreachable) the gateway is the
  `UnavailableBotRequestGateway` and a click answers "unavailable" at once.

### 4. A waiting state you can see

- **Sidebar** (`LobbySidebarService`): lane, `Players: X/min`, `Waiting: m:ss`, and one of `Waiting for opponents`,
  `Starting in Ns`, `Arena busy`. State comes from `IQueueProgress`, implemented by `QueueMatchScheduler`
  (countdown seconds, hold flag).
- **Action bar**: the same facts in one line, re-sent every second at priority 1 while queued.
- **Leave**: a main-menu tile and the picker's footer, plus a hotbar item. ADR-0232 allows one hotbar kit item, so the
  compass is **replaced** by "Leave queue" (`visible_if: state:queued`) while the player waits, and comes back after.
  `GuiVisibility` gained `state:queued` / `state:idle`; Match registers `ILobbyQueueState` and refreshes the kit via
  `ILobbyKitRefresher` on queue join/leave. F still opens the menu while queued.
- **Titles**: "MATCH FOUND / Starting in Ns" when the countdown opens, big digits for the last three seconds, and
  "TO THE ARENA" when the move starts (cross-server `transferring`, local `match-found`).

### 5. Logging

INFO lines: `Queue join / Queue leave (reason)` (event subscriber, covers menu, pad, command and quit), `Queue lane 'x'
roster ready`, `countdown cancelled`, `held`, `formed match`, `Queue formed`, `Assignment ... sent`, `accepted;
transferring N players`, `Transfer of <player> ... requested`, WARN `rejected` / ack timeout, and for bots: request
published (with runner count), status received, timeout.

### 6. The `moved too quickly!` bug

Not a jump pad: the lobby has none. dy = 13.32 = `0.42 + 0.1 * 129`: the lobby's root effect
(`PaperEntityEffectsGateway.freeze/hold`, used by weapon skills such as the katana and by the training dummies) applied
**Jump Boost 128**, which relied on the amplifier wrapping to a negative byte. Modern Paper keeps the full int, so each
jump launched the player 13 blocks up in one packet and Paper rejected the movement. The root now uses a transient
`jump_strength` attribute modifier of -100 % for the root's duration (removed by `thaw` or its timer), no potion.

## Consequences

- The picker's per-lane copy lives in `astera.play.lane.<id>.desc`; a new lane works without it (generic text).
- `tools:e2e` gains a throw-away Redis (`e2e-redis`) and `PlayEntryE2eTest`; it talks to Redis over a raw RESP socket.
- The bot runner (ADR-0247) must subscribe to `astera:bots:request`, queue its bots in `lane`, and answer on
  `astera:bots:status`; the DTOs are `core.application.bots.*`.
- Only one hotbar kit item exists at a time, so while queued the compass is not on the hotbar; the menu stays on `F`.
