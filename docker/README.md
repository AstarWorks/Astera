# Astera local dev — Docker

Paper 26.1.x with the Astera plugin pre-installed, plus Postgres and Redis.

## Prerequisites
- Docker 24+ and Docker Compose v2
- Minecraft Java Edition client matching `VERSION` (default **26.1.2**)

## First-time setup
```bash
# From the repo root
./gradlew :plugin:platform-paper-plugin:shadowJar   # optional: warms Gradle cache
cp docker/.env.example docker/.env                  # adjust passwords if desired
```

## Run
```bash
docker compose -f docker/docker-compose.yml up -d
docker compose -f docker/docker-compose.yml logs -f paper
```

## Verify
1. Launch Minecraft Java Edition 26.1.x.
2. Multiplayer → Direct Connect → `localhost:25565`.
3. In-game, run `/astera give @p example-sword`.
4. You should receive an Iron Sword named **サンプルソード** (ja) or **Example Sword** (en).

Stop with `docker compose -f docker/docker-compose.yml down` (`-v` also wipes volumes).
