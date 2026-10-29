#!/usr/bin/env bash
# Run after building docker/Dockerfile. Uses disposable volumes, never the dev stack.
set -euo pipefail
image=${1:?usage: bash docker/chat-preferences-volume-it.sh <newly-built-server-image>}
preferences=$(docker volume create)
data=$(docker volume create)
cleanup() { docker volume rm "$preferences" "$data" >/dev/null; }
trap cleanup EXIT
mounts=(-v "$data:/data" -v "$preferences:/data/plugins/Astera-Chat/preferences")
# Bypass the server entrypoint: fresh-volume ownership must work before any startup chown.
docker run --rm --user 1000:1000 --entrypoint sh "${mounts[@]}" "$image" -ec '
  dir=/data/plugins/Astera-Chat/preferences
  test "$(stat -c %u:%g "$dir")" = 1000:1000
  printf "translate=off\njapanize=off\n" > "$dir/volume-it"
'
# A second backend/recreated container reads and updates the same preferences as uid 1000.
docker run --rm --user 1000:1000 --entrypoint sh "${mounts[@]}" "$image" -ec '
  dir=/data/plugins/Astera-Chat/preferences
  grep -qx translate=off "$dir/volume-it"
  grep -qx japanize=off "$dir/volume-it"
  printf "translate=on\njapanize=on\n" > "$dir/volume-it"
'
echo 'PASS: fresh nested volume is owned by 1000:1000 and preferences persist across containers'
