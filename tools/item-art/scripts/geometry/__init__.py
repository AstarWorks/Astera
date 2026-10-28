"""Formula-driven geometric pattern toolkit for pixel art (frames, GUI tiles, HUD, item art).

Every function takes pixel-centre coordinate arrays (see core.grid) and returns a boolean mask or an
integer level map. No anti-aliasing: coverage is thresholded, shading is quantised to a few levels.
Catalogue + formulas: docs/art/geometric-patterns.md.
"""
from .core import *      # noqa: F401,F403
from .patterns import *  # noqa: F401,F403
