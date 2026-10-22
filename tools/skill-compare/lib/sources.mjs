// Where cases and Minecraft captures live. The server runs from the main checkout but agents work in worktrees
// (`.claude/worktrees/*`), so every request re-scans (cheap: a readdir per worktree) for
//   <wt>/tools/skill-compare/data/<case>/case.json   cases        (+ <wt>/tools/skill-compare/cache/<case>/ frames)
//   <wt>/build/vfx-shot/<skill>/<view>/              MC captures
// plus SKILL_COMPARE_DATA_DIRS (dirs that directly contain <case>/case.json) and SKILL_COMPARE_MEDIA_DIRS
// (extra vfx-shot dirs), both colon-separated.
import { existsSync, readdirSync, readFileSync, realpathSync, statSync } from "node:fs";
import { basename, join, resolve } from "node:path";
import { CASE_ID_RE } from "../src/timeline.js";

const readJson = (p) => JSON.parse(readFileSync(p, "utf8"));
const mtime = (p) => { try { return statSync(p).mtimeMs; } catch { return 0; } };
const real = (p) => { try { return realpathSync(p); } catch { return resolve(p); } };

/** Branch name of a checkout (works for a plain clone and for a worktree whose .git is a file). */
export function gitBranch(dir) {
  try {
    let gitDir = join(dir, ".git");
    if (statSync(gitDir).isFile()) gitDir = resolve(dir, /^gitdir:\s*(.+)$/m.exec(readFileSync(gitDir, "utf8"))[1].trim());
    const head = readFileSync(join(gitDir, "HEAD"), "utf8").trim();
    const m = /^ref: refs\/heads\/(.+)$/.exec(head);
    return m ? m[1] : head.slice(0, 8) + " (detached)";
  } catch { return ""; }
}

export function makeSources({ root, repo, vfxShotDir, mediaDirs, worktrees = true }) {
  /** [{name, branch, root(skill-compare dir), dataDir, cacheDir, vfxDir}] main first. */
  function sources() {
    const out = [];
    const seen = new Set();
    const add = (s) => { const k = real(s.dataDir); if (!seen.has(k) && existsSync(s.dataDir)) { seen.add(k); out.push(s); } };
    add({ name: "main", branch: gitBranch(repo), root, dataDir: join(root, "data"), cacheDir: join(root, "cache"), vfxDir: vfxShotDir });
    const wtRoot = join(repo, ".claude", "worktrees");
    if (worktrees && existsSync(wtRoot)) {
      for (const d of readdirSync(wtRoot, { withFileTypes: true })) {
        if (!d.isDirectory()) continue;
        const wt = join(wtRoot, d.name), sc = join(wt, "tools", "skill-compare");
        add({ name: d.name, branch: gitBranch(wt), root: sc, dataDir: join(sc, "data"), cacheDir: join(sc, "cache"), vfxDir: join(wt, "build", "vfx-shot") });
      }
    }
    for (const dd of (process.env.SKILL_COMPARE_DATA_DIRS || "").split(":").filter(Boolean)) {
      const dir = resolve(dd);
      add({ name: basename(resolve(dir, "..", "..", "..")) || dir, branch: "", root: resolve(dir, ".."), dataDir: dir, cacheDir: join(dir, "..", "cache"), vfxDir: join(dir, "..", "..", "..", "build", "vfx-shot") });
    }
    return out;
  }

  function vfxDirs() {
    const extra = (mediaDirs ?? (process.env.SKILL_COMPARE_MEDIA_DIRS || "").split(":").filter(Boolean)).map((d) => resolve(d));
    return [...new Set([...sources().map((s) => s.vfxDir), ...extra])];
  }

  /** The vfx-shot base dir that holds <skill>/<view>; the most recently written one when several do. */
  function vfxFind(skill, view) {
    let best = null, bestT = -1;
    for (const b of vfxDirs()) {
      const d = join(b, skill, view);
      if (!existsSync(d)) continue;
      const t = Math.max(mtime(join(d, "meta.json")), mtime(d));
      if (t > bestT) { best = b; bestT = t; }
    }
    return best;
  }

  /** Every case id -> the winning {id, src, c, file, mtime, dups}. Highest `rev` wins (a founder edit made through the
   *  server bumps it), then the newest case.json; an identical copy in main never loses to a worktree checkout. */
  function allCases() {
    const best = new Map();
    for (const src of sources()) {
      for (const d of readdirSync(src.dataDir, { withFileTypes: true })) {
        if (!d.isDirectory() || !CASE_ID_RE.test(d.name)) continue;
        const file = join(src.dataDir, d.name, "case.json");
        if (!existsSync(file)) continue;
        let c;
        try { c = readJson(file); } catch { continue; }
        const cand = { id: d.name, src, c, file, mtime: mtime(file), text: JSON.stringify(c) };
        const cur = best.get(d.name);
        if (!cur) { best.set(d.name, cand); continue; }
        const better = cand.text === cur.text ? false
          : (cand.c.rev ?? 0) !== (cur.c.rev ?? 0) ? (cand.c.rev ?? 0) > (cur.c.rev ?? 0)
          : cand.mtime > cur.mtime;
        if (better) best.set(d.name, cand);
      }
    }
    return best;
  }

  /** cache/<case> dir with meta.json: the winner's own, else any other source's. */
  function cacheFor(id, src) {
    const own = src && join(src.cacheDir, id);
    if (own && existsSync(join(own, "meta.json"))) return own;
    for (const s of sources()) { const p = join(s.cacheDir, id); if (existsSync(join(p, "meta.json"))) return p; }
    return null;
  }

  return { sources, vfxDirs, vfxFind, allCases, cacheFor };
}
