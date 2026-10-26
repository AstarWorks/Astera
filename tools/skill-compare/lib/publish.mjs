// Atomic, immutable-revision publication used by publish-preview / publish-lint / publish-round (same scheme as publish-capture.mjs):
// <root>/<skill>/<kind>/<revision>/{files..., manifest.json} is written under a staging dir and renamed into place, then
// <root>/<skill>/<kind>/current.json is replaced by rename. An open page keeps reading its own revision directory.
import { mkdirSync, writeFileSync, renameSync, rmSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { createHash, randomUUID } from "node:crypto";

export const SKILL_RE = /^[a-z0-9][a-z0-9-]{0,63}$/;

/** files: [{ name, bytes }] (flat names or "sub/name"); manifestOf(revision) -> object stored as manifest.json and current.json. */
export function publishRevision(root, skill, kind, files, manifestOf, hashExtra = "") {
  if (!SKILL_RE.test(skill)) throw Error("Invalid skill id");
  const parent = join(root, skill, kind);
  mkdirSync(parent, { recursive: true });
  const staging = join(parent, `.pending-${randomUUID()}`);
  mkdirSync(staging);
  try {
    const h = createHash("sha256").update(hashExtra);
    for (const f of files) {
      if (!f.bytes.length) throw Error("Empty file " + f.name);
      h.update(f.name).update(f.bytes);
      const p = join(staging, f.name);
      mkdirSync(dirname(p), { recursive: true });
      writeFileSync(p, f.bytes);
    }
    const revision = h.digest("hex");
    const manifest = { revision, ...manifestOf(revision) };
    writeFileSync(join(staging, "manifest.json"), JSON.stringify(manifest));
    const destination = join(parent, revision);
    if (!existsSync(destination)) renameSync(staging, destination);
    const pointer = join(parent, `.current-${randomUUID()}.json`);
    writeFileSync(pointer, JSON.stringify(manifest));
    renameSync(pointer, join(parent, "current.json"));
    return manifest;
  } finally { rmSync(staging, { recursive: true, force: true }); }
}
