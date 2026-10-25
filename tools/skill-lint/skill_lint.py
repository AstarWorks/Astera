#!/usr/bin/env python3
"""skill-lint: machine-checkable facts about a VFX skill, run BEFORE every capture.

    python3 tools/skill-lint/skill_lint.py <skill-id> [--plan plan.json] [--targets file] [--capture <dir>] [--stamp-regen]
    make skill-lint SKILL=bastion-greatsword-s1 [CAPTURE=<dir>]

Checks (each prints PASS/FAIL with the tick and the number; exit 1 on any FAIL):
  1 reference beat match   plan dump vs content/skill-lint/<skill>.targets.yaml (blade direction, roll, body)
  1b overlay               plan dump projected onto the annotated reference frames (content/skill-lint/<skill>.keypoints.yaml): pixel error per beat + PNG
  2 one weapon + grip      visibleWeapons == 1 every tick; grip on the right-hand bone
  3 fx follow the blade    particle / display emission vs the blade path; nothing fires in hold / coil beats
  4 budget + visual traps  simultaneous Displays, particles per tick, haze sprites
  5 sound per beat         every beat that needs sound has a play_sound, and the .ogg exists
  6 regen consistency      generated outputs match the inputs they were generated from (stamp file)
  7 capture                optional: meta.json validity, contentRevision vs the current tree

Plan dump (written by the runtime, build/skill-lint/<skill>/plan.json): see docs/contributing/vfx-team/skill-lint.md.
"""
import argparse
import glob
import hashlib
import json
import math
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/item-art/scripts"))

RESULTS = []  # (check, status, text)


def emit(check, ok, text):
    RESULTS.append((check, ok, text))
    print("[%s] %s  %s" % ("PASS" if ok else "FAIL", check, text))


# ---- vector helpers ------------------------------------------------------------------------------------------------
def sub(a, b):
    return tuple(x - y for x, y in zip(a, b))


def norm(v):
    n = math.sqrt(sum(x * x for x in v))
    return tuple(x / n for x in v) if n > 1e-9 else (0.0, 0.0, 0.0)


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def qrot(q, v):
    """q = [qx, qy, qz, qw] rotating a local vector into caster-local space."""
    x, y, z, w = q
    tx, ty, tz = 2 * (y * v[2] - z * v[1]), 2 * (z * v[0] - x * v[2]), 2 * (x * v[1] - y * v[0])
    return (v[0] + w * tx + (y * tz - z * ty), v[1] + w * ty + (z * tx - x * tz), v[2] + w * tz + (x * ty - y * tx))


def angdiff(a, b):
    return abs((a - b + 180.0) % 360.0 - 180.0)


def seg_dist(p, a, b):
    ab = sub(b, a)
    L = dot(ab, ab)
    t = 0.0 if L < 1e-12 else max(0.0, min(1.0, dot(sub(p, a), ab) / L))
    return math.dist(p, tuple(a[i] + ab[i] * t for i in range(3)))


def lit(v, default=0):
    if v is None:
        return default
    return v.get("value", default) if isinstance(v, dict) else v


def load_yaml(rel):
    return yaml.safe_load((ROOT / rel).read_text())


def ticks_str(r):
    return "t%d-%d" % (r[0], r[1]) if r[0] != r[1] else "t%d" % r[0]


# ---- skill structure -----------------------------------------------------------------------------------------------
class Skill:
    def __init__(self, skill_id):
        self.id = skill_id
        self.doc = load_yaml("content/skills/%s.yaml" % skill_id)
        self.steps = self.doc["phases"]["cast"]
        self.vfx = {}      # vfx_id -> (delay, doc)
        self.rig_vfx = None
        for s in self.steps:
            if s.get("type") == "play_vfx":
                vid = s["vfx_id"]
                d = load_yaml("content/vfx/%s.yaml" % vid)
                self.vfx[vid] = (int(lit(s.get("delay_ticks"))), d)
                if d.get("rigs") and self.rig_vfx is None:
                    self.rig_vfx = vid
        self.sounds = [(int(lit(s.get("delay_ticks"))), s["sound_id"]) for s in self.steps if s.get("type") == "play_sound"]
        self.contacts = [int(lit(s.get("delay_ticks"))) for s in self.steps if s.get("type") == "hit_stop"]


