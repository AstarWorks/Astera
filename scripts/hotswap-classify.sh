#!/usr/bin/env bash
# Decide whether a plugin change can be hot swapped into a running backend or needs a restart (ADR-0310).
# Redefinition replaces class bodies but never re-runs boot-once code, so these changes would "apply" silently
# with stale wiring. This script makes them loud: the caller restarts and logs the reason.
#
#   hotswap-classify.sh paths <from> <to>      source-level check of a git range (autodeploy, before the build)
#   hotswap-classify.sh jars <old.jar> <new.jar>   bytecode-level check (dev-deploy.sh hotswap, authoritative)
#   hotswap-classify.sh dry-run [N]            `paths` for each of the last N (default 20) commits of origin/main
#
# `paths` / `jars` print ONE line and exit 0 for "hotswap ..." or exit 1 for "restart reason=...".
# Restart triggers:
#   - plugin.yml, META-INF/services, any other jar resource or db migration (the agent refuses these anyway)
#   - Astera*Plugin and *Wiring* classes / files (boot-once registration of listeners, commands, services, tasks)
#   - build files that change the shaded jar (dependency versions, shading)
#   - a changed constructor, instance initialiser, static initialiser or field set (existing instances keep
#     the old state; a field added to a live object is null/0). Exact bytecode check (`jars`); `paths` flags
#     source lines that look like one only with CLASSIFY_INIT_HEURISTIC=1 (set by `dry-run`; autodeploy leaves
#     it off so a source guess never forces a restart the exact check would not).
set -euo pipefail
[ "${1:-}" != jars ] || { set -- jars "$(realpath "${2:?old.jar}")" "$(realpath "${3:?new.jar}")"; }
cd "$(dirname "$0")/.."

# Files whose change always needs a restart (the file list of a git range).
path_reason() { # path_reason <path>  -> prints a short reason, or nothing
  case "$1" in
    plugin/*/src/main/resources/*) echo "resource" ;;
    plugin/*/src/main/kotlin/*/platform/Astera*Plugin.kt) echo "wiring" ;;
    plugin/*/src/main/kotlin/*Wiring*.kt|plugin/*/src/main/kotlin/*/platform/wiring/*) echo "wiring" ;;
    gradle/libs.versions.toml|buildSrc/*|plugin/*/build.gradle.kts|build.gradle.kts|settings.gradle.kts|gradle.properties) echo "build" ;;
  esac
}

# Source lines that look like constructor / initialiser changes (heuristic; the jar check is exact).
INIT_LINE='^[+-][[:space:]]*(init[[:space:]]*\{|constructor[[:space:]]*\(|companion[[:space:]]+object|(@[A-Za-z]+[[:space:]]+)?((private|internal|public|protected|data|sealed|abstract|open|enum|value|inner|annotation)[[:space:]]+)*(class|object)[[:space:]]+[A-Za-z0-9_]+.*\()'

classify_paths() { # classify_paths <from> <to>
  local from=$1 to=$2 p r reasons=() jar_change=0 f
  while IFS= read -r p; do
    case "$p" in plugin/*/src/main/*|gradle/libs.versions.toml|buildSrc/*|*.gradle.kts|gradle.properties) jar_change=1 ;; esac
    r=$(path_reason "$p")
    [ -z "$r" ] || reasons+=("$r:${p##*/}")
  done < <(git diff --name-only --no-renames "$from" "$to")
  if [ "$jar_change" = 0 ]; then echo "hotswap (no plugin jar change)"; return 0; fi
  # Constructor / initialiser heuristics on the changed Kotlin sources.
  [ "${CLASSIFY_INIT_HEURISTIC:-0}" = 1 ] && while IFS= read -r f; do
    if git diff -U0 "$from" "$to" -- "$f" | grep -Eq "$INIT_LINE"; then reasons+=("init:${f##*/}"); fi
  done < <(git diff --name-only --no-renames "$from" "$to" -- 'plugin/*/src/main/kotlin/*.kt' | grep -Ev 'Wiring|Astera[A-Za-z]*Plugin\.kt' || true) || true
  if [ "${#reasons[@]}" = 0 ]; then echo "hotswap (class bodies only)"; return 0; fi
  echo "restart reason=$(printf '%s\n' "${reasons[@]}" | sort -u | head -n 12 | paste -sd, -)"
  return 1
}

