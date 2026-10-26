#!/usr/bin/env python3
"""Run model-forge validate on the closed model and the open-angle models; print errors/warning kinds."""
import collections, sys
from pathlib import Path
HERE = Path(__file__).resolve()
FORGE = HERE.parents[2]
sys.path.insert(0, str(FORGE))
from recipe import ROOT, load_model, texture_map  # noqa: E402
import validate as V  # noqa: E402
validate = V.validate
_orig = V.schema_errors
# The 26.3 client takes any float angle (CuboidRotation.SingleAxisRotation); the forge schema still enforces
# the vanilla 22.5-step / +-45 enum. Drop only that enum error so the remaining art checks run.
V.schema_errors = lambda v, s, path='$': [e for e in _orig(v, s, path) if not (e.endswith("not in [-45, -22.5, 0, 22.5, 45]") and '.rotation.angle' in e)]

for name in ['couture-rapier'] + [f'couture-rapier-open-{t}' for t in (15, 30, 45, 60)]:
    m = load_model(ROOT/f'content/models/items/{name}.model.json')
    r = validate(m, texture_map(m))
    kinds = collections.Counter((w if isinstance(w, str) else w.get('kind')) for w in r['warnings'])
    errs = collections.Counter(e.split(':', 1)[-1].strip() for e in r['errors'])
    print(name, 'valid', r['valid'], 'elements', r.get('elements'), 'errors', dict(errs), 'warnings', dict(kinds),
          'bounds', r.get('rotated_bounds'))
