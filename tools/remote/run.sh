#!/usr/bin/env bash
# Run a heavy job (vfx-shot, heavy Gradle) on the Astera VM instead of the shared dev host.
#
#   tools/remote/run.sh [--kind vfx|gradle] [--pull <relpath-or-glob>]... [--env <NAME|PREFIX*>]... [--dry-run] [--local-only "msg"] -- <command...>
#   tools/remote/run.sh --status
#
# sync (git push + rsync of the working tree) -> run in the runner container on the VM -> pull the listed outputs back.
# ASTERA_HEAVY_HOST: unset = astera@192.168.14.3, EMPTY = run the command right here (old behaviour). Inside the runner
# (ASTERA_HEAVY_INSIDE=1) the command also just runs. See tools/remote/README.md.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd "$HERE/../.." && pwd -P)"
# shellcheck source=common.sh
. "$HERE/common.sh"

KIND=gradle; PULLS=(); ENVSPEC=(); DRY=0; LOCAL_ONLY=""; STATUS=0
while [ $# -gt 0 ]; do
  case "$1" in
    --kind) KIND="${2:?--kind needs vfx|gradle}"; shift 2 ;;
    --pull) PULLS+=("${2:?--pull needs a path}"); shift 2 ;;
    --env) ENVSPEC+=("${2:?--env needs a name}"); shift 2 ;;
    --dry-run) DRY=1; shift ;;
    --local-only) LOCAL_ONLY="${2:?--local-only needs a message}"; shift 2 ;;
    --status) STATUS=1; shift ;;
    --) shift; break ;;
    *) hdie "unknown option $1 (usage: run.sh [--kind k] [--pull p]... [--env n]... -- command...)" ;;
  esac
done
case "$KIND" in vfx|gradle) ;; *) hdie "--kind must be vfx|gradle" ;; esac

HOST="$(heavy_host)"
BASE="${ASTERA_HEAVY_BASE:-astera-heavy}"

if [ "$STATUS" = 1 ]; then
  [ -n "$HOST" ] || hdie "ASTERA_HEAVY_HOST is empty: there is no remote host"
  # shellcheck disable=SC2046,SC2086
  ssh $(heavy_ssh_opts) "$HOST" "$(heavy_remote_base_snippet); ASTERA_HEAVY_BASE=\"\$HB\" bash \"\$HB/bin/remote-exec.sh\" status" || { heavy_ssh_hint "$HOST"; exit 1; }
  exit 0
fi

[ $# -gt 0 ] || hdie "no command after --"

# ---- local fallback
if [ -z "$HOST" ] || [ "${ASTERA_HEAVY_INSIDE:-0}" = 1 ]; then
  cd "$ROOT" || exit 1
  exec "$@"
fi
if [ -n "$LOCAL_ONLY" ]; then hdie "$LOCAL_ONLY"; fi

# ---- helpers
# x: run, or (--dry-run) print the planned command
x() { if [ "$DRY" = 1 ]; then printf '[heavy] DRY: %s\n' "$(printf '%q ' "$@")" >&2; else "$@"; fi; }

TMP="$(mktemp -d "${TMPDIR:-/tmp}/astera-heavy.XXXXXX")" || exit 1
KEEPALIVE_PID=""
cleanup() { [ -n "$KEEPALIVE_PID" ] && kill "$KEEPALIVE_PID" 2>/dev/null; rm -rf "$TMP"; }
trap cleanup EXIT
trap 'exit 130' INT TERM HUP

cd "$ROOT" || exit 1
SSH_OPTS="$(heavy_ssh_opts)"
export GIT_SSH_COMMAND="ssh $SSH_OPTS"
RSH="ssh $SSH_OPTS"
ID="$(basename "$ROOT" | tr -c 'A-Za-z0-9._\n-' '_')-$(printf '%s' "$ROOT" | sha1sum | cut -c1-8)"
ssh_run() { # remote command string
  # shellcheck disable=SC2086
  ssh $SSH_OPTS "$HOST" "$1"
}

build_file_list() { # -> $TMP/files.list0 (NUL-separated, no directories/submodule gitlinks)
  local f
  : > "$TMP/files.list0"
  while IFS= read -r -d '' f; do
    if [ -d "$f" ] && [ ! -L "$f" ]; then continue; fi
    printf '%s\0' "$f" >> "$TMP/files.list0"
  done < <(git ls-files -z -c -o --exclude-standard)
}

build_env_file() { # -> $TMP/run.env
  local spec n names=() dropped=" SHOT_CACHE SHOT_LOCK SHOT_REGISTRY SHOT_SLOT_DIR "
  : > "$TMP/run.env"
  for spec in "${ENVSPEC[@]+"${ENVSPEC[@]}"}"; do
    case "$spec" in
      *'*') while IFS= read -r n; do names+=("$n"); done < <(compgen -v "${spec%\*}" || true) ;;
      *) names+=("$spec") ;;
    esac
  done
  declare -A seen=()
  for n in "${names[@]+"${names[@]}"}"; do
    [ -n "${seen[$n]:-}" ] && continue; seen[$n]=1
    [[ "$dropped" == *" $n "* ]] && continue
    [ -n "${!n+x}" ] || continue
    if [[ "${!n}" == *$'\n'* ]]; then hlog "WARN: $n contains a newline, not forwarded"; continue; fi
    if [ "$n" = SHOT_KEEP ] && [ "${!n}" = 1 ]; then hlog "WARN: SHOT_KEEP=1 is not supported remotely (the runner is per run); ignored"; continue; fi
    printf '%s=%s\n' "$n" "${!n}" >> "$TMP/run.env"
  done
  {
    echo "ASTERA_HEAVY_INSIDE=1"
    echo "ASTERA_HEAVY_CALLER=$(id -un)@$(hostname):$ROOT"
  } >> "$TMP/run.env"
}

