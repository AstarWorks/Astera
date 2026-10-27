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
    const num = (s) => (s?.match(/-?\d+(?:\.\d+)?/g) ?? []).slice(0, 3).map(Number);
    const spawns = [], pos = [];
    for (const l of text.split("\n")) {
      let m;
      if ((m = /\[vfx-trace\] spawn (\S+) (\S+) age=(\d+) planSpawn=(-?\d+) tick=(\d+)/.exec(l))) spawns.push({ vfx: m[1], element: m[2], age: +m[3], planSpawn: +m[4], tick: +m[5] });
      else if ((m = /\[vfx-trace\] pos (\S+) age=(\d+) tick=(\d+) caster=(.*?) target=(.*)$/.exec(l))) { const c = num(m[4]), t = num(m[5]); if (c.length === 3) pos.push({ age: +m[2], c, t: t.length === 3 ? t : null }); }
    }
    const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
    const out = { spawns: spawns.length, fxSpawnTicks: [...new Set(spawns.map((s) => s.planSpawn))].sort((a, b) => a - b) };
    if (pos.length) {
      const first = pos[0], last = pos[pos.length - 1];
      out.casterTravelBlocks = +dist(first.c, last.c).toFixed(2);
      out.casterMaxFromStartBlocks = +Math.max(...pos.map((p) => dist(first.c, p.c))).toFixed(2);
      const withT = [...pos].reverse().find((p) => p.t);
      if (withT) out.finalGapBlocks = +dist(withT.c, withT.t).toFixed(2);
    }
    return out;
  }

  function defaultTicks(id) {
    const bs = beatSheetOf(id);
    const t = new Set();
    for (const r of bs?.rows ?? []) if (r.ticks?.[0] != null) t.add(r.ticks[0]);
    for (const h of bs?.summary?.hits ?? []) t.add(h);
    const all = [...t].sort((a, b) => a - b);
    if (all.length <= 10) return all;
    const out = []; for (let i = 0; i < 10; i++) out.push(all[Math.round((i * (all.length - 1)) / 9)]);
    return [...new Set(out)];
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
          const fr = Math.min(Math.max(f, 0), n - 1), name = `frame_${String(fr).padStart(5, "0")}.jpg`;
          return { tick, frame: fr, refMs: ms, path: join(cdir, "frames", name), url: `/media/${id}/frames/${name}?v=${encodeURIComponent(c.referenceRevision || String(c.rev ?? 0))}`, note: fr !== f ? "clamped" : undefined };
        });
        const bg = cdir && n ? Array.from({ length: Math.min(16, n) }, (_, i) => join(cdir, "frames", `frame_${String(Math.round((i * (n - 1)) / Math.max(1, Math.min(16, n) - 1))).padStart(5, "0")}.jpg`)) : [];
        rows.push({ id: "ref", view, round: "ref", label: "ref (original)", sub: [meta ? `${fps} fps, ${meta.width}x${meta.height}` : "no cache", `cast ${cast} ms`], frames, bg, source: { kind: "reference", fps, frames: n, videoId: c.videoId, startS: c.start_s } });
        continue;
      }
      for (const round of rounds) {
        const r = pickRevision(skill, view, round);
        const rid = `${view}:${round}`;
        if (!r) { rows.push({ id: rid, view, round, label: `${view} ${round}`, sub: ["not published"], frames: ticks.map((tick) => ({ tick, note: "no such revision" })), bg: [], source: { kind: "missing" } }); continue; }
        const dir = join(publishedDir, skill, view, r.rev), fps = r.meta.fps ?? 20, k = fps / 20, castFrame = Math.round(((r.meta.cast_tick_ms ?? 0) * fps) / 1000), first = r.meta.firstTick ?? 0, count = r.meta.frames ?? 0;
        const frames = ticks.map((tick) => {
          const f = Math.floor(tick * k + 1e-6) + castFrame - Math.round(first * k);
          if (!count) return { tick, frame: null, note: "no frames" };
          const fr = Math.min(Math.max(f, 0), count - 1), name = `frame_${String(fr).padStart(3, "0")}.${r.extension}`;
          return { tick, frame: fr, path: join(dir, name), url: `/mc/vfx/${skill}/${view}/${name}?v=${r.rev}`, note: fr !== f ? `clamped (only ${count} frames)` : undefined };
        });
        const sha = shaOf(r.meta), isCurrent = r.rev === revisions(skill, view).current;
        let freshness = { state: "archived" };
        if (isCurrent) {
          freshness = F.judge(skill, { sha, capturedAt: r.meta.capturedAt ?? null, kind: view }, dev);
          const fl = failureOf(skill, view, { auto: { sha } });
          if (fl?.old) freshness = { ...freshness, state: "failed", reason: fl.reason, failedSha: fl.sha };
        }
        const names = readdirSafe(dir), bgNames = names.filter((n) => /^frame_\d+\./.test(n)).sort();
        rows.push({
          id: rid, view, round, label: `${view} ${round === "current" ? "cur" : round}`.slice(0, 22),
          sub: [`rev ${r.rev.slice(0, 8)}  sha ${sha ? sha.slice(0, 7) : "?"}`, (r.meta.capturedAt ?? "").slice(0, 16).replace("T", " ") + (isCurrent ? "  current" : "")],
          frames, bg: bgNames.map((n) => join(dir, n)),
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
    return { id, skill, ticks, views, rounds, rows, cast, dev, title: `${id}  dev ${dev?.sha?.slice(0, 7) ?? "?"}  ticks ${ticks.join(",")}` };
  }
  const readdirSafe = (d) => { try { return readdirSync(d); } catch { return []; } };

  function run(p, withSheet) {
    const spec = { out: null, cell: [288, 162], title: p.title, ticks: p.ticks, rows: p.rows.map((r) => ({ id: r.id, label: r.label, sub: r.sub, pair: r.pair, frames: r.frames.map((f) => ({ tick: f.tick, path: f.path ?? null, note: f.note })), bg: r.bg })) };
    const key = createHash("sha1").update(JSON.stringify(spec)).digest("hex").slice(0, 20);
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
    const qs = new URLSearchParams({ ticks: p.ticks.join(","), views: p.views.join(","), rounds: p.rounds.join(",") }).toString();
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
    return {
      id, skill: p.skill, dev: p.dev, ticks: p.ticks, views: p.views, rounds: p.rounds, castMs: p.cast,
      mapping: { mc: "frame = tick*fps/20 + castFrame - firstTick*fps/20 (20 fps: frame N = plan tick N)", ref: "frame = floor((castMs + tick*50) * fps / 1000)" },
      metricsDoc: "fx_pct = emissive pixels %; mean_luma/peak_luma(p99) 0-255; mover_h_pct = largest blob differing from the clip median, % of frame height (null on camera moves). Flags: fx/luma down >20% or mover height off >20% vs the next older round.",
      sources: p.rows.map((r) => ({ key: r.id, view: r.view, round: r.round, ...r.source })),
      ticksData: ticks, regression: regressions.length > 0, regressions,
      sheetUrl: `/api/cases/${id}/sheet.png?${qs}`,
    };
  }
  async function sheet(id, q) {
    const p = plan(id, q);
    if (p.error) return p;
    const res = await run(p, true);
    return { file: res.out };
  }
  return { compare, sheet };
}
