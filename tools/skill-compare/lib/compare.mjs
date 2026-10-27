// The one standard comparison API (GET /api/cases/<id>/compare, /sheet.png): reviewers, directors, the manager and the case page all
// look at the SAME frames at the SAME ticks, with the SAME numbers (scripts/compare.py), instead of hand-built crops.
//
// Tick mapping (case "mc.offset_ms" included, see src/timeline.js):
//   MC capture (side/top/compare/preview-side...)  frame = tick * fps/20 + castFrame - firstTick * fps/20   (20 fps: frame N = plan tick N)
//   reference video                                 frame = floor((castMs + tick*50) * fps / 1000)        (fps from the case's media cache, 30)
// Rounds: "current" = what current.json points to; "prev" / "prev2".. = the revision published before it (by capturedAt);
// a hex prefix (>=6) of a revision names it explicitly. Rows are ordered newest -> oldest; each row is compared against the next older
// round of the same view.
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync } from "node:fs";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { castMs } from "../src/timeline.js";
import { analyticHeights, refHeightAt } from "./framing.mjs";

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), "..", "scripts", "compare.py");
const REV_RE = /^[a-f0-9]{64}$/;
const MC_VIEWS = new Set(["compare", "input", "caster", "opponent", "side", "preview-side", "front", "back", "right", "top", "high", "near", "preview"]);
const MAX_TICKS = 12, MAX_ROWS = 16;
const readJson = (p) => { try { return JSON.parse(readFileSync(p, "utf8")); } catch { return null; } };
const mtime = (p) => { try { return statSync(p).mtimeMs; } catch { return 0; } };

