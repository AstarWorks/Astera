# Astera local dev — Docker

Paper 26.3 with the 7 Astera plugins and `content/` baked in, plus Postgres
and Redis. Superflat survival world, PvP on, offline mode.

## Run
```bash
cp docker/.env.example docker/.env && $EDITOR docker/.env   # set OPS=YourMinecraftName
make docker-up      # rebuilds the image (jars + content) and starts the stack
make docker-logs    # tail Paper logs
```

Content or code changes need `make docker-up` again (content is baked into the
image; do not bind-mount `../content` — the Docker daemon may be remote/shared).

Connect with Minecraft Java 26.3 to `localhost:25565`. Step-by-step play
instructions: [`docs/contributing/playtest.md`](../docs/contributing/playtest.md).

Stop with `make docker-down` (`make docker-nuke` also wipes volumes/world).
