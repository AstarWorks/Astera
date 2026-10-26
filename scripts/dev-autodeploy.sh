#!/usr/bin/env bash
# Host-local origin/main watcher. Runtime copy lives outside the detached checkout.
#
# Commands: up | down | status | run | once | mark-deployed <sha>
#   mark-deployed <sha>  Record a MANUAL deploy. Docker/compose changes are deployed automatically (stack step:
#                        recreate with the remote overlay, then FORCE deploy); only postgres/redis/volume changes log
#                        "manual deploy needed ... entire range held" and never advance last-sha. After deploying
#                        by hand from a checkout of <sha> (FORCE=1 make dev-deploy, plus
#                        the stack step: build locally + docker save|load + up --no-build; never build on the VM), run this so later
#                        commits are diffed from <sha> instead of staying held forever.
#
# ASTERA_DEV_HOST=user@host (given to `up`) deploys to the dev stack on that machine over DOCKER_HOST=ssh://...
# The watcher and its supervisor container stay on THIS host; only the stack commands go remote. Unset = local.
set -euo pipefail
# Default dev VM (non-secret). Export ASTERA_DEV_HOST="" to deploy to the local daemon instead.
export ASTERA_DEV_HOST="${ASTERA_DEV_HOST-astera@192.168.14.3}"
ROOT=/IdeaProjects/Astera
DEPLOY=$ROOT/.claude/worktrees/dev-deploy
STATE=$ROOT/.claude/state/dev-deploy
LOCK=/tmp/claude-1000/astera-dev-deploy.lock
SUPERVISOR=astera-dev-autodeploy
mkdir -p "$STATE" "$(dirname "$LOCK")"
log() { printf '%s %s\n' "$(date -Is)" "$*" | tee -a "$STATE/deploy.log"; }
# One greppable line for a FAILED deploy: the reason (failing content file / aborted reload) from build.log, so a
# manager sees why without opening it. `grep FAILED deploy.log` shows the reasons.
fail_reason() {
  local r
  # Only this deploy's output: build.log is append-only, older failures must not be reported as this one's reason.
  r=$(tail -c +"$((${BUILD_LOG_START:-0} + 1))" "$STATE/build.log" 2>/dev/null | tr -d '\r' | sed 's/\x1b\[[0-9;]*m//g' \
    | grep -E 'RUNTIME-DECODE FAIL|Failed to load content from|Reload aborted|DEPLOY FAILED|FAIL ' | tail -n 3 | tr '\n' '|' | cut -c1-500) || true
  printf '%s\n' "${r:-no reason found; see $STATE/build.log}"
}
fail() { log "FAILED $* reason: $(fail_reason)"; }
# Stack commands only (deploy / rollback); `up` / `down` / `status` keep talking to the local daemon.
use_remote_stack() {
  [ -n "${ASTERA_DEV_HOST:-}" ] || return 0
  export DOCKER_HOST="ssh://$ASTERA_DEV_HOST"
  export ASTERA_PUBLIC_HOST="${ASTERA_PUBLIC_HOST:-${ASTERA_DEV_HOST##*@}}"
  export POSTGRES_BIND="${POSTGRES_BIND:-127.0.0.1}" REDIS_BIND="${REDIS_BIND:-127.0.0.1}"
}
compose() {
  local files=(-f "$DEPLOY/docker/docker-compose.yml")
  # Same overlay as `make dev-deploy` / scripts/dev-deploy.sh (JAVA_TOOL_OPTIONS + pinned pack URL on the remote VM).
  [ -z "${ASTERA_DEV_HOST:-}" ] || files+=(-f "$DEPLOY/docker/docker-compose.remote.yml")
  docker compose -p astera-dev "${files[@]}" "$@"
}

# DRY_RUN=1: print every mutating command instead of running it (see the `stack-dry` command).
run() {
  if [ "${DRY_RUN:-0}" = 1 ]; then printf 'DRY-RUN:'; printf ' %q' "$@"; printf '\n'; else "$@"; fi
}

