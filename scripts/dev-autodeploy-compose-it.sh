#!/usr/bin/env bash
# CLI integration check: real Compose revisions in Git, no Docker or live deployment state.
set -euo pipefail
repo=$(git rev-parse --show-toplevel)
watcher=$repo/scripts/dev-autodeploy.sh
bash "$watcher" compose-dry b7b8ab0b7~1 d074967da
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/docker"
git show d074967da:docker/docker-compose.yml > "$scratch/docker/docker-compose.yml"
git show d074967da:docker/docker-compose.remote.yml > "$scratch/docker/docker-compose.remote.yml"
cd "$scratch"
git init -q
git config user.name 'Compose integration check'
git config user.email 'compose-it@example.invalid'
snapshot() {
  git add docker
  git -c commit.gpgsign=false commit -qm "$1"
}
snapshot base
base=$(git rev-parse HEAD)
check() {
  local expected=$1 label=$2 result status=0
  snapshot "$label"
  result=$(bash "$watcher" compose-dry "$base" HEAD 2>&1) || status=$?
  printf '%s: %s\n' "$label" "$result"
  if [ "$expected" = AUTO ]; then test "$status" = 0; else test "$status" = 1; fi
  [[ "$result" == "$expected:"* ]]
  git restore --source="$base" -- docker
}
sed -i 's/image: postgres:17-alpine/image: postgres:18-alpine/' docker/docker-compose.yml
check HOLD postgres-image
sed -i 's/image: redis:7-alpine/image: redis:8-alpine/' docker/docker-compose.yml
check HOLD redis-image
sed -i 's@postgres-data:/var/lib/postgresql/data@postgres-data:/changed@' docker/docker-compose.yml
check HOLD postgres-mount
sed -i '/^  postgres-data:$/a\    driver: local' docker/docker-compose.yml
check HOLD postgres-volume-definition
sed -i '/^  redis-data:$/a\    name: renamed-redis' docker/docker-compose.yml
check HOLD redis-volume-name
sed -i '/^  chat-preferences:$/d' docker/docker-compose.yml
check HOLD existing-volume-removal
sed -i 's/chat-preferences/chat-preferences-renamed/g' docker/docker-compose.yml
check HOLD existing-volume-key-rename
sed -i '/^  chat-preferences:$/a\    name: renamed-chat-preferences' docker/docker-compose.yml
check HOLD existing-volume-name-rename
sed -i '/^services:$/a\  postgres:\n    image: postgres:18-alpine' docker/docker-compose.remote.yml
check HOLD overlay-postgres-change
sed -i '/^services:$/a\  extra-service:\n    image: example/extra:latest\n    volumes:\n      - extra-data:/data' docker/docker-compose.yml
printf '  extra-data:\n' >> docker/docker-compose.yml
check AUTO new-service-and-volume
sed -i '/^  chat-preferences:$/a\    driver: local' docker/docker-compose.yml
check AUTO non-db-volume-options
sed -i 's@http://translator:8080@http://translator:8081@' docker/docker-compose.remote.yml
check AUTO overlay-backend-change
printf 'PASS: real translation range and 12 synthetic Compose revisions\n'
