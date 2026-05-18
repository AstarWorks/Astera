#!/usr/bin/env bash
# konsist-watcher: PostToolUse hook (matcher: Edit|Write).
#
# When an Edit/Write touches a Kotlin source under
#   plugin/<module>/src/main/kotlin/**/{domain,application,adapter}/**
# record the dirty (module, layer) pair in .claude/state/konsist-dirty.txt
# and print a one-line reminder to stderr.
#
# Never blocks (always exits 0). Workers can't run gradle (worker禁則),
# so this is warn-only. The orchestrator runs `make check` before commit.
#
# See .claude/rules/wave-execution.md and the SSOT:
#   tools/architecture-test/src/test/kotlin/com/astarworks/astera/architecture/ArchitectureTest.kt

set -euo pipefail

# Tool hook input is JSON on stdin
INPUT=$(cat || true)

# Extract file_path
FILE=""
if command -v jq >/dev/null 2>&1; then
  FILE=$(printf '%s' "$INPUT" | jq -r '.tool_input.file_path // empty' 2>/dev/null || true)
fi
if [ -z "$FILE" ]; then
  FILE=$(printf '%s' "$INPUT" | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)
fi

[ -z "$FILE" ] && exit 0

# Path filter: only Kotlin sources in a plugin's domain|application|adapter layer
case "$FILE" in
  */plugin/*/src/main/kotlin/*/domain/*|plugin/*/src/main/kotlin/*/domain/*) LAYER=domain ;;
  */plugin/*/src/main/kotlin/*/application/*|plugin/*/src/main/kotlin/*/application/*) LAYER=application ;;
  */plugin/*/src/main/kotlin/*/adapter/*|plugin/*/src/main/kotlin/*/adapter/*) LAYER=adapter ;;
  *) exit 0 ;;
esac

# Derive module name (the directory immediately under plugin/)
MODULE=$(printf '%s' "$FILE" | sed -n 's|.*plugin/\([^/]*\)/src/main/kotlin/.*|\1|p')
[ -z "$MODULE" ] && exit 0

STATE_DIR=".claude/state"
STATE_FILE="$STATE_DIR/konsist-dirty.txt"
mkdir -p "$STATE_DIR"

ENTRY="plugin/${MODULE}/${LAYER}"

# Dedup
if [ ! -f "$STATE_FILE" ] || ! grep -qxF "$ENTRY" "$STATE_FILE"; then
  printf '%s\n' "$ENTRY" >> "$STATE_FILE"
  printf '[konsist-watcher] dirty: %s — orchestrator should run `make check` before commit\n' "$ENTRY" >&2
fi

exit 0
