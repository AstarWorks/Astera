#!/usr/bin/env bash
# Minecraft-free skill-compare previews, rendered + published for the skills a push touched.
#   scripts/skill-preview-auto.sh <from-sha> <to-sha>     skills affected by that range (run from a checkout of <to-sha>)
#   scripts/skill-preview-auto.sh --skills a,b,c [--sha <sha>]
# Per skill that has a skill-compare case: plan dump -> particle-sim -> skill-preview (compare camera) || (fixed side camera) ->
# publish views `preview` and `preview-side` with provenance (git sha, content revision).
# Fast path: Gradle only compiles (once per code change) and prints the two runtime classpaths into build/skill-preview-cp/;
# every skill then runs as bare `java -cp` (no Gradle startup), skills in parallel (PREVIEW_JOBS, default 4), the two cameras in parallel.
# Never fails the caller. preview.log lines (format is a contract for the skill-compare page):
#   preview-started <sha> <skill>
#   preview-published <sha> <skill> (<n>s) [warn: ...] <publish output>
#   preview-FAILED <sha> <skill> <stage>: <reason> (see <log>)
#   preview-skip <sha>: ...
# A lint finding or particle-sim failure never blocks the preview: the plan is the only hard requirement, other findings become
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
  mapfile -t paths < <(git diff --name-only --no-renames "$from" "$to" -- content/skills content/vfx content/animations content/models content/sounds content/particles content/particle-skins content/poses content/items)
  declare -A seen=()
  for p in "${paths[@]}"; do
    case "$p" in
      content/skills/*.yaml) seen[$(basename "$p" .yaml)]=1 ;;
      *) # a shared asset: every skill YAML naming its stem
        stem=$(basename "$p")
        stem=${stem%%.*}
        while IFS= read -r f; do seen[$(basename "$f" .yaml)]=1; done < <(grep -rlw -- "$stem" content/skills 2>/dev/null) ;;
    esac
  done
  skills=("${!seen[@]}")
fi
# only skills a skill-compare case plays
# SKL-08 (ADR-0299): the v1 plan dump (SkillLintPlanDump / :tools:game-sim:skillLintPlan) was deleted with the old engine and the
# v2 trace feed for skill-viewer is not wired yet, so no preview can be produced. Skip loudly instead of failing every push.
log "preview-skip ${sha}: skill-preview pipeline disabled until the v2 trace feed lands (SKL-08)"
exit 0
cases=()
for s in "${skills[@]}"; do
  [ -f "content/skills/$s.yaml" ] || continue
  grep -lq "\"asteraSkillId\": \"$s\"" tools/skill-compare/data/*/case.json 2>/dev/null || continue
  cases+=("$s")
done
if [ "${#cases[@]}" -eq 0 ]; then log "preview-skip $sha: no skill-compare skill affected"; exit 0; fi
for s in "${cases[@]}"; do log "preview-started $sha $s"; done

exec 7>/tmp/claude-1000/astera-skill-preview.lock
flock 7
CP=$REPO/build/skill-preview-cp
mkdir -p "$CP"
# Compile + classpath only when code changed (content-only pushes keep the warm classpath).
code=$(git rev-parse HEAD:plugin HEAD:tools/game-sim HEAD:tools/particle-sim HEAD:buildSrc HEAD:gradle 2>/dev/null | tr -d '\n')
if [ "$(cat "$CP/stamp" 2>/dev/null)" != "$code" ] || [ ! -s "$CP/sim.cp" ] || [ ! -s "$CP/particle.cp" ]; then
  if ./gradlew -q --offline :tools:game-sim:previewClasspath > "$CP/sim.cp.tmp" 2> "$CP/gradle.log" \
     && ./gradlew -q --offline :tools:particle-sim:previewClasspath > "$CP/particle.cp.tmp" 2>> "$CP/gradle.log"; then
    tail -n1 "$CP/sim.cp.tmp" > "$CP/sim.cp"
    tail -n1 "$CP/particle.cp.tmp" > "$CP/particle.cp"
    printf '%s' "$code" > "$CP/stamp"
  else
    for s in "${cases[@]}"; do log "preview-FAILED $sha $s compile: $(grep -E 'error:|^e: |FAILED' "$CP/gradle.log" | head -n 1 | cut -c1-240)"; done
    exit 0
  fi
fi
if ! (cd tools/skill-viewer && bun install --frozen-lockfile >/dev/null 2>&1 && bun run build >/dev/null 2>&1); then
  for s in "${cases[@]}"; do log "preview-FAILED $sha $s skill-viewer build"; done
  exit 0
fi
SIMCP=$(cat "$CP/sim.cp")
PARTCP=$(cat "$CP/particle.cp")
JVM=(-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Djava.awt.headless=true)

reason() { # first meaningful failure line of a log
  grep -E 'ENOENT|Exception|error:|^e: |FAILED|Error' "$1" | grep -v 'WARN\|INFO' | head -n 1 | cut -c1-240
}
one() { # one <skill>
  local s=$1 t0=$SECONDS out=build/skill-preview/$1 lg=$STATE/preview-$1.log warn=""
  : > "$lg"
  rm -f "build/skill-preview/$s/particles.json" "build/skill-lint/$s/plan.json"
  if ! java "${JVM[@]}" -Dastera.sim.contentDir="$REPO/content" -Dastera.sim.traceDir="$REPO/build/sim-traces" -cp "$SIMCP" \
        com.astarworks.astera.gamesim.SkillLintPlanDump "$s" "$REPO/build/skill-lint" >> "$lg" 2>&1; then
    log "preview-FAILED $sha $s plan: $(reason "$lg") (see $lg)"
    return
  fi
  if ! java "${JVM[@]}" -cp "$PARTCP" com.astarworks.astera.particlesim.MainKt "$s" >> "$lg" 2>&1; then
    warn="particle-sim failed ($(reason "$lg")): particles not drawn"
    rm -f "build/skill-preview/$s/particles.json"
  fi
  (cd tools/skill-viewer && bun scripts/skill-preview.mjs --skill "$s" --no-ref ${warn:+--warn "$warn"} > "$lg.a" 2>&1) &
  local pa=$!
  (cd tools/skill-viewer && bun scripts/skill-preview.mjs --skill "$s" --no-ref --camera side --out "$REPO/$out/side" ${warn:+--warn "$warn"} > "$lg.b" 2>&1) &
  local pb=$!
  local ok=1
  wait $pa || ok=0
  wait $pb || ok=0
  cat "$lg.a" "$lg.b" >> "$lg" 2>/dev/null
  rm -f "$lg.a" "$lg.b"
  if [ "$ok" = 1 ] \
     && node tools/skill-compare/scripts/publish-preview.mjs "$s" >> "$lg" 2>&1 \
     && node tools/skill-compare/scripts/publish-preview.mjs "$s" --src "$out/side" --kind preview-side >> "$lg" 2>&1; then
    log "preview-published $sha $s ($((SECONDS - t0))s)${warn:+ [warn: $warn]} $(tail -n1 "$lg")"
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
