// Animation review (ADR-0250 skin-rig motions): content/animations/*.yaml x the captures `make anim-shot` leaves in
// <vfx-shot dir>/anim/<id>/ (main + every worktree + SKILL_COMPARE_MEDIA_DIRS, same discovery as the case captures)
// x the founder's verdict in data/anim/<id>/review.json (committed; rev/409 + editLog like case.json).
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join, resolve, sep } from "node:path";

export const ANIM_ID_RE = /^[a-z0-9][a-z0-9-]{0,63}$/;
/** Media under build/vfx-shot/anim/<id>/: rest stills and per-tick frames. Nothing else is reachable. */
export const ANIM_MEDIA_RE = /^(?:(?:vanilla|rig)-(?:front|side|top)\.png|(?:play|blend-in|blend-out)\/(?:front|side)\/frame_\d{3,4}\.(?:png|webp))$/;
export const STATUSES = ["unreviewed", "ok", "ng"];
const MAX_COMMENT = 4000, MAX_PINS = 100, MAX_PIN_TEXT = 1000;

const readJson = (p) => JSON.parse(readFileSync(p, "utf8"));
const mtime = (p) => { try { return statSync(p).mtimeMs; } catch { return 0; } };
const inside = (base, p) => { const r = resolve(p); return r.startsWith(resolve(base) + sep); };

export function validateReview(r) {
  const problems = [];
  if (!r || typeof r !== "object") return ["review must be an object"];
  if (!STATUSES.includes(r.status)) problems.push("status must be unreviewed|ok|ng");
  if (typeof r.comment !== "string" || r.comment.length > MAX_COMMENT) problems.push(`comment must be a string of at most ${MAX_COMMENT} chars`);
  if (!Array.isArray(r.pins) || r.pins.length > MAX_PINS) problems.push(`pins must be an array of at most ${MAX_PINS}`);
  else for (const [i, p] of r.pins.entries()) {
    if (!Number.isInteger(p?.tick) || p.tick < -10 || p.tick > 2000) problems.push(`pins[${i}].tick must be an integer tick`);
    if (typeof p?.text !== "string" || !p.text.trim() || p.text.length > MAX_PIN_TEXT) problems.push(`pins[${i}].text must be 1-${MAX_PIN_TEXT} chars`);
  }
  return problems;
}

/** One-line editLog summary, e.g. `status unreviewed->ng comment +pin@7 "arm pops"`: this is what agents read. */
export function reviewDiff(before, after) {
  const out = [];
  if ((before.status ?? "unreviewed") !== after.status) out.push(`status ${before.status ?? "unreviewed"}->${after.status}`);
  if ((before.comment ?? "") !== after.comment) out.push(`comment "${after.comment.replace(/\s+/g, " ").slice(0, 160)}"`);
  const key = (p) => `${p.tick}\u0000${p.text}`;
  const was = new Set((before.pins ?? []).map(key)), now = new Set(after.pins.map(key));
  for (const p of after.pins) if (!was.has(key(p))) out.push(`+pin@${p.tick} "${p.text.replace(/\s+/g, " ").slice(0, 120)}"`);
  for (const p of before.pins ?? []) if (!now.has(key(p))) out.push(`-pin@${p.tick}`);
  return out.length ? out.join(" ") : "no change";
}