classify_jars() { # classify_jars <old.jar> <new.jar>
  python3 - "$1" "$2" <<'PYTHON'
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

old_jar, new_jar = sys.argv[1], sys.argv[2]
WIRING = re.compile(r"(^|/)(Astera[A-Za-z]*Plugin|[A-Za-z]*Wiring[A-Za-z]*)(\$.*)?\.class$")
BOOKKEEPING = lambda n: n.endswith("/") or n == "META-INF/MANIFEST.MF" or n.startswith("META-INF/maven/")
reasons = []

def crcs(z):
    return {i.filename: i.CRC for i in z.infolist()}

def javap(z, name, workdir):
    target = Path(workdir) / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(z.read(name))
    out = subprocess.run(["javap", "-p", "-c", "-constants", str(target)], capture_output=True, text=True, check=True).stdout
    return out

def members(text):
    """header line -> normalised body (constant-pool indexes removed). Fields have an empty body."""
    result, current = {}, None
    for line in text.splitlines():
        if re.match(r"^  \S.*;$", line):
            current = line.strip()
            result[current] = []
        elif current is not None and line.startswith("    "):
            result[current].append(re.sub(r"#\d+(, #\d+)?", "#", line.strip()))
        elif not line.strip():
            current = None
    return result

with zipfile.ZipFile(old_jar) as zo, zipfile.ZipFile(new_jar) as zn:
    co, cn = crcs(zo), crcs(zn)
    for name in sorted(set(co) | set(cn)):
        if BOOKKEEPING(name) or co.get(name) == cn.get(name):
            continue
        if not name.endswith(".class"):
            reasons.append(f"resource:{name.rsplit('/', 1)[-1]}")
            continue
        short = name.rsplit("/", 1)[-1][:-6]
        if name not in cn:
            continue  # class removed: callers are redefined with it
        if WIRING.search(name):
            reasons.append(f"wiring:{short}")
            continue
        if name not in co:
            continue  # new class: loadable, nothing to be stale
        with tempfile.TemporaryDirectory() as old_dir, tempfile.TemporaryDirectory() as new_dir:
            mo, mn = members(javap(zo, name, old_dir)), members(javap(zn, name, new_dir))
        fields_o = {h for h, b in mo.items() if not b}
        fields_n = {h for h, b in mn.items() if not b}
        # Field set (instance or static): a field added to a live instance/singleton is null/0 until an init rerun.
        if fields_o != fields_n:
            reasons.append(f"fields:{short}")
            continue
        def init_members(m):
            ctor = re.compile(r"(^|[ .])" + re.escape(short) + r"\(")
            return {h: b for h, b in m.items() if b and (h.startswith("static {}") or ctor.search(h))}
        ctor_o, ctor_n = init_members(mo), init_members(mn)
        if ctor_o != ctor_n:
            reasons.append(f"init:{short}")

if reasons:
    uniq = sorted(set(reasons))
    print("restart reason=" + ",".join(uniq[:12]) + (f",+{len(uniq) - 12} more" if len(uniq) > 12 else ""))
    sys.exit(1)
print("hotswap")
PYTHON
}

case "${1:-}" in
  paths) [ "$#" = 3 ] || { echo "usage: $0 paths <from> <to>" >&2; exit 2; }; classify_paths "$2" "$3" ;;
  jars) [ "$#" = 3 ] || { echo "usage: $0 jars <old.jar> <new.jar>" >&2; exit 2; }; classify_jars "$2" "$3" ;;
  dry-run)
    n=${2:-20}
    export CLASSIFY_INIT_HEURISTIC=1
    printf '%-9s %-8s %-60s %s\n' sha verdict subject reason
    while read -r sha; do
      subject=$(git log -1 --format=%s "$sha" | cut -c1-60)
      if ! git rev-parse -q --verify "$sha^1" > /dev/null; then continue; fi
      if out=$(classify_paths "$sha^1" "$sha"); then v=hotswap; else v=restart; fi
      reason=${out#hotswap}; reason=${reason#restart }
      printf '%-9s %-8s %-60s %s\n' "$(git rev-parse --short=8 "$sha")" "$v" "$subject" "$reason"
    done < <(git rev-list --first-parent -n "$n" "${DRY_REF:-origin/main}")
    ;;
  *) echo "usage: $0 paths <from> <to> | jars <old.jar> <new.jar> | dry-run [N]" >&2; exit 2 ;;
esac