export function makeCompare({ publishedDir, getRec, cacheFor, F, failureOf, beatSheetOf }) {
  const outDir = join(tmpdir(), "skill-compare-sheets");
  mkdirSync(outDir, { recursive: true });
  const cache = new Map();

  /** published revisions of one view, oldest -> newest, with their manifest meta. */
  function revisions(skill, view) {
    const base = join(publishedDir, skill, view);
    let names = [];
    try { names = readdirSync(base).filter((n) => REV_RE.test(n)); } catch { return { revs: [], current: null }; }
    const current = readJson(join(base, "current.json"))?.revision ?? null;
    const revs = names.map((rev) => {
      const m = readJson(join(base, rev, "manifest.json")) ?? (rev === current ? readJson(join(base, "current.json")) : null);
      const meta = m?.meta ?? {};
      return { rev, extension: m?.extension ?? "png", meta, at: Date.parse(meta.capturedAt ?? "") || mtime(join(base, rev)) };
    }).sort((a, b) => a.at - b.at);
    return { revs, current };
  }
  function pickRevision(skill, view, round) {
    const { revs, current } = revisions(skill, view);
    const ci = Math.max(0, revs.findIndex((r) => r.rev === current));
    if (/^current$/.test(round)) return revs[ci] ?? null;
    const pm = /^prev(\d*)$/.exec(round);
    if (pm) return revs[ci - Math.max(1, +(pm[1] || 1))] ?? null;
    if (/^[a-f0-9]{6,64}$/.test(round)) return revs.find((r) => r.rev.startsWith(round)) ?? null;
    return null;
  }
  const shaOf = (meta) => meta.auto?.sha ?? meta.implementationCommit ?? meta.codeRevision ?? null;

  /** [vfx-trace] lines of the capture (the farm saves the client-visible server log slice next to the frames as trace.log). */
  function traceOf(dir) {
    let text = "";
    try { text = readFileSync(join(dir, "trace.log"), "utf8"); } catch { return null; }
    const num = (s) => [...(s?.matchAll(/[xyz]=(-?\d+(?:\.\d+)?)/g) ?? [])].map((m) => +m[1]);
    const spawns = [], pos = [];
    let castTick = null;  // the first play line = cast tick 0; spawn planSpawn/age are relative to each effect's own start
    for (const l of text.split(/\r?\n/)) {
      let m;
      if (castTick == null && (m = /\[vfx-trace\] play \S+ tick=(\d+)/.exec(l))) castTick = +m[1];
      if ((m = /\[vfx-trace\] spawn (\S+) (\S+) age=(\d+) planSpawn=(-?\d+) tick=(\d+)/.exec(l))) spawns.push({ vfx: m[1], element: m[2], age: +m[3], planSpawn: +m[4], tick: +m[5] });
      else if ((m = /\[vfx-trace\] pos (\S+) age=(\d+) tick=(\d+) caster=(.*?) target=(.*)$/.exec(l))) { const c = num(m[4]), t = num(m[5]); if (c.length === 3) pos.push({ age: +m[2], c, t: t.length === 3 ? t : null }); }
    }
    const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
    const out = { spawns: spawns.length, fxSpawnTicks: [...new Set(spawns.map((s) => s.tick - (castTick ?? 0)))].sort((a, b) => a - b) };
    if (pos.length) {
      const first = pos[0], last = pos[pos.length - 1];
      out.casterTravelBlocks = +dist(first.c, last.c).toFixed(2);
      out.casterMaxFromStartBlocks = +Math.max(...pos.map((p) => dist(first.c, p.c))).toFixed(2);
      const withT = [...pos].reverse().find((p) => p.t);
      if (withT) out.finalGapBlocks = +dist(withT.c, withT.t).toFixed(2);
    }
    return out;
  }

  /** Default ticks = the beat sheet's contact ticks: tick 0 (pre-cast framing), each hit +-2 ticks (no hits: every beat start). */
  function defaultTicks(id) {
    const bs = beatSheetOf(id);
    const hits = [...new Set([...(bs?.summary?.hits ?? []), ...(bs?.rows ?? []).map((r) => r.hit).filter((h) => h != null)])].sort((a, b) => a - b);
    const t = new Set([0]);
    if (hits.length) for (const h of hits) { t.add(Math.max(0, h - 2)); t.add(h); t.add(h + 2); }
    else for (const r of bs?.rows ?? []) if (r.ticks?.[0] != null) t.add(r.ticks[0]);
    const all = [...t].sort((a, b) => a - b);
    return all.length <= MAX_TICKS ? all : [0, ...hits].sort((a, b) => a - b).slice(0, MAX_TICKS);
  }

  /** Resolve query params into the row list (no image work yet). */
  function plan(id, q) {
    const rec = getRec(id);
    if (!rec) return { error: "no such case", status: 404 };
    const c = rec.c, skill = c.skill?.asteraSkillId;
    if (!skill) return { error: "case has no skill", status: 400 };
    let ticks = (q.get("ticks") ?? "").split(",").map((s) => s.trim()).filter(Boolean).map(Number);
    if (ticks.some((t) => !Number.isInteger(t) || t < 0 || t > 400)) return { error: "ticks: comma separated integers 0-400", status: 400 };
    if (!ticks.length) ticks = defaultTicks(id);
    ticks = [...new Set(ticks)].sort((a, b) => a - b).slice(0, MAX_TICKS);
    const views = (q.get("views") ?? "ref,compare,side,top,preview-side").split(",").map((s) => s.trim()).filter(Boolean);
    const rounds = (q.get("rounds") ?? "current,prev").split(",").map((s) => s.trim()).filter(Boolean);
    for (const v of views) if (v !== "ref" && !MC_VIEWS.has(v)) return { error: `unknown view '${v}'`, status: 400 };
    for (const r of rounds) if (!/^(current|prev\d*|[a-f0-9]{6,64})$/.test(r)) return { error: `bad round '${r}' (current | prev | prev2 | revision prefix)`, status: 400 };
    // viewing: zoom=native pastes source pixels 1:1 (never resampled; default whenever crop is given), zoom=fit is the downscaled overview.
    // crop = x0,y0,x1,y1 as fractions of the frame (same window on every row) or "auto" (a window around the caster + fx of each row).
    const cropQ = (q.get("crop") ?? "").trim();
    let crop = null;
    if (cropQ === "auto") crop = "auto";
    else if (cropQ) {
      crop = cropQ.split(",").map(Number);
      if (crop.length !== 4 || crop.some((x) => !Number.isFinite(x) || x < 0 || x > 1) || crop[2] - crop[0] < 0.02 || crop[3] - crop[1] < 0.02) return { error: "crop: 'auto' or x0,y0,x1,y1 fractions 0-1 (x1>x0, y1>y0)", status: 400 };
    }
    const zoom = (q.get("zoom") ?? (crop ? "native" : "fit")).trim();
    if (zoom !== "native" && zoom !== "fit") return { error: "zoom: native | fit", status: 400 };
    const dev = F.dev();
    const cast = castMs(c);
    const rows = [];
    for (const view of views) {
      if (view === "ref") {
        const cdir = cacheFor(id, rec.src), meta = cdir && readJson(join(cdir, "meta.json"));
        const fps = meta?.fps ?? 30, n = meta?.frameCount ?? 0;
        const frames = ticks.map((tick) => {
          const ms = cast + tick * 50, f = Math.floor((ms * fps) / 1000 + 1e-6);
          if (!cdir || !n) return { tick, frame: null, note: "no reference frames" };
          const fr = Math.min(Math.max(f, 0), n - 1), name = `frame_${String(fr).padStart(5, "0")}.png`;
          return { tick, frame: fr, refMs: ms, path: join(cdir, "frames", name), url: `/media/${id}/frames/${name}?v=${encodeURIComponent(c.referenceRevision || String(c.rev ?? 0))}`, note: fr !== f ? "clamped" : undefined };
        });
        const bg = cdir && n ? Array.from({ length: Math.min(16, n) }, (_, i) => join(cdir, "frames", `frame_${String(Math.round((i * (n - 1)) / Math.max(1, Math.min(16, n) - 1))).padStart(5, "0")}.png`)) : [];
        const k0 = cdir && n ? join(cdir, "frames", `frame_${String(Math.min(Math.max(Math.floor((cast * fps) / 1000 + 1e-6), 0), n - 1)).padStart(5, "0")}.png`) : null;
        rows.push({ id: "ref", view, round: "ref", keyPath: k0, hue: c.caster_key?.ref ?? null, label: "ref (original)", sub: [meta ? `${fps} fps, ${meta.source?.codec ?? "?"} ${meta.source?.bitrateKbps ?? "?"} kbps` : "no cache", `cast ${cast} ms`], frames, bg, source: { kind: "reference", fps, frames: n, videoId: c.videoId, startS: c.start_s, frameExt: "png", video: meta?.source ?? null } });
        continue;
      }
      for (const round of rounds) {
        const r = pickRevision(skill, view, round);
        const rid = `${view}:${round}`;
        if (!r) { rows.push({ id: rid, view, round, keyPath: null, hue: null, label: `${view} ${round}`, sub: ["not published"], frames: ticks.map((tick) => ({ tick, note: "no such revision" })), bg: [], source: { kind: "missing" } }); continue; }
        const dir = join(publishedDir, skill, view, r.rev), fps = r.meta.fps ?? 20, k = fps / 20, castFrame = Math.round(((r.meta.cast_tick_ms ?? 0) * fps) / 1000), first = r.meta.firstTick ?? 0, count = r.meta.frames ?? 0;
        const frames = ticks.map((tick) => {
          const f = Math.floor(tick * k + 1e-6) + castFrame - Math.round(first * k);
          if (!count) return { tick, frame: null, note: "no frames" };
          const fr = Math.min(Math.max(f, 0), count - 1), name = `frame_${String(fr).padStart(3, "0")}.${r.extension}`;
          return { tick, frame: fr, path: join(dir, name), url: `/mc/vfx/${skill}/${view}/${name}?v=${r.rev}`, note: fr !== f ? `clamped (only ${count} frames)` : undefined };
        });
        const f0 = Math.min(Math.max(castFrame - Math.round(first * k), 0), Math.max(count - 1, 0)), keyPath = count ? join(dir, `frame_${String(f0).padStart(3, "0")}.${r.extension}`) : null;
        const sha = shaOf(r.meta), isCurrent = r.rev === revisions(skill, view).current;
        let freshness = { state: "archived" };
        if (isCurrent) {
          freshness = F.judge(skill, { sha, capturedAt: r.meta.capturedAt ?? null, kind: view }, dev);
          const fl = failureOf(skill, view, { auto: { sha } });
          if (fl?.old) freshness = { ...freshness, state: "failed", reason: fl.reason, failedSha: fl.sha };
        }
        const names = readdirSafe(dir), bgNames = names.filter((n) => /^frame_\d+\./.test(n)).sort();
        rows.push({
          id: rid, view, round, keyPath, hue: c.caster_key?.mc ?? null, label: `${view} ${round === "current" ? "cur" : round}`.slice(0, 22),
          sub: [`rev ${r.rev.slice(0, 8)}  sha ${sha ? sha.slice(0, 7) : "?"}`, (r.meta.capturedAt ?? "").slice(0, 16).replace("T", " ") + (isCurrent ? "  current" : "")],
          frames, bg: bgNames.map((n) => join(dir, n)), cap: { dir, meta: r.meta },
          at: r.at, source: { kind: "capture", revision: r.rev, sha, capturedAt: r.meta.capturedAt ?? null, fps, frames: count, extension: r.extension, current: isCurrent, freshness, failure: isCurrent ? failureOf(skill, view, { auto: { sha } }) : null, trace: traceOf(dir) },
        });
      }
    }
    if (rows.length > MAX_ROWS) return { error: `too many rows (${rows.length} > ${MAX_ROWS})`, status: 400 };
    // pair every capture row with the next older capture (by capturedAt) of the same view
    rows.forEach((row, i) => {
      row.pair = null;
      if (row.view === "ref" || row.source.kind !== "capture") return;
      let best = -1;
      rows.forEach((o, j) => { if (j !== i && o.view === row.view && o.source.kind === "capture" && o.at < row.at && (best < 0 || o.at > rows[best].at)) best = j; });
      if (best >= 0) row.pair = best;
    });
    return { id, skill, ticks, views, rounds, rows, cast, dev, crop, zoom, title: `${id}  dev ${dev?.sha?.slice(0, 7) ?? "?"}  ticks ${ticks.join(",")}` };
  }
  const readdirSafe = (d) => { try { return readdirSync(d); } catch { return []; } };

  function run(p, withSheet) {
    const spec = { out: null, cell: [288, 162], zoom: p.zoom, crop: p.crop, title: p.title, ticks: p.ticks, rows: p.rows.map((r) => ({ id: r.id, label: r.label, sub: r.sub, pair: r.pair, keyPath: r.keyPath ?? null, hue: r.hue ?? null, frames: r.frames.map((f) => ({ tick: f.tick, path: f.path ?? null, note: f.note })), bg: r.bg })) };
    const key = createHash("sha1").update(String(mtime(SCRIPT))).update(JSON.stringify(spec)).digest("hex").slice(0, 20);
    const out = join(outDir, key + ".png");
    const hit = cache.get(key);
    if (hit && (!withSheet || existsSync(out))) return Promise.resolve({ ...hit, key, out });
    spec.out = out;
    return new Promise((resolve, reject) => {
      const child = spawn("python3", ["-I", SCRIPT], { stdio: ["pipe", "pipe", "pipe"] });
      let so = "", se = "";
      child.stdout.on("data", (d) => (so += d));
      child.stderr.on("data", (d) => (se += d));
      child.on("close", (code) => {
        if (code !== 0) return reject(new Error("compare.py failed: " + se.slice(-400)));
        try { const r = JSON.parse(so); cache.set(key, r); if (cache.size > 60) cache.delete(cache.keys().next().value); resolve({ ...r, key, out }); } catch (e) { reject(e); }
      });
      child.stdin.end(JSON.stringify(spec));
    });
  }

  /** JSON body of /compare. */
  async function compare(id, q) {
    const p = plan(id, q);
    if (p.error) return p;
    const res = await run(p, false);
    const qp = { ticks: p.ticks.join(","), views: p.views.join(","), rounds: p.rounds.join(",") };
    if (p.crop) qp.crop = p.crop === "auto" ? "auto" : p.crop.join(",");
    if (p.zoom === "native") qp.zoom = "native";
    const qs = new URLSearchParams(qp).toString();
    const regressions = [];
    const ticks = p.ticks.map((tick, ci) => {
      const cells = {};
      p.rows.forEach((row, ri) => {
        const cell = res.rows[ri].cells[ci], f = row.frames[ci];
        (cells[row.view] ??= {})[row.round] = { frame: f.frame ?? null, url: f.url ?? null, note: f.note ?? null, refMs: f.refMs, metrics: cell.metrics, delta: cell.delta, flags: cell.flags };
        if (cell.flags.length) regressions.push({ tick, view: row.view, round: row.round, vs: p.rows[row.pair]?.round, flags: cell.flags });
      });
      return { tick, cells };
    });
    // framing parity (round gate 0.5x-2x): MC = analytic caster height from the capture camera + trace caster position; ref = annotated
    // case.json ref_caster_height_pct. The key-colour bbox (caster_bbox_pct) stays in cell metrics as a debug number only.
    const rec = getRec(id), intent = readJson(join(dirname(rec.file), rec.c.intent?.file ?? "intent.json"));
    const ak = (intent?.actors ?? []).filter((a) => a.keys?.length), ex = (n) => ak.find((a) => a.id === n)?.keys[0].pos[0];
    const distance = ex("enemy") != null && ex("caster") != null ? +(ex("enemy") - ex("caster")).toFixed(2) : null;
    const annot = rec.c.ref_caster_height_pct ?? null;
    const med = (a) => { const v = a.filter((x) => x != null).sort((x, y) => x - y); return v.length ? v[Math.floor(v.length / 2)] : null; };
    const refPer = Object.fromEntries(p.ticks.map((t) => [t, refHeightAt(annot, t)]));
    const framing = p.rows.map((r, ri) => {
      const debug = med(res.rows[ri].cells.map((c) => c.metrics?.caster_bbox_pct));
      const dbg = { debugHueBboxPct: debug, casterKey: res.rows[ri].casterKey };
      if (r.view === "ref") {
        const vals = Object.values(refPer).filter((x) => x != null);
        return { key: r.id, casterHeightPct: med(vals), perTick: refPer, source: annot ? "annotated (case.json ref_caster_height_pct)" : null, error: annot ? null : "case.json has no ref_caster_height_pct {tick: % of frame height}", ...dbg };
      }
      const h = analyticHeights({ view: r.view, meta: r.cap?.meta, intent, dir: r.cap?.dir, ticks: p.ticks, distance });
      if (h.error) return { key: r.id, casterHeightPct: null, ratioToRef: null, withinTolerance: null, error: h.error, ...dbg };
      const per = {}, ratios = [];
      for (const t of p.ticks) {
        const x = h.perTick[t];
        per[t] = x ? { pct: x.pct, onScreen: x.onScreen } : null;
        if (x && refPer[t]) ratios.push(x.pct / refPer[t]);
      }
      const ratio = med(ratios), ratioN = ratios.length;
      return { key: r.id, casterHeightPct: med(Object.values(per).map((x) => x?.pct)), perTick: per, source: `analytic: ${h.method}, fov ${h.fov}, caster ${1.8} blocks`, ratioToRef: ratio == null ? null : +ratio.toFixed(2), ratioTicks: ratioN, withinTolerance: ratio == null ? null : ratio >= 0.5 && ratio <= 2, ...dbg };
    });
    return {
      id, skill: p.skill, dev: p.dev, ticks: p.ticks, views: p.views, rounds: p.rounds, castMs: p.cast, framing,
      mapping: { mc: "frame = tick*fps/20 + castFrame - firstTick*fps/20 (20 fps: frame N = plan tick N)", ref: "frame = floor((castMs + tick*50) * fps / 1000)" },
      metricsDoc: "framing[]: casterHeightPct = caster (1.8 blocks) on-screen height as % of frame height; MC rows analytic (capture camera + trace caster position), ref row annotated (case.json ref_caster_height_pct, nearest tick within 2); ratioToRef = median of per-tick this/ref (valid 0.5-2). debug only: caster_bbox_pct = height of the caster's key-colour mask (compact saturated hair/outfit hues from the pre-cast tick-0 frame of the same source; override with case.json caster_key {ref:[lo,hi],mc:[lo,hi]} in degrees) as % of frame height; fx_pct = emissive pixels new vs the clip median %; mean_luma/peak_luma(p99) 0-255; mover_h_pct informational. Flags: fx/luma down >20% vs the next older round.",
      sources: p.rows.map((r, ri) => ({ key: r.id, view: r.view, round: r.round, ...r.source, nativeWidth: res.rows[ri].native?.[0] ?? null, nativeHeight: res.rows[ri].native?.[1] ?? null, crop: res.rows[ri].crop })),
      view: { zoom: p.zoom, crop: p.crop, note: p.zoom === "native" ? "sheet cells are source pixels 1:1 (cropped, never resampled); smaller sources are padded" : "sheet is a downscaled overview; add zoom=native (and crop=x0,y0,x1,y1 fractions | crop=auto) to see source pixels" },
      ticksData: ticks, regression: regressions.length > 0, regressions,
      sheetUrl: res.sheetError ? null : `/api/cases/${id}/sheet.png?${qs}`, sheetError: res.sheetError ?? null,
    };
  }
  async function sheet(id, q) {
    const p = plan(id, q);
    if (p.error) return p;
    const res = await run(p, true);
    if (res.sheetError) return { error: res.sheetError, status: 400 };
    return { file: res.out };
  }
  return { compare, sheet };
}
