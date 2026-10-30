// Publish a `make skill-preview` render as the "preview" view of the skill-compare page (same atomic scheme as publish-capture.mjs).
//   node publish-preview.mjs <skill> [--src build/skill-preview/<skill>] [--root <published root>] [--kind preview|preview-side|lint]
// Frames build/skill-preview/<skill>/frames/tick_NNN.png (tick tFrom..tTo, meta.ticks) -> <root>/<skill>/preview/<revision>/frame_NNN.png.
// kind lint: the hit-volume overlay/t<tick>.png of the same render (trace-preview --overlay) -> <root>/<skill>/lint/<revision>/overlay/.
import { readFileSync, existsSync, readdirSync } from "node:fs";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { publishRevision, SKILL_RE } from "../lib/publish.mjs";
import { publishedRoot } from "../lib/revision.mjs";

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");

export function publishPreview(skill, { src, root, kind = "preview" } = {}) {
  if (kind === "lint") return publishOverlay(skill, { src, root });
  if (kind !== "preview" && kind !== "preview-side") throw Error("Invalid preview kind");
  if (!SKILL_RE.test(skill)) throw Error("Invalid skill id");
  src = resolve(src ?? join(REPO, "build", "skill-preview", skill));
  root = resolve(root ?? publishedRoot(REPO));
  const raw = readFileSync(join(src, "meta.json"), "utf8");
  const meta = JSON.parse(raw);
  if (meta.skill !== skill) throw Error("Preview metadata identity mismatch");
  if (!meta.provenance?.contentRevision) throw Error("Preview meta.json has no provenance (rendered by an older skill-preview): rerun make skill-preview");
  const [from, to] = meta.ticks;
  const frames = to - from + 1;
  if (!(frames >= 1 && frames <= 10000)) throw Error("Bad preview tick range");
  const files = [];
  for (let i = 0; i < frames; i++) {
    const p = join(src, "frames", `tick_${String(from + i).padStart(3, "0")}.png`);
    if (!existsSync(p)) throw Error("Missing preview frame " + p);
    files.push({ name: `frame_${String(i).padStart(3, "0")}.png`, bytes: readFileSync(p) });
  }
  let referenceRevision = null;
  try { referenceRevision = JSON.parse(readFileSync(join(REPO, "tools", "skill-compare", "data", meta.case, "case.json"), "utf8")).referenceRevision ?? null; } catch { /* case without revision */ }
  const view = {
    kind, camera: meta.camera ?? null, skill, case: meta.case, frames, firstTick: from, castFrame: -from, size: meta.size, weapon: meta.weapon?.item ?? null,
    particles: meta.particles != null, fxDisplays: meta.fxDisplays, warnings: meta.warnings ?? [], referenceRevision, ...meta.provenance,
  };
  return publishRevision(root, skill, kind, files, () => ({ extension: "png", meta: view }), raw);
}

/** The hit-volume overlay as the "lint" view. No checks: the check producer is gone, the overlay is the only review layer left. */
export function publishOverlay(skill, { src, root } = {}) {
  if (!SKILL_RE.test(skill)) throw Error("Invalid skill id");
  src = resolve(src ?? join(REPO, "build", "skill-preview", skill));
  root = resolve(root ?? publishedRoot(REPO));
  const raw = readFileSync(join(src, "meta.json"), "utf8");
  const meta = JSON.parse(raw);
  if (meta.skill !== skill) throw Error("Preview metadata identity mismatch");
  if (!meta.provenance?.contentRevision) throw Error("Preview meta.json has no provenance (rendered by an older skill-preview): rerun make skill-preview");
  const dir = join(src, "overlay");
  if (!existsSync(dir)) throw Error("No overlay frames in " + dir + ": render with --overlay (make skill-preview OVERLAY=1)");
  const names = readdirSync(dir).filter((n) => /^t-?\d+\.png$/.test(n)).sort();
  if (!names.length) throw Error("Overlay directory has no t<tick>.png frames: " + dir);
  const overlays = names.map((name) => ({ tick: Number(name.slice(1, -4)), name }));
  const files = names.map((name) => ({ name: `overlay/${name}`, bytes: readFileSync(join(dir, name)) }));
  let referenceRevision = null;
  try { referenceRevision = JSON.parse(readFileSync(join(REPO, "tools", "skill-compare", "data", meta.case, "case.json"), "utf8")).referenceRevision ?? null; } catch { /* case without revision */ }
  const view = { kind: "lint", skill, case: meta.case, camera: meta.camera ?? null, weapon: meta.weapon?.item ?? null, warnings: meta.warnings ?? [], referenceRevision, ...meta.provenance };
  return publishRevision(root, skill, "lint", files, () => ({ extension: "png", overlays, meta: view }), raw);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const a = process.argv.slice(2), opt = (k) => { const i = a.indexOf(k); return i >= 0 ? a[i + 1] : undefined; };
  if (!a[0] || a[0].startsWith("--")) throw Error("Usage: publish-preview.mjs <skill> [--src dir] [--root dir] [--kind preview|preview-side|lint]");
  const r = publishPreview(a[0], { src: opt("--src"), root: opt("--root"), kind: opt("--kind") });
  if (r.meta.kind === "lint") console.log(`published lint ${a[0]}: ${r.overlays.length} overlay ticks, revision ${r.revision.slice(0, 12)}, content ${r.meta.contentRevision.slice(0, 12)}${r.meta.contentDirty ? " (dirty)" : ""}`);
  else console.log(`published ${r.meta.kind} ${a[0]}: ${r.meta.frames} frames, revision ${r.revision.slice(0, 12)}, content ${r.meta.contentRevision.slice(0, 12)}${r.meta.contentDirty ? " (dirty)" : ""}`);
}
