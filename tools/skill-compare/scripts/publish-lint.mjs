// Publish the skill-lint report and the per-beat overlay PNGs as the "lint" view of the skill-compare page.
//   node publish-lint.mjs <skill> [--root <published root>]
// Reads build/skill-lint/<skill>/report.json (make skill-lint) and build/skill-lint/<skill>/overlay/*.png (make skill-overlay / lint check 1b).
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { publishRevision, SKILL_RE } from "../lib/publish.mjs";
import { publishedRoot } from "../lib/revision.mjs";

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");

export function publishLint(skill, { root } = {}) {
  if (!SKILL_RE.test(skill)) throw Error("Invalid skill id");
  root = resolve(root ?? publishedRoot(REPO));
  const dir = join(REPO, "build", "skill-lint", skill);
  const rp = join(dir, "report.json");
  const rawReport = existsSync(rp) ? readFileSync(rp, "utf8") : null;
  const report = rawReport ? JSON.parse(rawReport) : null;
  if (report && report.skill !== skill) throw Error("Lint report identity mismatch");
  const od = join(dir, "overlay");
  const overlays = existsSync(od) ? readdirSync(od).filter((f) => /^[A-Za-z0-9_-]+\.png$/.test(f)).sort() : [];
  if (!report && !overlays.length) throw Error(`nothing to publish: no ${rp} and no overlay PNGs (make skill-lint SKILL=${skill})`);
  const files = overlays.map((n) => ({ name: "overlay/" + n, bytes: readFileSync(join(od, n)) }));
  if (rawReport) files.push({ name: "report.json", bytes: Buffer.from(rawReport) });
  return publishRevision(root, skill, "lint", files, () => ({ report, overlays }));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const a = process.argv.slice(2), i = a.indexOf("--root");
  if (!a[0] || a[0].startsWith("--")) throw Error("Usage: publish-lint.mjs <skill> [--root dir]");
  const r = publishLint(a[0], { root: i >= 0 ? a[i + 1] : undefined });
  console.log(`published lint ${a[0]}: ${r.report ? `${r.report.summary.checks} checks, ${r.report.summary.fail} FAIL` : "no report"}, ${r.overlays.length} overlays, revision ${r.revision.slice(0, 12)}`);
}
