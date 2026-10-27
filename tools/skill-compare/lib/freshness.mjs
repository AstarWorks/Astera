// Honest freshness of the published videos: is the video the page shows still what dev would produce for THIS skill?
//   state "current"  video sha == dev sha, or the skill's own content did not change between them (tools/vfx-shot/changed-skills.py)
//   state "building" skill content changed after the video and a newer render is queued / running (or the pipeline is stalled)
//   state "failed"   the pipeline logged a failure for this skill after the video; `reason` = the tail of its log
//   state "unknown"  the video does not record its sha, or the sha is not in this repo
// Inputs are only the logs/state the two pipelines already write:
//   <dev-deploy>/deploy.log   "<iso> deployed <sha> [...]"                      (scripts/dev-autodeploy.sh)
//   <dev-deploy>/preview.log  "<iso> preview-published|preview-FAILED <sha> <skill> ..."   + preview-<skill>.log (scripts/skill-preview-auto.sh)
//   <capture-farm>/farm.log   "<stamp> deployed|PREVIEW|STEPPED|publish|done ..." + shot.log   (tools/vfx-shot/auto-capture.sh)
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const mtime = (p) => { try { return statSync(p).mtimeMs; } catch { return 0; } };
const read = (p) => { try { return readFileSync(p, "utf8"); } catch { return ""; } };
const tail = (p, n = 60) => read(p).split("\n").filter((l) => l.trim()).slice(-n);
export const sameSha = (a, b) => { if (!a || !b) return false; const n = Math.min(a.length, b.length, 12); return n >= 7 && a.slice(0, n) === b.slice(0, n); };
const parseTime = (s) => { if (!s) return 0; const t = Date.parse(s.replace(/([+-]\d\d)(\d\d)$/, "$1:$2")); return Number.isNaN(t) ? 0 : t; };
const PREVIEW_DIRS = /^content\/(skills|vfx|animations|models|sounds|particles|particle-skins|poses|items)\//;
const PREVIEW_KINDS = new Set(["preview", "preview-side"]);
const FARM_KINDS = new Set(["side", "top"]);
const STALL_MS = 30 * 60 * 1000;