def element_span(el, duration):
    s = int(el.get("start_tick", 0))
    e = el.get("end_tick")
    return s, int(e) if e is not None else int(duration)


def keyframe_at(el, t):
    kfs = el.get("keyframes") or []
    pts = [(float(k.get("t", 0)), k["translate"]) for k in kfs if "translate" in k]
    if not pts:
        return None
    if t <= pts[0][0]:
        return tuple(pts[0][1])
    for (ta, a), (tb, b) in zip(pts, pts[1:]):
        if t <= tb:
            f = (t - ta) / (tb - ta) if tb > ta else 0.0
            return tuple(a[i] + (b[i] - a[i]) * f for i in range(3))
    return tuple(pts[-1][1])


def emission_ticks(el, duration):
    """(first, last) element ticks at which this element spawns particles, or None when it is a plain display."""
    kind = el.get("kind", {})
    s, e = element_span(el, duration)
    if kind.get("type") == "particle":
        every = max(1, int(kind.get("every_ticks", 1)))
        return [t for t in range(s, e + 1, every)]
    if "emit" in el:
        em = el["emit"]
        n = max(1, math.ceil(float(em.get("count", 1)) / max(float(em.get("rate_per_tick", 1)), 1e-6)))
        return list(range(s, s + n))
    return None


# ---- plan dump -----------------------------------------------------------------------------------------------------
def load_plan(path):
    if not path.is_file():
        return None
    d = json.loads(path.read_text())
    d["by_tick"] = {int(t["tick"]): t for t in d["ticks"]}
    return d


def blade_axis(w):
    return norm(sub(w["tip"], w["pos"]))


def face_normal(w):
    return norm(cross(blade_axis(w), tuple(w["edgeNormal"])))


def torso_lean_twist(rot):
    """(lean, twist) of the torso in degrees. Twist = |heading| of the torso's own forward axis from the caster facing (+z).
    Lean = forward pitch of the torso's up axis measured in the torso's OWN heading frame, so a forward bend is positive whichever way
    the caster is turned (measured along the caster's +z it flipped sign past 90 degrees of twist: the B3 'twist 132' workaround)."""
    up, fw = qrot(rot, (0, 1, 0)), qrot(rot, (0, 0, 1))
    psi = math.atan2(fw[0], fw[2])
    up_fwd = up[0] * math.sin(psi) + up[2] * math.cos(psi)   # up rotated by -psi about y, z component
    return math.degrees(math.atan2(up_fwd, up[1])), abs(math.degrees(psi))


# ---- check 1b ------------------------------------------------------------------------------------------------------
def check1b(skill, plan):
    """Project the plan dump onto each annotated reference frame (tools/pose-solve/overlay.py) and report the pixel error per beat."""
    c = "1b overlay"
    kp = ROOT / "content/skill-lint" / (skill.id + ".keypoints.yaml")
    if not kp.is_file():
        emit(c, True, "skipped: no %s (annotate the reference beats to enable the pixel check)" % kp.relative_to(ROOT))
        return
    if plan is None:
        emit(c, False, "no plan dump: cannot project it onto the reference frames")
        return
    sys.path.append(str(ROOT / "tools/pose-solve"))
    import overlay
    out = ROOT / "build/skill-lint" / skill.id / "overlay"
    res, tol = overlay.run(skill.id, plan, out)
    for bid, tick, ok, m in res:
        name = "1b %s t%s" % (bid, tick)
        if "error" in m:
            emit(name, False, m["error"])
            continue
        why = []
        if m["rms_frac"] > tol["rms_frac"]:
            why.append("skeleton RMS over the limit %.0f%%" % (100 * tol["rms_frac"]))
        if m.get("blade_deg", 0) > tol["blade_deg"]:
            why.append("blade angle over %.0f deg" % tol["blade_deg"])
        if m.get("tip_off_frac", 0) > tol["tip_off_frac"]:
            why.append("annotated tip off the blade line by more than %.0f%% of the figure" % (100 * tol["tip_off_frac"]))
        worst = max(m["per_point"].items(), key=lambda kv: kv[1])
        emit(name, ok, "skeleton RMS %.1f px (%.1f%% of the %d px figure), blade %.1f deg off on screen, tip %.1f px off the blade line, worst point %s %.0f px%s%s" % (
            m["rms_px"], 100 * m["rms_frac"], m["fig_h"], m.get("blade_deg", float("nan")), m.get("tip_off_px", float("nan")), worst[0], worst[1],
            ("; " + ", ".join(why)) if why else "", "; overlay %s" % (out / (bid + ".png")).relative_to(ROOT) if (out / (bid + ".png")).is_file() else ""))


