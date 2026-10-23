# Vanilla materials — vendetta-rifle

The original 178 cuboids, rotations, face coverage and display transforms are
unchanged. Every face uses `material.apply` at one texel per local model unit
(seed 20261023); no part exceeds 16 units and no splitting was needed. The old
painted item atlas is no longer referenced. All resources use the vanilla block
atlas; no custom or Mojang PNGs are shipped.

| Parts | Catalog material |
|---|---|
| Receiver, barrel, scope body, mounts, magazine, guard and fittings | `metal.netherite_block` |
| Stock, cheek comb, neck and dark wood forearm | `wood.stripped_dark_oak_log` (grain along each part's long axis; log ends on caps) |
| Grip/ribs, buttpad and buttplate ribs | `cloth.black_wool` |
| Bayonet including bevels/fullers, screws/fasteners, trigger and bolt lever | `metal.iron_block` |
| Muzzle bore, ports, ejection recess | `concrete.black_concrete` |
| Crimson collars/rings/bezel, red bayonet guard and identity stripes | `concrete.red_concrete` |
| Narrow magenta insets | `concrete.magenta_concrete` |
| Jewel facets | `gem.amethyst_block` |
| Exposed optic/lens facets | `gem.diamond_block` |
| Butt bindings | `cloth.red_wool` |

Opened `before.png`, `after.png` (same eight cameras), `contexts.png` (Forge),
`player-contexts.png` (nine mannequin/display contexts), `sweep.png` (24 oblique
and horizontal views), top/bottom captures and `inventory.png` (16/32/64 pixels).
The dark gunmetal, red trim and bright silver bayonet stay distinct. The wood
follows the stock/forearm length; thin red/pink accents use quiet concrete crops.
No new seams, stretched texels or gaps were visible. At 16 pixels the small scope
and jewels merge into the silhouette; the barrel/bayonet and stock remain legible.
In both first-person previews the original long stock extends below the viewport;
third-person side view keeps the muzzle ahead of the player. Transforms are retained.
No locked concept was supplied for this material-only task; before is a regression
reference, not an invented concept target. No geometry fix was proposed or applied.

Verifier: exact comparison of all non-face element properties, face directions
and all display transforms passes. Forge reports zero errors, 466 conservative
AABB overlaps, 39 possible coplanar contacts and one disconnected-component report,
identical to the original. The temporary mixed-atlas warning during material
assignment comes from the unused imported item slot; pruning unused slots and
re-importing the installed model removes it. These retained construction overlaps
are not a full z-fighting audit.

Reproduce from this commit's parent with fresh directories:

```sh
git show HEAD^:content/models/items/vendetta-rifle.model.json > /tmp/vendetta-before.json
python3 tools/model-forge/batches/vanilla-material/vendetta-rifle/remake.py \
  --project build/vendetta-reproduce --before /tmp/vendetta-before.json
python3 tools/model-forge/context_preview.py --out build/vendetta-contexts vendetta-rifle
python3 tools/model-forge/context_preview.py --viewer --out build/vendetta-viewer vendetta-rifle
bun tools/model-forge/review.mjs build/vendetta-viewer/viewer.html build/vendetta-sweep
```

The remake logs Forge operations and produces before/after/Forge contexts. Additional
mannequin contexts, sweep metadata and inventory enlargement are saved beside them.
The viewer HTML and texture cache stay local/ignored because they embed vanilla pixels.

Native capture uses the pilot's installed 26.3 portablemc runtime, unique Xvfb display
(`-displayfd`), llvmpipe, FOV 70, view bob off, GUI scale 2, 1280×720. The game directory
is `build/vendetta-rifle-client`, containing a private copy of the already-created
isolated creative superflat review world (no external server). `capture-pack.py`
installs only this exact model/item definition and the builder-equivalent blocks
atlas directory source; no vanilla PNGs. `capture-client.py` launches and captures
under the shared lock:

```sh
python3 tools/model-forge/batches/vanilla-material/vendetta-rifle/capture-pack.py \
  --out build/vendetta-rifle-client/resourcepacks/vanilla-material
# Prepare an isolated creative superflat save named "Vendetta material review".
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/vendetta-rifle/capture-client.py
```

Catalog wishes: quiet red metal and a magenta gemstone. Red/magenta concrete
and amethyst are the closest existing entries, with concrete chosen to avoid
noise on sub-unit accents. No catalog/shared-tool change was made.

Remaining risks: retained original bevel overlaps; small ornaments lose detail at
inventory size; no combat animation, other FOV or shader verification. Production
pack build, integration checks and deployment remain manager/CI responsibilities.
No Gradle, unit tests, E2E, Docker, external server or push was run.

Native verdict: opened `client.png`, the unedited 1280×720 Xvfb root capture.
The stock grain, dark receiver/scope, red/pink insets and silver bayonet render
correctly; the center crosshair is clear. The original stock continues below the
bottom-right viewport edge, agreeing with the mannequin preview. No missing-model,
missing-texture, multiple-atlas or duplicate-sprite messages occur in `client-log.txt`.
The offline launcher logs unavailable account/Realms requests, unavailable audio
and `minecraft:end_of_frame` post-effect; these do not prevent the material capture.
The screenshot verifies this isolated pack in native first person, not a production
pack deployment or combat animation. No visual fix was needed after native review.
