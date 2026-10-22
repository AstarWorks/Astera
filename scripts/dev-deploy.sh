#!/usr/bin/env bash
# Fast redeploy into the running docker dev stack (F1-I, ADR-0186).
#
#   scripts/dev-deploy.sh plugins   build the 7 shadow jars, restart only the backends whose jars changed
#   scripts/dev-deploy.sh content   push content/ into the backends and reload it in place (no restart)
#
# Env: BACKENDS="lobby game" (which backends to touch, in this order), FORCE=1 (redeploy even when the
# jars are byte-identical), BOOT_TIMEOUT (seconds to wait for a backend to answer RCON, default 240).
#
# Velocity, Postgres, Redis and the resource pack are never touched. The Docker daemon is shared with
# the host, so bind mounts of the repo would be empty: everything goes in with `docker compose cp`.
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f docker/docker-compose.yml)
BACKENDS=${BACKENDS:-"lobby game"}
BOOT_TIMEOUT=${BOOT_TIMEOUT:-240}
PLUGINS=(core items combat match stats economy persistence)

log() { printf '[dev-deploy %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
now() { date +%s.%N; }
elapsed() { awk -v a="$1" -v b="$(now)" 'BEGIN { printf "%.1fs", b - a }'; }

other_backend() { if [ "$1" = lobby ]; then echo game; else echo lobby; fi; }

rcon() { local svc=$1; shift; "${COMPOSE[@]}" exec -T "$svc" rcon-cli "$@" 2>&1 | tr -d '\r' | sed 's/\x1b\[[0-9;]*m//g' | sed '/^$/d' || true; }

is_running() { [ "$("${COMPOSE[@]}" ps --status running -q "$1" | wc -l)" -gt 0 ]; }

# Ready = Paper answers RCON (it opens RCON right before "Done"). The container healthcheck only runs every 30 s.
wait_ready() {
  local svc=$1 deadline=$((SECONDS + BOOT_TIMEOUT))
  until [ -n "$(online "$svc")" ]; do
    [ "$SECONDS" -lt "$deadline" ] || { log "ERROR: $svc not ready after ${BOOT_TIMEOUT}s"; exit 1; }
    sleep 1
  done
}

quiet() { local out; out=$("$@" 2>&1) || { printf '%s\n' "$out" >&2; return 1; }; }

# Number of players on a backend; empty when RCON does not answer.
online() { rcon "$1" list | sed -n 's/.*There are \([0-9][0-9]*\) of a max.*/\1/p' | head -n1; }

wait_empty() {
  local svc=$1 deadline=$((SECONDS + 30)) n
  while :; do
    n=$(online "$svc")
    [ "$n" = 0 ] && return 0
    [ "$SECONDS" -lt "$deadline" ] || { log "WARN: $n player(s) still on $svc; they will be sent by Velocity's fallback"; return 0; }
    sleep 1
  done
}

# Move everyone off $1 to the other backend. Prints the number of players parked.
drain() {
  local svc=$1 target n
  target=$(other_backend "$svc")
  n=$(online "$svc")
  if [ -z "$n" ] || [ "$n" = 0 ]; then echo 0; return 0; fi
  if [ -z "$(online "$target")" ]; then
    log "WARN: $target is not up, cannot park $n player(s) from $svc"
    echo 0; return 0
  fi
  log "$svc: moving $n player(s) to $target (running matches are cancelled)"
  rcon "$svc" "astera admin drain $target" >&2
  wait_empty "$svc" >&2
  echo "$n"
}

plugins() {
  local t0 t
  t0=$(now)
  log "building shadow jars"
  t=$(now)
  ./gradlew -q $(printf ':plugin:%s:shadowJar ' "${PLUGINS[@]}")
  log "build: $(elapsed "$t")"

  local jars=() j
  for p in "${PLUGINS[@]}"; do
    j=$(ls plugin/"$p"/build/libs/astera-*.jar | head -n1)
    jars+=("$j")
  done

  for svc in $BACKENDS; do
    if ! is_running "$svc"; then log "$svc is not running: start the stack with 'make docker-up' first"; exit 1; fi
    local changed=() remote jar name
    remote=$("${COMPOSE[@]}" exec -T "$svc" sh -c 'sha256sum /data/plugins/astera-*.jar 2>/dev/null' | tr -d '\r')
    for jar in "${jars[@]}"; do
      name=$(basename "$jar")
      if [ "${FORCE:-0}" = 1 ] || ! grep -q "^$(sha256sum "$jar" | cut -d' ' -f1)  /data/plugins/$name\$" <<<"$remote"; then
        changed+=("$jar")
      fi
    done
    if [ "${#changed[@]}" = 0 ]; then log "$svc: jars unchanged, nothing to do"; continue; fi
    log "$svc: changed: $(printf '%s ' "${changed[@]##*/}")"

    local t_svc parked
    t_svc=$(now)
    parked=$(drain "$svc")
    t=$(now)
    quiet "${COMPOSE[@]}" stop -t 60 "$svc"
    log "$svc: stopped ($(elapsed "$t"))"
    t=$(now)
    for jar in "${changed[@]}"; do
      # /data/plugins is what Paper loads; /plugins is itzg's sync source on every start (see ADR-0186).
      quiet "${COMPOSE[@]}" cp "$jar" "$svc:/data/plugins/$(basename "$jar")"
      quiet "${COMPOSE[@]}" cp "$jar" "$svc:/plugins/$(basename "$jar")"
    done
    log "$svc: copied ${#changed[@]} jar(s) ($(elapsed "$t"))"
    t=$(now)
    quiet "${COMPOSE[@]}" start "$svc"
    wait_ready "$svc"
    log "$svc: booted ($(elapsed "$t"))"
    if [ "$parked" -gt 0 ]; then
      local other
      other=$(other_backend "$svc")
      log "$svc: bringing $parked player(s) back from $other"
      rcon "$other" "astera admin drain $svc keep" >&2
    fi
    log "$svc: done in $(elapsed "$t_svc")"
  done
  log "total: $(elapsed "$t0")"
}

content() {
  local t0 svc t
  t0=$(now)
  for svc in $BACKENDS; do
    if ! is_running "$svc"; then log "$svc is not running: start the stack with 'make docker-up' first"; exit 1; fi
    t=$(now)
    # Copy next to /content, then swap, so files deleted from the repo disappear too.
    quiet "${COMPOSE[@]}" exec -T -u root "$svc" rm -rf /content.new
    quiet "${COMPOSE[@]}" cp content/. "$svc:/content.new"
    quiet "${COMPOSE[@]}" exec -T -u root "$svc" sh -c 'rm -rf /content.old && mv /content /content.old && mv /content.new /content && rm -rf /content.old'
    log "$svc: content copied ($(elapsed "$t"))"
    t=$(now)
    local out
    out=$(rcon "$svc" "astera admin reload all")
    log "$svc: reload: ${out:-<no output>} ($(elapsed "$t"))"
  done
  log "total: $(elapsed "$t0")"
}

case "${1:-}" in
  plugins) plugins ;;
  content) content ;;
  *) echo "usage: $0 plugins|content" >&2; exit 2 ;;
esac