# ---- check 1 -------------------------------------------------------------------------------------------------------
def check1(targets, plan):
    c = "1 beats"
    if plan is None:
        emit(c, False, "no plan dump (build/skill-lint/%s/plan.json): the runtime has not written one, nothing to compare" % targets["skill"])
        return
    H = float(targets.get("caster_height", 1.8))
    bones = targets["bones"]
    for beat in targets["beats"]:
        r = beat["ticks"]
        bid = "1 %s %s" % (beat["id"], ticks_str(r))
        conf = beat.get("confidence", "?")
        blade_t, body_t = beat.get("blade"), beat.get("body")
        if not blade_t and not body_t:
            emit(bid, True, "no pose target (camera-only beat), confidence %s" % conf)
            continue
        bad, worst, n = [], {}, 0
        for t in range(r[0], r[1] + 1):
            tk = plan["by_tick"].get(t)
            if tk is None:
                bad.append("t%d missing from the dump" % t)
                continue
            n += 1
            vals = {}
            w = tk.get("weapon")
            if blade_t and w:
                d = blade_axis(w)
                vals["tip_side_deg"] = (math.degrees(math.atan2(d[1], d[2])), "tip_side_deg", angdiff)
                vals["tip_y"] = (w["tip"][1], "tip_y", lambda a, b: abs(a - b))
                if "face_normal" in blade_t:
                    f = face_normal(w)
                    tgt = norm(blade_t["face_normal"])
                    ang = math.degrees(math.acos(min(1.0, abs(dot(f, tgt)))))
                    vals["face_deg"] = (ang, None, None)
            elif blade_t:
                bad.append("t%d no weapon in the dump" % t)
            if body_t:
                hb, tb = tk["bones"].get(bones["hips"]), tk["bones"].get(bones["torso"])
                if hb is None or tb is None:
                    bad.append("t%d bone %s/%s missing" % (t, bones["hips"], bones["torso"]))
                else:
                    vals["hips_h"] = (hb["pos"][1] / H, "hips_h", lambda a, b: abs(a - b))
                    lean, twist = torso_lean_twist(tb["rot"])
                    vals["lean_deg"] = (lean, "lean_deg", lambda a, b: abs(a - b))
                    vals["twist_deg"] = (twist, "twist_deg", lambda a, b: abs(a - b))
            for key, (val, tkey, fn) in vals.items():
                if key == "face_deg":
                    tgt_tol = blade_t.get("face_tol", 35)
                    err, tol, tv = val, tgt_tol, "normal %s" % blade_t["face_normal"]
                else:
                    src = blade_t if key in ("tip_side_deg", "tip_y") else body_t
                    if src is None or key not in src:
                        continue
                    tv, tol = src[key]
                    err = fn(val, tv)
                if key not in worst or err / tol > worst[key][0] / worst[key][2]:
                    worst[key] = (err, t, tol, val, tv)
                if err > tol:
                    bad.append("t%d %s %.2f vs target %s (+-%s, off by %.2f)" % (t, key, val, tv, tol, err))
        ok = not bad
        summ = "; ".join("%s worst t%d %.2f (target %s +-%s)" % (k, v[1], v[3], v[4] if not isinstance(v[4], str) else v[4], v[2])
                         for k, v in sorted(worst.items()))
        if ok:
            emit(bid, True, "%d ticks in tolerance, confidence %s; %s" % (n, conf, summ))
        else:
            shown = bad[:4] + (["... %d more" % (len(bad) - 4)] if len(bad) > 4 else [])
            emit(bid, False, "%d out-of-tolerance readings over %d ticks, confidence %s: %s" % (len(bad), max(n, 1), conf, " | ".join(shown)))


