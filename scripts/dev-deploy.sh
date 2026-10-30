#!/usr/bin/env bash
# Fast redeploy into the running docker dev stack (F1-I, ADR-0186; hotswap and pack: F3-9, ADR-0224).
#
#   scripts/dev-deploy.sh plugins   build the 8 shadow jars, restart only the backends whose jars changed
#   scripts/dev-deploy.sh hotswap   build the 8 shadow jars and apply the changed classes to the RUNNING
#                                   backends (JBR + agent image, `make dev-hotswap-up`); a backend restarts
#                                   only when the JVM refuses the change
#   scripts/dev-deploy.sh deploy    rebuild+restart the resource pack server (docker-cached), build jars AND push content/ into each backend while it still runs. On the hotswap
#                                   image (ADR-0310, the dev default) changed jars are hot swapped and content reloaded in
#                                   place; a backend restarts ONCE (players moved, not kicked) only when the change needs boot
#                                   code (see scripts/hotswap-classify.sh), when FORCE=1 or ASTERA_HOTSWAP=0, or on a plain image;
#                                   it then boots with matching jar+content and builds every arena in that boot.
#                                   Jars byte-identical and no FORCE: content-only, reloaded in place like `content`.
#   scripts/dev-deploy.sh content   push content/ into the backends and reload it in place (no restart)
#   scripts/dev-deploy.sh pack      re-read the resource pack hash on the backends and re-push the pack
#   scripts/dev-deploy.sh selftest  offline check of the reload-failure detection (no docker)
#
# Every path exits non-zero, with the server log excerpt naming the failing file, when a reload/boot rejects
# content ("Reload aborted"), and ends by printing the deployed sha and the effective content revision.
#
# Env: BACKENDS="lobby game" (which backends to touch, in this order), FORCE=1 (redeploy even when the
# jars are byte-identical), BOOT_TIMEOUT (seconds to wait for a backend to answer RCON, default 240),
# COMPOSE_FILES="docker/docker-compose.yml docker/docker-compose.hotswap.yml" (compose files to use) and
# COMPOSE_PROJECT_NAME (another stack, e.g. an experiment next to the live one), PACK_FORCE=1 (`pack`
# re-pushes even when the hash is unchanged).
#
# ASTERA_DEV_HOST=user@host runs everything against the Docker daemon on that machine (DOCKER_HOST=ssh://...):
# jars are still built here, `docker compose cp` / `docker exec` go over ssh. Unset = the local daemon.
#
# ASTERA_HOTSWAP=0 / ASTERA_RESTART_REASON=<text> (autodeploy): force the restart path for `deploy` and name why.
# ASTERA_DEPLOY_OUTCOME=<file> (autodeploy): one line per backend is appended, `hotswapped <svc>` or
# `restart <svc> reason=...`, so the caller can log what really happened.
#
# Velocity, Postgres and Redis are never touched. The Docker daemon is shared with the host, so bind
# mounts of the repo would be empty: everything goes in with `docker compose cp`.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -n "${ASTERA_DEV_HOST:-}" ]; then
  export DOCKER_HOST="ssh://$ASTERA_DEV_HOST"
  # Always derived from the dev host: a stale external ASTERA_PUBLIC_HOST / ASTERA_RESOURCE_PACK_URL must not win.
  export ASTERA_PUBLIC_HOST="${ASTERA_DEV_HOST##*@}"
  unset ASTERA_RESOURCE_PACK_URL
  export POSTGRES_BIND="${POSTGRES_BIND:-127.0.0.1}" REDIS_BIND="${REDIS_BIND:-127.0.0.1}"
fi

COMPOSE=(docker compose)
COMPOSE_FILES=${COMPOSE_FILES:-docker/docker-compose.yml}
# Remote VM: JAVA_TOOL_OPTIONS + the pack URL pinned to the dev host (docker/docker-compose.remote.yml).
if [ -n "${ASTERA_DEV_HOST:-}" ]; then COMPOSE_FILES="$COMPOSE_FILES docker/docker-compose.remote.yml"; fi
for f in $COMPOSE_FILES; do COMPOSE+=(-f "$f"); done
BACKENDS=${BACKENDS:-"lobby game"}
BOOT_TIMEOUT=${BOOT_TIMEOUT:-240}
PLUGINS=(core chat items combat match stats economy persistence)

# `docker compose exec -T` returns as soon as its stdin hits EOF (cron, CI, `< /dev/null`), cutting the command
# off or dropping its output. Plain `docker exec` without -i attaches no stdin and always waits.
cid() { "${COMPOSE[@]}" ps -q "$1"; }
dexec() { local svc=$1; shift; docker exec "$(cid "$svc")" "$@"; }
dexec_root() { local svc=$1; shift; docker exec -u root "$(cid "$svc")" "$@"; }

