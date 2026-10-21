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

## 4. Run a match

```
/astera-match start example-arena      # replies "Match started: <matchId>"
/astera-match entry <matchId> <yourName>
/astera-match list
/astera-match end <matchId>
```

Stage spawn points are at y=-60 (superflat surface); see `content/stages/example-arena.yaml`.

## Logs / reset

- Logs: `docker compose -f docker/docker-compose.yml logs -f paper`
- Deleted/renamed files under `content/` stay in the `/data` volume (the image
  sync only overwrites). Remove them with `docker exec astera-paper rm /data/content/<path>`
  or reset the volume.
- Reset world and data: `make docker-nuke` then `make docker-up`
  (`LEVEL_TYPE=FLAT` only applies to a freshly created world).
