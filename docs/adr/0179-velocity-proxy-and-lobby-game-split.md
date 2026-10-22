---
status: Accepted
date: 2026-10-22
deciders: founder + Claude (orchestrator)
supersedes: null
superseded_by: null
---

# ADR-0179: Velocity proxy, lobby/game server roles, cross-server queue hand-off

## Status

Accepted. Realises the adapters ADR-0084 deferred (`IProxyMessenger`,
`IServerTransferGateway`) and extends ADR-0174's wiring seam with a server role.

## Context

The playtest stack ran one Paper server with `ONLINE_MODE=FALSE` exposed on the
host: anyone could join under any name, including an op's. The founder wants the
lobby and PvP on separate servers behind Velocity.

## Decision

### 1. Topology (docker/docker-compose.yml, deploy/minecraft)

```
player --25565--> velocity  (online-mode=true, modern forwarding)
                    |-- lobby  Paper, ASTERA_SERVER_ROLE=lobby, name "lobby"
                    `-- game   Paper, ASTERA_SERVER_ROLE=game,  name "game"
lobby, game --> postgres (ASTERA_PG_*)  +  redis (ASTERA_REDIS_URI)
```

- Velocity is the only service publishing a game port. Backends run
  `ONLINE_MODE=FALSE` with Paper `proxies.velocity.enabled/online-mode/secret`
  (itzg `PATCH_DEFINITIONS=/patches`, patch baked into the image), so they accept
  only connections signed with the shared **forwarding secret**.
- The secret is never committed: compose requires `VELOCITY_FORWARDING_SECRET`
  (generated into the git-ignored `docker/.env` by `make docker-up`); in k8s it is
  a Secret reference (`astera-runtime`).
- `[servers] lobby, game; try = ["lobby"]`. Separate data volumes; both backends
  share Postgres and Redis. Redis config is unified on `ASTERA_REDIS_URI` (the old
  `ASTERA_REDIS_HOST/PORT` compose variables were never read).
- Containers are no longer name-pinned and host ports are variables, so a second
  stack can run beside a live one.

### 2. Server role

`ASTERA_SERVER_ROLE` = `lobby` | `game` | `all` (default `all`),
`ASTERA_SERVER_NAME` = the backend's name in `[servers]`. Core reads them once
(`ServerEnvironment`), every plugin builds its `WiringContext` with the same role
and name. Wiring gates by role:

| Feature | lobby | game | all |
|---|---|---|---|
| combat, items, stats, economy, persistence | yes | yes | yes |
| queue command, queue scheduler, lobby queue intents, lobby sidebar | yes | no | yes |
| lobby NPCs (incl. shop NPC) + shop intent, first-time onboarding | yes | no | yes |
| local match formation (`MatchFoundUseCase`) | no (remote hand-off) | n/a | yes |
| assignment receiver, return-to-lobby transfer | no | yes | no |
| in-world post-match lobby teleport | yes | no | yes |

First-time onboarding is lobby-only because player profiles are still in-memory per
backend: a game server would otherwise treat every arrival as a new player and
re-run the starter grant.

### 3. Transfer

- `RedisProxyMessenger` (`IProxyMessenger`): Lettuce pub/sub. Each backend listens
  on `astera:proxy:to:<name>` and `astera:proxy:all`; frame =
  `UTF(from) UTF(channel) int(len) bytes`. Handlers run on the game thread.
- `sendToServer` = BungeeCord plugin channel `Connect` sub-message
  (`BungeeCordPlayerConnector`); Velocity implements it, no proxy-side plugin.
- Both are registered at priority 100 over the in-memory loopback (lobby/game
  role with `ASTERA_REDIS_URI` only). The existing `InMemoryServerTransferGateway`
  (permission check, `PlayerTransferRequested`, deferred queues) is built on top of
  the active messenger; system hops (`MatchFound`, `LobbyReturn`) skip the
  per-server permission node.

### 4. Assignment protocol (`MATCH_FOUND`, `MATCH_STATUS` channels)

1. Lobby `QueueMatchScheduler` (unchanged) asks a `MatchFormer`. On `lobby` that is
   `RemoteMatchFormer`: it takes the formation from the matchmaker, publishes a
   `MatchAssignment(id, stage, modeKey, teams -> player ids)` to the game server and
   marks the game server busy (queue holds, players are told the arena is in use).
2. Game `MatchAssignmentReceiver`: busy (an assignment or an active match) ->
   `Rejected`; else `Accepted`.
3. Lobby on `Accepted`: transfers every player (`MatchFound`). On `Rejected` or an
   ack timeout (10 s): players are re-queued, notified, busy cleared.
4. Game waits for the assigned players (`PlayerJoinedServer`). All arrived, or the
   arrival timeout (45 s) hit: start the match with the assigned stage/mode and
   enter every arrived player on their team (assignment team i -> match team i).
   Nobody arrived: drop the assignment (`Ended`). Missing players are dropped.
5. On `MatchEnded`, after 8 s the game server transfers all participants back
   (`LobbyReturn`) and sends `Ended`; the lobby clears busy. A 30 min lease on the
   lobby releases a game server that never answers `Ended`.
6. Anyone who connects to the game server without an assignment (or too late) and is
   not in an active match is told so and sent back to the lobby after 3 s.

Timing and server names live in `CrossServerSettings` (`ASTERA_LOBBY_SERVER`,
`ASTERA_GAME_SERVER` override the names).

## Consequences

- One match at a time on the game server. Parallel matches need several game
  servers and an assignment router (later; the protocol carries the target server).
- Persisted state (wallet, vault, loadouts, stats, inventory snapshot, session) is
  shared through Postgres (Persistence overlays the in-memory repositories at
  priority 100 when `ASTERA_PG_*` is set). Not shared: player profiles, rating,
  tutorial state (still in-memory) - a lobby restart repeats the starter grant
  (pre-existing).
- Known race: the lobby saves the inventory snapshot on quit while the game server
  restores it on join; with fast hops the restore can run before the save lands.
  To watch in the first real playtest.
- Admin commands such as `/astera-match start` still exist on a lobby backend.

## Verification

- Unit: `CrossServerHandoffTest` (codec, happy path, busy reject, ack timeout,
  arrival timeout, nobody arrives, stray bounce).
- Scenario: `:tools:game-sim` `LobbyGameSplitScenarioTest` boots a lobby-role and a
  game-role sim joined by `SimProxyNetwork` (fake Redis + proxy): queue on the
  lobby -> assignment -> transfer -> match on the game server -> end -> back on the
  lobby -> a held second player is then assigned.
- Compose stack booted under a separate project name (see playtest.md).

## References

ADR-0084, ADR-0135, ADR-0089, ADR-0068, ADR-0174.