# ---- check 2 -------------------------------------------------------------------------------------------------------
def check2(targets, plan):
    c = "2 weapon"
    if plan is None:
        emit(c, False, "no plan dump: cannot verify weapon count or grip")
        return
    hand = targets["bones"]["hand"]
    tol = float(targets.get("grip_tolerance", 0.3))
    multi, far, nobone = [], [], []
    for tk in plan["ticks"]:
        t = tk["tick"]
        if tk.get("visibleWeapons") != 1:
            multi.append((t, tk.get("visibleWeapons")))
        hb, w = tk["bones"].get(hand), tk.get("weapon")
        if hb is None:
            nobone.append(t)
        elif w and math.dist(hb["pos"], w["pos"]) > tol:
            far.append((t, math.dist(hb["pos"], w["pos"])))
    n = len(plan["ticks"])
    if multi:
        emit(c + " count", False, "visibleWeapons != 1 on %d/%d ticks, first t%d = %s" % (len(multi), n, multi[0][0], multi[0][1]))
    else:
        emit(c + " count", True, "visibleWeapons == 1 on all %d ticks" % n)
    if nobone:
        emit(c + " grip", False, "hand bone %r missing from the dump at t%d (+%d more)" % (hand, nobone[0], len(nobone) - 1))
    elif far:
        emit(c + " grip", False, "grip %.2f blocks from %s at t%d (limit %.2f); %d/%d ticks off" % (far[0][1], hand, far[0][0], tol, len(far), n))
    else:
        emit(c + " grip", True, "grip within %.2f of %s on all %d ticks" % (tol, hand, n))


# ---- check 3 -------------------------------------------------------------------------------------------------------
def check3(skill, targets, plan):
    from rig_path import RigPath
    c = "3 fx"
    if skill.rig_vfx is None:
        emit(c, False, "skill plays no rig; nothing to follow")
        return
    none_beats = [(b["id"], b["ticks"]) for b in targets["beats"] if b.get("fx") == "none"]
    source = "plan dump" if plan else "rig_path.py (no plan dump)"
    emit(c + " source", True, "blade path from %s" % source)
    bad_hold, bad_lead, checked = [], [], 0
    for vid, (delay, doc) in skill.vfx.items():
        if vid == skill.rig_vfx:
            continue
        rp = RigPath(skill.id, skill.rig_vfx, fx_vfx=vid)
        dur = doc.get("duration_ticks", 0)
        spawn_dash = rp.dash_offset(delay)

        def seg(T):
            """Blade segment (grip, tip) at skill tick T in this file's fx frame (x = left)."""
            if plan and T in plan["by_tick"] and plan["by_tick"][T].get("weapon"):
                w = plan["by_tick"][T]["weapon"]
                dz = rp.dash_offset(T) - spawn_dash
                return tuple((-p[0], p[1], p[2] + dz) for p in (w["pos"], w["tip"]))
            return rp.blade_point(T - delay, 0.0), rp.blade_point(T - delay, 1.0)

        for el in doc.get("elements", []):
            em = emission_ticks(el, dur)
            s, e = element_span(el, dur)
            if em is None:
                spawn = [s]
            else:
                spawn = em
            for et in spawn:
                T = et + delay
                for bid, rr in none_beats:
                    if rr[0] <= T <= rr[1]:
                        bad_hold.append("%s %s element %s at skill t%d" % (bid, "emits" if em else "starts", el["id"], T))
            if el.get("kind", {}).get("type") == "particle":
                for et in em:
                    T = et + delay
                    P = keyframe_at(el, et - s)
                    if P is None:
                        continue
                    checked += 1
                    d0 = seg_dist(P, *seg(T))
                    later = [(k, seg_dist(P, *seg(T + k))) for k in (1, 2, 3)]
                    k, dk = min(later, key=lambda x: x[1])
                    if d0 > 0.5 and dk < d0 - 0.35:
                        bad_lead.append("%s at skill t%d leads the blade: %.2f blocks away now, %.2f at t%d" % (el["id"], T, d0, dk, T + k))
    if bad_hold:
        emit(c + " hold/coil", False, "%d spawns inside fx:none beats, first: %s" % (len(bad_hold), "; ".join(bad_hold[:3])))
    else:
        emit(c + " hold/coil", True, "no emission or display start in fx:none beats %s" % ", ".join("%s %s" % (b, ticks_str(r)) for b, r in none_beats))
    if bad_lead:
        emit(c + " lead", False, "%d/%d particle emissions lead the blade, first: %s" % (len(bad_lead), checked, "; ".join(bad_lead[:3])))
    else:
        emit(c + " lead", True, "%d particle emissions checked, none lead the blade" % checked)


