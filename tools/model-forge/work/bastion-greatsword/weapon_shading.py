"""Mark a held weapon model for the weapon shading rule of ADR-0253.

Every face gets tintindex 0 and the model gets `astera_tints: [0xFEFEFE]`; the pack builder turns that into a
`minecraft:constant` tint, so the item reaches the patched core/item.vsh with vertex colour (254, 254, 254), which
the shader reads as "held weapon": colour back to white, same lighting rule as the weapon-rig parts (lifted
underside). Rig parts and held item then shade identically from every angle.

    python3 -I weapon_shading.py content/models/items/bastion-greatsword.model.json   # in place
"""
import json
import sys
from pathlib import Path

MARKER_RGB = 0xFEFEFE


def mark(model: dict) -> dict:
    model['astera_tints'] = [MARKER_RGB]
    for element in model['elements']:
        for face in element['faces'].values():
            face['tintindex'] = 0
    return model


if __name__ == '__main__':
    for arg in sys.argv[1:]:
        path = Path(arg)
        path.write_text(json.dumps(mark(json.loads(path.read_text())), indent=2) + '\n')
        print('marked', path)
