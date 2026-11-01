#!/usr/bin/env bash
# Proof for a shared-part migration: every skill-oracle golden that changed since <base-ref> must be identical once the op path labels
# (`op=<path>`) and the particle `seed=` values are stripped. Everything else (tick, verb, instance, position, velocity, count, data,
# ids, the g-/r- lines, row order) has to match. A non-empty normalized diff is a changed emission, not a relabel.
#
#   tools/game-sim/scripts/oracle-normalized-diff.sh [<base-ref>]        (default HEAD: the uncommitted regen against the last commit)
#
# Prints each changed golden with its raw and normalized diff line counts, the skills whose seeds changed, and exits 1 on any
# normalized difference (the first differing lines are printed).
set -euo pipefail

base="${1:-HEAD}"
dir="tools/game-sim/src/test/resources/skill-oracle"
cd "$(git rev-parse --show-toplevel)"

norm() { sed -E 's/ op=[^ ]*/ op=~/g; s/ seed=-?[0-9]+/ seed=~/g'; }

changed=0
bad=0
seeds=()
while IFS= read -r f; do
  [ -z "$f" ] && continue
  changed=$((changed + 1))
  skill="$(basename "$(dirname "$f")")"
  old="$(mktemp)"; new="$(mktemp)"
  git show "$base:$f" | norm > "$old"
  norm < "$f" > "$new"
  raw=$(diff <(git show "$base:$f") "$f" | grep -c '^[<>]' || true)
  nrm=$(diff "$old" "$new" | grep -c '^[<>]' || true)
  if ! diff -q <(git show "$base:$f" | sed -E 's/ op=[^ ]*//g') <(sed -E 's/ op=[^ ]*//g' "$f") > /dev/null; then seeds+=("$skill"); fi
  printf '%-34s raw diff lines %-5s normalized diff lines %s\n' "$skill" "$raw" "$nrm"
  if [ "$nrm" != "0" ]; then
    bad=$((bad + 1))
    diff "$old" "$new" | head -8
  fi
  rm -f "$old" "$new"
done < <(git diff --name-only "$base" -- "$dir"; git ls-files --others --exclude-standard -- "$dir")

echo "changed goldens: $changed, with a non-empty normalized diff: $bad"
if [ "${#seeds[@]}" -gt 0 ]; then echo "skills whose particle seeds changed: ${seeds[*]}"; fi
[ "$bad" = "0" ]