# ---- check 4 -------------------------------------------------------------------------------------------------------
def haze_stats(sprite):
    import numpy as np
    from PIL import Image
    f = ROOT / "content/textures/generated/sprites" / (sprite + ".png")
    if not f.is_file():
        return None
    a = np.array(Image.open(f).convert("RGBA"))[..., 3]
    h = a.shape[0]
    frames = [a[:, i:i + h] for i in range(0, a.shape[1] - h + 1, h)] or [a]
    hazy = total = 0
    for fr in frames:
        vis = fr > 8
        if not vis.any():
            continue
        total += 1
        core = (fr >= 160).sum() / vis.sum()
        ys, xs = np.where(vis)
        aspect = (xs.max() - xs.min() + 1) / (ys.max() - ys.min() + 1)
        if core < 0.05 and fr[vis].mean() < 110:
            hazy += 1
    return hazy, total, int(a.max())


def check4(skill, targets):
    c = "4 budget"
    cap_d, cap_p = targets["budget"]["displays"], targets["budget"]["particles"]
    end = 0
    for vid, (delay, doc) in skill.vfx.items():
        end = max(end, delay + int(doc.get("duration_ticks", 0)))
    disp, parts = [0] * (end + 1), [0] * (end + 1)
    for vid, (delay, doc) in skill.vfx.items():
        dur = int(doc.get("duration_ticks", 0))
        if doc.get("rigs"):
            n = sum(len(r.get("parts", [])) for r in doc["rigs"])
            for T in range(delay, delay + dur):
                disp[T] += n
        for el in doc.get("elements", []):
            s, e = element_span(el, dur)
            kind = el.get("kind", {})
            if kind.get("type") == "particle":
                for et in emission_ticks(el, dur):
                    parts[et + delay] += int(load_particle_count(kind.get("particle_id")))
            elif "emit" in el:
                em = emission_ticks(el, dur)
                per = float(el["emit"].get("rate_per_tick", 1))
                for et in em:
                    parts[et + delay] += int(per)
                # the emitted sprites are Displays that live for their lifetime
                life = int(el["emit"].get("lifetime", el["emit"].get("lifetime_ticks", 6)))
                for T in range(s + delay, s + delay + len(em) + life):
                    disp[min(T, end)] += min(int(el["emit"].get("count", 1)), int(per) * (T - s - delay + 1))
            else:
                for T in range(s + delay, min(e + delay, end + 1)):
                    disp[T] += 1
    peak_t = max(range(len(disp)), key=lambda t: disp[t])
    peak_p = max(range(len(parts)), key=lambda t: parts[t])
    emit(c + " displays", disp[peak_t] <= cap_d, "peak %d simultaneous Displays at skill t%d (budget %d)" % (disp[peak_t], peak_t, cap_d))
    emit(c + " particles", parts[peak_p] <= cap_p, "peak %d particles spawned per tick at skill t%d (budget %d)" % (parts[peak_p], peak_p, cap_p))
    sprites = sorted({el["kind"]["sprite"] for _, doc in skill.vfx.values() for el in doc.get("elements", [])
                      if el.get("kind", {}).get("type") == "sprite"})
    flagged, missing = [], []
    for sp in sprites:
        st = haze_stats(sp)
        if st is None:
            missing.append(sp)
        elif st[1] and st[0] / st[1] >= 0.5:
            flagged.append("%s (%d/%d frames with no core: alpha>=160 on <5%% of visible pixels, mean alpha <110; peak alpha %d)" % (sp, st[0], st[1], st[2]))
    if missing:
        emit(c + " sprites", False, "sprite png missing: %s" % ", ".join(missing))
    if flagged:
        emit(c + " haze", False, "%d haze sprites (reads as a ring on sky): %s" % (len(flagged), "; ".join(flagged)))
    else:
        emit(c + " haze", True, "%d sprites checked, none are a thin haze" % len(sprites))