export function makeFreshness({ repo, devDir, farmDir }) {
  const deployLog = join(devDir, "deploy.log"), previewLog = join(devDir, "preview.log");
  const farmLog = join(farmDir, "farm.log"), shotLog = join(farmDir, "shot.log");

  const git = (...a) => execFileSync("git", ["-C", repo, ...a], { encoding: "utf8", maxBuffer: 1 << 26, stdio: ["ignore", "pipe", "ignore"] });
  const hasCommit = (sha) => { try { git("cat-file", "-e", sha + "^{commit}"); return true; } catch { return false; } };

  const isAncestor = (a, b) => { try { git("merge-base", "--is-ancestor", a, b); return true; } catch { return false; } };

  /** skills whose own content changed between two commits (the capture farm's mapping), memoised per pair. */
  const memo = new Map();
  function changedSkills(from, to) {
    const k = from + ".." + to;
    if (memo.has(k)) return memo.get(k);
    let v = null;
    try {
      if (hasCommit(from) && hasCommit(to)) {
        const out = execFileSync("python3", ["-I", join(repo, "tools", "vfx-shot", "changed-skills.py"), "--root", repo, from, to], { encoding: "utf8", timeout: 30_000, stdio: ["ignore", "pipe", "ignore"] });
        v = new Set(out.split("\n").filter(Boolean));
        // the preview pipeline also reacts to models / particles / items (scripts/skill-preview-auto.sh): same mapping, a wider directory set
        const paths = git("diff", "--name-only", "--no-renames", from, to, "--", "content").split("\n").filter((p) => PREVIEW_DIRS.test(p));
        v.previewExtra = new Set();
        for (const p of paths) {
          if (/^content\/skills\/.+\.ya?ml$/.test(p)) { v.previewExtra.add(p.replace(/^.*\//, "").replace(/\.ya?ml$/, "")); continue; }
          const stem = p.replace(/^.*\//, "").split(".")[0];
          if (stem.length < 3) continue;
          try { for (const f of execFileSync("grep", ["-rlw", "--", stem, join(repo, "content", "skills")], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).split("\n").filter(Boolean)) v.previewExtra.add(f.replace(/^.*\//, "").replace(/\.ya?ml$/, "")); } catch { /* no skill names it */ }
        }
      }
    } catch { v = null; }
    if (memo.size > 400) memo.clear();
    memo.set(k, v);
    return v;
  }
  const affected = (skill, from, to, kind) => {
    const s = changedSkills(from, to);
    if (!s) return null;
    return s.has(skill) || (PREVIEW_KINDS.has(kind) && s.previewExtra.has(skill));
  };

  /** last "deployed <sha>" of the dev autodeploy log */
  function dev() {
    const line = tail(deployLog, 400).reverse().find((l) => / deployed [0-9a-f]{7,40}\b/.test(l));
    const m = line && /^(\S+) deployed ([0-9a-f]{7,40})/.exec(line);
    return m ? { sha: m[2], at: m[1] } : null;
  }

  /** per-skill records from preview.log: [{t, sha, ok, line}] */
  function previewRecords(skill) {
    const out = [];
    for (const l of tail(previewLog, 600)) {
      const m = /^(\S+) preview-(published|FAILED) ([0-9a-f]{7,40}) (\S+)(.*)$/.exec(l);
      if (!m) continue;
      if (m[4] === skill) out.push({ t: parseTime(m[1]), sha: m[3], ok: m[2] === "published", line: m[5].trim(), global: false });
      else if (m[2] === "FAILED" && m[4] === "skill-viewer") out.push({ t: parseTime(m[1]), sha: m[3], ok: false, line: "skill-viewer build " + m[5].trim(), global: true });
    }
    return out;
  }
  /** farm.log -> { records for the skill, run: {sha8, done} of the latest "deployed" line }  */
  function farmInfo(skill) {
    const recs = []; let run = null;
    for (const l of tail(farmLog, 800)) {
      const m = /^(\S+) (.*)$/.exec(l); if (!m) continue;
      const t = parseTime(m[1]), body = m[2];
      let x;
      if ((x = /^deployed ([0-9a-f]{7,40}) .* skills:(.*)$/.exec(body))) run = { sha: x[1], t, skills: x[2].trim().split(/\s+/).filter(Boolean), done: false };
      else if ((x = /^done ([0-9a-f]{7,40}):/.exec(body)) && run && sameSha(run.sha, x[1])) run.done = true;
      else if ((x = /^(PREVIEW|STEPPED) (\S+) (published|failed|not published)(.*)$/.exec(body)) && x[2] === skill) recs.push({ t, sha: run?.sha ?? null, ok: x[3] === "published", stage: x[1], line: (x[3] + x[4]).trim() });
      else if ((x = /^publish (\S+)\/(\S+) \((\w+)\) REFUSED: (.*)$/.exec(body)) && x[1] === skill) recs.push({ t, sha: run?.sha ?? null, ok: false, stage: x[3], line: x[4].trim() });
    }
    return { recs, run };
  }

  function previewReason(skill, rec) {
    if (rec.global) return rec.line;
    const own = tail(join(devDir, `preview-${skill}.log`), 3).map((l) => l.replace(/\u001b\[[0-9;]*m/g, "").trim()).filter(Boolean).pop();
    return own || rec.line;
  }
  function farmReason(rec) {
    if (/^(not published|failed)/.test(rec.line) || !rec.line) return tail(shotLog, 3).map((l) => l.replace(/\u001b\[[0-9;]*m/g, "").trim()).filter(Boolean).pop() || rec.line;
    return rec.line;
  }

  /**
   * One video: { sha (the video's own content sha), capturedAt, kind: "preview" | "preview-side" | <farm view> }.
   * Returns { state, label, devSha, videoSha, reason?, stage?, stalled? }.
   */
  function judge(skill, video, devNow = dev()) {
    const out = { state: "unknown", devSha: devNow?.sha ?? null, videoSha: video.sha ?? null, capturedAt: video.capturedAt ?? null };
    if (!devNow || !video.sha) return out;
    if (sameSha(video.sha, devNow.sha)) return { ...out, state: "current" };
    // only the pipelines' own views are ever re-rendered; legacy views (compare / input / caster ...) are never "building"
    if (!PREVIEW_KINDS.has(video.kind) && !FARM_KINDS.has(video.kind)) return out;
    // rendered from a commit AHEAD of what dev has deployed (preview runs on the pushed sha first): not outdated
    if (hasCommit(video.sha) && hasCommit(devNow.sha) && isAncestor(devNow.sha, video.sha)) return { ...out, state: "current", ahead: true };
    const ch = affected(skill, video.sha, devNow.sha, video.kind);
    if (ch === null) return out;
    if (!ch) return { ...out, state: "current", unchanged: true };
    const devAt = parseTime(devNow.at), now = Date.now(), tv = parseTime(video.capturedAt);
    if (PREVIEW_KINDS.has(video.kind)) {
      const last = previewRecords(skill).filter((r) => r.t > tv).pop();
      if (last && !last.ok && last.t >= devAt) return { ...out, state: "failed", reason: previewReason(skill, last), failedSha: last.sha };
      const lg = mtime(join(devDir, `preview-${skill}.log`));
      const running = lg >= devAt && now - lg < 10 * 60 * 1000;
      return { ...out, state: "building", stage: running ? "running" : "queued", stalled: devAt > 0 && now - devAt > STALL_MS && !running };
    }
    const { recs, run } = farmInfo(skill);
    const last = recs.filter((r) => r.t > tv).pop();
    if (last && !last.ok && last.t >= devAt) {
      // a failed STEPPED pass leaves the realtime PREVIEW in place: only the final failure (nothing newer) is shown
      return { ...out, state: "failed", reason: farmReason(last), failedSha: last.sha };
    }
    const runningHere = run && sameSha(run.sha, devNow.sha) && !run.done;
    return { ...out, state: "building", stage: runningHere ? "running" : "queued", stalled: devAt > 0 && now - devAt > STALL_MS && !runningHere };
  }

  /** cheap change signature of what judge() reads for one skill (SSE watcher): the skill's own log lines + the farm/deploy run state */
  const tailCache = new Map();
  const tailOf = (p) => { const m = mtime(p), c = tailCache.get(p); if (c && c.m === m) return c.lines; const lines = tail(p, 600); tailCache.set(p, { m, lines }); return lines; };
  const signature = (skill) => {
    const mine = (p) => { const l = tailOf(p).filter((x) => x.includes(skill)); return l.length + ":" + (l[l.length - 1] ?? ""); };
    const farm = tailOf(farmLog).filter((x) => / (deployed|done) [0-9a-f]{7,}/.test(x)).pop() ?? "";
    return [mtime(deployLog), mine(previewLog), mine(farmLog), farm, mtime(join(devDir, `preview-${skill}.log`))].join("|");
  };

  return { dev, judge, signature, changedSkills };
}
