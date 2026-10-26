#!/usr/bin/env bash
# Move the live dev stack (compose project astera-dev) to another Docker host (OPS-17).
#
#   scripts/dev-migrate-host.sh user@host
#
# Idempotent; prints every step. NEVER stops or modifies the local stack: it only reads from it
# (pg_dump, tar of non-world state). Cut over afterwards by hand (see docs/contributing/playtest.md,
# "Running the dev stack on a separate host").
#
# How it reaches the target: DOCKER_HOST=ssh://user@host. Compose interpolates docker/.env on THIS
# machine and sends build contexts over ssh, so docker/.env is NOT copied to the target (the Velocity
# forwarding secret and passwords only travel inside the container definitions over ssh, never printed).
#
# Steps: 1 ssh+docker check, 2 local .env check, 3 images + containers + postgres/redis up on the target,
# 4 pg_dump -> restore (skipped when the target DB already has tables), 5 non-world state of lobby-data /
# game-data (worlds are regenerated: void world + slots, ADR-0255), 6 bring the whole stack up, 7 connect address.
#
# Env: RCON_WAIT_SECONDS=900 (total wait for lobby+game RCON), DRY_RUN=1 (print the steps, run nothing), RESTORE=1 (redo DB + volume restore even if already done;
# overwrites newer data on the target), ASTERA_PUBLIC_HOST (address clients use, default the host part),
# SRC_PROJECT (default astera-dev), DATA_PATHS (non-world paths to carry, relative to /data; default
# "*.json plugins"), ASTERA_PORT / RESOURCE_PACK_PORT (as in docker-compose.yml).
set -euo pipefail
cd "$(dirname "$0")/.."

TARGET=${1:-}
[ -n "$TARGET" ] && [[ "$TARGET" == *@* ]] || { echo "usage: $0 user@host   (DRY_RUN=1 to preview)" >&2; exit 2; }

