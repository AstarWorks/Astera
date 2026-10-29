// Publish a plain stills grid (pose-review cases): rows of reference | preview | in-game images, no overlay, no lint.
//   node publish-stills.mjs <skill> <spec.json> [--root <published root>]
// spec.json: { "title": "...", "rows": [ { "label": "side", "reference": "<png>", "preview": "<png>", "ingame": "<png>" } ] }
// Image paths are absolute or relative to the spec file; "reference" is optional. Same atomic revision scheme as publish-preview.mjs.
import { readFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { publishRevision, SKILL_RE } from "../lib/publish.mjs";
import { publishedRoot, stampTree } from "../lib/revision.mjs";

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const COLS = ["reference", "preview", "ingame"];

export function publishStills(skill, specFile, { root } = {}) {
  if (!SKILL_RE.test(skill)) throw Error("Invalid skill id");
  root = resolve(root ?? publishedRoot(REPO));
  const spec = JSON.parse(readFileSync(specFile, "utf8"));
  if (!Array.isArray(spec.rows) || !spec.rows.length || spec.rows.length > 20) throw Error("spec.rows must be a non-empty array (max 20)");
  const files = [], rows = [];
  spec.rows.forEach((r, i) => {
    const row = { label: String(r.label ?? `row${i}`) };
    for (const c of COLS) {
      if (!r[c]) continue;
      const name = `${i}-${c}.png`;
      files.push({ name, bytes: readFileSync(resolve(dirname(specFile), r[c])) });
      row[c] = name;
    }
    rows.push(row);
  });
  return publishRevision(root, skill, "stills", files, () => ({ title: spec.title ?? skill, rows, ...stampTree(REPO) }));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const a = process.argv.slice(2), i = a.indexOf("--root");
  if (!a[0] || !a[1] || a[0].startsWith("--")) throw Error("Usage: publish-stills.mjs <skill> <spec.json> [--root dir]");
  const r = publishStills(a[0], resolve(a[1]), { root: i >= 0 ? a[i + 1] : undefined });
  console.log(`published stills ${a[0]}: ${r.rows.length} rows, revision ${r.revision.slice(0, 12)}`);
}
