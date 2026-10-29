#!/usr/bin/env python3
"""knight-sword-t2..t4 reuse the base knight-sword geometry and frame; only the blade steel tint changes per tier."""
import json
from pathlib import Path
ROOT = Path(__file__).resolve().parents[4]
base = json.loads((ROOT / 'content/models/items/knight-sword.model.json').read_text())
frame = (ROOT / 'content/frames/knight-sword.yaml').read_text()
# tint indices 1 (blade web), 2 (edge bevel), 5 (fuller inset) are the steel; the rest (leather, gold) stay.
TIERS = {2: None, 3: {1: 0x9ec0e8, 2: 0xd9edff, 5: 0x6f93c2}, 4: {1: 0xb9a0e0, 2: 0xefe0ff, 5: 0x7a5fb0}}
for n, ch in TIERS.items():
    m = json.loads(json.dumps(base))
    if ch:
        for i, v in ch.items(): m['astera_tints'][i] = v
    (ROOT / f'content/models/items/knight-sword-t{n}.model.json').write_text(json.dumps(m, indent=2) + '\n')
    text = frame.replace('item: knight-sword\n', f'item: knight-sword-t{n}\n').replace('model: astera:knight-sword', f'model: astera:knight-sword-t{n}')
    text = text.replace('# Point table for the knight-sword ', f'# Point table for the knight-sword-t{n} (same geometry, display and points as knight-sword; only the steel tint differs) ', 1)
    (ROOT / f'content/frames/knight-sword-t{n}.yaml').write_text(text)