DRY=${DRY_RUN:-0}
SRC_PROJECT=${SRC_PROJECT:-astera-dev}
PUBLIC_HOST=${ASTERA_PUBLIC_HOST:-${TARGET##*@}}
GAME_PORT=${ASTERA_PORT:-25565}
PACK_PORT=${RESOURCE_PACK_PORT:-8099}
DATA_PATHS=${DATA_PATHS:-"*.json plugins"}
COMPOSE_FILE=docker/docker-compose.yml
SSH=(ssh -o BatchMode=yes -o ConnectTimeout=10 "$TARGET")
STEP=0
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

step() { STEP=$((STEP + 1)); printf '\n== [%s] %s\n' "$STEP" "$*"; }
info() { printf '   %s\n' "$*"; }
# Print "+ cmd", run it unless DRY_RUN.
run() { printf '   + %s\n' "$*"; [ "$DRY" = 1 ] || "$@"; }
# Remote daemon / local daemon.
rdocker() { DOCKER_HOST="ssh://$TARGET" docker "$@"; }
rcompose() {
  DOCKER_HOST="ssh://$TARGET" ASTERA_PUBLIC_HOST="$PUBLIC_HOST" POSTGRES_BIND=127.0.0.1 REDIS_BIND=127.0.0.1 \
    docker compose -p "$SRC_PROJECT" -f "$COMPOSE_FILE" "$@"
}
ldocker() { env -u DOCKER_HOST docker "$@"; }
die() { echo "ERROR: $*" >&2; exit 1; }

echo "Migrating dev stack '$SRC_PROJECT' -> $TARGET (public host $PUBLIC_HOST)"
[ "$DRY" != 1 ] || echo "DRY_RUN=1: nothing below is executed."

# ---- 1. target reachable ------------------------------------------------------------------------------
step "check ssh and docker on $TARGET"
if [ "$DRY" = 1 ]; then
  info "+ ssh -o BatchMode=yes $TARGET true"
  info "+ ssh $TARGET docker version / docker compose version / free -g / df -h /var/lib/docker"
else
  "${SSH[@]}" true || die "ssh to $TARGET failed (key auth required, BatchMode)"
  info "ssh ok"
  "${SSH[@]}" 'docker version --format "docker server {{.Server.Version}}"' || die "docker not usable by this user on $TARGET (docker group?)"
  "${SSH[@]}" 'docker compose version' || die "docker compose plugin missing on $TARGET"
  "${SSH[@]}" 'free -g | sed -n 2p; df -h /var/lib/docker | tail -n1' | sed 's/^/   remote: /'
fi

# ---- 2. env -------------------------------------------------------------------------------------------
step "docker/.env stays local (compose interpolates it client-side over DOCKER_HOST=ssh://); not copied, not printed"
if [ -f docker/.env ] && grep -q '^VELOCITY_FORWARDING_SECRET=.' docker/.env; then
  info "docker/.env present, forwarding secret set (value not shown)"
elif [ "$DRY" = 1 ]; then
  info "WARNING: docker/.env missing or without VELOCITY_FORWARDING_SECRET here; the real run would stop (run it from the checkout that has the live .env)"
else
  die "docker/.env with VELOCITY_FORWARDING_SECRET not found; run from the checkout that has the live docker/.env"
fi
info "same secret as the local stack => bot-runner and any tooling keep working; players reconnect to the new address"

# ---- 3. images, containers, postgres + redis ------------------------------------------------------------
step "build images and create containers on the target (no backend started yet)"
run rcompose up -d --build postgres redis
run rcompose up --no-start --build

# ---- 4. database ----------------------------------------------------------------------------------------
step "pg_dump astera DB from ${SRC_PROJECT}-postgres-1 -> restore into the target postgres"
SRC_PG=${SRC_PROJECT}-postgres-1
if [ "$DRY" = 1 ]; then
  info "+ wait until target postgres is healthy"
  info "+ target table count; skip when > 0 unless RESTORE=1"
  info "+ docker exec $SRC_PG pg_dump -U astera -d astera --clean --if-exists --no-owner --no-privileges | ssh docker exec -i <target postgres> psql -U astera -d astera -v ON_ERROR_STOP=1 -q"
else
  ldocker inspect "$SRC_PG" >/dev/null 2>&1 || die "$SRC_PG not found on the local daemon"
  RPG=$(rcompose ps -q postgres)
  until [ "$(rdocker inspect -f '{{.State.Health.Status}}' "$RPG")" = healthy ]; do sleep 2; done
  tables=$(rdocker exec "$RPG" psql -U astera -d astera -tAc "select count(*) from information_schema.tables where table_schema='public'" | tr -d '[:space:]')
  if [ "$tables" != 0 ] && [ "${RESTORE:-0}" != 1 ]; then
    info "target DB already has $tables tables: restore skipped (RESTORE=1 to overwrite)"
  else
    running=$(rcompose ps --status running -q lobby game | wc -l)
    [ "$running" = 0 ] || [ "${RESTORE:-0}" != 1 ] || die "backends are running on the target; stop them before RESTORE=1"
    info "dumping ... (stream, not printed)"
    ldocker exec "$SRC_PG" pg_dump -U astera -d astera --clean --if-exists --no-owner --no-privileges \
      | rdocker exec -i "$RPG" psql -U astera -d astera -v ON_ERROR_STOP=1 -q
    src_n=$(ldocker exec "$SRC_PG" psql -U astera -d astera -tAc "select count(*) from information_schema.tables where table_schema='public'" | tr -d '[:space:]')
    dst_n=$(rdocker exec "$RPG" psql -U astera -d astera -tAc "select count(*) from information_schema.tables where table_schema='public'" | tr -d '[:space:]')
    info "tables: source=$src_n target=$dst_n"
    [ "$src_n" = "$dst_n" ] || die "table count differs after restore"
  fi
fi

# ---- 5. non-world volume state ----------------------------------------------------------------------------
step "carry non-world state of lobby-data / game-data ($DATA_PATHS; world*, jars, logs, caches skipped)"
for svc in lobby game; do
  src=${SRC_PROJECT}-${svc}-1
  vol=${SRC_PROJECT}_${svc}-data
  if [ "$DRY" = 1 ]; then
    info "+ [$svc] tar from $src:/data ($DATA_PATHS, --exclude='*.jar' --exclude='world*') -> volume $vol on the target, marker /data/.astera-migrated"
    continue
  fi
  ldocker inspect "$src" >/dev/null 2>&1 || die "$src not found on the local daemon"
  image=$(rcompose images -q "$svc" | head -n1)
  [ -n "$image" ] || image=astera/minecraft-server:dev
  if [ "${RESTORE:-0}" != 1 ] && rdocker run --rm --entrypoint sh -v "$vol:/data" "$image" -c 'test -e /data/.astera-migrated'; then
    info "$svc: already migrated (RESTORE=1 to redo)"
    continue
  fi
  # shellcheck disable=SC2016
  ldocker exec "$src" sh -c 'cd /data && set -- '"$DATA_PATHS"' && [ -e "$1" ] || exit 0; tar -cf - --exclude="*.jar" --exclude="world*" --exclude="logs" --exclude="cache" "$@" 2>/dev/null || true' > "$TMP/$svc.tar"
  size=$(wc -c < "$TMP/$svc.tar")
  info "$svc: archive $size bytes"
  rdocker run --rm -i --entrypoint sh -v "$vol:/data" "$image" -c \
    'cat > /tmp/in.tar; [ ! -s /tmp/in.tar ] || tar -xf /tmp/in.tar -C /data; touch /data/.astera-migrated' < "$TMP/$svc.tar"
  info "$svc: restored"
done

# ---- 6. bring the stack up -------------------------------------------------------------------------------------
step "bring the whole stack up on the target"
run rcompose up -d --build
if [ "$DRY" != 1 ]; then
  wait_s=${RCON_WAIT_SECONDS:-900}   # first boot on a fresh host downloads Paper/Velocity (helper retries on flaky links)
  deadline=$((SECONDS + wait_s))
  for svc in lobby game; do
    until rdocker exec "$(rcompose ps -q "$svc")" rcon-cli list 2>/dev/null | grep -q 'There are'; do
      [ "$SECONDS" -lt "$deadline" ] || die "$svc not answering RCON after ${wait_s}s (RCON_WAIT_SECONDS) (docker compose logs $svc on the target)"
      sleep 3
    done
    info "$svc: up"
  done
fi

# ---- 7. address ------------------------------------------------------------------------------------------------
step "connect address"
echo "   Minecraft : $PUBLIC_HOST:$GAME_PORT   (open 25565/tcp)"
echo "   Pack      : http://$PUBLIC_HOST:$PACK_PORT/astera.zip   (open $PACK_PORT/tcp)"
if [ "$DRY" != 1 ]; then
  if curl -fsI --max-time 8 "http://$PUBLIC_HOST:$PACK_PORT/astera.zip" >/dev/null 2>&1; then
    echo "   pack URL reachable from here"
  else
    echo "   WARNING: pack URL not reachable from here (firewall / port?)"
  fi
fi
echo
echo "Local stack untouched. Cut over: stop the local stack, then use ASTERA_DEV_HOST=$TARGET for make dev-* (see playtest.md)."