# Images are NEVER built on the dev VM (8 cores / 16 GB, runs the live stack: an on-VM gradle image build once drove
# it into swap, load 144, sshd timeouts). They are built on THIS host's daemon (DOCKER_HOST unset), streamed to the
# VM with `docker save | docker load`, and the VM only ever runs `up --no-build`.
# Service -> image tag (lobby and game share one image, so building `lobby` covers both).
image_of() {
  case "$1" in
    velocity) echo astera/velocity:dev ;;
    bot-runner) echo astera/bot-runner:dev ;;
    lobby|game) echo astera/minecraft-server:dev ;;
    resource-pack) echo astera/resource-pack:dev ;;
    *) return 1 ;;
  esac
}
build_local() { # build_local <service>...   (local daemon, no remote overlay needed)
  run env -u DOCKER_HOST docker compose -p astera-dev -f "$DEPLOY/docker/docker-compose.yml" build "$@"
}
ship_images() { # ship_images <service>...   (no-op without a remote host: the local daemon IS the stack)
  [ -n "${ASTERA_DEV_HOST:-}" ] || return 0
  local images=() svc
  for svc in "$@"; do images+=("$(image_of "$svc")"); done
  if [ "${DRY_RUN:-0}" = 1 ]; then
    echo "DRY-RUN: env -u DOCKER_HOST docker save ${images[*]} | docker load   # DOCKER_HOST=$DOCKER_HOST"
  else
    env -u DOCKER_HOST docker save "${images[@]}" | docker load
  fi
}
build_and_ship() { build_local "$@" || return 1; ship_images "$@"; }

# Available memory (MiB) on the stack host; empty when it cannot be read (treated as "too low").
vm_avail_mb() {
  if [ "${DRY_RUN:-0}" = 1 ]; then echo "${DRY_AVAIL_MB:-99999}"; return 0; fi
  local cmd="awk '/MemAvailable/ {print int(\$2/1024)}' /proc/meminfo"
  if [ -z "${ASTERA_DEV_HOST:-}" ]; then sh -c "$cmd"
  else ssh -o BatchMode=yes -o ConnectTimeout=10 "$ASTERA_DEV_HOST" "$cmd"; fi
}
MIN_AVAIL_MB=${ASTERA_STACK_MIN_AVAIL_MB:-3000}
mem_ok() { local a; a=$(vm_avail_mb 2>/dev/null) || a=; [[ "$a" =~ ^[0-9]+$ ]] && [ "$a" -ge "$MIN_AVAIL_MB" ]; }

# Wait until a backend answers RCON (it was just recreated).
wait_backend() {
  [ "${DRY_RUN:-0}" != 1 ] || { echo "DRY-RUN: wait for $1 rcon"; return 0; }
  local deadline=$((SECONDS + 300))
  until docker exec "astera-dev-$1-1" rcon-cli list 2>/dev/null | grep -q 'There are'; do
    [ "$SECONDS" -lt "$deadline" ] || return 1
    sleep 3
  done
}

# Stack step: images are built locally + shipped; recreate ONE service at a time with --no-build
# (lobby, then game once lobby answers; velocity and bot-runner last). Services whose image/config are unchanged
# are left alone by compose. postgres/redis are never touched (--no-deps).
stack_step() {
  build_and_ship lobby velocity bot-runner || return 1
  run compose up -d --no-build --no-deps lobby || return 1
  wait_backend lobby || return 1
  run compose up -d --no-build --no-deps game || return 1
  wait_backend game || return 1
  run compose up -d --no-build --no-deps velocity || return 1
  run compose up -d --no-build --no-deps bot-runner || return 1
}

