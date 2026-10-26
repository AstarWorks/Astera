#!/usr/bin/env bash
# Fast redeploy into the running docker dev stack (F1-I, ADR-0186; hotswap and pack: F3-9, ADR-0224).
#
#   scripts/dev-deploy.sh plugins   build the 7 shadow jars, restart only the backends whose jars changed
#   scripts/dev-deploy.sh hotswap   build the 7 shadow jars and apply the changed classes to the RUNNING
#                                   backends (JBR + agent image, `make dev-hotswap-up`); a backend restarts
#                                   only when the JVM refuses the change
#   scripts/dev-deploy.sh deploy    build jars AND push content/ into each backend while it still runs, then restart
#                                   that backend ONCE (players moved, not kicked): it boots with matching jar+content
#                                   and builds every arena in that single boot. Jars byte-identical and no FORCE:
#                                   content-only, reloaded in place like `content`.
#   scripts/dev-deploy.sh content   push content/ into the backends and reload it in place (no restart)
#   scripts/dev-deploy.sh pack      re-read the resource pack hash on the backends and re-push the pack
#
# Env: BACKENDS="lobby game" (which backends to touch, in this order), FORCE=1 (redeploy even when the
# jars are byte-identical), BOOT_TIMEOUT (seconds to wait for a backend to answer RCON, default 240),
# COMPOSE_FILES="docker/docker-compose.yml docker/docker-compose.hotswap.yml" (compose files to use) and
# COMPOSE_PROJECT_NAME (another stack, e.g. an experiment next to the live one), PACK_FORCE=1 (`pack`
# re-pushes even when the hash is unchanged).
#
# Velocity, Postgres and Redis are never touched. The Docker daemon is shared with the host, so bind
# mounts of the repo would be empty: everything goes in with `docker compose cp`.
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE=(docker compose)
for f in ${COMPOSE_FILES:-docker/docker-compose.yml}; do COMPOSE+=(-f "$f"); done
BACKENDS=${BACKENDS:-"lobby game"}
BOOT_TIMEOUT=${BOOT_TIMEOUT:-240}
PLUGINS=(core items combat match stats economy persistence)

# `docker compose exec -T` returns as soon as its stdin hits EOF (cron, CI, `< /dev/null`), cutting the command
# off or dropping its output. Plain `docker exec` without -i attaches no stdin and always waits.
cid() { "${COMPOSE[@]}" ps -q "$1"; }
dexec() { local svc=$1; shift; docker exec "$(cid "$svc")" "$@"; }
dexec_root() { local svc=$1; shift; docker exec -u root "$(cid "$svc")" "$@"; }

