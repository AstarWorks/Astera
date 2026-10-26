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
import { makeAnim, ANIM_ID_RE } from "./lib/anim.mjs";
import { spawnSync } from "node:child_process";
import { contentRevision, publishedRoot } from "./lib/revision.mjs";
import { parseBeatSheet } from "./lib/beatsheet.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const MAX_BODY = 1_000_000;

const SKILL_RE = /^[a-z0-9][a-z0-9-]{0,63}$/;

export function makeHandler(opts = {}) {
  const root = opts.root ?? HERE;
  const repo = opts.repo ?? resolve(HERE, "..", "..");
  const publicDir = join(root, "public");
  const vfxShotDir = opts.vfxShotDir ?? process.env.SKILL_COMPARE_VFX_SHOT ?? join(repo, "build", "vfx-shot");
  const S = makeSources({ root, repo, vfxShotDir, mediaDirs: opts.mediaDirs, worktrees: opts.worktrees ?? true });
  const vfxFind = S.vfxFind;
  const A = makeAnim({ repo, root, S });
  const publishedDir = opts.publishedDir ?? publishedRoot(repo);
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
      media: !!S.cacheFor(id, src) || !!stillsOf(c.skill?.asteraSkillId), edits: (c.editLog || []).length, from: { worktree: src.name, branch: src.branch },
    }));
  }

  /** Capture provenance shown as a badge on the page (and written to the brief manifest by scripts/brief.py). */
  let treeCache = { at: 0, v: null };
  /** content revision of the tree this server runs from (3 s cache); views made from another content revision are STALE. */
  const currentTree = () => {
    if (Date.now() - treeCache.at < 3000) return treeCache.v;
    let v = null; try { v = contentRevision(repo); } catch { /* not a checkout: staleness unknown */ }
    treeCache = { at: Date.now(), v }; return v;
  };
  const provOf = (m) => {
    const cur = currentTree();
    return { branch: m.branch ?? null, commit: m.implementationCommit ?? m.gitRevision ?? null, capturedAt: m.capturedAt ?? null,
      fov: m.fov ?? null, fovMode: m.fovMode ?? null, resolution: m.resolution ?? null, view: m.view ?? null,
      skippedTicks: m.skippedTicks ?? m.captureQuality?.skippedTicks ?? null, deployedSha: m.stack?.deployedCodeRevision ?? null,
      repeated: m.captureQuality?.maxRepeatedFrames ?? null, reviewRequired: !!m.captureQuality?.reviewRequired,
      auto: m.auto ?? null,
      contentRevision: m.contentRevision ?? null, contentDirty: m.contentDirty ?? null, codeDirty: m.codeDirty ?? null,
      stale: cur && m.contentRevision ? m.contentRevision !== cur.contentRevision : null, currentContent: cur?.contentRevision ?? null };
  };

  /** Preview / lint / round notes published for a skill (build/skill-compare-published/<skill>/{preview,lint,round}/current.json). */
  function reviewViews(skill, referenceRevision) {
    const out = { tree: currentTree(), preview: null, previewSide: null, lint: null, round: null };
    if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(skill || "")) return out;
    const cur = (kind) => { const f = join(publishedDir, skill, kind, "current.json"); if (!existsSync(f)) return null; try { return readJson(f); } catch { return null; } };
    // preview = the intent's moving compare camera; preview-side = fixed side camera, caster faces screen-right (skill-preview --camera side).
    // Neither is withheld for a revision mismatch: prov.stale / referenceStale are flags for the page to badge.
    const previewOf = (kind) => {
      const pv = cur(kind);
      if (!(pv && /^[a-f0-9]{64}$/.test(pv.revision || ""))) return null;
      return { base: `/mc/vfx/${skill}/${kind}/`, count: pv.meta.frames, castFrame: pv.meta.castFrame ?? 0, extension: pv.extension || "png", revision: pv.revision,
        prov: { ...provOf(pv.meta), published: true, kind }, warnings: pv.meta.warnings ?? [], particles: !!pv.meta.particles, camera: pv.meta.camera ?? null,
        referenceStale: !!(referenceRevision && pv.meta.referenceRevision && pv.meta.referenceRevision !== referenceRevision) };
    };
    out.preview = previewOf("preview");
    out.previewSide = previewOf("preview-side");
    const ln = cur("lint");
    if (ln && /^[a-f0-9]{64}$/.test(ln.revision || "")) {
      out.lint = { revision: ln.revision, report: ln.report ?? null, overlays: ln.overlays ?? [], base: `/lint/${skill}/${ln.revision}/overlay/`,
        prov: ln.report ? { ...provOf(ln.report.provenance ?? {}), published: true, kind: "lint" } : null };
    }
    const rd = cur("round");
    if (rd && typeof rd.text === "string") out.round = { text: rd.text, revision: rd.revision, images: Array.isArray(rd.images) ? rd.images : [], imgBase: `/round/${skill}/${rd.revision}/img/`, prov: { ...provOf(rd), published: true, kind: "round" } };
    return out;
  }

  /** Plain stills grid (pose-review cases): <published>/<skill>/stills/current.json (scripts/publish-stills.mjs). */
  function stillsOf(skill) {
    if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(skill || "")) return null;
    const f = join(publishedDir, skill, "stills", "current.json");
    if (!existsSync(f)) return null;
    try {
      const m = readJson(f);
      if (!/^[a-f0-9]{64}$/.test(m.revision || "")) return null;
      return { revision: m.revision, title: m.title, rows: m.rows, base: `/stills/${skill}/${m.revision}/`, contentRevision: m.contentRevision ?? null, contentDirty: m.contentDirty ?? null };
    } catch { return null; }
  }

  function mcInfo(skill, referenceRevision, contentRevision) {
    const info = { skill, sources: {}, hitTicks: [], castTickInTrace: 5 };
    if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(skill || "")) return info;
    for (const view of ["compare", "input", "caster", "opponent", "side", "preview-side", "front", "back", "right", "top", "high", "near"]) {
      const pointer = join(publishedDir, skill, view, "current.json");
      if (existsSync(pointer)) {
        try {
          const { revision, extension, meta } = readJson(pointer);
          // A capture is never hidden for its revisions (only for being invalid): null = "unknown", different = "stale", both get a badge.
          if (!/^[a-f0-9]{64}$/.test(revision) || (meta.captureQuality?.invalid && meta.auto?.kind !== "preview")) continue;
          // STALE = the reference (video/trim) changed after this capture; the skill content version is the capture's own sha (auto/implementationCommit), never compared to case.json.
          const caseRev = !referenceRevision ? "match" : !meta.referenceRevision ? "unknown" : meta.referenceRevision === referenceRevision ? "match" : "stale";
          info.sources["vfx-" + view] = { kind: "frames", base: `/mc/vfx/${skill}/${view}/`,
            count: meta.frames, fps: meta.fps || 20, castFrame: Math.round(((meta.cast_tick_ms ?? 0) * (meta.fps || 20)) / 1000), extension, revision, prov: { ...provOf(meta), published: true, caseRev } };
        } catch { /* Never fall back to a partially overwritten source after publication. */ }
        continue;
      }
      const base = vfxFind(skill, view);
      const d = base && join(base, skill, view);
      if (base) {
        if (referenceRevision) {
          try { const m = readJson(join(d, "meta.json")); if (m.captureQuality?.invalid || m.referenceRevision !== referenceRevision || (contentRevision && m.caseContentRevision !== contentRevision)) continue; } catch { continue; }
        }
        const n = readdirSync(d).filter((f) => /^frame_\d{3,4}\.png$/.test(f)).length;
        const webpReady = readdirSync(d).filter((f) => /^frame_\d{3,4}\.png$/.test(f)).every((f) => existsSync(join(d, f.replace(/\.png$/, ".webp"))));
        // compare: frames before the cast (meta.json cast_tick_ms); FPS=60 captures hold 3 frames per tick
        let castFrame = 0, fps = 20, prov = { published: false };
        try { const m = readJson(join(d, "meta.json")); fps = m.fps || 20; castFrame = Math.round(((m.cast_tick_ms ?? 0) * fps) / 1000); prov = { ...provOf(m), published: false }; } catch { /* no meta: frame 0 = cast */ }
        if (n) info.sources["vfx-" + view] = { kind: "frames", base: `/mc/vfx/${skill}/${view}/`, count: n, fps, castFrame, prov,
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
      views: reviewViews(c.skill?.asteraSkillId, c.referenceRevision),
      stills: stillsOf(c.skill?.asteraSkillId),
      review: readReview(rec), beatSheet: parseBeatSheet(join(dir, "beat-sheet")), dev: devDeployed(),
      sound: soundOf(id, c, rec),
    };
  }

  // ---- founder-facing review record: data/<case>/review.json -----------------------------------------------------
  // { round, gate (1|2), focus, fireHint, soundKind, reviewer: {<beat>: {v: ok|ng, reason}}, founder: {<beat>: {v, note}}, founderAt, founderWho }
  // Agents write everything but `founder`; the page writes only `founder` (POST /api/cases/<id>/founder, no rev: it never clobbers the rest).
  const reviewFile = (rec) => join(dirname(rec.file), "review.json");
  function readReview(rec) {
    try { return readJson(reviewFile(rec)); } catch { return null; }
  }
  const BEAT_KEY_RE = /^[A-Za-z0-9_-]{1,24}$/;
  async function saveFounder(id, req) {
    if (!(req.headers.get("content-type") || "").startsWith("application/json")) return err(415, "application/json required");
    const text = await req.text();
    if (text.length > MAX_BODY) return err(413, "body too large");
    let body;
    try { body = JSON.parse(text); } catch { return err(400, "invalid JSON"); }
    const rec = S.allCases().get(id);
    if (!rec) return err(404, "no such case");
    const marks = {};
    for (const [k, v] of Object.entries(body.founder || {})) {
      if (!BEAT_KEY_RE.test(k) || !v || !["ok", "ng"].includes(v.v)) continue;
      marks[k] = { v: v.v, note: String(v.note || "").slice(0, 400) };
    }
    const cur = readReview(rec) || {};
    const next = { ...cur, founder: marks, founderAt: new Date().toISOString(), founderWho: String(body.who || "founder").slice(0, 40) };
    writeCaseFile(reviewFile(rec), next);
    return json({ ok: true, review: next });
  }

  // ---- author files: data/<case>/{briefs,deliver,evidence}/** (read-only, text + images) ------------------------------------
  const AUTHOR_DIRS = ["briefs", "deliver", "evidence"], AUTHOR_FILE_RE = /\.(md|txt|yaml|yml|json|png|jpg|webp)$/i;
  function listAuthorFiles(rec) {
    const base = dirname(rec.file), out = [];
    const walk = (rel) => {
      for (const d of readdirSync(join(base, rel), { withFileTypes: true })) {
        const r = rel + "/" + d.name;
        if (d.isDirectory()) walk(r); else if (AUTHOR_FILE_RE.test(d.name) && out.length < 600) out.push(r);
      }
    };
    for (const d of AUTHOR_DIRS) if (existsSync(join(base, d))) walk(d);
    for (const f of ["notes.md", "beat-sheet-check.md"]) if (existsSync(join(base, f))) out.push(f);
    return out;
  }
  const MIME = { md: "text/plain; charset=utf-8", txt: "text/plain; charset=utf-8", yaml: "text/plain; charset=utf-8", yml: "text/plain; charset=utf-8", json: "application/json", png: "image/png", jpg: "image/jpeg", webp: "image/webp" };

  /** Which dev build the founder would test in game: env SKILL_COMPARE_DEV_SHA or <published>/dev-deployed.json {sha, at} (written by the deploy step). */
  function devDeployed() {
    if (process.env.SKILL_COMPARE_DEV_SHA) return { sha: process.env.SKILL_COMPARE_DEV_SHA.trim(), at: null };
    try { // the dev autodeploy watcher's log: last "deployed <sha>" line (primary checkout state dir; also env override)
      const log = [process.env.SKILL_COMPARE_DEPLOY_LOG, join(import.meta.dir, "../../.claude/state/dev-deploy/deploy.log"), join(import.meta.dir, "../../../../state/dev-deploy/deploy.log")].find((f) => f && existsSync(f));
      const last = readFileSync(log, "utf8").split("\n").filter((l) => / deployed [0-9a-f]{7,40}\b/.test(l)).pop();
      const m = last && /^(\S+) deployed ([0-9a-f]{7,40})/.exec(last);
      if (m) return { sha: m[2], at: m[1] };
    } catch { /* log unreachable: fall through to dev-deployed.json */ }
    try { const d = readJson(join(publishedDir, "dev-deployed.json")); return /^[0-9a-f]{7,40}$/.test(d.sha || "") ? { sha: d.sha, at: d.at ?? null } : null; } catch { return null; }
  }

  // ---- sound: 参照 (the reference clip's audio) / 今回 / 前回 (<published>/<skill>/sound/{current,previous}.{ogg,mp3,wav}) -----------
  const SOUND_EXT = { ogg: "audio/ogg", mp3: "audio/mpeg", wav: "audio/wav" };
  function soundFile(skill, which) {
    if (!SKILL_RE.test(skill || "")) return null;
    for (const ext of Object.keys(SOUND_EXT)) { const f = join(publishedDir, skill, "sound", `${which}.${ext}`); if (existsSync(f)) return { f, type: SOUND_EXT[ext] }; }
    return null;
  }
  const referenceVideo = (c, rec) => {
    for (const d of [join(rec.src.cacheDir, "videos"), ...S.sources().map((x) => join(x.cacheDir, "videos"))]) { const f = join(d, `${c.videoId}.mp4`); if (existsSync(f)) return f; }
    return null;
  };
  function soundOf(id, c, rec) {
    const skill = c.skill?.asteraSkillId;
    return {
      reference: referenceVideo(c, rec) ? { url: `/sound/${id}/reference#t=${c.start_s},${c.end_s}` } : null,
      current: soundFile(skill, "current") ? { url: `/sound/${id}/current` } : null,
      previous: soundFile(skill, "previous") ? { url: `/sound/${id}/previous` } : null,
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

  const bundles = new Map();   // entry name -> { stamp, text }; app.js = the case page, anim.js = the animation review pages
  async function appJs(entry = "app") {
    const srcDir = join(root, "src");
    const stamp = Math.max(...readdirSync(srcDir).map((f) => statSync(join(srcDir, f)).mtimeMs), statSync(join(root, "package.json")).mtimeMs);
    let b = bundles.get(entry);
    if (!b || stamp !== b.stamp) {
      const r = await Bun.build({ entrypoints: [join(srcDir, entry + ".js")], target: "browser", minify: false });
      if (!r.success) return new Response(r.logs.join("\n"), { status: 500 });
      b = { stamp, text: await r.outputs[0].text() };
      bundles.set(entry, b);
    }
    return new Response(b.text, { headers: { "content-type": "text/javascript", "cache-control": "no-store" } });
  }

  async function saveReview(id, req) {
    if (!(req.headers.get("content-type") || "").startsWith("application/json")) return err(415, "application/json required");
    const text = await req.text();
    if (text.length > MAX_BODY) return err(413, "body too large");
    let body;
    try { body = JSON.parse(text); } catch { return err(400, "invalid JSON"); }
    const r = A.applyReview(id, body.review, body.who);
    if (r.status !== 200) return json(r.body, r.status);
    writeCaseFile(r.file, r.next);
    return json(r.body);
  }

  // Keypoints for the pose solver (tools/pose-solve): content/skill-lint/<skill>.keypoints.yaml, read / written through kp.py (the one validator).
  const kpScript = join(repo, "tools", "pose-solve", "kp.py");
  const kpRun = (args, input) => spawnSync("python3", [kpScript, ...args], { cwd: repo, input, encoding: "utf8", timeout: 60_000 });

  async function keypoints(skill, req) {
    if (req.method === "GET") {
      const r = kpRun(["read", skill]);
      return r.status === 0 ? new Response(r.stdout, { headers: { "content-type": "application/json", "cache-control": "no-store" } }) : err(404, (r.stderr || "no keypoints file").trim().split("\n").pop());
    }
    if (req.method !== "POST" && req.method !== "PUT") return err(405, "method not allowed");
    const text = await req.text();
    if (text.length > MAX_BODY) return err(413, "body too large");
    try { JSON.parse(text); } catch { return err(400, "invalid JSON"); }
    const r = kpRun(["write", skill], text);
    return r.status === 0 ? json({ ok: true }) : json({ error: (r.stderr || "write failed").trim().split("\n").slice(-4).join(" ") }, 422);
  }

  return async function handle(req) {
    const url = new URL(req.url);
    const p = url.pathname;
    let m;
    if (p === "/annotate.html") return file(join(publicDir, "annotate.html"), "text/html; charset=utf-8");
    if (p === "/annotate.js") return appJs("annotate");
    if ((m = /^\/api\/keypoints\/([a-z0-9-]+)$/.exec(p))) return SKILL_RE.test(m[1]) ? keypoints(m[1], req) : err(400, "bad skill id");
    if ((m = /^\/api\/keypoints\/([a-z0-9-]+)\/frame\/(\d{1,4})\.png$/.exec(p))) {
      const r = kpRun(["frame", m[1], m[2]]);
      return r.status === 0 ? file(r.stdout.trim(), "image/png") : err(404, (r.stderr || "no frame").trim().split("\n").pop());
    }
    if ((m = /^\/api\/keypoints\/([a-z0-9-]+)\/overlay\/([A-Za-z0-9-]+)\.png$/.exec(p))) {
      const f = join(repo, "build", "skill-lint", m[1], "overlay", m[2] + ".png");
      return inside(join(repo, "build"), f) ? file(f, "image/png") : err(400, "bad path");
    }
    if ((m = /^\/round\/([a-z0-9-]+)\/([a-f0-9]{64})\/img\/([A-Za-z0-9_-][A-Za-z0-9._-]*\.(png|jpg|webp))$/.exec(p))) {
      const f = join(publishedDir, m[1], "round", m[2], "img", m[3]);
      if (!existsSync(f) || !statSync(f).isFile()) return err(404, "not found");
      return new Response(Bun.file(f), { headers: { "content-type": m[4] === "png" ? "image/png" : m[4] === "jpg" ? "image/jpeg" : "image/webp", "x-content-type-options": "nosniff", "cache-control": "private, max-age=31536000, immutable" } });
    }
    if (p === "/stills.html") return file(join(publicDir, "stills.html"), "text/html; charset=utf-8");
    if ((m = /^\/stills\/([a-z0-9-]+)\/([a-f0-9]{64})\/(\d{1,2}-(?:reference|preview|ingame)\.png)$/.exec(p))) return file(join(publishedDir, m[1], "stills", m[2], m[3]), "image/png");
    if (p === "/" || p === "/index.html") return file(join(publicDir, "index.html"), "text/html; charset=utf-8");
    if (p === "/case.html" || /^\/case\/[a-z0-9-]+$/.test(p)) return file(join(publicDir, "case.html"), "text/html; charset=utf-8");
    if ((m = /^\/evidence\/([a-z0-9-]+)\.png$/.exec(p))) return file(join(repo, "build", "limbus-review", "pairs-round2", m[1] + ".png"), "image/png");
    if ((m = /^\/report\/([a-z0-9-]+)$/.exec(p))) return file(join(root, "data", m[1] + "-REPORT.md"), "text/plain; charset=utf-8");
    if ((m = /^\/reference\/([a-z0-9-]+)\/([a-z0-9-]+\.(?:json|md))$/.exec(p))) return file(join(root, "data", "reference-analysis", m[1], m[2]), m[2].endsWith("json") ? "application/json" : "text/plain; charset=utf-8");
    if (p === "/anim" || p === "/anim/") return file(join(publicDir, "anim-index.html"), "text/html; charset=utf-8");
    if (p === "/anim.html") return file(join(publicDir, "anim.html"), "text/html; charset=utf-8");
    if (p === "/anim-index.js") return file(join(publicDir, "anim-index.js"), "text/javascript");
    if (p === "/anim.js") return appJs("anim");
    if (p === "/anim.css") return file(join(publicDir, "anim.css"), "text/css");
    if (p === "/api/anims" && req.method === "GET") return json(A.list());
    if ((m = /^\/api\/anims\/([a-z0-9-]+)(\/review)?$/.exec(p))) {
      if (!ANIM_ID_RE.test(m[1])) return err(400, "bad id");
      if (!m[2] && req.method === "GET") { const d = A.detail(m[1]); return d ? json(d) : err(404, "no such animation"); }
      if (m[2] && req.method === "POST") return saveReview(m[1], req);
      return err(405, "method not allowed");
    }
    if ((m = /^\/anim-media\/([a-z0-9-]+)\/(.+)$/.exec(p))) {
      const f = A.mediaFile(m[1], m[2]);
      return f ? file(f, f.endsWith(".webp") ? "image/webp" : "image/png") : err(404, "not found");
    }
    if (p === "/app.js") return appJs();
    if (p === "/index.js") return file(join(publicDir, "index.js"), "text/javascript");
    if (p === "/style.css") return file(join(publicDir, "style.css"), "text/css");
    if (p === "/case.css") return file(join(publicDir, "case.css"), "text/css");
    if (p === "/api/cases" && req.method === "GET") return json(listCases());
    if (p === "/api/index" && req.method === "GET") return json(buildIndex({ repo, S, mcInfo }));
    if ((m = /^\/icon\/([a-z0-9-]+)\.png$/.exec(p))) return file(join(repo, "content", "textures", "generated", "items", m[1] + ".png"), "image/png");
    if ((m = /^\/api\/cases\/([a-z0-9-]+)\/founder$/.exec(p))) {
      if (!CASE_ID_RE.test(m[1])) return err(400, "bad id");
      return req.method === "POST" ? saveFounder(m[1], req) : err(405, "method not allowed");
    }
    if ((m = /^\/api\/cases\/([a-z0-9-]+)\/files$/.exec(p))) {
      const rec = S.allCases().get(m[1]);
      return rec ? json(listAuthorFiles(rec)) : err(404, "no such case");
    }
    if ((m = /^\/casefile\/([a-z0-9-]+)\/((?:briefs|deliver|evidence)\/[^?]+|notes\.md|beat-sheet-check\.md)$/.exec(p))) {
      const rec = S.allCases().get(m[1]);
      if (!rec) return err(404, "no such case");
      const base = dirname(rec.file), rel = decodeURIComponent(m[2]), f = join(base, rel);
      return inside(base, f) && AUTHOR_FILE_RE.test(f) ? file(f, MIME[f.split(".").pop().toLowerCase()]) : err(400, "bad path");
    }
    if ((m = /^\/beat\/([a-z0-9-]+)\/([A-Za-z0-9_-][A-Za-z0-9._-]*\.(png|jpg|webp))$/.exec(p))) {
      const rec = S.allCases().get(m[1]);
      return rec ? file(join(dirname(rec.file), "beat-sheet", m[2]), m[3] === "png" ? "image/png" : m[3] === "jpg" ? "image/jpeg" : "image/webp") : err(404, "no such case");
    }
    if ((m = /^\/sound\/([a-z0-9-]+)\/(reference|current|previous)$/.exec(p))) {
      const rec = S.allCases().get(m[1]);
      if (!rec) return err(404, "no such case");
      const sf = m[2] === "reference" ? (() => { const f = referenceVideo(rec.c, rec); return f && { f, type: "video/mp4" }; })() : soundFile(rec.c.skill?.asteraSkillId, m[2]);
      return sf ? file(sf.f, sf.type) : err(404, "no sound");
    }
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
    if ((m = /^\/mc\/vfx\/([a-z0-9-]+)\/(compare|input|caster|opponent|side|preview-side|front|back|right|top|high|near|preview)\/(frame_\d{3,4}\.(?:png|webp))$/.exec(p))) {
      const revision = new URL(req.url).searchParams.get("v");
      if (/^[a-f0-9]{64}$/.test(revision || "")) {
        const f = join(publishedDir, m[1], m[2], revision, m[3]);
        return file(f, f.endsWith("webp") ? "image/webp" : "image/png");
      }
      const base = vfxFind(m[1], m[2]) ?? vfxShotDir;
      const f = join(base, m[1], m[2], m[3]);
      return inside(base, f) ? file(f, f.endsWith("webp") ? "image/webp" : "image/png") : err(400, "bad path");
    }
    if ((m = /^\/lint\/([a-z0-9-]+)\/([a-f0-9]{64})\/overlay\/([A-Za-z0-9_-]+\.png)$/.exec(p))) return file(join(publishedDir, m[1], "lint", m[2], "overlay", m[3]), "image/png");
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
