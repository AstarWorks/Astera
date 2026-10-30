#!/usr/bin/env bash
# Decide how a plugin change reaches a running backend: hot swap, hot swap + rewire, or restart (ADR-0310, ADR-0311 P5).
# Redefinition replaces class bodies but never re-runs boot-once code. A plugin with a rewire lifecycle (REWIRABLE)
# can re-run its wiring in place (`astera admin rewire <plugin>`), so wiring / constructor / field changes in its jar
# are applied by hot swap + rewire. Everything else that would "apply" silently with stale wiring restarts, loudly.
#
#   hotswap-classify.sh paths <from> <to>      source-level check of a git range (autodeploy, before the build)
#   hotswap-classify.sh jars <old.jar> <new.jar>   bytecode-level check (dev-deploy.sh, authoritative; the plugin is
#                                              taken from the jar name astera-<plugin>-<version>.jar)
#   hotswap-classify.sh dry-run [N]            `paths` for each of the last N (default 20) commits of origin/main
#   hotswap-classify.sh dry-run --paths <file>...   `paths` for a hand-written list of changed files
#
# `paths` / `jars` print ONE line and exit 0 for "hotswap ..." or "rewire <plugin>[,<plugin>] ..." (hot swap, then
# rewire those plugins), or exit 1 for "restart reason=...".
# Decision table (REWIRABLE = chat items combat match stats economy; core and persistence are not):
#   - plugin.yml, META-INF/services, any other jar resource or db migration            -> restart (all plugins)
#   - build files that change the shaded jar (dependency versions, shading)            -> restart
#   - Astera*Plugin class (onEnable runs once; its fields hold the coordinator)         -> restart (all plugins)
#   - *Wiring* / *Lifecycle* class or file, plugin NOT rewirable (core, persistence)    -> restart
#   - same, plugin rewirable: body or constructor/field change                          -> rewire <plugin>
#       except a Lifecycle whose constructor/fields changed (the coordinator holds the instance) or a Wiring `object`
#       whose fields changed (initialisers do not re-run)                               -> restart
#   - changed constructor, initialiser, static initialiser or field set of another class:
#       plugin not rewirable                                                            -> restart
#       class built inside `ctx.stateful { Name(...) }` (carried across a rewire), or a Kotlin `object`
#                                                                                       -> restart (live instance keeps old shape)
#       any other class (a new wiring generation builds fresh instances)                -> rewire <plugin>
#   - class body changes only                                                           -> hotswap
# Dependents of a rewired plugin (combat <- items, match <- combat, economy <- stats) are rewired by the coordinator.
# Exact bytecode check is `jars`; `paths` flags source lines that look like a constructor / initialiser change only
# with CLASSIFY_INIT_HEURISTIC=1 (set by `dry-run`; autodeploy leaves it off so a source guess never forces a
# rewire the exact check would not).
# Env: ASTERA_REWIRE=0 treats every plugin as not rewirable (old behaviour); ASTERA_REWIRE_ALL=1 rewires every
# rewirable plugin whose jar changed, even for body-only changes.
set -euo pipefail
[ "${1:-}" != jars ] || { set -- jars "$(realpath "${2:?old.jar}")" "$(realpath "${3:?new.jar}")"; }
cd "$(dirname "$0")/.."

REWIRABLE="chat items combat match stats economy"
export REWIRABLE
is_rewirable() { # is_rewirable <plugin>
  [ "${ASTERA_REWIRE:-1}" != 0 ] || return 1
  case " $REWIRABLE " in *" $1 "*) return 0 ;; esac
  return 1
}
plugin_of() { case "$1" in plugin/*/*) p=${1#plugin/}; echo "${p%%/*}" ;; esac; }

# path_verdict <path> -> "restart:<reason>" | "rewire:<plugin>" | nothing
path_verdict() {
  local pl; pl=$(plugin_of "$1")
  case "$1" in
    plugin/*/src/main/resources/*) echo "restart:resource:${1##*/}" ;;
    plugin/*/src/main/kotlin/*/platform/Astera*Plugin.kt) echo "restart:plugin:${1##*/}" ;;
    plugin/*/src/main/kotlin/*Wiring*.kt|plugin/*/src/main/kotlin/*Lifecycle*.kt|plugin/*/src/main/kotlin/*/platform/wiring/*)
      if is_rewirable "$pl"; then echo "rewire:$pl"; else echo "restart:wiring:${1##*/}"; fi ;;
    gradle/libs.versions.toml|buildSrc/*|plugin/*/build.gradle.kts|build.gradle.kts|settings.gradle.kts|gradle.properties) echo "restart:build:${1##*/}" ;;
  esac
}

