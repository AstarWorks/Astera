# Playtest (Docker)

Goal: boot the server, log in with a Minecraft Java client, get weapons, play a match.

## 1. Build and boot

```bash
cp docker/.env.example docker/.env     # set OPS=<your Minecraft name>
make docker-up                          # builds 7 jars + bakes content/, starts the stack
make docker-logs                        # wait for "Done (...)!" and all Astera-* enabled
```

`make docker-up` rebuilds every time; re-run it after changing code or `content/`.

## 2. Connect

Minecraft Java Edition 26.1.x -> Multiplayer -> Direct Connect -> `localhost:25565`
(host:25565; `online-mode=false`, any username). World is superflat survival, PvP on.
You must be op (`OPS=` in `docker/.env`, or run `op <name>` in the server console:
`docker exec -i astera-paper rcon-cli op <name>`).

## 3. Get weapons

```
/astera-items give <yourName> example-sword
/astera-items give <yourName> example-pistol
/astera-items give <yourName> example-wand
/astera-items give <yourName> example-sword 3     # optional amount
```

## 4. Run a match (queue)

Matches form automatically from the queue; the config is
`content/matchmaking/queue.yaml` (shipped with `min_players: 1`, so you can
start alone; friends who join the queue before the countdown ends are
split across the two teams).

```
/astera-queue join norma     # "Joined queue: norma", then "n/min players queued"
/astera-queue status         # your position + how many players are queued
/astera-queue leave          # optional: leave the queue
```

What happens next (no further commands):

1. Once `min_players` are queued a countdown starts (default 10 s, announced
   in chat at 10/5/3/2/1). It restarts if someone leaves or disconnects, and
   is cancelled if the queue drops below the minimum. A full queue
   (`teams x players_per_team`) starts immediately.
2. "Match found!": you are put on a team (sizes balanced within 1) and
   teleported to the stage spawn (`content/stages/example-arena.yaml`).
3. A 10 s in-arena COUNTDOWN (`match_countdown_seconds`), then the match is ACTIVE.
4. When the match ends (win condition, or `/astera-match end <matchId>`) everyone
   is teleported back to the lobby and can `/astera-queue join norma` again.
   `/astera-match list` shows the match id while it runs.

If the arena is still in use, the queue waits (you are told once) and the
next match starts as soon as it is free.

Tuning (`content/matchmaking/queue.yaml`, `/astera reload matchmaking` applies it):
`check_interval_seconds`, `queue_countdown_seconds`, `match_countdown_seconds`, and
per lane `stage`, `teams`, `players_per_team`, `min_players`.

Admin shortcuts (skip the queue): `/astera-match start example-arena`, then
`/astera-match entry <matchId> <name>` (teleports and auto-balances teams), plus
`/astera-match phase|end|abort|list`.

## Logs / reset

- Logs: `docker compose -f docker/docker-compose.yml logs -f paper`
- Content is baked into the image at `/content` (outside the `/data` volume),
  so every `make docker-up` serves exactly the repo's `content/`.
- Reset world and data: `make docker-nuke` then `make docker-up`
  (`LEVEL_TYPE=FLAT` only applies to a freshly created world).
