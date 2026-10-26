#!/usr/bin/env bash
# Minecraft-free skill-compare previews, rendered + published automatically for the skills a push touched.
#   scripts/skill-preview-auto.sh <from-sha> <to-sha>     skills affected by that range (run from a checkout of <to-sha>)
#   scripts/skill-preview-auto.sh --skills a,b,c [--sha <sha>]
# Per skill that has a skill-compare case: skillLintPlan -> particle-sim -> skill-preview (compare camera) + (fixed side camera) ->
# publish views `preview` and `preview-side` with provenance (git sha, content revision). Never fails the caller; logs one line per skill to
# .claude/state/dev-deploy/preview.log ("preview-published <sha> <skill> ...").
# Hooked from scripts/dev-autodeploy.sh right after `deployed <sha>`.
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

skills=()
sha=$(git rev-parse HEAD)
if [ "${1:-}" = --skills ]; then
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
cases=()
for s in "${skills[@]}"; do
  if grep -lq "\"asteraSkillId\": \"$s\"" tools/skill-compare/data/*/case.json 2>/dev/null; then cases+=("$s"); fi
done
if [ "${#cases[@]}" -eq 0 ]; then log "preview-skip $sha: no skill-compare skill affected"; exit 0; fi

exec 7>/tmp/claude-1000/astera-skill-preview.lock
flock 7
if ! (cd tools/skill-viewer && bun install --frozen-lockfile >/dev/null 2>&1 && bun run build >/dev/null 2>&1); then
  log "preview-FAILED $sha skill-viewer build"
  exit 0
fi
for s in "${cases[@]}"; do
  t0=$SECONDS
  out=build/skill-preview/$s
  if {
    ./gradlew :tools:game-sim:skillLintPlan -Pskill="$s" -q --offline \
      && ./gradlew :tools:particle-sim:run --args="$s" -q \
      && (cd tools/skill-viewer \
          && bun scripts/skill-preview.mjs --skill "$s" --no-ref \
          && bun scripts/skill-preview.mjs --skill "$s" --no-ref --camera side --out "$REPO/$out/side") \
      && node tools/skill-compare/scripts/publish-preview.mjs "$s" \
      && node tools/skill-compare/scripts/publish-preview.mjs "$s" --src "$out/side" --kind preview-side
  } > "$STATE/preview-$s.log" 2>&1; then
    log "preview-published $sha $s ($((SECONDS - t0))s) $(tail -n1 "$STATE/preview-$s.log")"
  else
    log "preview-FAILED $sha $s (see $STATE/preview-$s.log)"
  fi
done
exit 0
