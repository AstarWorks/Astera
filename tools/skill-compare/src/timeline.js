// Pure timeline model shared by the browser app, the server and the tests.
// Master clock = milliseconds since the case range start (case.start_s). Everything else is derived from it:
//   video frame  = floor(ms * fps / 1000)            (native fps, 30 for the source videos)
//   MC tick      = floor((ms - castMs) / 50)         (castMs = skill start + offset slider)

export const TICK_MS = 50;

export const clamp = (x, a, b) => Math.max(a, Math.min(b, x));

export function msToVideoFrame(ms, fps, frameCount) {
  const i = Math.floor((ms * fps) / 1000 + 1e-6);
  return frameCount == null ? Math.max(0, i) : clamp(i, 0, frameCount - 1);
}
export const videoFrameToMs = (i, fps) => (i * 1000) / fps;

export const msToTick = (ms, castMs) => Math.floor((ms - castMs) / TICK_MS + 1e-6);
export const tickToMs = (tick, castMs) => castMs + tick * TICK_MS;

/** Absolute video seconds <-> master clock ms. */
export const sToMs = (s, caseStartS) => Math.round((s - caseStartS) * 1000);
export const msToS = (ms, caseStartS) => Math.round((caseStartS + ms / 1000) * 1000) / 1000;

/** Cast moment on the master clock: skill start plus the user offset. */
export function castMs(c) {
  const skill = (c.segments || []).find((s) => s.kind === "skill");
  const startS = skill ? skill.start_s : c.start_s;
  return sToMs(startS, c.start_s) + (c.mc?.offset_ms ?? 0);
}

/**
 * Focus zones: merged [from,to] ms windows around strong onsets and every cut. Onset strength is relative to the
 * strongest onset in range; `minRel` picks "strong".
 */
export function focusZones(onsets, cuts, caseStartS, windowMs = 150, minRel = 0.5) {
  const max = Math.max(0, ...onsets.map((o) => o.s));
  const pts = [];
  for (const o of onsets) if (max > 0 && o.s >= max * minRel) pts.push(sToMs(o.t, caseStartS));
  for (const c of cuts) pts.push(sToMs(c.t, caseStartS));
  pts.sort((a, b) => a - b);
  const zones = [];
  for (const p of pts) {
    const z = { from: p - windowMs, to: p + windowMs };
    const last = zones[zones.length - 1];
    if (last && z.from <= last.to) last.to = Math.max(last.to, z.to);
    else zones.push(z);
  }
  return zones;
}

export const inZone = (ms, zones) => zones.some((z) => ms >= z.from && ms <= z.to);

/** Effective playback speed: inside a focus zone, never faster than `factor`. */
export function effectiveSpeed(ms, base, zones, enabled, factor) {
  return enabled && inZone(ms, zones) ? Math.min(base, factor) : base;
}

// ---- case.json -----------------------------------------------------------------------------------------------

export const CASE_ID_RE = /^[a-z0-9][a-z0-9-]{0,63}$/;
export const VIDEO_ID_RE = /^[A-Za-z0-9_-]{6,20}$/;
const KINDS = ["skill", "range", "hit"];
export const BEAT_TEXT = ["label", "weapon", "caster_move", "vfx", "notes"];

/** Returns a list of human-readable problems (empty = valid). */
export function validateCase(c) {
  const e = [];
  const num = (v) => typeof v === "number" && Number.isFinite(v);
  if (!c || typeof c !== "object") return ["case must be an object"];
  if (!CASE_ID_RE.test(c.id ?? "")) e.push("id must match " + CASE_ID_RE);
  if (!VIDEO_ID_RE.test(c.videoId ?? "")) e.push("videoId looks wrong");
  if (!num(c.start_s) || !num(c.end_s) || c.start_s < 0 || c.end_s <= c.start_s) e.push("need 0 <= start_s < end_s");
  if (c.end_s - c.start_s > 120) e.push("range longer than 120 s");
  if (!c.skill || typeof c.skill.name !== "string") e.push("skill.name required");
  if (!Array.isArray(c.segments)) return [...e, "segments must be an array"];
  const ids = new Set();
  for (const s of c.segments) {
    if (!s || typeof s.id !== "string" || !s.id) { e.push("segment without id"); continue; }
    if (ids.has(s.id)) e.push("duplicate segment id " + s.id);
    ids.add(s.id);
    if (!KINDS.includes(s.kind)) e.push(`${s.id}: kind must be one of ${KINDS}`);
    if (typeof s.label !== "string") e.push(`${s.id}: label must be a string`);
    if (s.notes != null && typeof s.notes !== "string") e.push(`${s.id}: notes must be a string`);
    if (s.kind === "hit") {
      if (!num(s.t_s)) e.push(`${s.id}: hit needs t_s`);
    } else if (!num(s.start_s) || !num(s.end_s) || s.end_s < s.start_s) e.push(`${s.id}: needs start_s <= end_s`);
  }
  if (c.beats != null) {
    if (!Array.isArray(c.beats)) e.push("beats must be an array");
    else c.beats.forEach((b, i) => {
      if (!b || typeof b !== "object") { e.push(`beats[${i}] must be an object`); return; }
      if (!num(b.t_s)) e.push(`beats[${i}]: t_s must be a number`);
      for (const k of BEAT_TEXT) if (b[k] != null && typeof b[k] !== "string") e.push(`beats[${i}]: ${k} must be a string`);
    });
  }
  if (c.mc && !num(c.mc.offset_ms ?? 0)) e.push("mc.offset_ms must be a number");
  if (c.focus && (!num(c.focus.factor) || c.focus.factor <= 0 || c.focus.factor > 1)) e.push("focus.factor must be in (0,1]");
  return e;
}

/** Short description of what changed between two cases' segments/notes, for the edit log. */
export function diffSummary(before, after) {
  const b = new Map((before?.segments ?? []).map((s) => [s.id, s]));
  const a = new Map((after.segments ?? []).map((s) => [s.id, s]));
  const out = [];
  for (const [id, s] of a) {
    const o = b.get(id);
    if (!o) out.push(`+${id}`);
    else if (JSON.stringify(o) !== JSON.stringify(s)) out.push(`~${id}`);
  }
  for (const id of b.keys()) if (!a.has(id)) out.push(`-${id}`);
  if ((before?.notes ?? "") !== (after.notes ?? "")) out.push("notes");
  if (JSON.stringify(before?.mc ?? null) !== JSON.stringify(after.mc ?? null)) out.push("mc");
  if (JSON.stringify(before?.focus ?? null) !== JSON.stringify(after.focus ?? null)) out.push("focus");
  return out.length ? out.join(" ") : "no change";
}