log() { printf '[dev-deploy %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
now() { date +%s.%N; }
elapsed() { awk -v a="$1" -v b="$(now)" 'BEGIN { printf "%.1fs", b - a }'; }

other_backend() { if [ "$1" = lobby ]; then echo game; else echo lobby; fi; }

rcon() { local svc=$1; shift; dexec "$svc" rcon-cli "$@" 2>&1 | tr -d '\r' | sed 's/\x1b\[[0-9;]*m//g' | sed '/^$/d' || true; }

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

JARS=()
build_jars() {
  local t p
  log "building shadow jars"
  t=$(now)
  ./gradlew -q $(printf ':plugin:%s:shadowJar ' "${PLUGINS[@]}")
  log "build: $(elapsed "$t")"
  JARS=()
  for p in "${PLUGINS[@]}"; do
    JARS+=("$(ls plugin/"$p"/build/libs/astera-*.jar | head -n1)")
  done
}

# Jars that differ from what the backend boots with. /plugins is itzg's sync source on every start, so it
# is the state a restart would produce (and `hotswap` keeps it in step with the running JVM).
changed_jars() {
  local svc=$1 remote jar name
  remote=$(dexec "$svc" sh -c 'sha256sum /plugins/astera-*.jar 2>/dev/null' | tr -d '\r')
  for jar in "${JARS[@]}"; do
    name=$(basename "$jar")
    if [ "${FORCE:-0}" = 1 ] || ! grep -q "^$(sha256sum "$jar" | cut -d' ' -f1)  /plugins/$name\$" <<<"$remote"; then
      echo "$jar"
    fi
  done
}

# Drain, stop, copy the given jars, start, wait for RCON, bring the parked players back.
restart_backend() {
  local svc=$1; shift
  local changed=("$@") t t_svc parked jar other
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
    other=$(other_backend "$svc")
    log "$svc: bringing $parked player(s) back from $other"
    rcon "$other" "astera admin drain $svc keep" >&2
  fi
  log "$svc: done in $(elapsed "$t_svc")"
}

plugins() {
  local t0 svc
  t0=$(now)
  build_jars
  for svc in $BACKENDS; do
    if ! is_running "$svc"; then log "$svc is not running: start the stack with 'make docker-up' first"; exit 1; fi
    local changed=()
    mapfile -t changed < <(changed_jars "$svc")
    if [ "${#changed[@]}" = 0 ]; then log "$svc: jars unchanged, nothing to do"; continue; fi
    log "$svc: changed: $(printf '%s ' "${changed[@]##*/}")"
    restart_backend "$svc" "${changed[@]}"
  done
  log "total: $(elapsed "$t0")"
}

# ---- hot code swap (F3-9, ADR-0224) ------------------------------------------------------------------
# The agent in the backend JVM (docker/hotswap/AsteraHotswapAgent.java, loopback port inside the container)
# diffs the running jar (/plugins/<jar>) against the new one and redefines the changed classes in place.
HOTSWAP_PORT=${HOTSWAP_PORT:-5006}
HOTSWAP_DIR=/tmp/astera-hotswap/new

hotswap_request() {
  local svc=$1; shift
  local msg='APPLY\n' jar name reply deadline=$((SECONDS + 120))
  for jar in "$@"; do
    name=$(basename "$jar")
    msg+="JAR $name /plugins/$name $HOTSWAP_DIR/$name\\n"
  done
  msg+='END\n'
  reply=$HOTSWAP_DIR.reply
  # The exchange runs detached inside the container and leaves its answer in a file that is polled with short
  # execs: a long-running `docker exec` can return before its command has finished (stdin at EOF), which
  # would lose the answer.
  dexec_root "$svc" rm -f "$reply" "$reply.tmp"
  docker exec -d -u root "$(cid "$svc")" bash -c \
    "exec 3<>/dev/tcp/127.0.0.1/$HOTSWAP_PORT && printf '%b' \"\$0\" >&3 && cat <&3 > $reply.tmp; mv -f $reply.tmp $reply" "$msg"
  until [ -n "$(dexec_root "$svc" sh -c "test -s $reply && echo ready")" ]; do
    [ "$SECONDS" -lt "$deadline" ] || return 0
    sleep 0.3
  done
  dexec_root "$svc" cat "$reply" | tr -d '\r'
}

hotswap() {
  local t0 svc t out last jar
  t0=$(now)
  build_jars
  for svc in $BACKENDS; do
    if ! is_running "$svc"; then log "$svc is not running: start the stack with 'make dev-hotswap-up' first"; exit 1; fi
    local changed=()
    mapfile -t changed < <(changed_jars "$svc")
    if [ "${#changed[@]}" = 0 ]; then log "$svc: jars unchanged, nothing to do"; continue; fi
    log "$svc: changed: $(printf '%s ' "${changed[@]##*/}")"
    if [ "$(dexec "$svc" printenv ASTERA_DEV_HOTSWAP 2>/dev/null | tr -d '\r')" != 1 ]; then
      log "$svc: not the hotswap image (ASTERA_DEV_HOTSWAP unset), restarting instead; 'make dev-hotswap-up' once enables hotswap"
      restart_backend "$svc" "${changed[@]}"
      continue
    fi
    t=$(now)
    quiet dexec_root "$svc" sh -c "rm -rf $HOTSWAP_DIR && mkdir -p $HOTSWAP_DIR"
    for jar in "${changed[@]}"; do quiet "${COMPOSE[@]}" cp "$jar" "$svc:$HOTSWAP_DIR/$(basename "$jar")"; done
    out=$(hotswap_request "$svc" "${changed[@]}" || true)
    last=$(tail -n1 <<<"$out")
    sed -n '/^INFO /s/^INFO /  /p' <<<"$out" >&2
    case "$last" in
      OK*)
        # Keep the next-boot copy in step with the running code.
        for jar in "${changed[@]}"; do
          quiet dexec_root "$svc" cp -f "$HOTSWAP_DIR/$(basename "$jar")" "/plugins/$(basename "$jar")"
        done
        log "$svc: hot swapped, no restart (${last#OK }; $(elapsed "$t"))"
        ;;
      *)
        log "$svc: hotswap not possible (${last:-no answer from the agent}); restarting instead"
        restart_backend "$svc" "${changed[@]}"
        ;;
    esac
  done
  log "total: $(elapsed "$t0")"
}

# Copy content/ next to /content, then swap, so files deleted from the repo disappear too.
stage_content() {
  local svc=$1
  quiet dexec_root "$svc" rm -rf /content.new
  quiet "${COMPOSE[@]}" cp content/. "$svc:/content.new"
  quiet dexec_root "$svc" sh -c 'rm -rf /content.old && mv /content /content.old && mv /content.new /content && rm -rf /content.old'
}

refresh_items() {
  local out
  # Items already in inventories keep the name/lore/model they were given with: refresh them in place.
  out=$(rcon "$1" "astera items refresh")
  log "$1: held items: ${out:-<no output>}"
}

content() {
  local t0 svc t out
  t0=$(now)
  for svc in $BACKENDS; do
    if ! is_running "$svc"; then log "$svc is not running: start the stack with 'make docker-up' first"; exit 1; fi
    t=$(now)
    stage_content "$svc"
    log "$svc: content copied ($(elapsed "$t"))"
    t=$(now)
    out=$(rcon "$svc" "astera admin reload all")
    log "$svc: reload: ${out:-<no output>} ($(elapsed "$t"))"
    refresh_items "$svc"
  done
  log "total: $(elapsed "$t0")"
}

# Jars + content in one restart. Content is swapped in while the old JVM runs (it only reads /content on
# boot or on `reload all`, neither happens before the stop), so the one boot sees matching jar and content.
deploy() {
  local t0 svc t out
  t0=$(now)
  build_jars
  for svc in $BACKENDS; do
    if ! is_running "$svc"; then log "$svc is not running: start the stack with 'make docker-up' first"; exit 1; fi
    local changed=()
    mapfile -t changed < <(changed_jars "$svc")
    t=$(now)
    stage_content "$svc"
    log "$svc: content staged ($(elapsed "$t"))"
    if [ "${#changed[@]}" = 0 ]; then
      log "$svc: jars unchanged, reloading content in place (FORCE=1 restarts to rebuild arenas)"
      out=$(rcon "$svc" "astera admin reload all")
      log "$svc: reload: ${out:-<no output>}"
      refresh_items "$svc"
      continue
    fi
    log "$svc: changed: $(printf '%s ' "${changed[@]##*/}")"
    restart_backend "$svc" "${changed[@]}"
    refresh_items "$svc"
  done
  log "total: $(elapsed "$t0")"
}

# Re-read the pack hash (the pack host serves the new astera.zip.sha1) and re-push the pack to everybody.
pack() {
  local t0 svc out
  t0=$(now)
  for svc in $BACKENDS; do
    if ! is_running "$svc"; then log "$svc is not running: start the stack with 'make docker-up' first"; exit 1; fi
    out=$(rcon "$svc" "astera admin pack reload${PACK_FORCE:+ force}")
    log "$svc: ${out:-<no output>}"
  done
  log "total: $(elapsed "$t0")"
}

case "${1:-}" in
  plugins) plugins ;;
  hotswap) hotswap ;;
  deploy) deploy ;;
  content) content ;;
  pack) pack ;;
  *) echo "usage: $0 plugins|hotswap|deploy|content|pack" >&2; exit 2 ;;
esac
