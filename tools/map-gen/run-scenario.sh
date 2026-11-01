#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
proof_dir=${1:-/tmp/claude-1000/anni-map}
mkdir -p "$proof_dir"
lib="$repo/tools/arena-preview/build/install/arena-preview/lib"
if [[ ! -d "$lib" ]]; then
  echo 'First run: ./gradlew :tools:arena-preview:installDist (compile/distribution only)' >&2
  exit 1
fi
classpath=$(find "$lib" -name '*.jar' -printf '%p:' | sed 's/:$//')
kotlinc -classpath "$classpath" "$repo/tools/map-gen/HighmarchScenario.kt" -d "$proof_dir/highmarch-scenario.jar"
kotlin -J-Xmx2g -classpath "$proof_dir/highmarch-scenario.jar:$classpath" HighmarchScenarioKt "$repo" "$proof_dir/coordinates.json" "$proof_dir/traversal.md" "${2:-}"
