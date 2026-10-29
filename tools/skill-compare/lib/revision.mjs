// Tree provenance shared by the compare server (current tree), skill-preview (stamps its meta.json) and the publish scripts.
// contentRevision is the same fingerprint as tools/vfx-shot/capture_meta.py:
// `git rev-parse HEAD:content` plus "+dirty-<sha256[:12]>" over the uncommitted/untracked files under content/.
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";

const git = (repo, ...a) => execFileSync("git", ["-C", repo, ...a], { encoding: "utf8", maxBuffer: 1 << 26 }).trim();

export function contentRevision(repo) {
  const names = [...new Set(git(repo, "ls-files", "-m", "-d", "-o", "--exclude-standard", "-z", "--", "content").split("\0").filter(Boolean))].sort();
  const d = createHash("sha256");
  for (const n of names) {
    const f = join(repo, n);
    d.update(Buffer.from(n + "\0"));
    d.update(existsSync(f) ? readFileSync(f) : Buffer.from("<deleted>"));
  }
  return { contentRevision: git(repo, "rev-parse", "HEAD:content") + (names.length ? "+dirty-" + d.digest("hex").slice(0, 12) : ""), contentDirty: names.length > 0 };
}

/** What a view (preview / lint report / capture) must record about the tree it was made from. */
export function stampTree(repo) {
  let codeDirty = false;
  try { codeDirty = git(repo, "status", "--porcelain", "--untracked-files=no", "--", ".", ":(exclude)content") !== ""; } catch { /* keep false */ }
  return {
    branch: git(repo, "rev-parse", "--abbrev-ref", "HEAD"), gitRevision: git(repo, "rev-parse", "HEAD"),
    ...contentRevision(repo), codeDirty, capturedAt: new Date().toISOString(),
  };
}

/** The checkout that owns the shared build/ (the primary worktree), also when called from .claude/worktrees/<slug>. */
export function primaryRepo(repo) {
  try { return dirname(git(repo, "rev-parse", "--path-format=absolute", "--git-common-dir")); } catch { return repo; }
}

export const publishedRoot = (repo) => process.env.SKILL_COMPARE_PUBLISHED || join(primaryRepo(repo), "build", "skill-compare-published");