# Source lines that look like constructor / initialiser changes (heuristic; the jar check is exact).
INIT_LINE='^[+-][[:space:]]*(init[[:space:]]*\{|constructor[[:space:]]*\(|companion[[:space:]]+object|(@[A-Za-z]+[[:space:]]+)?((private|internal|public|protected|data|sealed|abstract|open|enum|value|inner|annotation)[[:space:]]+)*(class|object)[[:space:]]+[A-Za-z0-9_]+.*\()'

# decide <jar_change 0|1> then verdict strings on stdin lines; prints the one-line verdict, returns 1 for restart.
decide() {
  local jar_change=$1 v restarts=() rewires=()
  while IFS= read -r v; do
    case "$v" in
      restart:*) restarts+=("${v#restart:}") ;;
      rewire:*) rewires+=("${v#rewire:}") ;;
    esac
  done
  if [ "$jar_change" = 0 ]; then echo "hotswap (no plugin jar change)"; return 0; fi
  if [ "${#restarts[@]}" -gt 0 ]; then
    echo "restart reason=$(printf '%s\n' "${restarts[@]}" | sort -u | head -n 12 | paste -sd, -)"
    return 1
  fi
  if [ "${#rewires[@]}" -gt 0 ]; then
    echo "rewire $(printf '%s\n' "${rewires[@]}" | sort -u | paste -sd, -)"
    return 0
  fi
  echo "hotswap (class bodies only)"
}

classify_list() { # classify_list <git-from|""> <git-to|""> ; changed paths on stdin
  local from=$1 to=$2 p f pl jar_change=0 verdicts=()
  while IFS= read -r p; do
    case "$p" in plugin/*/src/main/*|gradle/libs.versions.toml|buildSrc/*|*.gradle.kts|gradle.properties) jar_change=1 ;; esac
    r=$(path_verdict "$p"); [ -z "$r" ] || verdicts+=("$r")
  done
  # Constructor / initialiser heuristics on the changed Kotlin sources (git mode only).
  if [ "${CLASSIFY_INIT_HEURISTIC:-0}" = 1 ] && [ -n "$from" ]; then
    while IFS= read -r f; do
      if git diff -U0 "$from" "$to" -- "$f" | grep -Eq "$INIT_LINE"; then
        pl=$(plugin_of "$f")
        if is_rewirable "$pl"; then verdicts+=("rewire:$pl"); else verdicts+=("restart:init:${f##*/}"); fi
      fi
    done < <(git diff --name-only --no-renames "$from" "$to" -- 'plugin/*/src/main/kotlin/*.kt' | grep -Ev 'Wiring|Lifecycle|Astera[A-Za-z]*Plugin\.kt' || true) || true
  fi
  printf '%s\n' "${verdicts[@]:-}" | decide "$jar_change"
}

classify_paths() { # classify_paths <from> <to>
  git diff --name-only --no-renames "$1" "$2" | classify_list "$1" "$2"
}