# A failed deploy must never leave the server down: bring back everything that ran before the deploy began.
restore_running() {
  local n s
  for n in ${running_before:-}; do
    [ "$(docker inspect -f '{{.State.Running}}' "$n" 2>/dev/null)" != true ] || continue
    s=${n#astera-dev-}; s=${s%-*}
    if docker inspect "$n" >/dev/null 2>&1; then docker start "$n" || true
    else compose up -d --no-build --no-deps "$s" || true; fi
  done
}

# All builds finish before any running service changes. Backups cover files outside /data,
# plus the next-boot jars in /data/plugins; named data volumes are never replaced.
rollback() {
  local svc jar
  for svc in lobby game; do
    compose start "$svc" || return
    docker exec -u root "astera-dev-$svc-1" rm -rf /content || return
    docker cp "$STATE/rollback/$svc/content" "astera-dev-$svc-1:/content" || return
    if [ "$plugins" = 1 ]; then
      docker exec -u root "astera-dev-$svc-1" sh -c 'rm -f /plugins/astera-*.jar /data/plugins/astera-*.jar' || return
      compose stop -t 60 "$svc" || return
      docker cp "$STATE/rollback/$svc/plugins/." "astera-dev-$svc-1:/plugins" || return
      for jar in "$STATE/rollback/$svc/plugins"/astera-*.jar; do
        docker cp "$jar" "astera-dev-$svc-1:/data/plugins/$(basename "$jar")" || return
      done
    fi
    if [ "$plugins" = 1 ]; then compose start "$svc" || return; fi
  done
  if [ "$pack" = 1 ]; then
    docker tag "$old_pack" astera/resource-pack:dev || return
    compose up -d --no-deps --no-build resource-pack || return
  fi
  # Reload restored files directly: dev-content would copy the failed checkout again.
  local deadline=$((SECONDS + 240))
  for svc in lobby game; do
    until docker exec "astera-dev-$svc-1" rcon-cli list | grep -q 'There are'; do
      [ "$SECONDS" -lt "$deadline" ] || return 1
      sleep 2
    done
    confirm "$svc" "astera admin reload all" "Reloaded all." || return
  done
  make dev-pack || return
}

# RCON can return success even for rejected commands. Confirm the actual server reply.
confirm() {
  local svc=$1 command=$2 expected=$3 out attempt
  for ((attempt=0; attempt<3; attempt++)); do
    # rcon-cli colors its replies (ESC[32mReloaded ESC[33mall...): strip SGR codes and CRs before matching.
    out=$(docker exec "astera-dev-$svc-1" rcon-cli "$command" | sed 's/\x1b\[[0-9;]*m//g; s/\r//g') || return 1
    printf '%s\n' "$out"
    [[ "$out" == *"$expected"* ]] && return 0
    [ -z "$out" ] || return 1
    sleep 1
  done
  return 1
}

deploy() (
  flock 9
  cd "$DEPLOY"
  use_remote_stack
  export COMPOSE_PROJECT_NAME=astera-dev COMPOSE_FILES=docker/docker-compose.yml BACKENDS="lobby game"
  export ASTERA_PREBUILT_IMAGES=1  # dev-deploy.sh must not `--build` the pack image on the VM
  local sha last path step=fetch plugins=0 content=0 pack=0 stack=0 manual=0 old_pack running_before
  git fetch origin main >> "$STATE/build.log" 2>&1 || { log "FAILED - fetch"; return; }
  sha=$(git rev-parse origin/main)
  [ "$sha" != "$(cat "$STATE/attempted-sha" 2>/dev/null || true)" ] || return 0
  last=$(cat "$STATE/last-sha" 2>/dev/null || true)
  printf '%s\n' "$sha" > "$STATE/attempted-sha.tmp"
  mv "$STATE/attempted-sha.tmp" "$STATE/attempted-sha"
  if [ -z "$last" ]; then
    plugins=1; content=1; pack=1
  else
    git diff --name-only --no-renames -z "$last" "$sha" > "$STATE/paths" || { log "FAILED $sha diff"; return; }
    while IFS= read -r -d '' path; do
      case "$path" in
        # E2E / docs / hotswap-only files never reach the dev stack.
        docker/e2e-*|docker/*e2e*|docker/README.md|docker/hotswap/*|docker/docker-compose.hotswap.yml|*Dockerfile.e2e-runner*) ;;
        docker/*|*Dockerfile*|*compose*.yml|*compose*.yaml) stack=1 ;;
        content/models/*|content/textures/*|content/sounds/*|content/sprites/*|content/effects/*|content/hud/*|content/fonts/*|content/pack-assets/*|content/shader-patches/*|content/animations/*) pack=1 ;;
        content/*) content=1 ;;
        tools/resource-pack*) pack=1 ;;
        plugin/core/*) plugins=1; pack=1 ;;
        plugin/*) plugins=1 ;;
        gradle/*|buildSrc/*|config/*|Makefile|*.gradle.kts|gradle.properties|gradlew*) plugins=1; pack=1 ;;
      esac
    done < "$STATE/paths"
  fi
  if [ "$stack" = 1 ]; then
    # Hold only for truly unsafe changes: anything touching the postgres/redis services or any volume definition.
    if git diff -U0 --no-renames "$last" "$sha" -- docker/docker-compose.yml docker/docker-compose.remote.yml \
        | grep -E '^[+-]' | grep -Ev '^(\+\+\+|---)' | grep -Eiq 'postgres|redis|pgdata|^[+-][[:space:]]*volumes:|[a-z]+-data'; then
      manual=1
    else
      # Recreate lobby/game/velocity/bot-runner from the remote-overlay compose, then a FORCE deploy
      # (container-local /plugins and /content are lost on recreate).
      plugins=1; content=1; pack=1
    fi
  fi
  if [ "$manual" = 1 ]; then
    log "manual deploy needed $sha [unsafe docker/compose change (postgres/redis/volumes); entire range held]"
    printf '%s\n' "$sha" > "$STATE/manual-needed"
    return 0
  fi
  # Never pile a recreate onto a starved VM. The sha is re-armed (attempted-sha cleared) so the next poll retries;
  # the skip is logged once per sha.
  if [ "$stack" = 1 ] && ! mem_ok; then
    if [ "$(cat "$STATE/stack-skip-sha" 2>/dev/null || true)" != "$sha" ]; then
      log "SKIPPED $sha stack: VM available memory < ${MIN_AVAIL_MB} MiB (or unreadable); will retry"
      printf '%s\n' "$sha" > "$STATE/stack-skip-sha"
    fi
    rm -f "$STATE/attempted-sha"
    return 0
  fi
  # Builds on this host once exhausted the shared disk and prevented player saves.
  local available_kib
  available_kib=$(df -Pk "$DEPLOY" | awk 'NR == 2 {print $4}')
  if [ "$available_kib" -lt 10485760 ]; then log "FAILED $sha disk-space (need 10 GiB free)"; return; fi
  if ! git checkout --detach "$sha" >> "$STATE/build.log" 2>&1; then log "FAILED $sha checkout"; return; fi
  local steps=() tasks=() svc
  [ "$stack" = 0 ] || steps+=(stack)
  [ "$plugins" = 0 ] || steps+=(plugins)
  [ "$pack" = 0 ] || steps+=(pack)
  [ "$content" = 0 ] || steps+=(content)
  log "deploying $sha [${steps[*]:-none}]"
  BUILD_LOG_START=$(stat -c %s "$STATE/build.log" 2>/dev/null || echo 0)
  old_pack=$(docker inspect astera-dev-resource-pack-1 --format '{{.Image}}')
  running_before=$(docker ps --format '{{.Names}}' --filter name=astera-dev- | tr '\n' ' ')
  if [ "$plugins" = 1 ]; then
    for svc in core items combat match stats economy persistence; do tasks+=(":plugin:$svc:shadowJar"); done
    ./gradlew "${tasks[@]}" >> "$STATE/build.log" 2>&1 || { log "FAILED $sha build-plugins"; return; }
  fi
  if [ "$pack" = 1 ]; then
    build_and_ship resource-pack >> "$STATE/build.log" 2>&1 || { log "FAILED $sha build-pack"; return; }
  fi
  if [ "${#steps[@]}" -gt 0 ]; then
    rm -rf "$STATE/rollback"
    for svc in lobby game; do
      mkdir -p "$STATE/rollback/$svc"
      docker cp "astera-dev-$svc-1:/plugins" "$STATE/rollback/$svc/plugins" >> "$STATE/build.log" 2>&1 || { log "FAILED $sha backup"; return; }
      docker cp "astera-dev-$svc-1:/content" "$STATE/rollback/$svc/content" >> "$STATE/build.log" 2>&1 || { log "FAILED $sha backup"; return; }
    done
  fi
  # Jars first, then pack, then content (new loaders exist before reloading new YAML).
  for step in "${steps[@]}"; do
    printf '%s\n' "$step" > "$STATE/current-step"
    if (
      set -e
      if [ "$step" = stack ]; then
        stack_step || exit 1
      elif [ "$step" = pack ]; then compose up -d --no-deps --no-build resource-pack || exit 1; fi
      if [ "$step" = stack ]; then
        : # containers recreated above; the plugins step force-redeploys jars + content
      elif [ "$step" = plugins ] && [ "$content" = 1 ]; then
        # One restart with new jars AND new YAML staged together (no old-jar/new-content mismatch, arenas built once).
        if [ "$stack" = 1 ]; then FORCE=1 make dev-deploy || exit 1; else make dev-deploy || exit 1; fi
      elif [ "$step" = content ] && [ "$plugins" = 1 ]; then
        : # already pushed by dev-deploy; only the reload confirmation below runs
      else
        make "dev-$step" || exit 1
      fi
      for svc in lobby game; do
        case "$step" in
          stack) ;;
          content) confirm "$svc" "astera admin reload all" "Reloaded all." || exit 1 ;;
          pack)
            hash=$(docker exec astera-dev-resource-pack-1 cat /usr/share/nginx/html/astera.zip.sha1) || exit 1
            hash=${hash:0:8}
            [ "${#hash}" = 8 ] || exit 1
            confirm "$svc" "astera admin pack reload" "$hash" || exit 1
            ;;
        esac
      done
    ) >> "$STATE/build.log" 2>&1; then :; else
      fail "$sha $step"
      { restore_running; rollback; } >> "$STATE/build.log" 2>&1 || log "FAILED $sha rollback (manual recovery needed)"
      restore_running >> "$STATE/build.log" 2>&1 || true
      return 0
    fi
  done
  printf '%s\n' "$sha" > "$STATE/last-sha.tmp"
  mv "$STATE/last-sha.tmp" "$STATE/last-sha"
  rm -f "$STATE/manual-needed" "$STATE/current-step"
  log "deployed $sha [${steps[*]:-none}]"
) 9>"$LOCK"

case "${1:-run}" in
  run)
    # Singleton survives supervisor restarts; never leave a second worker behind.
    exec 8>"$STATE/watcher.lock"
    flock -n 8 || exit 0
    log "watcher started; deployed=$(cat "$STATE/last-sha" 2>/dev/null || echo none) host=${ASTERA_DEV_HOST:-local}"
    while :; do
      bash "$0" once || log "FAILED - watcher (see build.log)"
      sleep 60
    done
    ;;
  once) deploy ;;
  stack-dry)
    # Print (never run) the stack step's commands. DRY_AVAIL_MB=<n> exercises the memory guard.
    export DRY_RUN=1; cd "$DEPLOY"; use_remote_stack
    if mem_ok; then stack_step; else echo "DRY-RUN: stack step would be SKIPPED (available memory below ${MIN_AVAIL_MB} MiB)"; fi
    ;;
  up)
    test -f "$DEPLOY/docker/.env" || { echo "Missing $DEPLOY/docker/.env" >&2; exit 1; }
    # The daemon is outside this devcontainer: repo bind mounts would be empty.
    # Supervise docker exec instead, using the already restartable devcontainer.
    host=$(docker inspect "$(hostname)" --format '{{.Name}}')
    test "$(docker inspect "$host" --format '{{.HostConfig.RestartPolicy.Name}}')" = unless-stopped
    "$0" down
    cp "$0" "$STATE/watcher.sh.tmp"
    mv "$STATE/watcher.sh.tmp" "$STATE/watcher.sh"
    docker image inspect docker:cli >/dev/null 2>&1 || docker pull docker:cli
    docker rm -f "$SUPERVISOR" >/dev/null 2>&1 || true
    docker run -d --name "$SUPERVISOR" --restart unless-stopped \
      -v /var/run/docker.sock:/var/run/docker.sock \
      -e TARGET="$host" -e ASTERA_DEV_HOST="${ASTERA_DEV_HOST:-}" -e RUN_AS="$(id -u)" -e WATCHER="$STATE/watcher.sh" \
      -e WORKER_PATH="$PATH" -e WORKER_JAVA_HOME="${JAVA_HOME:-}" \
      --entrypoint sh docker:cli -c '
        trap "exit 0" TERM INT
        while :; do
          docker exec -u "$RUN_AS" -e ASTERA_DEV_HOST="$ASTERA_DEV_HOST" -e PATH="$WORKER_PATH" -e JAVA_HOME="$WORKER_JAVA_HOME" \
            "$TARGET" bash "$WATCHER" run
          sleep 10 & wait $!
        done'
    ;;
  down)
    # Let a deployment finish before stopping the worker; do not interrupt file copies.
    # shellcheck disable=SC2016
    flock "$LOCK" bash -c '
      docker rm -f astera-dev-autodeploy >/dev/null 2>&1 || true
      for pid in $(pgrep -u "$(id -u)" -f "^bash /IdeaProjects/Astera/.claude/state/dev-deploy/watcher.sh run$"); do
        pkill -P "$pid" || true
        kill "$pid" || true
      done'
    ;;
  status)
    docker inspect "$SUPERVISOR" --format '{{.State.Status}} restart={{.HostConfig.RestartPolicy.Name}}'
    tail -n 10 "$STATE/deploy.log" 2>/dev/null || true
    ;;
  mark-deployed)
    sha_arg=${2:-}
    [ -n "$sha_arg" ] || { echo "usage: $0 mark-deployed <sha>" >&2; exit 2; }
    sha=$(git -C "$DEPLOY" rev-parse --verify "$sha_arg^{commit}") || { echo "unknown commit: $sha_arg" >&2; exit 1; }
    (
      flock 9
      for f in last-sha attempted-sha; do
        printf '%s\n' "$sha" > "$STATE/$f.tmp"
        mv "$STATE/$f.tmp" "$STATE/$f"
      done
      rm -f "$STATE/manual-needed"
      log "deployed $sha [manual]"
    ) 9>"$LOCK"
    ;;
  *) echo "usage: $0 up|down|status|run|once|stack-dry|mark-deployed <sha>" >&2; exit 2 ;;
esac
