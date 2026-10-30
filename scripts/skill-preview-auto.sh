#!/usr/bin/env bash
# Minecraft-free skill-compare previews, rendered + published for the skills a push touched.
#   scripts/skill-preview-auto.sh <from-sha> <to-sha>     skills affected by that range (run from a checkout of <to-sha>)
#   scripts/skill-preview-auto.sh --skills a,b,c [--sha <sha>]
# Per skill that has a skill-compare case: ONE game-sim run casts every skill (v2 sim trace, astera-sim-trace/2, SKL-08 / ADR-0301)
# -> trace-preview (compare camera + hit-volume overlay) || (fixed side camera) -> publish views `preview`, `preview-side` and the
# overlay as `lint`, with provenance (git sha, content revision). Renders run in parallel (PREVIEW_JOBS, default 4).
# Never fails the caller. preview.log lines (format is a contract for the skill-compare page):
#   preview-started <sha> <skill>
#   preview-published <sha> <skill> (<n>s) [warn: ...] <publish output>
#   preview-FAILED <sha> <skill> <stage>: <reason> (see <log>)
#   preview-skip <sha>: ...
# Cast diagnostics (unknown points, ...) never block the preview: the trace is the only hard requirement, other findings become
# `warnings` in the published provenance. Triggered by scripts/skill-preview-watch.sh (on origin/main moving).
set -uo pipefail
cd "$(dirname "$0")/.."
REPO=$PWD
PRIMARY=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")
STATE=$PRIMARY/.claude/state/dev-deploy
LOG=$STATE/preview.log
mkdir -p "$STATE" /tmp/claude-1000
log() { printf '%s %s\n' "$(date -Is)" "$*" >> "$LOG"; }
# bun's shared cache is not writable for every user on this host; a private one always works.
export BUN_INSTALL_CACHE_DIR=${BUN_INSTALL_CACHE_DIR:-/tmp/claude-1000/bun-cache}
JOBS=${PREVIEW_JOBS:-4}

