"""zip a directory tree (paths relative to the dir) -- stand-in for `zip`, which the dev container may lack.

usage: python3 -I mkzip.py <src-dir> <out.zip>
"""
import os
import sys
import zipfile

src, out = sys.argv[1], sys.argv[2]
os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _dirs, files in os.walk(src):
        for name in sorted(files):
            full = os.path.join(root, name)
            z.write(full, os.path.relpath(full, src))
