#!/usr/bin/env bash
# Host-local origin/main watcher. Runtime copy lives outside the detached checkout.
#
# Commands: up | down | status | run | once | mark-deployed <sha>
#   mark-deployed <sha>  Record a MANUAL deploy. When a range holds docker/compose changes the watcher logs
#                        "manual deploy needed ... entire range held" and never advances last-sha. After deploying
#                        by hand from a checkout of <sha> (FORCE=1 make dev-deploy, plus
#                        docker compose up -d --build --no-deps bot-runner resource-pack), run this so later
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
# Stack commands only (deploy / rollback); `up` / `down` / `status` keep talking to the local daemon.
use_remote_stack() {
  [ -n "${ASTERA_DEV_HOST:-}" ] || return 0
  export DOCKER_HOST="ssh://$ASTERA_DEV_HOST"
  export ASTERA_PUBLIC_HOST="${ASTERA_PUBLIC_HOST:-${ASTERA_DEV_HOST##*@}}"
  export POSTGRES_BIND="${POSTGRES_BIND:-127.0.0.1}" REDIS_BIND="${REDIS_BIND:-127.0.0.1}"
}
compose() { docker compose -p astera-dev -f "$DEPLOY/docker/docker-compose.yml" "$@"; }

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
  local sha last path step=fetch plugins=0 content=0 pack=0 manual=0 old_pack
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
        docker/*|*Dockerfile*|*compose*.yml|*compose*.yaml) manual=1 ;;
        content/models/*|content/textures/*|content/sounds/*|content/sprites/*|content/effects/*|content/hud/*|content/fonts/*|content/pack-assets/*|content/shader-patches/*|content/animations/*) pack=1 ;;
        content/*) content=1 ;;
        tools/resource-pack*) pack=1 ;;
        plugin/core/*) plugins=1; pack=1 ;;
        plugin/*) plugins=1 ;;
        gradle/*|buildSrc/*|config/*|Makefile|*.gradle.kts|gradle.properties|gradlew*) plugins=1; pack=1 ;;
      esac
    done < "$STATE/paths"
  fi
  if [ "$manual" = 1 ]; then
    log "manual deploy needed $sha [docker/compose changes; entire range held]"
    printf '%s\n' "$sha" > "$STATE/manual-needed"
    return 0
  fi
  # Builds on this host once exhausted the shared disk and prevented player saves.
  local available_kib
  available_kib=$(df -Pk "$DEPLOY" | awk 'NR == 2 {print $4}')
  if [ "$available_kib" -lt 10485760 ]; then log "FAILED $sha disk-space (need 10 GiB free)"; return; fi
  if ! git checkout --detach "$sha" >> "$STATE/build.log" 2>&1; then log "FAILED $sha checkout"; return; fi
  local steps=() tasks=() svc
  [ "$plugins" = 0 ] || steps+=(plugins)
  [ "$pack" = 0 ] || steps+=(pack)
  [ "$content" = 0 ] || steps+=(content)
  log "deploying $sha [${steps[*]:-none}]"
  old_pack=$(docker inspect astera-dev-resource-pack-1 --format '{{.Image}}')
  if [ "$plugins" = 1 ]; then
    for svc in core items combat match stats economy persistence; do tasks+=(":plugin:$svc:shadowJar"); done
    ./gradlew "${tasks[@]}" >> "$STATE/build.log" 2>&1 || { log "FAILED $sha build-plugins"; return; }
  fi
  if [ "$pack" = 1 ]; then
    compose build resource-pack >> "$STATE/build.log" 2>&1 || { log "FAILED $sha build-pack"; return; }
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
      if [ "$step" = pack ]; then compose up -d --no-deps --no-build resource-pack || exit 1; fi
      if [ "$step" = plugins ] && [ "$content" = 1 ]; then
        # One restart with new jars AND new YAML staged together (no old-jar/new-content mismatch, arenas built once).
        make dev-deploy || exit 1
      elif [ "$step" = content ] && [ "$plugins" = 1 ]; then
        : # already pushed by dev-deploy; only the reload confirmation below runs
      else
        make "dev-$step" || exit 1
      fi
      for svc in lobby game; do
        case "$step" in
          content) confirm "$svc" "astera admin reload all" "Reloaded all." || exit 1 ;;
          pack)
            hash=$(docker exec astera-dev-resource-pack-1 cat /usr/share/nginx/html/astera.zip.sha1) || exit 1
            hash=${hash:0:8}
            [ "${#hash}" = 8 ] || exit 1
            confirm "$svc" "astera admin pack reload" "Resource pack $hash" || exit 1
            ;;
        esac
      done
    ) >> "$STATE/build.log" 2>&1; then :; else
      log "FAILED $sha $step"
      rollback >> "$STATE/build.log" 2>&1 || log "FAILED $sha rollback (manual recovery needed)"
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
    while :; do
      bash "$0" once || log "FAILED - watcher (see build.log)"
      sleep 60
    done
    ;;
  once) deploy ;;
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
  *) echo "usage: $0 up|down|status|run|once|mark-deployed <sha>" >&2; exit 2 ;;
esac