_pcount = {}


def load_particle_count(pid):
    if pid not in _pcount:
        f = ROOT / "content/particles" / (str(pid) + ".yaml")
        _pcount[pid] = yaml.safe_load(f.read_text()).get("count", 1) if f.is_file() else 1
    return _pcount[pid]


# ---- check 5 -------------------------------------------------------------------------------------------------------
def sound_file(sound_id):
    ns, _, path = sound_id.partition(":")
    return ROOT / "content/sounds" / (path + ".ogg")


def check5(skill, targets):
    c = "5 sound"
    for beat in targets["beats"]:
        w = (beat.get("sound") or {}).get("window")
        if not w:
            continue
        hits = [(t, s) for t, s in skill.sounds if w[0] <= t <= w[1]]
        bid = "5 %s %s" % (beat["id"], ticks_str(w))
        if not hits:
            emit(bid, False, "needs sound in t%d-%d, skill has none (sounds at %s)" % (w[0], w[1], sorted({t for t, _ in skill.sounds})))
            continue
        gone = [s for t, s in hits if not sound_file(s).is_file()]
        if gone:
            emit(bid, False, "sound events at %s but file missing: %s" % ([t for t, _ in hits], ", ".join(str(sound_file(s).relative_to(ROOT)) for s in gone)))
        else:
            emit(bid, True, "%d sound events (t%s), all files exist" % (len(hits), ",".join(str(t) for t, _ in hits)))
    gone = sorted({s for _, s in skill.sounds if not sound_file(s).is_file()})
    emit(c + " files", not gone, "all %d referenced sound files exist" % len({s for _, s in skill.sounds}) if not gone else "missing: %s" % ", ".join(gone))


# ---- check 6 -------------------------------------------------------------------------------------------------------
def file_hash(paths):
    h = hashlib.sha256()
    for p in sorted(paths):
        h.update(p.encode() + b"\0" + Path(ROOT / p).read_bytes())
    return h.hexdigest()


def regen_state(skill, targets):
    rg = targets["regen"]
    files = list(rg["inputs"]["files"])
    skill_part = json.dumps([s for s in skill.steps if s.get("type") in rg["inputs"]["skill_step_types"]], sort_keys=True, default=str)
    inputs = hashlib.sha256((file_hash(files) + skill_part).encode()).hexdigest()
    outs = sorted({str(Path(p).relative_to(ROOT)) for pat in rg["outputs"] for p in glob.glob(str(ROOT / pat))})
    return {"inputs": inputs, "outputs": file_hash(outs), "output_files": len(outs)}


def regen_stamp_path(skill_id):
    return ROOT / "content/skill-lint" / (skill_id + ".regen.json")


def check6(skill, targets):
    c = "6 regen"
    st = regen_state(skill, targets)
    sp = regen_stamp_path(skill.id)
    if not sp.is_file():
        emit(c, False, "no regen stamp %s: run %s then `skill_lint.py %s --stamp-regen` and commit the stamp" % (sp.relative_to(ROOT), targets["regen"]["script"], skill.id))
        return
    old = json.loads(sp.read_text())
    if old["inputs"] != st["inputs"]:
        emit(c, False, "inputs changed since the last regen (rig / generator scripts / skill play_vfx, dash, freeze): rerun %s" % targets["regen"]["script"])
    elif old["outputs"] != st["outputs"]:
        emit(c, False, "generated outputs differ from the stamped regen result (hand-edited or regenerated without --stamp-regen)")
    else:
        emit(c, True, "inputs %s and %d outputs match the regen stamp" % (st["inputs"][:8], st["output_files"]))


