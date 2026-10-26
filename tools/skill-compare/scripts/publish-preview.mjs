// Publish a `make skill-preview` render as the "preview" view of the skill-compare page (same atomic scheme as publish-capture.mjs).
//   node publish-preview.mjs <skill> [--src build/skill-preview/<skill>] [--root <published root>]
// Frames build/skill-preview/<skill>/frames/tick_NNN.png (tick tFrom..tTo, meta.ticks) -> <root>/<skill>/preview/<revision>/frame_NNN.png.
import { readFileSync, existsSync } from "node:fs";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { publishRevision, SKILL_RE } from "../lib/publish.mjs";
import { publishedRoot } from "../lib/revision.mjs";

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");

export function publishPreview(skill, { src, root, kind = "preview" } = {}) {
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

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const a = process.argv.slice(2), opt = (k) => { const i = a.indexOf(k); return i >= 0 ? a[i + 1] : undefined; };
  if (!a[0] || a[0].startsWith("--")) throw Error("Usage: publish-preview.mjs <skill> [--src dir] [--root dir] [--kind preview|preview-side]");
  const r = publishPreview(a[0], { src: opt("--src"), root: opt("--root"), kind: opt("--kind") });
  console.log(`published ${r.meta.kind} ${a[0]}: ${r.meta.frames} frames, revision ${r.revision.slice(0, 12)}, content ${r.meta.contentRevision.slice(0, 12)}${r.meta.contentDirty ? " (dirty)" : ""}`);
}
