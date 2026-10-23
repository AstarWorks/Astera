// skill-compare server: static app + case data + cached media. Bun only (Bun.serve / Bun.build).
//   bun server.mjs                 -> http://0.0.0.0:8097
//   PORT=9000 HOST=127.0.0.1 bun server.mjs
// It only ever reads from: public/, src/ (bundled), data/, cache/, and the three Astera build dirs below.
import { existsSync, readdirSync, readFileSync, renameSync, statSync, writeFileSync, mkdirSync } from "node:fs";
import { join, resolve, sep, dirname } from "node:path";
import { networkInterfaces } from "node:os";
import { fileURLToPath } from "node:url";
import { CASE_ID_RE, validateCase, diffSummary } from "./src/timeline.js";
import { makeSources } from "./lib/sources.mjs";
import { buildIndex } from "./lib/weapons.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const MAX_BODY = 1_000_000;

export function makeHandler(opts = {}) {
  const root = opts.root ?? HERE;
  const repo = opts.repo ?? resolve(HERE, "..", "..");
  const publicDir = join(root, "public");
  const vfxShotDir = opts.vfxShotDir ?? process.env.SKILL_COMPARE_VFX_SHOT ?? join(repo, "build", "vfx-shot");
  const S = makeSources({ root, repo, vfxShotDir, mediaDirs: opts.mediaDirs, worktrees: opts.worktrees ?? true });
  const vfxFind = S.vfxFind;
  const publishedDir = opts.publishedDir ?? process.env.SKILL_COMPARE_PUBLISHED ?? join(repo, "build", "skill-compare-published");
  const viewerDir = opts.viewerDir ?? join(repo, "tools", "skill-viewer", "build", "skill-frames");
  const tracesDir = opts.tracesDir ?? join(repo, "tools", "game-sim", "build", "sim-traces");

  const json = (o, status = 200) =>
    new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
  const err = (status, msg) => json({ error: msg }, status);
  const readJson = (p) => JSON.parse(readFileSync(p, "utf8"));
  const inside = (base, p) => { const r = resolve(p); return r === resolve(base) || r.startsWith(resolve(base) + sep); };

  function listCases() {
    return [...S.allCases().values()].map(({ id, c, src }) => ({
      id, videoId: c.videoId, skill: c.skill, start_s: c.start_s, end_s: c.end_s,
      hits: (c.segments || []).filter((x) => x.kind === "hit").length,
      media: !!S.cacheFor(id, src), edits: (c.editLog || []).length, from: { worktree: src.name, branch: src.branch },
    }));
  }

  /** Capture provenance shown as a badge on the page (and written to the brief manifest by scripts/brief.py). */
  const provOf = (m) => ({ branch: m.branch ?? null, commit: m.implementationCommit ?? null, capturedAt: m.capturedAt ?? null,
    fov: m.fov ?? null, fovMode: m.fovMode ?? null, resolution: m.resolution ?? null, view: m.view ?? null,
    repeated: m.captureQuality?.maxRepeatedFrames ?? null, reviewRequired: !!m.captureQuality?.reviewRequired });

  function mcInfo(skill, referenceRevision, contentRevision) {
    const info = { skill, sources: {}, hitTicks: [], castTickInTrace: 5 };
    if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(skill || "")) return info;
    for (const view of ["compare", "input", "caster", "opponent", "side"]) {
      const pointer = join(publishedDir, skill, view, "current.json");
      if (existsSync(pointer)) {
        try {
          const { revision, extension, meta } = readJson(pointer);
          if (!/^[a-f0-9]{64}$/.test(revision) || meta.captureQuality?.invalid ||
              (referenceRevision && meta.referenceRevision !== referenceRevision) ||
              (contentRevision && meta.contentRevision !== contentRevision)) continue;
          info.sources["vfx-" + view] = { kind: "frames", base: `/mc/vfx/${skill}/${view}/`,
            count: meta.frames, castFrame: Math.round((meta.cast_tick_ms ?? 0) / 50), extension, revision, prov: { ...provOf(meta), published: true } };
        } catch { /* Never fall back to a partially overwritten source after publication. */ }
        continue;
      }
      const base = vfxFind(skill, view);
      const d = base && join(base, skill, view);
      if (base) {
        if (referenceRevision) {
          try { const m = readJson(join(d, "meta.json")); if (m.captureQuality?.invalid || m.referenceRevision !== referenceRevision || (contentRevision && m.contentRevision !== contentRevision)) continue; } catch { continue; }
        }
        const n = readdirSync(d).filter((f) => /^frame_\d{3,4}\.png$/.test(f)).length;
        const webpReady = readdirSync(d).filter((f) => /^frame_\d{3,4}\.png$/.test(f)).every((f) => existsSync(join(d, f.replace(/\.png$/, ".webp"))));
        let castFrame = 0, prov = { published: false };  // compare: frames before the cast (meta.json cast_tick_ms)
        try { const m = readJson(join(d, "meta.json")); castFrame = Math.round((m.cast_tick_ms ?? 0) / 50); prov = { ...provOf(m), published: false }; } catch { /* no meta: frame 0 = cast */ }
        if (n) info.sources["vfx-" + view] = { kind: "frames", base: `/mc/vfx/${skill}/${view}/`, count: n, castFrame, prov,
          extension: webpReady ? "webp" : "png",
          revision: String(statSync(existsSync(join(d, "meta.json")) ? join(d, "meta.json") : d).mtimeMs) };
      }
    }
    if (referenceRevision) return info;
    const kd = join(viewerDir, skill, "frames");
    if (existsSync(kd)) {
      const keys = readdirSync(kd).map((f) => /^key-(\d+)\.png$/.exec(f)).filter(Boolean).map((m) => +m[1]).sort((a, b) => a - b);
      if (keys.length) info.sources["viewer"] = { kind: "keys", base: `/mc/viewer/${skill}/`, keys };
    }
    const tp = join(tracesDir, `showcase-${skill}.json`);
    if (existsSync(tp)) {
      try {
        const t = readJson(tp);
        info.hitTicks = [...new Set((t.ticks || []).filter((k) => (k.damage || []).length).map((k) => k.tick))];
        info.traceHitsAreAbsolute = true;
      } catch { /* unreadable trace: no markers */ }
    }
    const mp = join(S.vfxDirs().find((b) => existsSync(join(b, skill, "meta.json"))) ?? vfxShotDir, skill, "meta.json");
    if (existsSync(mp)) {
      try { const m = readJson(mp); if (Array.isArray(m.hitTicks)) { info.hitTicks = m.hitTicks; info.traceHitsAreAbsolute = false; } } catch { /* ignore */ }
    }
    return info;
  }

  function getCase(id) {
    const rec = S.allCases().get(id);
    if (!rec) return null;
    const { c, src } = rec, dir = join(src.dataDir, id);
    const cdir = S.cacheFor(id, src);
    const ap = cdir && join(cdir, "analysis.json");
    return {
      case: c, from: { worktree: src.name, branch: src.branch },
      weapon: weaponOf(c.skill?.asteraSkillId, c.weapon),
      media: cdir ? { ready: true, ...readJson(join(cdir, "meta.json")), revision: c.referenceRevision || String(c.rev ?? 0), base: `/media/${id}/frames/` } : { ready: false },
      analysis: ap && existsSync(ap) ? readJson(ap) : { waveform: [], waveformStepMs: 10, onsets: [], cuts: [] },
      mc: mcInfo(c.skill?.asteraSkillId, c.referenceRevision, c.contentRevision),
    };
  }

  // breadcrumb target: the roster weapon this skill (or the case's `weapon` field) belongs to
  function weaponOf(skillId, hint) {
    const w = buildIndex({ repo, S, mcInfo }).weapons.find((x) => x.id === hint || x.slots.some((r) => r.skillId === skillId));
    return w ? { id: w.id, ja: w.ja, en: w.en, slot: w.slots.find((r) => r.skillId === skillId)?.tag ?? "" } : null;
  }

  async function saveCase(id, req) {
    if (!(req.headers.get("content-type") || "").startsWith("application/json")) return err(415, "application/json required");
    const text = await req.text();
    if (text.length > MAX_BODY) return err(413, "body too large");
    let body;
    try { body = JSON.parse(text); } catch { return err(400, "invalid JSON"); }
    const { case: incoming, who } = body;
    if (!incoming || incoming.id !== id) return err(400, "case.id must equal the URL id");
    const problems = validateCase(incoming);
    if (problems.length) return json({ error: "invalid case", problems }, 422);
    const rec = S.allCases().get(id);
    if (!rec) return err(404, "no such case");
    const cp = rec.file;
    const before = rec.c;
    if ((incoming.rev ?? 0) !== (before.rev ?? 0)) return json({ error: "stale rev: reload (someone else saved)", rev: before.rev ?? 0 }, 409);
    const next = { ...incoming, rev: (before.rev ?? 0) + 1 };
    next.editLog = [...(before.editLog || []), {
      who: String(who || "anonymous").slice(0, 40),
      at: new Date().toISOString(),
      summary: diffSummary(before, next),
    }].slice(-200);
    writeCaseFile(cp, next);
    return json({ ok: true, case: next });
  }

  function file(path, type) {
    if (!existsSync(path) || !statSync(path).isFile()) return new Response("not found", { status: 404 });
    return new Response(Bun.file(path), { headers: { ...(type ? { "content-type": type } : {}), "cache-control": /\.(png|jpg|webp)$/.test(path) ? "private, max-age=300" : "no-cache" } });
  }

  let bundle = { stamp: 0, text: "" };
  async function appJs() {
    const srcDir = join(root, "src");
    const stamp = Math.max(...readdirSync(srcDir).map((f) => statSync(join(srcDir, f)).mtimeMs), statSync(join(root, "package.json")).mtimeMs);
    if (stamp !== bundle.stamp) {
      const r = await Bun.build({ entrypoints: [join(srcDir, "app.js")], target: "browser", minify: false });
      if (!r.success) return new Response(r.logs.join("\n"), { status: 500 });
      bundle = { stamp, text: await r.outputs[0].text() };
    }
    return new Response(bundle.text, { headers: { "content-type": "text/javascript", "cache-control": "no-store" } });
  }

  return async function handle(req) {
    const url = new URL(req.url);
    const p = url.pathname;
    let m;
    if (p === "/" || p === "/index.html") return file(join(publicDir, "index.html"), "text/html; charset=utf-8");
    if (p === "/case.html" || /^\/case\/[a-z0-9-]+$/.test(p)) return file(join(publicDir, "case.html"), "text/html; charset=utf-8");
    if ((m = /^\/evidence\/([a-z0-9-]+)\.png$/.exec(p))) return file(join(repo, "build", "limbus-review", "pairs-round2", m[1] + ".png"), "image/png");
    if ((m = /^\/report\/([a-z0-9-]+)$/.exec(p))) return file(join(root, "data", m[1] + "-REPORT.md"), "text/plain; charset=utf-8");
    if ((m = /^\/reference\/([a-z0-9-]+)\/([a-z0-9-]+\.(?:json|md))$/.exec(p))) return file(join(root, "data", "reference-analysis", m[1], m[2]), m[2].endsWith("json") ? "application/json" : "text/plain; charset=utf-8");
    if (p === "/app.js") return appJs();
    if (p === "/index.js") return file(join(publicDir, "index.js"), "text/javascript");
    if (p === "/style.css") return file(join(publicDir, "style.css"), "text/css");
    if (p === "/api/cases" && req.method === "GET") return json(listCases());
    if (p === "/api/index" && req.method === "GET") return json(buildIndex({ repo, S, mcInfo }));
    if ((m = /^\/icon\/([a-z0-9-]+)\.png$/.exec(p))) return file(join(repo, "content", "textures", "generated", "items", m[1] + ".png"), "image/png");
    if ((m = /^\/api\/cases\/([a-z0-9-]+)$/.exec(p))) {
      if (!CASE_ID_RE.test(m[1])) return err(400, "bad id");
      if (req.method === "GET") { const c = getCase(m[1]); return c ? json(c) : err(404, "no such case"); }
      if (req.method === "POST") { return saveCase(m[1], req); }
      return err(405, "method not allowed");
    }
    if ((m = /^\/media\/([a-z0-9-]+)\/frames\/(frame_\d{5}\.jpg)$/.exec(p))) {
      const cdir = S.cacheFor(m[1], S.allCases().get(m[1])?.src);
      if (!cdir) return err(404, "no media");
      const f = join(cdir, "frames", m[2]);
      return inside(cdir, f) ? file(f, "image/jpeg") : err(400, "bad path");
    }
    if ((m = /^\/mc\/vfx\/([a-z0-9-]+)\/(compare|input|caster|opponent|side)\/(frame_\d{3,4}\.(?:png|webp))$/.exec(p))) {
      const revision = new URL(req.url).searchParams.get("v");
      if (/^[a-f0-9]{64}$/.test(revision || "")) {
        const f = join(publishedDir, m[1], m[2], revision, m[3]);
        return file(f, f.endsWith("webp") ? "image/webp" : "image/png");
      }
      const base = vfxFind(m[1], m[2]) ?? vfxShotDir;
      const f = join(base, m[1], m[2], m[3]);
      return inside(base, f) ? file(f, f.endsWith("webp") ? "image/webp" : "image/png") : err(400, "bad path");
    }
    if ((m = /^\/mc\/viewer\/([a-z0-9-]+)\/(key-\d+\.png)$/.exec(p))) {
      const f = join(viewerDir, m[1], "frames", m[2]);
      return inside(viewerDir, f) ? file(f, "image/png") : err(400, "bad path");
    }
    return err(404, "not found");
  };
}

export function writeCaseFile(path, c) {
  mkdirSync(dirname(path), { recursive: true });
  const tmp = path + ".tmp";
  writeFileSync(tmp, JSON.stringify(c, null, 2) + "\n");
  renameSync(tmp, path);
}

if (import.meta.main) {
  const port = Number(process.env.PORT || 8097);
  const hostname = process.env.HOST || "0.0.0.0";
  const handle = makeHandler({ repo: process.env.SKILL_COMPARE_REPO || undefined });
  Bun.serve({ port, hostname, fetch: handle });
  const lan = Object.values(networkInterfaces()).flat().filter((i) => i && i.family === "IPv4" && !i.internal).map((i) => i.address);
  console.log(`skill-compare on http://${hostname}:${port}  (LAN: ${lan.map((a) => `http://${a}:${port}`).join(" ") || "-"})`);
}