log() { printf '[dev-deploy %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
outcome() { [ -z "${ASTERA_DEPLOY_OUTCOME:-}" ] || printf '%s\n' "$*" >> "$ASTERA_DEPLOY_OUTCOME"; }
now() { date +%s.%N; }
elapsed() { awk -v a="$1" -v b="$(now)" 'BEGIN { printf "%.1fs", b - a }'; }

# ---- reload verification ---------------------------------------------------------------------------------
# A rejected reload ("Reload aborted: one or more files failed to parse") keeps the OLD content running, and
# rcon-cli still exits 0. Every path that reloads or boots content checks the reply / server log and fails loudly.
# The server replies in the active locale (en or ja, content/languages/*.yaml): keep both phrasings in sync.
FAIL_PATTERN='Reload aborted|aborted|failed|error|exception|unknown|cannot|could not|not found|中止|中断|失敗|不明|できません|読み込めません|見つかりません'
RELOAD_OK='Reloaded all.|再読み込みしました'
LOG_EXCERPT_PATTERN='Failed to load content from|Reload aborted|Content validation failed|[0-9]+ failed \(dir=|ERROR|Exception'

# reply_ok <ok-substring[|alt...]> <reply>: success only when the reply holds one of the `|`-separated expected
# texts (en/ja alternatives) and no failure marker outside them.
reply_ok() {
  local expected=$1 reply=$2 alt rest found=1
  local -a alts
  [ -n "$reply" ] || return 1
  IFS='|' read -ra alts <<<"$expected"
  rest=$reply
  for alt in "${alts[@]}"; do
    [ -n "$alt" ] || continue
    if [[ "$reply" == *"$alt"* ]]; then found=0; rest=${rest//$alt/}; fi
  done
  [ "$found" = 0 ] || return 1
  if grep -Eiq "$FAIL_PATTERN" <<<"$rest"; then return 1; fi
  return 0
}

# The server log lines naming the failing file(s); reload failures log "Failed to load content from <file>: <cause>".
log_excerpt() {
  local svc=$1 since=${2:-10m}
  docker logs --since "$since" "$(cid "$svc")" 2>&1 | tr -d '\r' | sed 's/\x1b\[[0-9;]*m//g' \
    | grep -E "$LOG_EXCERPT_PATTERN" | tail -n 25 || true
}

die_loud() {
  local svc=$1 what=$2 reply=$3 excerpt=$4
  {
    echo
    echo "################################################################"
    echo "# DEPLOY FAILED on $svc: $what"
    echo "# server reply: ${reply:-<no output>}"
    echo "# the server is STILL RUNNING THE PREVIOUS STATE (stale content)"
    echo "# server log excerpt (failing file/field):"
    if [ -n "$excerpt" ]; then sed 's/^/#   /' <<<"$excerpt"; else echo "#   <none found; run: docker logs <$svc container> | grep -i 'failed to load'>"; fi
    echo "################################################################"
  } >&2
  exit 1
}

# Reload content on $1 and require "Reloaded all." (or the ja reply); otherwise exit non-zero with the log excerpt.
reload_checked() {
  local svc=$1 out
  out=$(rcon "$svc" "astera admin reload all")
  log "$svc: reload: ${out:-<no output>}"
  reply_ok "$RELOAD_OK" "$out" || die_loud "$svc" "content reload was not a success" "$out" "$(log_excerpt "$svc")"
}

# Boot-time content failures (plugins/hotswap/deploy restarts) show only in the log: scan it since $2.
verify_boot_content() {
  local svc=$1 since=$2 bad
  bad=$(docker logs --since "$since" "$(cid "$svc")" 2>&1 | tr -d '\r' | sed 's/\x1b\[[0-9;]*m//g' \
    | grep -E 'Failed to load content from|Reload aborted|Content validation failed|Content load complete: [0-9]+ ok, [1-9][0-9]* failed' | tail -n 25 || true)
  [ -z "$bad" ] || die_loud "$svc" "content failed to load after restart" "<boot log>" "$bad"
}

# Deployed sha + the effective content revision (hash over every file under content/), repo vs backend.
content_rev_local() { (cd content && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -c1-12); }
content_rev_remote() {
  dexec "$1" sh -c 'cd /content && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -c1-12' 2>/dev/null | tr -d '\r'
}
deploy_summary() {
  local svc local_rev remote_rev dirty=""
  [ -z "$(git status --porcelain --untracked-files=no 2>/dev/null)" ] || dirty="+dirty"
  local_rev=$(content_rev_local)
  log "deployed sha: $(git rev-parse --short=12 HEAD)$dirty ($(git log -1 --format=%s | cut -c1-70))"
  for svc in $BACKENDS; do
    remote_rev=$(content_rev_remote "$svc" || true)
    if [ "$remote_rev" = "$local_rev" ]; then
      log "$svc: effective content revision $remote_rev (matches repo)"
    else
      log "WARN: $svc: effective content revision ${remote_rev:-?} differs from repo $local_rev"
    fi
  done
}

# Offline fixtures for the detection above: `scripts/dev-deploy.sh selftest`.
selftest() {
  local n=0 bad=0 r
  t() { n=$((n+1)); if reply_ok "$1" "$2"; then r=ok; else r=fail; fi; [ "$r" = "$3" ] || { echo "selftest #$n FAILED: expected $3 got $r for: $2" >&2; bad=$((bad+1)); }; }
  t "$RELOAD_OK" "Reloaded all." ok
  t "$RELOAD_OK" "all を再読み込みしました。" ok
  t "$RELOAD_OK" "reload を中止しました。一部 YAML がパースに失敗しています。直前の状態は保持されています。" fail
  t "$RELOAD_OK" "カテゴリ 'particles' のリロードが中断されました。" fail
  t "$RELOAD_OK" "Reloaded all. (28 categories)" ok
  t "$RELOAD_OK" "Reload aborted: one or more files failed to parse. Previous state preserved; check server log." fail
  t "$RELOAD_OK" "Reload of category 'particles' aborted; previous state preserved." fail
  t "$RELOAD_OK" "" fail
  t "$RELOAD_OK" "Unknown command" fail
  t "Resource pack 1a2b3c4d" "Resource pack 1a2b3c4d re-pushed to 2 players" ok
  t "Resource pack 1a2b3c4d" "Resource pack hash unchanged" fail
  t "リソースパック 1a2b3c4d" "リソースパック 1a2b3c4d（変更: 2）を 2 人へ再送信しました。" ok
  t "リソースパック 1a2b3c4d" "リソースパックを読み込めません（ASTERA_RESOURCE_PACK_URL 未設定）。" fail
  echo "selftest: $n cases, $bad failed"
  [ "$bad" = 0 ]
}

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
  outcome "restart $svc reason=${RESTART_REASON:-${ASTERA_RESTART_REASON:-jar change (restart path)}}"
  local changed=("$@") t t_svc parked jar other boot_since
  t_svc=$(now)
  boot_since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
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
  verify_boot_content "$svc" "$boot_since"
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
  deploy_summary
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

# True when the backend runs the JBR + agent image (docker/docker-compose.hotswap.yml).
is_hotswap_backend() { [ "$(dexec "$1" printenv ASTERA_DEV_HOTSWAP 2>/dev/null | tr -d '\r')" = 1 ]; }

# Apply the changed jars to a RUNNING hotswap backend, or restart it (loudly, with the reason) when the change needs
# boot code: scripts/hotswap-classify.sh diffs the running jars against the new ones (plugin.yml/resources, wiring
# classes, constructors, initialisers, field sets), and the agent itself refuses what the JVM cannot redefine.
apply_jars() { # apply_jars <svc> <jar>...
  local svc=$1; shift
  local changed=("$@") t out last jar verdict name old_dir reasons=()
  if ! is_hotswap_backend "$svc"; then
    RESTART_REASON="not the hotswap image (ASTERA_DEV_HOTSWAP unset)" restart_backend "$svc" "${changed[@]}"
    return 0
  fi
  old_dir=$(mktemp -d)
  for jar in "${changed[@]}"; do
    name=$(basename "$jar")
    if "${COMPOSE[@]}" cp "$svc:/plugins/$name" "$old_dir/$name" >/dev/null 2>&1; then
      verdict=$(scripts/hotswap-classify.sh jars "$old_dir/$name" "$jar" 2>&1) || reasons+=("$name ${verdict#restart }")
    else
      reasons+=("$name reason=no baseline jar in the container")
    fi
  done
  rm -rf "$old_dir"
  if [ "${#reasons[@]}" -gt 0 ]; then
    log "$svc: RESTART needed, wiring/resource/constructor change: ${reasons[*]}"
    RESTART_REASON="${reasons[*]}" restart_backend "$svc" "${changed[@]}"
    return 0
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
      outcome "hotswapped $svc"
      log "$svc: hot swapped, no restart (${last#OK }; $(elapsed "$t"))"
      ;;
    *)
      log "$svc: hotswap refused (${last:-no answer from the agent}); restarting instead"
      RESTART_REASON="agent refused: ${last:-no answer from the agent}" restart_backend "$svc" "${changed[@]}"
      ;;
  esac
}

hotswap() {
  local t0 svc
  t0=$(now)
  build_jars
  for svc in $BACKENDS; do
    if ! is_running "$svc"; then log "$svc is not running: start the stack with 'make dev-hotswap-up' first"; exit 1; fi
    local changed=()
    mapfile -t changed < <(changed_jars "$svc")
    if [ "${#changed[@]}" = 0 ]; then log "$svc: jars unchanged, nothing to do"; continue; fi
    log "$svc: changed: $(printf '%s ' "${changed[@]##*/}")"
    apply_jars "$svc" "${changed[@]}"
  done
  deploy_summary
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
    reload_checked "$svc"
    log "$svc: reload took $(elapsed "$t")"
    refresh_items "$svc"
  done
  deploy_summary
  log "total: $(elapsed "$t0")"
}

# Rebuild the pack image (layer-cached when content/ and the pack tool are unchanged) and recreate only the
# pack server, so astera.zip + .sha1 are current before the backends boot and read the hash.
rebuild_pack() {
  local t
  t=$(now)
  # ASTERA_PREBUILT_IMAGES=1 (dev-autodeploy): the image was built locally and shipped; never build on the VM.
  local build=--build
  [ "${ASTERA_PREBUILT_IMAGES:-0}" != 1 ] || build=--no-build
  quiet "${COMPOSE[@]}" up -d "$build" --no-deps resource-pack
  log "resource-pack: rebuilt ($(elapsed "$t"))"
}

# Print the pack URL the backends really run with and fail unless it answers 200.
verify_pack_url() {
  local svc url code
  for svc in $BACKENDS; do
    url=$(dexec "$svc" printenv ASTERA_RESOURCE_PACK_URL | tr -d '\r')
    log "$svc: effective pack URL: $url"
    code=$(curl -sfI -o /dev/null -w '%{http_code}' --max-time 20 "$url" || true)
    if [ "$code" != 200 ]; then
      log "ERROR: pack URL $url did not return 200 (got '${code:-no response}'); clients cannot download the pack"
      exit 1
    fi
  done
  log "pack URL OK (200)"
}

# Jars + content in one restart. Content is swapped in while the old JVM runs (it only reads /content on
# boot or on `reload all`, neither happens before the stop), so the one boot sees matching jar and content.
deploy() {
  local t0 svc t out
  t0=$(now)
  build_jars
  rebuild_pack
  for svc in $BACKENDS; do
    if ! is_running "$svc"; then log "$svc is not running: start the stack with 'make docker-up' first"; exit 1; fi
    local changed=()
    mapfile -t changed < <(changed_jars "$svc")
    t=$(now)
    stage_content "$svc"
    log "$svc: content staged ($(elapsed "$t"))"
    if [ "${#changed[@]}" = 0 ]; then
      log "$svc: jars unchanged, reloading content in place (FORCE=1 restarts to rebuild arenas)"
      reload_checked "$svc"
      refresh_items "$svc"
      continue
    fi
    log "$svc: changed: $(printf '%s ' "${changed[@]##*/}")"
    if [ "${FORCE:-0}" != 1 ] && [ "${ASTERA_HOTSWAP:-1}" != 0 ] && is_hotswap_backend "$svc"; then
      # Content is already staged; swap the code, then reload content in place (a restart inside apply_jars
      # boots the staged content itself and the reload is then a no-op confirmation).
      apply_jars "$svc" "${changed[@]}"
      reload_checked "$svc"
    else
      RESTART_REASON="${ASTERA_RESTART_REASON:-FORCE=1, ASTERA_HOTSWAP=0 or plain image}" restart_backend "$svc" "${changed[@]}"
    fi
    refresh_items "$svc"
  done
  pack
  verify_pack_url
  deploy_summary
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
    if [ -z "$out" ] || grep -Eiq "$FAIL_PATTERN" <<<"$out"; then
      die_loud "$svc" "resource pack reload was not a success" "$out" "$(log_excerpt "$svc")"
    fi
  done
  deploy_summary
  log "total: $(elapsed "$t0")"
}

case "${1:-}" in
  plugins) plugins ;;
  hotswap) hotswap ;;
  deploy) deploy ;;
  content) content ;;
  pack) pack ;;
  selftest) selftest ;;
  *) echo "usage: $0 plugins|hotswap|deploy|content|pack|selftest" >&2; exit 2 ;;
esac
