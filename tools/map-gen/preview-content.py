#!/usr/bin/env python3
"""Make the authorised TEMPORARY generator-coordinate handoff preview; never edits repository generators."""
import json
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[2]
OUT = Path('/tmp/claude-1000/anni-map/preview-content')
shutil.copytree(ROOT / 'content', OUT, dirs_exist_ok=True)
data = json.loads(Path('/tmp/claude-1000/anni-map/coordinates.json').read_text())
for path in (OUT / 'generators').glob('annihilation-*.yaml'):
    text = path.read_text()
    if 'match_stage_id: annihilation-arena\n' not in text:
        continue
    name = re.search(r'^id: annihilation-(.*)$', text, re.M)[1]
    key = name if name == 'center-wave' else name + '-1'
    x, y, z = data['stars'][key]
    text = re.sub(r'^position: .*$', f'position: {{ x: {x + .5}, y: {y}.0, z: {z + .5} }}', text, flags=re.M)
    # Coordinates only; amounts, timings and the existing radius 16 stay as authored by star-economy.
    path.write_text(text)
print(OUT)