classify_jars() { # classify_jars <old.jar> <new.jar>
  python3 - "$1" "$2" <<'PYTHON'
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

old_jar, new_jar = sys.argv[1], sys.argv[2]
PLUGIN = re.compile(r"(^|/)Astera[A-Za-z]*Plugin(\$.*)?\.class$")
WIRING = re.compile(r"(^|/)[A-Za-z]*Wiring[A-Za-z]*(\$.*)?\.class$")
LIFECYCLE = re.compile(r"(^|/)[A-Za-z]*Lifecycle(\$.*)?\.class$")
BOOKKEEPING = lambda n: n.endswith("/") or n == "META-INF/MANIFEST.MF" or n.startswith("META-INF/maven/")

m = re.match(r"astera-([a-z]+)-", os.path.basename(new_jar))
plugin = m.group(1) if m else ""
rewirable = plugin in os.environ.get("REWIRABLE", "").split() and os.environ.get("ASTERA_REWIRE", "1") != "0"
rewire_all = os.environ.get("ASTERA_REWIRE_ALL", "0") == "1"

# Classes built inside `ctx.stateful { Name(...) }`: their instances are carried across a rewire, so a shape change
# would leave live instances with the old layout. Read from the sources of the checkout this script lives in.
carried = set()
if rewirable:
    stateful = re.compile(r"stateful\s*(?:<[^{}]*>)?\s*\{\s*(?:[A-Za-z0-9_.]*\.)?([A-Z][A-Za-z0-9_]*)\s*[(<]")
    for src in Path("plugin", plugin).rglob("*.kt"):
        carried.update(stateful.findall(src.read_text(encoding="utf-8", errors="replace")))

restarts, rewires = [], []

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
            restarts.append(f"resource:{name.rsplit('/', 1)[-1]}")
            continue
        short = name.rsplit("/", 1)[-1][:-6]
        if name not in cn:
            continue  # class removed: callers are redefined with it
        is_plugin, is_wiring, is_life = bool(PLUGIN.search(name)), bool(WIRING.search(name)), bool(LIFECYCLE.search(name))
        if (is_plugin or is_wiring) and not rewirable:
            restarts.append(f"wiring:{short}")
            continue
        if is_plugin:
            restarts.append(f"plugin:{short}")
            continue
        if name not in co:
            continue  # new class: loadable, nothing to be stale
        with tempfile.TemporaryDirectory() as old_dir, tempfile.TemporaryDirectory() as new_dir:
            mo, mn = members(javap(zo, name, old_dir)), members(javap(zn, name, new_dir))
        fields_o = {h for h, b in mo.items() if not b}
        fields_n = {h for h, b in mn.items() if not b}
        def init_members(mm):
            ctor = re.compile(r"(^|[ .])" + re.escape(short) + r"\(")
            return {h: b for h, b in mm.items() if b and (h.startswith("static {}") or ctor.search(h))}
        fields_changed = fields_o != fields_n
        init_changed = init_members(mo) != init_members(mn)
        shape = fields_changed or init_changed
        is_object = any(h.endswith(" INSTANCE;") for h in fields_o | fields_n)
        why = "fields" if fields_changed else "init"
        if not rewirable:
            if shape:
                restarts.append(f"{why}:{short}")
            continue
        outer = short.split("$")[0]
        if is_life:
            (restarts if shape else rewires).append(f"lifecycle:{short}")
        elif is_wiring:
            if shape and is_object and fields_changed:
                restarts.append(f"object:{short}")
            else:
                rewires.append(f"wiring:{short}")
        elif shape:
            if outer in carried:
                restarts.append(f"carried:{short}")
            elif is_object:
                restarts.append(f"object:{short}")
            else:
                rewires.append(f"{why}:{short}")
        elif rewire_all:
            rewires.append(f"all:{short}")

def fmt(items):
    uniq = sorted(set(items))
    return ",".join(uniq[:12]) + (f",+{len(uniq) - 12} more" if len(uniq) > 12 else "")

if restarts:
    print("restart reason=" + fmt(restarts))
    sys.exit(1)
if rewires:
    print(f"rewire {plugin} reason=" + fmt(rewires))
    sys.exit(0)
print("hotswap")
PYTHON
}

case "${1:-}" in
  paths) [ "$#" = 3 ] || { echo "usage: $0 paths <from> <to>" >&2; exit 2; }; classify_paths "$2" "$3" ;;
  jars) [ "$#" = 3 ] || { echo "usage: $0 jars <old.jar> <new.jar>" >&2; exit 2; }; classify_jars "$2" "$3" ;;
  dry-run)
    printf '%-9s %-8s %-60s %s\n' sha verdict subject reason
    if [ "${2:-}" = --paths ]; then
      shift 2
      export CLASSIFY_INIT_HEURISTIC=0
      if out=$(printf '%s\n' "$@" | classify_list "" ""); then :; else :; fi
      v=${out%% *}; v=${v%%(*}
      printf '%-9s %-8s %-60s %s\n' paths "${v:-hotswap}" "$(printf '%s ' "$@" | cut -c1-60)" "${out#* }"
      exit 0
    fi
    n=${2:-20}
    export CLASSIFY_INIT_HEURISTIC=1
    while read -r sha; do
      subject=$(git log -1 --format=%s "$sha" | cut -c1-60)
      if ! git rev-parse -q --verify "$sha^1" > /dev/null; then continue; fi
      if out=$(classify_paths "$sha^1" "$sha"); then v=${out%% *}; else v=restart; fi
      v=${v%%(*}
      reason=${out#* }; [ "$out" != "$v" ] || reason=
      printf '%-9s %-8s %-60s %s\n' "$(git rev-parse --short=8 "$sha")" "$v" "$subject" "$reason"
    done < <(git rev-list --first-parent -n "$n" "${DRY_REF:-origin/main}")
    ;;
  *) echo "usage: $0 paths <from> <to> | jars <old.jar> <new.jar> | dry-run [N | --paths <file>...]" >&2; exit 2 ;;
esac
