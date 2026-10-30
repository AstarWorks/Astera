#!/usr/bin/env bash
# One-time (idempotent) setup of the Astera VM as the runner host for heavy jobs. Run from the dev host:
#   tools/remote/setup.sh [user@host]        (default: $ASTERA_HEAVY_HOST or astera@192.168.14.3)  ==  make heavy-setup
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=common.sh
. "$HERE/common.sh"

HOST="${1:-$(heavy_host)}"
[ -n "$HOST" ] || hdie "no host (ASTERA_HEAVY_HOST is empty); pass user@host"
SSH_OPTS="$(heavy_ssh_opts)"
rsh() { # shellcheck disable=SC2086
  ssh $SSH_OPTS "$HOST" "$@"; }
BASE_SNIPPET="$(heavy_remote_base_snippet)"

hlog "setting up $HOST (base ${ASTERA_HEAVY_BASE:-astera-heavy})"
rsh true || { heavy_ssh_hint "$HOST"; exit 1; }
if ! rsh docker info >/dev/null 2>&1; then
  hlog "docker is not usable for $HOST's user. On the VM run once:  sudo usermod -aG docker ${HOST%@*}   (then log in again) and re-run this script."
  exit 1
fi
rsh 'command -v rsync >/dev/null && command -v git >/dev/null && command -v python3 >/dev/null && command -v flock >/dev/null' \
  || hdie "the VM needs rsync, git, python3 and flock (util-linux)"

rsh "$BASE_SNIPPET; mkdir -p \"\$HB/w\" \"\$HB/home\" \"\$HB/locks/claude-1000\" \"\$HB/bin\" \"\$HB/runner-src\"
  [ -f \"\$HB/heavy.env\" ] || printf '%s\n' '# host defaults for tools/remote (the caller environment wins)' 'HEAVY_SLOTS=1' > \"\$HB/heavy.env\"" \
  || hdie "creating the directories failed"

RSH="ssh $SSH_OPTS"
SRC="$(rsh "$BASE_SNIPPET; echo \"\$HB\"")" || hdie "cannot resolve the remote base"
rsync -a --delete -e "$RSH" "$HERE/" "$HOST:$SRC/runner-src/" || hdie "rsync of tools/remote failed"
rsync -a -e "$RSH" "$HERE/remote-exec.sh" "$HOST:$SRC/bin/remote-exec.sh" || hdie "copying remote-exec.sh failed"

hlog "building $HEAVY_IMAGE on the VM (first build downloads ~1 GB of layers)"
rsh "cd '$SRC/runner-src' && docker build -f Dockerfile.heavy-runner --build-arg HEAVY_UID=\$(id -u) --build-arg HEAVY_GID=\$(id -g) -t '$HEAVY_IMAGE' ." \
  || hdie "docker build failed"

hlog "pre-pulling the stock images vfx-shot uses"
for img in itzg/minecraft-server:java25 nginx:1.27-alpine redis:7-alpine; do
  rsh "docker pull -q '$img' >/dev/null" || hlog "WARN: pulling $img failed (vfx-shot will pull it on first use)"
done

hlog "done. images:"
rsh "docker images --format '  {{.Repository}}:{{.Tag}}  {{.Size}}' | grep -E 'heavy-runner|itzg/minecraft-server|nginx|redis'; grep MemAvailable /proc/meminfo"