# ---- check 7 -------------------------------------------------------------------------------------------------------
def git(*a):
    return subprocess.check_output(["git", *a], cwd=ROOT, text=True).strip()


def content_revision():
    """Same fingerprint tools/vfx-shot/capture_meta.py stamps: git tree of content/ + digest of uncommitted changes."""
    names = sorted({n for n in git("ls-files", "-m", "-d", "-o", "--exclude-standard", "-z", "--", "content").split("\0") if n})
    d = hashlib.sha256()
    for n in names:
        f = ROOT / n
        d.update(n.encode() + b"\0")
        d.update(f.read_bytes() if f.is_file() else b"<deleted>")
    return git("rev-parse", "HEAD:content") + ("+dirty-" + d.hexdigest()[:12] if names else "")


def check7(skill, capdir):
    c = "7 capture"
    if capdir is None:
        emit(c, True, "skipped (no --capture <dir>)")
        return
    mp = Path(capdir) / "meta.json"
    if not mp.is_file():
        emit(c + " meta", False, "%s missing" % mp)
        return
    meta = json.loads(mp.read_text())
    q = meta.get("captureQuality", {})
    emit(c + " meta", meta.get("skill") == skill.id and isinstance(meta.get("frames"), int) and meta["frames"] >= 1 and not q.get("invalid"),
         "skill %s, frames %s, invalid=%s%s" % (meta.get("skill"), meta.get("frames"), q.get("invalid", False), (" (%s)" % q.get("reason")) if q.get("invalid") else ""))
    cur = content_revision()
    emit(c + " revision", meta.get("contentRevision") == cur, "capture contentRevision %s vs tree %s" % (meta.get("contentRevision"), cur))
    emit(c + " repeats", q.get("maxRepeatedFrames", 0) < 15, "longest run of identical frames %s (review at >= 15)" % q.get("maxRepeatedFrames"))
    print("[TODO] %s weapon-count  not implemented: per-frame weapon-count heuristic for FPS=60 captures; judge duplicate swords by eye until it exists" % c)


# ---- main ----------------------------------------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("skill")
    ap.add_argument("--plan")
    ap.add_argument("--targets")
    ap.add_argument("--capture")
    ap.add_argument("--stamp-regen", action="store_true")
    a = ap.parse_args()
    skill = Skill(a.skill)
    tpath = Path(a.targets) if a.targets else ROOT / "content/skill-lint" / (a.skill + ".targets.yaml")
    if not tpath.is_file():
        print("[FAIL] 0 targets  no target file %s" % tpath)
        return 1
    targets = yaml.safe_load(tpath.read_text())
    if a.stamp_regen:
        st = regen_state(skill, targets)
        regen_stamp_path(a.skill).write_text(json.dumps(st, indent=2) + "\n")
        print("stamped %s" % regen_stamp_path(a.skill).relative_to(ROOT))
        return 0
    plan = load_plan(Path(a.plan) if a.plan else ROOT / "build/skill-lint" / a.skill / "plan.json")
    print("skill-lint %s  (targets %s, plan dump %s)\n" % (a.skill, tpath.relative_to(ROOT) if tpath.is_relative_to(ROOT) else tpath, "present" if plan else "MISSING"))
    check1(targets, plan)
    check1b(skill, plan)
    check2(targets, plan)
    check3(skill, targets, plan)
    check4(skill, targets)
    check5(skill, targets)
    check6(skill, targets)
    check7(skill, a.capture)
    fails = [r for r in RESULTS if not r[1]]
    print("\n%d checks, %d FAIL. %s" % (len(RESULTS), len(fails), "Do not capture." if fails else "OK to capture."))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
