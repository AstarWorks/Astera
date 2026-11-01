#!/usr/bin/env python3
"""Run a review round; retain raw failures and explicitly labelled temporary generator-coordinate preview."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
PROOF = Path('/tmp/claude-1000/anni-map')
round_dir = PROOF / ('round-' + sys.argv[1])
round_dir.mkdir(parents=True, exist_ok=True)
results = {}

def run(label, command):
    start = time.monotonic()
    with (round_dir / (label + '.log')).open('w') as log:
        result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    results[label] = {'seconds': round(time.monotonic() - start, 2), 'exit': result.returncode}
    print(label, results[label], flush=True)
    return result.returncode

check = run('content-lint', ['./gradlew', ':tools:content-lint:run'])
raw = run('make-arena-preview-repository', ['make', 'arena-preview'])
# The temporary content is authorised in the brief; only generator coordinates are patched.
subprocess.run(['python3', 'tools/map-gen/preview-content.py'], cwd=ROOT, check=True)
preview = run('preview-temporary-coordinates', [str(ROOT / 'tools/arena-preview/build/install/arena-preview/bin/arena-preview'),
                                               str(PROOF / 'preview-content'), str(ROOT / 'build/arena-previews')])
for kind in ('top', 'iso', 'height', 'section'):
    shutil.copy2(ROOT / f'build/arena-previews/annihilation-arena-{kind}.png', round_dir)
shutil.copy2(ROOT / 'build/arena-previews/report.md', round_dir / 'report.md')
results['arena_bytes'] = (ROOT / 'content/arenas/annihilation-highmarch.yaml').stat().st_size
(round_dir / 'timings.json').write_text(json.dumps(results, indent=2) + '\n')
print(round_dir, flush=True)
sys.exit(check or preview)
