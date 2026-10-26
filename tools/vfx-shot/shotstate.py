"""Fingerprints of what a warm capture stack serves: content, resource pack inputs and plugin code.

usage:
  python3 -I shotstate.py inputs <repo-root>          JSON {contentSourceHash, packHash, codeHash}
  python3 -I shotstate.py stage  <repo-root> <dest>   copy the content/ working tree (tracked + untracked, not ignored)
                                                       into <dest> (emptied first); prints the file count

All hashes cover the working tree, so uncommitted edits count. `content/` is staged from `git ls-files`, so what
the server gets is exactly what the hash says (gitignored scratch files never reach the server or the hash).
"""
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

# Inputs of :tools:resource-pack:buildResourcePack (tools/resource-pack/build.gradle.kts and its sources) plus the
# content folders the pack tool also reads (items for generated models, particle skins, fonts).
PACK_CONTENT_DIRS = ("textures", "models", "sounds", "hud", "fonts", "pack-assets", "shader-patches",
                     "particle-skins", "items", "sprites")
PACK_CODE_DIRS = ("tools/resource-pack",)
# Everything that ends up inside the 7 plugin jars.
# kernel/ and core/ (feature-*) are shaded into astera-core: a change there must rebuild and restart too.
CODE_PATHS = ("plugin", "kernel", "core", "buildSrc", "gradle", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "config")


def files(root, *paths):
    out = subprocess.check_output(["git", "ls-files", "-c", "-o", "--exclude-standard", "-z", "--", *paths],
                                  cwd=root).decode()
    # `git ls-files -c` lists a tracked file that was deleted in the working tree; skip those.
    return sorted(n for n in out.split("\0") if n and (Path(root) / n).is_file())


def digest(root, names):
    h = hashlib.sha256()
    for n in names:
        h.update(n.encode() + b"\0")
        h.update(hashlib.sha256((Path(root) / n).read_bytes()).digest())
    return h.hexdigest()[:16]


def inputs(root):
    # plugin/*/build is gitignored, so jars and caches never enter the code hash.
    return {
        "contentSourceHash": digest(root, files(root, "content")),
        "packHash": digest(root, files(root, *(f"content/{d}" for d in PACK_CONTENT_DIRS), *PACK_CODE_DIRS)),
        "codeHash": digest(root, files(root, *CODE_PATHS)),
    }


def stage(root, dest):
    dest = Path(dest)
    shutil.rmtree(dest, ignore_errors=True)
    names = files(root, "content")
    for n in names:
        target = dest / Path(n).relative_to("content")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(Path(root) / n, target)
    return len(names)


if __name__ == "__main__":
    if sys.argv[1] == "inputs":
        print(json.dumps(inputs(sys.argv[2])))
    elif sys.argv[1] == "stage":
        print(stage(sys.argv[2], sys.argv[3]))
    else:
        sys.exit("usage: shotstate.py inputs|stage ...")
