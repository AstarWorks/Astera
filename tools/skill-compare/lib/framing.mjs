// Framing parity without image guessing: the caster's on-screen height as % of the frame height, computed from the capture camera and
// the caster position in the server trace (entity height 1.8), compared against a per-case annotated reference height.
//
// Camera model = the one vfx-shot actually drove (the capture meta does not store it, so it is rebuilt deterministically):
//   compare : tools/vfx-shot/compare_plan.py  (intent.json camera keys, caster at the origin, world = origin + intent coords, hard cuts,
//             `transition: linear` keys sampled per tick, keys with another fov dollied along the view ray to the first key's fov)
//   side / right / front / back / high / near : vfx-shot.sh orbit_cam (caster faces +z, enemy DISTANCE ahead)
// Caster position = `[vfx-trace] pos <rig> age=N caster=Vec3(..)` lines of the capture's trace.log, relative to the age-0 sample
// (the capture places the caster at the arena origin, so that sample is the origin). Vertical FOV, 16:9 frame.
import { readFileSync } from "node:fs";
import { join } from "node:path";

export const CASTER_HEIGHT = 1.8;
const RAD = Math.PI / 180;
const ORBIT = new Set(["side", "right", "front", "back", "high", "near"]);

/** caster position per cast tick (age), relative to the age-0 sample; null without a usable trace. */
export function casterPath(dir) {
  let text;
  try { text = readFileSync(join(dir, "trace.log"), "utf8"); } catch { return null; }
  let rig = null;
  const byAge = new Map();
  for (const l of text.split(/\r?\n/)) {
    let m;
    if (rig == null && (m = /\[vfx-trace\] play (\S+) tick=\d+/.exec(l))) rig = m[1];
    if ((m = /\[vfx-trace\] pos (\S+) age=(\d+) tick=\d+ caster=Vec3\(x=(-?[\d.]+), y=(-?[\d.]+), z=(-?[\d.]+)\)/.exec(l)) && (rig == null || m[1] === rig)) {
      rig ??= m[1];
      byAge.set(+m[2], [+m[3], +m[4], +m[5]]);
    }
  }
  if (!byAge.size) return null;
  const ages = [...byAge.keys()].sort((a, b) => a - b), o = byAge.get(ages[0]);
  const last = ages[ages.length - 1];
  const rel = (age) => { const p = byAge.get(age) ?? byAge.get(Math.max(...ages.filter((a) => a <= age))) ; return p ? [p[0] - o[0], p[1] - o[1], p[2] - o[2]] : [0, 0, 0]; };
  const abs = (age) => { const c = Math.min(Math.max(age, ages[0]), last), p = byAge.get(c) ?? byAge.get(Math.max(...ages.filter((a) => a <= c))); return p ? [...p] : o; };
  return { at: (tick) => rel(Math.min(Math.max(tick, ages[0]), last)), abs, from: ages[0], to: last };
}

function look(pos, tgt) {
  const dx = tgt[0] - pos[0], dy = tgt[1] - pos[1], dz = tgt[2] - pos[2];
  return { yaw: Math.atan2(-dx, dz) / RAD, pitch: -Math.atan2(dy, Math.hypot(dx, dz)) / RAD };
}

/** compare_plan.py: [{ms, eye, yaw, pitch}] in plan coordinates (caster at the origin) + the client fov. */
function comparePlan(intent, mode, fovMeta) {
  const actors = Object.fromEntries((intent?.actors ?? []).map((a) => [a.id, a]));
  let enemyX = 4.2;
  if (actors.enemy?.keys?.length) enemyX = actors.enemy.keys[0].pos[0] - (actors.caster?.keys?.[0]?.pos?.[0] ?? 0);
  let keys = intent?.camera ?? [];
  if (mode === "side" || !keys.length) { const mid = enemyX / 2; keys = [{ t: 0, pos: [mid, 1.6, 6.0], target: [mid, 1.0, 0.0], fov: 55 }]; }
  const fov0 = fovMeta ?? +(keys[0].fov ?? 70), k0 = Math.tan(fov0 * RAD / 2);
  const sampled = [keys[0]];
  for (let i = 1; i < keys.length; i++) {
    const a = keys[i - 1], b = keys[i];
    if (b.transition === "linear") {
      for (let t = Math.floor(a.t) + 1; t < Math.ceil(b.t); t++) {
        const u = (t - a.t) / (b.t - a.t), lerp = (x, y) => x + (y - x) * u;
        sampled.push({ t, pos: a.pos.map((x, j) => lerp(x, b.pos[j])), target: a.target.map((x, j) => lerp(x, b.target[j])), fov: lerp(a.fov ?? fov0, b.fov ?? fov0) });
      }
    }
    sampled.push(b);
  }
  const cams = sampled.map((k) => {
    const s = Math.tan((k.fov ?? fov0) * RAD / 2) / k0;
    const p = k.target.map((t, j) => t + (k.pos[j] - t) * s);
    return { ms: Math.round(k.t * 50), eye: p, ...look(p, k.target) };
  });
  return { fov: fov0, cams };
}

