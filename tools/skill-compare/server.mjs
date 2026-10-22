// skill-compare server: static app + case data + cached media. Bun only (Bun.serve / Bun.build).
//   bun server.mjs                 -> http://0.0.0.0:8097
//   PORT=9000 HOST=127.0.0.1 bun server.mjs
// It only ever reads from: public/, src/ (bundled), data/, cache/, and the three Astera build dirs below.
import { existsSync, readdirSync, readFileSync, renameSync, statSync, writeFileSync, mkdirSync } from "node:fs";
import { join, resolve, sep, dirname } from "node:path";
import { networkInterfaces } from "node:os";
import { fileURLToPath } from "node:url";
import { CASE_ID_RE, validateCase, diffSummary } from "./src/timeline.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const MAX_BODY = 1_000_000;

export function makeHandler(opts = {}) {
  const root = opts.root ?? HERE;
  const repo = opts.repo ?? resolve(HERE, "..", "..");
  const dataDir = join(root, "data");
  const cacheDir = join(root, "cache");
  const publicDir = join(root, "public");
  const vfxShotDir = opts.vfxShotDir ?? process.env.SKILL_COMPARE_VFX_SHOT ?? join(repo, "build", "vfx-shot");
  const viewerDir = opts.viewerDir ?? join(repo, "tools", "skill-viewer", "build", "skill-frames");
  const tracesDir = opts.tracesDir ?? join(repo, "tools", "game-sim", "build", "sim-traces");

  const json = (o, status = 200) =>
    new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
  const err = (status, msg) => json({ error: msg }, status);
  const readJson = (p) => JSON.parse(readFileSync(p, "utf8"));
  const inside = (base, p) => { const r = resolve(p); return r === resolve(base) || r.startsWith(resolve(base) + sep); };

  function listCases() {
    if (!existsSync(dataDir)) return [];
    return readdirSync(dataDir, { withFileTypes: true })
      .filter((d) => d.isDirectory() && CASE_ID_RE.test(d.name) && existsSync(join(dataDir, d.name, "case.json")))
      .map((d) => {
        const c = readJson(join(dataDir, d.name, "case.json"));
        return {
          id: d.name, videoId: c.videoId, skill: c.skill, start_s: c.start_s, end_s: c.end_s,
          hits: (c.segments || []).filter((s) => s.kind === "hit").length,
          media: existsSync(join(cacheDir, d.name, "meta.json")),
          edits: (c.editLog || []).length,
        };
      });
  }

  function mcInfo(skill) {
    const info = { skill, sources: {}, hitTicks: [], castTickInTrace: 5 };
    if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(skill || "")) return info;
    for (const view of ["compare", "caster", "opponent", "side"]) {
      const d = join(vfxShotDir, skill, view);
      if (existsSync(d)) {
        const n = readdirSync(d).filter((f) => /^frame_\d{3,4}\.png$/.test(f)).length;
        let castFrame = 0;  // compare: frames before the cast (meta.json cast_tick_ms)
        try { castFrame = Math.round((readJson(join(d, "meta.json")).cast_tick_ms ?? 0) / 50); } catch { /* no meta: frame 0 = cast */ }
        if (n) info.sources["vfx-" + view] = { kind: "frames", base: `/mc/vfx/${skill}/${view}/`, count: n, castFrame };
      }
    }
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
    const mp = join(vfxShotDir, skill, "meta.json");
    if (existsSync(mp)) {
      try { const m = readJson(mp); if (Array.isArray(m.hitTicks)) { info.hitTicks = m.hitTicks; info.traceHitsAreAbsolute = false; } } catch { /* ignore */ }
    }
    return info;
  }

  function getCase(id) {
    const dir = join(dataDir, id);
    const cp = join(dir, "case.json");
    if (!existsSync(cp)) return null;
    const c = readJson(cp);
    const intentPath = join(dir, c.intent?.file ?? "intent.json");
    const intent = inside(dir, intentPath) && existsSync(intentPath) ? readJson(intentPath) : null;
    const mp = join(cacheDir, id, "meta.json"), ap = join(cacheDir, id, "analysis.json");
    return {
      case: c, intent,
      media: existsSync(mp) ? { ready: true, ...readJson(mp), base: `/media/${id}/frames/` } : { ready: false },
      analysis: existsSync(ap) ? readJson(ap) : { waveform: [], waveformStepMs: 10, onsets: [], cuts: [] },
      mc: mcInfo(c.skill?.asteraSkillId),
    };
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
    const cp = join(dataDir, id, "case.json");
    const before = readJson(cp);
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
    return new Response(Bun.file(path), { headers: type ? { "content-type": type } : {} });
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
    if (p === "/app.js") return appJs();
    if (p === "/index.js") return file(join(publicDir, "index.js"), "text/javascript");
    if (p === "/style.css") return file(join(publicDir, "style.css"), "text/css");
    if (p === "/api/cases" && req.method === "GET") return json(listCases());
    if ((m = /^\/api\/cases\/([a-z0-9-]+)$/.exec(p))) {
      if (!CASE_ID_RE.test(m[1])) return err(400, "bad id");
      if (req.method === "GET") { const c = getCase(m[1]); return c ? json(c) : err(404, "no such case"); }
      if (req.method === "POST") { if (!existsSync(join(dataDir, m[1], "case.json"))) return err(404, "no such case"); return saveCase(m[1], req); }
      return err(405, "method not allowed");
    }
    if ((m = /^\/media\/([a-z0-9-]+)\/frames\/(frame_\d{5}\.jpg)$/.exec(p))) {
      const f = join(cacheDir, m[1], "frames", m[2]);
      return inside(cacheDir, f) ? file(f, "image/jpeg") : err(400, "bad path");
    }
    if ((m = /^\/mc\/vfx\/([a-z0-9-]+)\/(compare|caster|opponent|side)\/(frame_\d{3,4}\.png)$/.exec(p))) {
      const f = join(vfxShotDir, m[1], m[2], m[3]);
      return inside(vfxShotDir, f) ? file(f, "image/png") : err(400, "bad path");
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
  const handle = makeHandler();
  Bun.serve({ port, hostname, fetch: handle });
  const lan = Object.values(networkInterfaces()).flat().filter((i) => i && i.family === "IPv4" && !i.internal).map((i) => i.address);
  console.log(`skill-compare on http://${hostname}:${port}  (LAN: ${lan.map((a) => `http://${a}:${port}`).join(" ") || "-"})`);
}
