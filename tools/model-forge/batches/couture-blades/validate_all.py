#!/usr/bin/env python3
"""model-forge validate over every couture-rapier model (drops only the 22.5-step rotation enum, see open_angles.py).
needs MODEL_FORGE_CLIENT_JAR (preview input only)."""
import collections, sys
from pathlib import Path
FORGE = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(FORGE))
from recipe import ROOT, load_model, texture_map  # noqa: E402
import validate as V  # noqa: E402
_orig = V.schema_errors
V.schema_errors = lambda v, s, path='$': [e for e in _orig(v, s, path) if not (e.endswith("not in [-45, -22.5, 0, 22.5, 45]") and '.rotation.angle' in e)]
names = ['couture-rapier'] + [f'couture-rapier-{s}' for s in ('open-15', 'open-30', 'open-45', 'open-60', 'blade-1', 'blade-2', 'blade-3', 'thrown-flight', 'thrown-stuck')]
for name in names:
    m = load_model(ROOT / f'content/models/items/{name}.model.json')
    r = V.validate(m, texture_map(m))
    kinds = collections.Counter((w if isinstance(w, str) else w.get('kind')) for w in r['warnings'])
    errs = collections.Counter(e.split(':', 1)[-1].strip() for e in r['errors'])
    print(name, 'valid', r['valid'], 'elements', r.get('elements'), 'errors', dict(errs), 'warnings', dict(kinds))