/** vfx-shot.sh orbit_cam: eye = feet tp + 1.62; world offsets from the caster feet (caster faces +z). */
function orbitCam(view, d) {
  const T = {
    side: [[-Math.max(3.9, (d / 2 + 1.5) / 1.245), 0.1, d / 2], [0, 1.0, d / 2]],
    right: [[Math.max(3.9, (d / 2 + 1.5) / 1.245), 0.1, d / 2], [0, 1.0, d / 2]],
    front: [[2.0, 1.7, d + 3.0], [0, 1.2, 0.6]],
    back: [[-1.0, 2.0, -5.0], [0, 1.2, 0.8]],
    high: [[4.0, 5.8, -1.5], [0, 0.9, 1.0]],
    near: [[2.2, 1.62, 1.2], [0, 1.2, 0.3]],
  }[view];
  const [c, lk] = T, eye = [c[0], c[1] + 1.62, c[2]];
  return { eye, ...look(eye, lk) };
}

/** On-screen caster height (% of frame height) for a camera and the caster feet position; null when behind the camera. */
function project(cam, fov, feet) {
  const yaw = cam.yaw * RAD, pitch = cam.pitch * RAD;
  const f = [-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)];
  const u = [-Math.sin(yaw) * Math.sin(pitch), Math.cos(pitch), Math.cos(yaw) * Math.sin(pitch)];
  const r = [-Math.cos(yaw), 0, -Math.sin(yaw)];
  const th = Math.tan(fov * RAD / 2);
  const ndc = (p) => {
    const v = [p[0] - cam.eye[0], p[1] - cam.eye[1], p[2] - cam.eye[2]], dot = (a) => a[0] * v[0] + a[1] * v[1] + a[2] * v[2];
    const z = dot(f);
    return z > 0.05 ? { x: dot(r) / (z * th * (16 / 9)), y: dot(u) / (z * th) } : null;
  };
  const lo = ndc(feet), hi = ndc([feet[0], feet[1] + CASTER_HEIGHT, feet[2]]);
  if (!lo || !hi) return null;
  const inside = (p) => Math.abs(p.x) <= 1 && Math.abs(p.y) <= 1;
  return { pct: +(((hi.y - lo.y) / 2) * 100).toFixed(1), onScreen: inside(lo) && inside(hi), centerX: +((lo.x + 1) / 2).toFixed(2) };
}

/**
 * Per-tick analytic caster height of one MC capture row.
 * @returns {{ method: string, fov: number, perTick: Record<number, {pct:number,onScreen:boolean}|null> } | { error: string }}
 */
export function analyticHeights({ view, meta, intent, dir, ticks, distance }) {
  if (!(Array.isArray(meta?.cameraKeys) && meta.cameraKeys.length) && view !== "compare" && !ORBIT.has(view)) return { error: `no camera model for view '${view}' (compare, side, right, front, back, high, near)` };
  const path = casterPath(dir);
  if (!path) return { error: "no trace.log with [vfx-trace] pos lines (caster position unknown)" };
  const fovMeta = typeof meta?.fov === "number" ? meta.fov : null;
  let camAt, fov, method, world = false;
  const keys = Array.isArray(meta?.cameraKeys) ? meta.cameraKeys.filter((k) => Array.isArray(k.pos) && Number.isFinite(k.yaw) && Number.isFinite(k.pitch)).sort((a, b) => a.tick - b.tick) : [];
  if (keys.length) {
    // recorded by the capture itself: world eye position (not the feet tp), yaw/pitch degrees, fov per key, tick = cast-relative plan tick
    world = true; fov = fovMeta ?? keys[0].fov ?? 70; method = "capture meta cameraKeys + trace caster";
    camAt = (tick) => { let k = keys[0]; for (const c of keys) if (c.tick <= tick) k = c; return { eye: k.pos, yaw: k.yaw, pitch: k.pitch, fov: k.fov }; };
  } else if (view === "compare") {
    const plan = comparePlan(intent, meta?.camera ?? "case", fovMeta);
    fov = plan.fov;
    camAt = (tick) => { let c = plan.cams[0]; for (const k of plan.cams) if (k.ms <= tick * 50) c = k; return c; };
    method = "compare_plan camera keys + trace caster (reconstructed; capture meta has no cameraKeys)";
  } else {
    const d = meta?.distance ?? distance ?? 4;
    fov = fovMeta ?? 70;
    const cam = orbitCam(view, d);
    camAt = () => cam;
    method = "orbit_cam + trace caster (reconstructed; capture meta has no cameraKeys)";
  }
  const perTick = {};
  for (const t of ticks) { const c = camAt(t); perTick[t] = project(c, c.fov ?? fov, world ? path.abs(t) : path.at(t)); }
  return { method, fov, perTick };
}

/** ref_caster_height_pct {"<tick>": pct} from case.json: exact tick, else the nearest annotated tick within +-2 ticks; null otherwise. */
export function refHeightAt(annot, tick) {
  if (!annot) return null;
  let best = null;
  for (const [k, v] of Object.entries(annot)) {
    const d = Math.abs(+k - tick);
    if (typeof v === "number" && d <= 2 && (best == null || d < best.d)) best = { d, v };
  }
  return best ? best.v : null;
}
