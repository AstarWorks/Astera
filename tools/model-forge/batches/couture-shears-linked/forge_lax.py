#!/usr/bin/env python3
"""forge.py with the vanilla 22.5-step rotation-angle enum dropped (26.3 client accepts any float angle;
same relaxation as couture-shears-open/validate_open.py). Everything else is unchanged."""
import sys
from pathlib import Path
FORGE = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(FORGE))
import core  # noqa: E402
_orig = core.schema_errors
core.schema_errors = lambda *a, **k: [e for e in _orig(*a, **k)
                                      if not (e.endswith("not in [-45, -22.5, 0, 22.5, 45]") and ".rotation.angle" in e)]
import forge  # noqa: E402
forge.main()
