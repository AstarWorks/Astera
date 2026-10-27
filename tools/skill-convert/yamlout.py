"""Tiny deterministic YAML writer for the v2 skill converter.

Data model: dict / list / str / int / float / bool / None. A dict key '#' holds a list of comment lines
that are written above that mapping (inside lists: above the `- ` entry, at the top of a block mapping).
Short mappings and sequences are written in flow style (like content/skills/couture-rapier-s2), long ones as blocks.
"""
import json
import re

FLOW_LIMIT = 200
_SAFE = re.compile(r"^[A-Za-z_][A-Za-z0-9_.\-]*$")
_RESERVED = {"true", "false", "null", "yes", "no", "on", "off", "y", "n", "~"}


def scal(v):
    if v is None:
        return "null"
    if v is True:
        return "true"
    if v is False:
        return "false"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        if v == int(v) and abs(v) < 1e12:
            return repr(float(v))
        return repr(round(v, 6))
    s = str(v)
    if _SAFE.match(s) and s.lower() not in _RESERVED:
        return s
    return json.dumps(s, ensure_ascii=False)


def key(k):
    return scal(k) if isinstance(k, str) else str(k)


def _has_comment(v):
    if isinstance(v, dict):
        return "#" in v or any(_has_comment(x) for x in v.values())
    if isinstance(v, list):
        return any(_has_comment(x) for x in v)
    return False


def flow(v):
    if isinstance(v, dict):
        return "{" + ", ".join(f"{key(k)}: {flow(x)}" for k, x in v.items() if k != "#") + "}"
    if isinstance(v, list):
        return "[" + ", ".join(flow(x) for x in v) + "]"
    return scal(v)


def _flowable(v):
    return not _has_comment(v) and len(flow(v)) <= FLOW_LIMIT


def block(d, ind):
    pad = " " * ind
    out = []
    for c in d.get("#", []):
        out.append(f"{pad}# {c}")
    for k, v in d.items():
        if k == "#":
            continue
        out.extend(_entry(key(k), v, ind))
    return out


def _entry(k, v, ind):
    pad = " " * ind
    if isinstance(v, dict):
        if not v:
            return [f"{pad}{k}: {{}}"]
        if _flowable(v):
            return [f"{pad}{k}: {flow(v)}"]
        return [f"{pad}{k}:"] + block(v, ind + 2)
    if isinstance(v, list):
        if not v:
            return [f"{pad}{k}: []"]
        if all(not isinstance(x, (dict, list)) for x in v) and _flowable(v):
            return [f"{pad}{k}: {flow(v)}"]
        out = [f"{pad}{k}:"]
        for item in v:
            out.extend(_item(item, ind + 2))
        return out
    return [f"{pad}{k}: {scal(v)}"]


def _item(item, ind):
    pad = " " * ind
    if isinstance(item, dict):
        out = [f"{pad}# {c}" for c in item.get("#", [])]
        rest = {k: x for k, x in item.items() if k != "#"}
        if _flowable(rest):
            return out + [f"{pad}- {flow(rest)}"]
        lines = block(rest, ind + 2)
        lines[0] = f"{pad}- " + lines[0][ind + 2:]
        return out + lines
    if isinstance(item, list):
        return [f"{pad}- {flow(item)}"]
    return [f"{pad}- {scal(item)}"]


def dump_doc(d, header_comments=()):
    lines = [f"# {c}" for c in header_comments]
    lines += block(d, 0)
    return "\n".join(lines) + "\n"
