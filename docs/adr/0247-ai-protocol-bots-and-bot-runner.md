---
status: Accepted
date: 2026-10-23
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0247: AI protocol bots and the bot runner service

## Status

Accepted

## Context

The founder wants to play "me vs bot" on the live server, and wants bot-vs-bot matches in E2E. The whole match stack
(queue, handoff between lobby and game backend, teams, death/respawn, rewards) is keyed on real players. The protocol
bots of `:tools:e2e` (MCProtocolLib, ADR-0222) already behave like real clients but only follow scripts; they cannot
fight on their own.

## Decision

1. **Bots are real protocol clients** (the E2E `Bot`), not in-process NPC players. They log in, queue, get handed to
   the game backend, die, respawn and collect rewards through the unchanged production path. The match plugin needs no
   "fake participant" code.
2. **New module `:tools:bot-runner`** (not shipped, not in `check`). It now owns `Bot` and `VelocityForwarding`
   (still in package `com.astarworks.astera.e2e`, so the E2E suites compile unchanged; `:tools:e2e` depends on it),
   plus `com.astarworks.astera.bots`:
   - `BotBrain`: the combat AI, one tick per 50 ms.
   - `Terrain`: blocks rebuilt from chunk / block-update packets, for edge probing.
   - `StageObjectives`: objective knowledge read from `content/` (`queue.yaml` lane -> stage -> regions / spawns).
   - `BotRunner` + `BotSession`: the long-running service.
3. **Runner = compose service `bot-runner`** in `docker/docker-compose.yml` (image `docker/Dockerfile.bot-runner`).
   It is a plain service: `make dev-plugins` / `make dev-content` never touch it. Rebuild after AI changes with
   `docker compose -f docker/docker-compose.yml up -d --build bot-runner`.
4. **Login path.** The production Velocity is online-mode and refuses an account without a Mojang session. The
   runner therefore connects to the `lobby` backend directly and answers Paper's modern-forwarding query with the
   shared secret (same as `make live-bots`, ADR-0221), then follows the proxy `Connect` messages
   (`lobby` -> `game` -> `lobby`) exactly like Velocity would. Players see bots as ordinary players named `Bot_*`.

### Request protocol (Redis pub/sub, DTOs in `core.application.bots`)

`BotMatchWire` (channels + JSON codec), `BotRequest`, `BotStatus`, `BotState` (ADR-0248) live in `plugin:core` so the lobby/match plugins can publish and consume them
without depending on the runner; the runner depends on `:plugin:core` for the same classes.

| Channel | Payload |
|---|---|
| `astera:bots:request` | `{"requestId","lane","count","requestedBy"}` (`BotRequest`) |
| `astera:bots:status` | `{"requestId","state":"spawning\|queued\|failed","bots":[names],"reason"}` (`BotStatus`, `reason` may be null) |

Flow: the runner answers `spawning` with the chosen names at once; bots log in one by one (Paper throttles logins from
one address, ~5 s apart); after every bot of the request issued `astera queue join <lane>` and got a chat reply it
publishes `queued`. `failed` carries the reason (invalid lane, count < 1, cap reached, login failure). Unknown JSON
fields are ignored on both sides, so either side may add fields first.

Rules: at most `BOT_MAX` (default 8) bots connected at once; a request that would exceed the cap fails as a whole.
Names are `Bot_Ash`, `Bot_Birch`, ... (prefix `Bot_`). A bot disconnects ~6 s after it is back in the
lobby from a match (time to read the result and rewards), after `BOT_QUEUE_TIMEOUT_MINUTES` (default 10) without a
match, on being kicked, or after 40 minutes.

### Combat AI

Per tick, entirely from packets:

- **Perception**: entity add/move/teleport/remove packets (positions of all players, names from the tab list), own
  game mode (spectator = death cam, the bot waits quietly), scoreboard teams (no friendly fire), hotbar, action bar
  (`Ammo n/m`), food bar (energy, ADR-0194).
- **Target**: nearest enemy player. Faces it with an aim error (`BotSkill.aimErrorDeg`, default 4 degrees).
- **Movement**: exactly one position packet per tick wrapped in `ClientTickEnd` (`Bot.nudge()`, the 26.x rule). Melee
  weapons chase to ~1.6 blocks and circle; ranged ones hold ~9 blocks, back off when closer than ~5.5 and strafe.
- **Weapons**: the loadout is learned by selecting each hotbar slot once and reading the action bar (ranged items show
  `Ammo`). Melee swings are `punch` + `attack(entity)` every 700 ms while the food bar shows enough energy; the
  ranged item fires every ~450 ms and is reloaded with the Shift+Q sequence when the bar reads `Ammo 0` (or 12 shots
  went by without a reading).
- **Edges**: a step is taken only when the block under the landing point AND under a point 0.8 blocks further is
  solid (`Terrain.floorAt`); otherwise the heading is bent in steps up to 130 degrees, and the bot stands still if
  every direction is a drop. The bot never steps up a block (no jump physics); it follows drops of up to two blocks.
- **No enemy in sight**: walk to the last enemy sighting (8 s), else the mode objective (`StageObjectives`: the
  enemy `core-<team>` region, which it digs; other enemy-owned regions; shared tnt / wool regions; else the enemy
  team spawn), else wander. Chunks it never received are treated as unknown, not as void.
- **Death**: spectator mode = idle; a vanilla death screen gets the Respawn button.

The AI is deliberately beatable: it has reaction slack (swing / shot intervals), aim error and no leading of moving
targets.

### E2E

`BotVsBotE2eTest` (`make e2e`, no Redis needed: it drives two `Bot` + `BotBrain` directly against the `all`-role
backend): queue norma -> handoff -> ACTIVE -> the AI produces at least one kill without RCON damage -> the match ends
by itself with one VICTORY and one DEFEAT -> both bots are back in the lobby with summary and rewards. Bounded to 5
minutes of play.

## Consequences

### Positive
- Playing against bots and filling a queue need no match-plugin changes.
- The AI is exercised in CI by the same code the live runner runs.

### Negative / Trade-offs
- Bots cannot jump or fall: the arena must be walkable at one level (steps of 0 and drops of up to two blocks).
- The AI knows items only by their action-bar behaviour (ranged = shows ammo); staves and other resource types play
  as melee items until the AI learns them.
- Bots bypass Velocity (they need to authenticate with the forwarding secret), so the proxy's own behaviour is not
  exercised by them.
- Bot-vs-bot E2E depends on the arena being walkable by the edge probe; the sky-island arena work may need the
  probe tuned (`PROBE_AHEAD`, `maxDrop`).

## Alternatives Considered

- **In-process NPC players**: the whole match stack is keyed on real players, so every system would need a fake
  participant path. Rejected by the manager.
- **Joining through Velocity**: needs an offline-mode proxy; production is online-mode.
- **Server-side RCON forced kills in E2E**: proves nothing about the AI.

## References

- ADR-0221 (live-stack bots), ADR-0222 (E2E harness), ADR-0194 (food bar = energy), ADR-0245
- `docs/contributing/playtest.md` (§ Bots), `scripts/bots.sh`
