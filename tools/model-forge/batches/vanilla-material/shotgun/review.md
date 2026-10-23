# Shotgun palette restoration — ADR-0251

The reference is `36743b70:content/models/items/shotgun.model.json`, rendered by
Forge as [before.png](before.png). Its original PNG is byte-identical to the
current source texture. The **current HEAD** supplies geometry and display,
including all 127 existing cuboids and internal split-face omissions.

| Parts | Vanilla pattern | Tint |
|---|---|---|
| Barrel, receiver plates/bevels, muzzle, trigger, screws | iron block | `#c5dbe6` |
| Receiver, lower tube, carrier, guard heel | iron block | `#242d3a` |
| Pump ferrules, front guard | iron block | `#3b4858` |
| Rail teeth | iron block | `#a2b4bf` |
| Stock, wrist, pump and wooden ribs | stripped oak | `#b58d6b` |
| Cheek comb | stripped oak | `#ffdfaa` |
| Shell cases and identity stripes | white concrete | `#ec4164` |
| Shell seams and selectors | white concrete | `#ff9caa` |
| Brass shell heads | iron block | `#fff0a5` |
| Buttpad and buttplate ribs | white wool | `#3f4c5d` |
| Open bore | white concrete | `#151d28` |

The historical painted palette contains dark blue steel `(32,40,51)`, silver
`(176,195,205)`, walnut `(117,71,42)`, warm comb `(184,127,64)`, rose shells
`(236,65,100)` and pale brass `(255,240,165)`. Light iron/concrete compensate
for multiplication; stripped oak preserves wood grain with a brown tint.
This restores the silver barrel rather than retaining the first pass's netherite.
No texture sprite, custom PNG, shared catalog or Forge implementation is changed.

## Visual review and verification

Opened the before/after eight-angle boards, all seven Forge contexts, the
26-direction browser sweep, all nine player contexts, and 16/32/64px inventory.
Silver barrel/plates contrast against the blue-black receiver. Walnut pump/stock
and pink-red shells retain the original identity. End-on bore stays dark; thin
metal edges remain readable. Grain and split seams show no new conspicuous defect.
At 16px the narrow silhouette dominates; wood/silver contrast survives, while
individual shell detail needs 32/64px. No geometry or transform fix was proposed.
The verifier independently compares every non-face element field, face-key set
and display block against HEAD, checks one texel/unit on every face, zero Forge
validation errors, and exact export/reimport. See [verification.json](verification.json).
Existing overlap/contact warnings remain conservative and are not a full geometry audit.

Native [client.png](client.png) and [client-offhand.png](client-offhand.png) were
opened after a successful capture under the shared client lock. Both are raw
1280×720 Xvfb screenshots from the real 26.3 client in an isolated creative
superflat. The tinted wood, silver-blue barrel, blue-black receiver, rose shells
and pale brass render correctly; both hands leave the screen center clear.
The stock continues below the screen with the preserved display transform.
The log contains no model, atlas or tint errors; offline-account/Realms and
unavailable-audio messages are unrelated.

## Reproduce

```sh
git show 36743b70:content/models/items/shotgun.model.json > /tmp/shotgun-original.json
python3 tools/model-forge/batches/vanilla-material/shotgun/remake.py \
  --project /tmp/shotgun-tint-fresh --before /tmp/shotgun-original.json
python3 tools/model-forge/context_preview.py --viewer --out build/shotgun-reviewer shotgun
bun tools/model-forge/review.mjs build/shotgun-reviewer/viewer.html build/shotgun-angle-review
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/shotgun/capture-client.py \
  --world-source '<isolated 26.3 superflat world directory>'
```

The minimal capture pack follows `ResourcePackBuilder.addModels/modelDefinition`:
remove `astera_tints` from cuboid JSON and emit that RGB table as native
`minecraft:constant` sources in `assets/astera/items/shotgun.json`. Each face keeps
its tintindex. It references only native block textures and includes no PNGs.
This does not claim the production ZIP was built or deployed.

## Remaining risks / catalog wishes

- Stripped birch would offer a lighter neutral wood base; the catalog lacks it.
  Stripped oak is the closest available pattern and works with the brown tint.
- Native lighting/perspective differs from Forge; use animations, shader packs,
  and production pack assembly/deployment remain manager verification.
- Pre-existing overlapping bevels and tiny pieces are retained with geometry.
- No Gradle tests, unit tests, E2E, Docker, standalone servers or push were run.