export function makeAnim({ repo, root, S }) {
  const animDir = join(repo, "content", "animations");
  const skillDir = join(repo, "content", "skills");

  function definition(id) {
    const file = join(animDir, id + ".yaml");
    if (!existsSync(file)) return null;
    let y;
    try { y = Bun.YAML.parse(readFileSync(file, "utf8")); } catch { return null; }
    const text = readFileSync(file, "utf8");
    return {
      id, lengthTicks: y.length_ticks ?? 0, loop: !!y.loop, lockMovement: !!y.lock_movement,
      blendInTicks: y.blend_in_ticks ?? 0, blendOutTicks: y.blend_out_ticks ?? 0,
      bones: Object.keys(y.bones ?? {}), note: (/^#\s*(.+)$/m.exec(text)?.[1] ?? "").slice(0, 300),
    };
  }

  function ids() {
    if (!existsSync(animDir)) return [];
    return readdirSync(animDir, { withFileTypes: true })
      .filter((d) => d.isFile() && d.name.endsWith(".yaml") && ANIM_ID_RE.test(d.name.slice(0, -5)))
      .map((d) => d.name.slice(0, -5)).sort();
  }

  /** Skills whose `play_animation` runs this motion (a capture can be started from either id). */
  function skillsPlaying(id) {
    const out = [];
    if (!existsSync(skillDir)) return out;
    const re = new RegExp(`^\\s*animation:\\s*"?${id}"?\\s*$`, "m");
    for (const f of readdirSync(skillDir)) {
      if (!f.endsWith(".yaml")) continue;
      try { if (re.test(readFileSync(join(skillDir, f), "utf8"))) out.push(f.slice(0, -5)); } catch { /* unreadable skill */ }
    }
    return out;
  }

  /** Every build/vfx-shot dir that may hold anim/<id>: the case sources' dirs, SKILL_COMPARE_MEDIA_DIRS, this repo's, and every checkout under
   *  the worktree root (SKILL_COMPARE_WORKTREE_ROOT or <repo>/.claude/worktrees), whether or not it has skill-compare case data. */
  function captureBases() {
    const out = new Set(S.vfxDirs().map((d) => resolve(d)));
    out.add(resolve(repo, "build", "vfx-shot"));
    const wtRoot = process.env.SKILL_COMPARE_WORKTREE_ROOT || join(repo, ".claude", "worktrees");
    if (existsSync(wtRoot)) {
      for (const d of readdirSync(wtRoot, { withFileTypes: true })) if (d.isDirectory()) out.add(resolve(wtRoot, d.name, "build", "vfx-shot"));
    }
    return [...out];
  }

  /** The newest <vfx-shot dir>/anim/<id> that has meta.json (main, worktrees, SKILL_COMPARE_MEDIA_DIRS). */
  function captureOf(id) {
    let best = null, bestT = -1;
    for (const b of captureBases()) {
      const dir = join(b, "anim", id), t = mtime(join(dir, "meta.json"));
      if (t > bestT) { best = { dir, base: b }; bestT = t; }
    }
    if (!best) return null;
    try {
      const meta = readJson(join(best.dir, "meta.json"));
      const src = S.sources().find((s) => resolve(s.vfxDir) === resolve(best.base));
      return { dir: best.dir, meta, mtime: bestT, from: src ? { worktree: src.name, branch: src.branch } : { worktree: best.base.split("/.claude/worktrees/")[1]?.split("/")[0] ?? "media-dir", branch: meta.branch ?? "" } };
    } catch { return null; }
  }

  const reviewFiles = (id) => S.sources().map((s) => ({ src: s, file: join(s.dataDir, "anim", id, "review.json") })).filter((x) => existsSync(x.file));

  /** Highest rev wins, then the newest file (same rule as cases). Absent: an empty unreviewed record, written into main. */
  function reviewOf(id) {
    let best = null;
    for (const { src, file } of reviewFiles(id)) {
      let r; try { r = readJson(file); } catch { continue; }
      const cand = { r, file, mtime: mtime(file), src };
      if (!best || (r.rev ?? 0) > (best.r.rev ?? 0) || ((r.rev ?? 0) === (best.r.rev ?? 0) && cand.mtime > best.mtime)) best = cand;
    }
    if (best) return { review: { id, rev: 0, status: "unreviewed", comment: "", pins: [], editLog: [], ...best.r }, file: best.file, from: best.src.name };
    return { review: { id, rev: 0, status: "unreviewed", comment: "", pins: [], editLog: [] }, file: join(root, "data", "anim", id, "review.json"), from: "main" };
  }

  const frameUrl = (id, rel, v) => `/anim-media/${id}/${rel}?v=${Math.round(v)}`;

  function summary(id) {
    const def = definition(id);
    if (!def) return null;
    const cap = captureOf(id), { review } = reviewOf(id);
    const last = review.comment?.trim() || review.pins?.at(-1)?.text || "";
    const lastAt = review.editLog?.at(-1);
    let thumb = null;
    if (cap) {
      const pre = cap.meta.playPreTicks ?? 3, n = Math.max(0, Math.min(def.lengthTicks >> 1, (cap.meta.frames ?? 1) - pre - 1));
      const ext = existsSync(join(cap.dir, "play", "side", "frame_000.webp")) ? "webp" : "png";
      thumb = frameUrl(id, `play/side/frame_${String(pre + n).padStart(3, "0")}.${ext}`, cap.mtime);
    }
    return {
      ...def, skills: skillsPlaying(id),
      capture: cap && { commit: cap.meta.implementationCommit ?? null, branch: cap.meta.branch ?? null, capturedAt: cap.meta.capturedAt ?? null, from: cap.from },
      thumb, review: { status: review.status, comment: last, rev: review.rev ?? 0, updated: lastAt ? { who: lastAt.who, at: lastAt.at } : null },
    };
  }

  const list = () => ids().map(summary).filter(Boolean);

  const provOf = (m) => ({ branch: m.branch ?? null, commit: m.implementationCommit ?? null, capturedAt: m.capturedAt ?? null,
    fov: m.fov ?? null, fovMode: m.fovMode ?? null, resolution: m.resolution ?? null, view: m.view ?? null, published: false });

  function detail(id) {
    const def = definition(id);
    if (!def) return null;
    const cap = captureOf(id), { review, from } = reviewOf(id);
    let capture = null;
    if (cap) {
      const m = cap.meta, v = cap.mtime;
      const ext = (kind, view) => (existsSync(join(cap.dir, kind, view, "frame_000.webp")) ? "webp" : "png");
      const strip = (kind, view, n) => Array.from({ length: n }, (_, i) => frameUrl(id, `${kind}/${view}/frame_${String(i).padStart(3, "0")}.${ext(kind, view)}`, v));
      const playFrames = (view) => {
        const dir = join(cap.dir, "play", view);
        const n = existsSync(dir) ? readdirSync(dir).filter((f) => /^frame_\d{3,4}\.png$/.test(f)).length : 0;
        return strip("play", view, n);
      };
      const rest = {};
      for (const view of ["front", "side", "top"]) {
        rest[view] = {};
        for (const kind of ["vanilla", "rig"]) if (existsSync(join(cap.dir, `${kind}-${view}.png`))) rest[view][kind] = frameUrl(id, `${kind}-${view}.png`, v);
      }
      const blend = (kind, info) => {
        const out = {};
        for (const view of ["front", "side"]) {
          const dir = join(cap.dir, kind, view);
          const n = existsSync(dir) ? readdirSync(dir).filter((f) => /^frame_\d{3,4}\.png$/.test(f)).length : 0;
          out[view] = strip(kind, view, n).map((url, i) => ({ url, tick: (info?.firstTick ?? 0) + i }));
        }
        return { ...out, relativeTo: info?.relativeTo ?? "start" };
      };
      capture = {
        prov: provOf(m), from: cap.from, preTicks: m.playPreTicks ?? 3, restTick: m.restTick ?? 0, trigger: m.trigger ?? null,
        rest, play: { front: playFrames("front"), side: playFrames("side") },
        blendIn: blend("blend-in", m.blendIn), blendOut: blend("blend-out", m.blendOut),
      };
    }
    return { def, skills: skillsPlaying(id), capture, review, reviewFrom: from };
  }

  function mediaFile(id, rel) {
    if (!ANIM_ID_RE.test(id) || !ANIM_MEDIA_RE.test(rel)) return null;
    const cap = captureOf(id);
    if (!cap) return null;
    const f = join(cap.dir, rel);
    return inside(cap.dir, f) ? f : null;
  }

  /** Validate + rev check + write. Returns {status, body}; the caller owns HTTP and the atomic file write. */
  function applyReview(id, incoming, who) {
    if (!definition(id)) return { status: 404, body: { error: "no such animation" } };
    if (!incoming || incoming.id !== id) return { status: 400, body: { error: "review.id must equal the URL id" } };
    const problems = validateReview(incoming);
    if (problems.length) return { status: 422, body: { error: "invalid review", problems } };
    const cur = reviewOf(id);
    if ((incoming.rev ?? 0) !== (cur.review.rev ?? 0)) return { status: 409, body: { error: "stale rev: reload (someone else saved)", rev: cur.review.rev ?? 0 } };
    const next = {
      id, rev: (cur.review.rev ?? 0) + 1, status: incoming.status, comment: incoming.comment,
      pins: incoming.pins.map((p) => ({ tick: p.tick, text: p.text })).sort((a, b) => a.tick - b.tick),
      editLog: [...(cur.review.editLog || []), { who: String(who || "anonymous").slice(0, 40), at: new Date().toISOString(), summary: reviewDiff(cur.review, { ...incoming, pins: incoming.pins }) }].slice(-200),
    };
    return { status: 200, body: { ok: true, review: next }, file: cur.file, next };
  }

  return { ids, list, detail, mediaFile, applyReview, reviewOf };
}