skills=()
sha=$(git rev-parse HEAD)
if [ "${1:-}" = --all ]; then   # backfill: every skill a skill-compare case plays
  mapfile -t skills < <(grep -ho '"asteraSkillId": "[^"]*"' tools/skill-compare/data/*/case.json | sed 's/.*: "//;s/"//' | sort -u)
elif [ "${1:-}" = --skills ]; then
  IFS=, read -r -a skills <<<"${2:-}"
  if [ "${3:-}" = --sha ]; then sha=${4:-$sha}; fi
else
  from=${1:?usage: $0 <from-sha> <to-sha> or --skills a,b}
  to=${2:-HEAD}
  sha=$(git rev-parse "$to")
  mapfile -t paths < <(git diff --name-only --no-renames "$from" "$to" -- content/skills content/frames content/vfx content/animations content/models content/sounds content/particles content/particle-skins content/poses content/items \
    kernel/domain/src/main/kotlin/com/astarworks/astera/core/domain/skillengine kernel/runtime/src/main/kotlin/com/astarworks/astera/core/application/skillengine \
    plugin/combat/src/main/kotlin/com/astarworks/astera/combat/adapter/paper/skillengine tools/game-sim/src/test/kotlin/com/astarworks/astera/gamesim/SkillTraceExportScenarioTest.kt tools/skill-viewer)
  declare -A seen=()
  all=0
  for p in "${paths[@]}"; do
    case "$p" in
      kernel/*|plugin/*|tools/*) all=1 ;;                               # engine / sim / viewer code: every skill-compare skill
      content/skills/*/*) rest=${p#content/skills/}; seen[${rest%%/*}]=1 ;;   # v2 skill dir
      content/skills/*.yaml) seen[$(basename "$p" .yaml)]=1 ;;
      *) # a shared asset (frame, vfx, model, ...): every skill (dir or file) naming its stem
        stem=$(basename "$p")
        stem=${stem%%.*}
        while IFS= read -r f; do
          rest=${f#content/skills/}
          case "$rest" in */*) seen[${rest%%/*}]=1 ;; *) seen[$(basename "$f" .yaml)]=1 ;; esac
        done < <(grep -rlw -- "$stem" content/skills 2>/dev/null) ;;
    esac
  done
  if [ "$all" = 1 ]; then
    mapfile -t skills < <(grep -ho '"asteraSkillId": "[^"]*"' tools/skill-compare/data/*/case.json | sed 's/.*: "//;s/"//' | sort -u)
  else
    skills=("${!seen[@]}")
  fi
fi
# only skills a skill-compare case plays
cases=()
for s in "${skills[@]}"; do
  [ -f "content/skills/$s.yaml" ] || [ -f "content/skills/$s/skill.yaml" ] || continue
  grep -lq "\"asteraSkillId\": \"$s\"" tools/skill-compare/data/*/case.json 2>/dev/null || continue
  cases+=("$s")
done
if [ "${#cases[@]}" -eq 0 ]; then log "preview-skip $sha: no skill-compare skill affected"; exit 0; fi
for s in "${cases[@]}"; do log "preview-started $sha $s"; done

exec 7>/tmp/claude-1000/astera-skill-preview.lock
flock 7
# One Gradle run casts all skills (the game-sim scenario compiles incrementally; content-only pushes recompile nothing).
if ! ./gradlew -q --offline :tools:game-sim:test --tests '*SkillTraceExportScenarioTest' -Pskill="$(IFS=,; echo "${cases[*]}")" \
     > "$STATE/preview-trace.log" 2>&1; then
  for s in "${cases[@]}"; do log "preview-FAILED $sha $s trace: $(grep -E 'error:|^e: |FAILED|Exception' "$STATE/preview-trace.log" | head -n 1 | cut -c1-240) (see $STATE/preview-trace.log)"; done
  exit 0
fi
if ! (cd tools/skill-viewer && bun install --frozen-lockfile >/dev/null 2>&1 && bun run build >/dev/null 2>&1); then
  for s in "${cases[@]}"; do log "preview-FAILED $sha $s skill-viewer build"; done
  exit 0
fi

reason() { # first meaningful failure line of a log
  grep -E 'ENOENT|Exception|error:|^e: |FAILED|Error|missing' "$1" | grep -v 'WARN\|INFO' | head -n 1 | cut -c1-240
}
one() { # one <skill>
  local s=$1 t0=$SECONDS out=build/skill-preview/$1 lg=$STATE/preview-$1.log
  : > "$lg"
  if [ ! -s "tools/game-sim/build/sim-traces/v2/$s.json" ]; then
    log "preview-FAILED $sha $s trace: no tools/game-sim/build/sim-traces/v2/$s.json (is it a v2 skill?)"
    return
  fi
  (cd tools/skill-viewer && bun scripts/trace-preview.mjs --skill "$s" --no-ref --overlay > "$lg.a" 2>&1) &
  local pa=$!
  (cd tools/skill-viewer && bun scripts/trace-preview.mjs --skill "$s" --no-ref --camera side --out "$REPO/$out/side" > "$lg.b" 2>&1) &
  local pb=$!
  local ok=1
  wait $pa || ok=0
  wait $pb || ok=0
  cat "$lg.a" "$lg.b" >> "$lg" 2>/dev/null
  rm -f "$lg.a" "$lg.b"
  if [ "$ok" = 1 ] \
     && node tools/skill-compare/scripts/publish-preview.mjs "$s" >> "$lg" 2>&1 \
     && node tools/skill-compare/scripts/publish-preview.mjs "$s" --src "$out/side" --kind preview-side >> "$lg" 2>&1 \
     && node tools/skill-compare/scripts/publish-preview.mjs "$s" --kind lint >> "$lg" 2>&1; then
    log "preview-published $sha $s ($((SECONDS - t0))s) $(tail -n1 "$lg")"
  else
    log "preview-FAILED $sha $s render/publish: $(reason "$lg") (see $lg)"
  fi
}
running=0
for s in "${cases[@]}"; do
  one "$s" &
  running=$((running + 1))
  if [ "$running" -ge "$JOBS" ]; then
    wait -n
    running=$((running - 1))
  fi
done
wait
exit 0
