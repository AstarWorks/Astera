#!/usr/bin/env python3
"""One-time text migration of existing v2 look files to the minimal presentation schema (ADR-0303 section 7):

  track key  rotation: [yaw,pitch,roll]  ->  turn: [rotation vector], densified to one key per tick (rotvec.py)
  mesh       rest: / tilt: [euler]       ->  rest_turn: / tilt_turn: [rotation vector]
  plate      (no size)                   ->  size: <sprite px * 0.025> blocks (the natural glyph, what scale 1.0 drew)
  plate      size_frac: f                ->  reported (needs the parent tip distance; hand-resolved)

  python3 tools/skill-convert/migrate_turn.py content/skills [more roots]
"""
import os
import re
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rotvec import convert_keys, euler_to_vec  # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


def num(x):
    s = ("%.4f" % float(x)).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def vec(v):
    return "[" + ", ".join(num(c) for c in v) + "]"


def key_line(k):
    parts = ["t: %d" % k["t"]]
    for name in ("offset", "turn", "scale"):
        if name in k:
            v = k[name]
            parts.append("%s: %s" % (name, vec(v) if isinstance(v, (list, tuple)) else num(v) if isinstance(v, (int, float)) else v))
    if "ease" in k:
        parts.append("ease: %s" % k["ease"])
    return "{" + ", ".join(parts) + "}"


_px = {}


def sprite_px(sid):
    if sid not in _px:
        p = os.path.join(REPO, "content", "sprites", sid + ".yaml")
        px = 32
        if os.path.exists(p):
            px = int(yaml.safe_load(open(p)).get("size", 32))
        else:
            print("  no sprite file for %s: 32 px" % sid)
        _px[sid] = px
    return _px[sid]


KEY_LINE = re.compile(r"^(\s*)- (\{t: .*\})\s*$")
INLINE_KEYS = re.compile(r"keys: (\[\{t: .*\}\])\}$")


def load_flow(text):
    return yaml.safe_load(text)


def migrate_text(text):
    lines = text.split("\n")
    out = []
    i = 0
    changed = False
    while i < len(lines):
        ln = lines[i]
        m = KEY_LINE.match(ln)
        if m:
            j = i
            keys = []
            while j < len(lines) and KEY_LINE.match(lines[j]):
                keys.append(load_flow(KEY_LINE.match(lines[j]).group(2)))
                j += 1
            if any("rotation" in k for k in keys):
                for k in convert_keys(keys):
                    out.append("%s- %s" % (m.group(1), key_line(k)))
                changed = True
            else:
                out.extend(lines[i:j])
            i = j
            continue
        m = INLINE_KEYS.search(ln)
        if m and "rotation" in m.group(1):
            keys = load_flow(m.group(1))
            new = "[" + ", ".join(key_line(k) for k in convert_keys(keys)) + "]"
            ln = ln[:m.start(1)] + new + "}"
            changed = True
        # mesh rest / tilt (flow form only appears in generated lines)
        for old, new_name in (("rest", "rest_turn"), ("tilt", "tilt_turn")):
            mm = re.search(r"(?<![\w_])%s: (\[[^\]]*\])" % old, ln)
            if mm and "op: mesh" in ln:
                ln = ln[:mm.start()] + "%s: %s" % (new_name, vec(euler_to_vec(load_flow(mm.group(1))))) + ln[mm.end():]
                changed = True
        if re.search(r"\bop: plate\b", ln) and "{" in ln and "size:" not in ln and "size_frac" not in ln:
            sm = re.search(r"sprite: ([\w.-]+)", ln)
            if sm:
                ln = ln[:sm.end()] + ", size: %s" % num(sprite_px(sm.group(1)) * 0.025) + ln[sm.end():]
                changed = True
        if re.match(r"^\s+op: plate\s*$", ln):
            # block-form plate: find its sprite line within the item and add size after it
            ind = re.match(r"^(\s*)", ln).group(1)
            k = i + 1
            while k < len(lines) and lines[k].startswith(ind) and not lines[k].startswith(ind + "-"):
                sm = re.match(r"^(\s*)sprite: ([\w.-]+)\s*$", lines[k])
                if sm:
                    out.append(ln)
                    out.extend(lines[i + 1:k + 1])
                    out.append("%ssize: %s" % (sm.group(1), num(sprite_px(sm.group(2)) * 0.025)))
                    i = k + 1
                    changed = True
                    break
                k += 1
            else:
                out.append(ln)
                i += 1
            continue
        if "size_frac" in ln:
            print("  NEEDS HAND: size_frac: %s" % ln.strip()[:120])
        out.append(ln)
        i += 1
    return "\n".join(out), changed


def main():
    for root in sys.argv[1:]:
        for dp, _, fs in os.walk(root):
            for f in sorted(fs):
                if not f.endswith(".yaml"):
                    continue
                p = os.path.join(dp, f)
                text = open(p).read()
                new, changed = migrate_text(text)
                if changed:
                    open(p, "w").write(new)
                    print("migrated %s" % p)


if __name__ == "__main__":
    main()
