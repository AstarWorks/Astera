// Publish the director's per-skill round notes (round.md) to the top of the skill-compare page.
//   node publish-round.mjs <skill> [<round.md>] [--root <published root>]
// Default source: tools/skill-compare/data/<case>/round.md of the case that plays the skill. Plain text / light Markdown, shown as-is.
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { publishRevision, SKILL_RE } from "../lib/publish.mjs";
import { publishedRoot, stampTree } from "../lib/revision.mjs";

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const DATA = join(REPO, "tools", "skill-compare", "data");

export function publishRound(skill, { file, root } = {}) {
  if (!SKILL_RE.test(skill)) throw Error("Invalid skill id");
  root = resolve(root ?? publishedRoot(REPO));
  if (!file) {
    const id = readdirSync(DATA).find((d) => { try { return JSON.parse(readFileSync(join(DATA, d, "case.json"), "utf8")).skill?.asteraSkillId === skill; } catch { return false; } });
    file = id && join(DATA, id, "round.md");
  }
  if (!file || !existsSync(file)) return null;   // optional
  const text = readFileSync(file, "utf8");
  if (!text.trim()) return null;
  if (text.length > 20000) throw Error("round.md too long (keep it short)");
  const stamp = stampTree(REPO);
  return publishRevision(root, skill, "round", [{ name: "round.md", bytes: Buffer.from(text) }], () => ({ text, ...stamp }));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const a = process.argv.slice(2);
  const ri = a.indexOf("--root");
  const root = ri >= 0 ? a[ri + 1] : undefined;
  const pos = a.filter((x, j) => ri < 0 || (j !== ri && j !== ri + 1));
  if (!pos[0]) throw Error("Usage: publish-round.mjs <skill> [round.md] [--root dir]");
  const r = publishRound(pos[0], { file: pos[1] && resolve(pos[1]), root });
  console.log(r ? `published round notes ${pos[0]}: revision ${r.revision.slice(0, 12)}` : `no round.md for ${pos[0]}; nothing published`);
}
