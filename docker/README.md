# Astera local dev — Docker

Velocity proxy in front of two Paper 26.3 backends (`lobby` and `game`) running
the 7 Astera plugins with `content/` baked in, plus Postgres and Redis
(ADR-0179). Superflat survival world, PvP on.

```
player --25565--> velocity (online-mode, Mojang login, modern forwarding)
                     |-- lobby (ASTERA_SERVER_ROLE=lobby): queue, shop, vault, NPCs
                     `-- game  (ASTERA_SERVER_ROLE=game):  matches only
lobby, game --> postgres (wallet / vault / profile / ...) + redis (match assignments)
```

Only `velocity` publishes a game port. The backends run `ONLINE_MODE=FALSE` but
accept only connections signed with the forwarding secret, so nobody can join a
backend directly or pose as someone else.

## Run
```bash
cp docker/.env.example docker/.env && $EDITOR docker/.env   # set OPS=YourMinecraftName
make docker-up      # rebuilds the image (jars + content), generates the forwarding secret, starts the stack
make docker-logs    # tail velocity + lobby + game
```

`make docker-up` appends a random `VELOCITY_FORWARDING_SECRET` to `docker/.env`
the first time (the file is git-ignored; never commit a real value). Running
`docker compose` by hand without that variable set fails with a clear message.

Content or code changes need `make docker-up` again (content is baked into the
image; do not bind-mount `../content` — the Docker daemon may be remote/shared).

Connect with a **real, paid Minecraft Java 26.3 account** to `localhost:25565`
(Velocity authenticates against Mojang). Step-by-step play instructions:
[`docs/contributing/playtest.md`](../docs/contributing/playtest.md).

## Layout

| Path | Purpose |
|---|---|
| `Dockerfile` | Paper backend image (plugins + `content/` + `/patches`) |
| `velocity/` | Velocity image: itzg/mc-proxy + `velocity.toml` (`[servers]` lobby + game, `try = ["lobby"]`) |
| `paper-patches/velocity.json` | itzg patch: Paper `proxies.velocity` enabled, online-mode, secret from `CFG_VELOCITY_SECRET` |

## Running a second stack

There are no pinned container names and the host ports are variables
(`ASTERA_PORT`, `POSTGRES_PORT`, `REDIS_PORT`), so:

```bash
ASTERA_PORT=25570 POSTGRES_PORT=5441 REDIS_PORT=6391 \
  docker compose -p astera-other -f docker/docker-compose.yml up -d --build
```

Operator access: `docker compose -f docker/docker-compose.yml exec lobby rcon-cli`
(or `game`). RCON and metrics are not published to the host.

Stop with `make docker-down` (`make docker-nuke` also wipes volumes/worlds).
