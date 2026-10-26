# couture-rapier (Couturier's Shears) v2 review

Items: `astera:items/couture-rapier` (closed, 108 elements), `couture-rapier-blade-a` (54), `couture-rapier-blade-b` (54).
Source: `tools/model-forge/work/couture-rapier-v2/` (`run.py <scratch>` rebuilds everything; `build.py` is the generator).

## Pivot and hinge
- Pivot = model point (8, 8, 8) = model centre = centre of the pearl rosette. Hinge axis = model +Z.
- Half A = front (z 8..9, petals to z 10.7), half B = back (z 7..8); B is the exact mirror of A in x and z about the pivot.
- Both half models keep the full-model coordinates, so an item_display of either half with the entity at the pivot rotates about the hinge when its transformation rotates about Z (no translation compensation needed).
- Rest pose = about 3 deg ajar per half (stair-stepped, no rotated blades). Open: A counter-clockwise (tip toward -x), B clockwise. Evidence `open-close.png` at 0/30/60 deg per half: no collisions, rings clear each other.

## Materials (vanilla patterns, constant tints only)
iron_block tints: body #3f4658, ridge #8089a3, edge #f4eaf0 (bevel strips z 0.22..0.78 vs body 0..1), black #1f232c (neck, rings), silver #cfd2dc (ring trim, both faces), pearl #fff3fa (rosette, studs, bead); white_wool #8a2f55 (thread bindings); pink_wool #e0809c (rosette heart).

## Display
gui rot [10,-15,-35] s0.48; first person [0,-35,12] t[0.5,1.0,-4.5] s0.42 (r2 after the real-client shot showed blades too large); third person [35,-90,0] t[0,2.4,1.5] s0.75; ground [75,0,-90] s0.36; fixed [0,180,-35] s0.62.

## Evidence
- `all-angles.png` (26 views; before the inner trim), `inventory.png` (16/32/64), `open-close.png`.
- `context/` context_preview boards both hands (first/third person, GUI, ground, fixed).
- `client-*.png`: real 26.3 client via local Paper server (`capture-client.py`), first person both hands, third person back/front both hands, fixed display and dropped item visible in the first shot. No model errors in log.
- validate: valid, 0 errors, 306 coplanar-contact warnings (touching bars; no flicker seen in client shots).

## Known weak points
- 16 px: blade plus ring only; the two-loop silhouette appears from 32 px.
- First person hides the rings (sword-like grip, intended); third person shows rings below the hand.
- `content/models/generated/items/couture-rapier.model.json` (flat sprite fallback) is untouched.