T0=$SECONDS
hlog "host=$HOST id=$ID base=$BASE kind=$KIND"

# ---- sync
build_file_list
build_env_file
if [ "$DRY" = 1 ]; then
  hlog "DRY: $(tr -cd '\0' < "$TMP/files.list0" | wc -c) file(s) in the sync list; first entries: $(tr '\0' ' ' < "$TMP/files.list0" | cut -c1-160)"
  hlog "DRY: env file would contain:"; sed 's/^/[heavy]   /' "$TMP/run.env" >&2
  REPO="<remote-home>/$BASE/w/$ID/repo"
else
  REPO="$(ssh_run "$(heavy_remote_base_snippet); mkdir -p \"\$HB/w/$ID/repo\" && cd \"\$HB/w/$ID/repo\" && { [ -d .git ] || git init -q; } && git config receive.denyCurrentBranch ignore && pwd -P")" \
    || { heavy_ssh_hint "$HOST"; exit 1; }
fi
W="$(dirname "$REPO")"
x git push --quiet --force "$HOST:$REPO" HEAD:refs/heads/heavy-sync || { heavy_ssh_hint "$HOST"; hdie "git push to the VM failed"; }
x rsync -a -e "$RSH" "$TMP/files.list0" "$HOST:$W/files.list0" || hdie "rsync of the file list failed"
x rsync -a --no-recursive --from0 --files-from="$TMP/files.list0" --delete-missing-args -e "$RSH" ./ "$HOST:$REPO/" || hdie "rsync of the working tree failed"
RUN_ENV="$W/run.$$.env"
x rsync -a -e "$RSH" "$TMP/run.env" "$HOST:$RUN_ENV" || hdie "copying the env file failed"
FWD=""
for n in $(compgen -v HEAVY_ || true); do
  case "$n" in HEAVY_DEFAULT_HOST|HEAVY_IMAGE) continue ;; esac
  FWD+="$(printf '%s=%q ' "$n" "${!n}")"
done
RBASE="ASTERA_HEAVY_BASE=$(printf '%q' "$BASE") $FWD"
if [ "$DRY" = 1 ]; then
  hlog "DRY: ssh $HOST $RBASE bash $REPO/tools/remote/remote-exec.sh prepare $ID"
else
  ssh_run "$(heavy_remote_base_snippet); $RBASE bash \"\$HB/w/$ID/repo/tools/remote/remote-exec.sh\" prepare $ID" >&2 \
    || hdie "remote prepare failed (is the runner source in the synced tree? see tools/remote/README.md)"
fi
T_SYNC=$SECONDS
hlog "sync done in $((T_SYNC - T0))s"

# ---- run
CMD_Q="$(printf '%q ' "$@")"
REMOTE_CMD="$RBASE bash $(printf '%q' "$REPO")/tools/remote/remote-exec.sh run $ID $KIND $(printf '%q' "$RUN_ENV") -- $CMD_Q"
RC=0
if [ "$DRY" = 1 ]; then
  hlog "DRY: ssh $HOST $REMOTE_CMD"
else
  if [ -t 0 ]; then
    # shellcheck disable=SC2086
    ssh -t -t $SSH_OPTS "$HOST" "$REMOTE_CMD"; RC=$?
  else
    # stdin must stay open while the job runs: the remote end treats EOF as "the client died" and stops the runner
    # shellcheck disable=SC2086
    exec {KFD}< <(exec sleep 86400); KEEPALIVE_PID=$!
    ssh -T $SSH_OPTS "$HOST" "$REMOTE_CMD" <&"$KFD" & SSH_PID=$!
    trap 'kill "$SSH_PID" 2>/dev/null; exit 130' INT TERM HUP
    wait "$SSH_PID"; RC=$?
  fi
  if [ "$RC" = 255 ]; then hlog "ssh connection lost (rc=255)"; heavy_ssh_hint "$HOST"; fi
fi
T_RUN=$SECONDS
hlog "run finished rc=$RC in $((T_RUN - T_SYNC))s"

# ---- pull (also after a failed run)
for p in "${PULLS[@]+"${PULLS[@]}"}"; do
  x rsync -a -R --exclude='.state/' --exclude='.clients/' --exclude='*.lock' -e "$RSH" "$HOST:$REPO/./$p" ./
  prc=$?
  case "$prc" in 0|23|24) ;; *) hlog "WARN: pull of $p failed (rsync rc=$prc)" ;; esac
done
hlog "pull done in $((SECONDS - T_RUN))s; total $((SECONDS - T0))s"
exit "$RC"
