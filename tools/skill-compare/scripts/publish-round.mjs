// Publish the director's per-skill round notes (round.md) to the top of the skill-compare page.
//   node publish-round.mjs <skill> [<round.md>] [--root <published root>]
// Default source: tools/skill-compare/data/<case>/round.md of the case that plays the skill.
// Markdown (headings, tables, lists, **bold**, http(s) links); raw HTML is never interpreted. Images: `![alt](beat-top.png)` relative
// to the notes file; png/jpg/webp only; must stay inside the notes file's directory (no .., absolute paths, URLs or symlink escapes).
// They are validated and copied into the published revision (img/<name>).
import { readFileSync, readdirSync, existsSync, realpathSync, statSync } from "node:fs";
import { join, resolve, dirname, basename, extname, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { publishRevision, SKILL_RE } from "../lib/publish.mjs";
import { publishedRoot, stampTree } from "../lib/revision.mjs";

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const DATA = join(REPO, "tools", "skill-compare", "data");

export const MAX_TEXT = 20000, MAX_IMAGE_BYTES = 2 * 1024 * 1024, MAX_IMAGES = 12;
const MAGIC = {
  ".png": (b) => b.subarray(0, 4).toString("hex") === "89504e47",
  ".jpg": (b) => b[0] === 0xff && b[1] === 0xd8,
  ".webp": (b) => b.subarray(0, 4).toString() === "RIFF" && b.subarray(8, 12).toString() === "WEBP",
};

/** Validate `![alt](ref)` images (outside code fences); returns the text rewritten to img/<name> and the files to publish. */
export function collectImages(text, dir) {
  const base = realpathSync(dir), files = [], byPath = new Map(), names = new Set();
  let inFence = false;
  const out = text.split("\n").map((line) => {
    if (/^```/.test(line)) { inFence = !inFence; return line; }
    if (inFence) return line;
    return line.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (_, alt, ref) => {
      if (/^[a-z][a-z0-9+.-]*:|^\/|\\/i.test(ref)) throw Error(`image ${ref}: only relative paths next to the notes file are allowed`);
      let ext = extname(ref).toLowerCase();
      if (ext === ".jpeg") ext = ".jpg";
      if (!MAGIC[ext]) throw Error(`image ${ref}: only .png/.jpg/.jpeg/.webp`);
      const abs = join(base, decodeURI(ref));
      if (!existsSync(abs)) throw Error(`image ${ref}: file not found`);
      const real = realpathSync(abs);
      if (!real.startsWith(base + sep)) throw Error(`image ${ref}: outside the notes directory`);
      if (!statSync(real).isFile()) throw Error(`image ${ref}: not a file`);
      let name = byPath.get(real);
      if (!name) {
        const bytes = readFileSync(real);
        if (bytes.length > MAX_IMAGE_BYTES) throw Error(`image ${ref}: ${bytes.length} bytes > ${MAX_IMAGE_BYTES} cap`);
        if (!bytes.length || !MAGIC[ext](bytes)) throw Error(`image ${ref}: content is not a real ${ext} file`);
        if (files.length >= MAX_IMAGES) throw Error(`too many images (max ${MAX_IMAGES})`);
        const stem = basename(real, extname(real)).replace(/[^A-Za-z0-9_-]/g, "_").slice(0, 40) || "img";
        name = `${stem}${ext}`;
        for (let k = 2; names.has(name); k++) name = `${stem}-${k}${ext}`;
        names.add(name); byPath.set(real, name); files.push({ name: `img/${name}`, bytes });
      }
      return `![${alt}](img/${name})`;
    });
  });
  return { text: out.join("\n"), files };
}

export function publishRound(skill, { file, root } = {}) {
  if (!SKILL_RE.test(skill)) throw Error("Invalid skill id");
  root = resolve(root ?? publishedRoot(REPO));
  if (!file) {
    const id = readdirSync(DATA).find((d) => { try { return JSON.parse(readFileSync(join(DATA, d, "case.json"), "utf8")).skill?.asteraSkillId === skill; } catch { return false; } });
    file = id && join(DATA, id, "round.md");
  }
  if (!file || !existsSync(file)) return null;   // optional
  const raw = readFileSync(file, "utf8");
  if (!raw.trim()) return null;
  if (raw.length > MAX_TEXT) throw Error(`round notes too long (> ${MAX_TEXT} chars; keep it short)`);
  const { text, files } = collectImages(raw, dirname(resolve(file)));
  const stamp = stampTree(REPO);
  return publishRevision(root, skill, "round", [{ name: "round.md", bytes: Buffer.from(text) }, ...files],
    () => ({ text, images: files.map((f) => f.name.slice(4)), ...stamp }));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const a = process.argv.slice(2);
  const ri = a.indexOf("--root");
  const root = ri >= 0 ? a[ri + 1] : undefined;
  const pos = a.filter((x, j) => ri < 0 || (j !== ri && j !== ri + 1));
  if (!pos[0]) throw Error("Usage: publish-round.mjs <skill> [round.md] [--root dir]");
  const r = publishRound(pos[0], { file: pos[1] && resolve(pos[1]), root });
  console.log(r ? `published round notes ${pos[0]}: revision ${r.revision.slice(0, 12)} (${r.images.length} images)` : `no round.md for ${pos[0]}; nothing published`);
}
