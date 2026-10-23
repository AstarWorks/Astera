# Crimson chain axe — extended vanilla catalog redo

| Parts (original IDs) | Catalog material |
| --- | --- |
| crimson haft (e0001, split at Y=16) | wood.stripped_crimson_stem, Y grain and end caps |
| crimson crescents (e0003–e0074, every third element) | concrete.red_concrete |
| raised ribs, socket, spike, bridge, rims and ruby seat | metal.netherite_block |
| cutting bevels, ferrules, chains, rivets, pins, stars and gem glint | metal.iron_block |
| ruby crown and facets (e0081–e0083) | gem.redstone_block |

The previous copper blade and wool ruby approximations are replaced. The low-noise
red concrete keeps the narrow stepped crescents crimson; red nether bricks would add
masonry seams across the blade. Netherite provides a subdued warm-dark socket and ribs,
with bright iron cutting edges and modeled chain links. Crimson stem restores a wine-red
haft instead of brown oak. Redstone supplies the ruby's red mineral surface.
Concrete remains matte rather than red metal; a static crimson metal is the only
remaining catalog wish. No custom PNG or Mojang PNG is shipped. All references share
the native block atlas at one texel per model unit (seed 20261023).

## Critic

Opened the original before.png, after.png, contexts.png, player-contexts.jpg,
sweep.png, inventory.png, detail.png and xray.png. The original asset from
36743b70a5074a438dcc20e4d58beb774be06d65 supplies regression evidence; no external
concept image was supplied, so external-concept fidelity remains unassessed.

- Silhouette and proportions: yes, unchanged in the eight canonical views and
  26-direction sweep. Thin edge-on blades remain deliberately thin.
- Ornament and joins: yes, stepped crescents, dark socket and bright chains remain
  distinct. Existing star/socket gaps, intersections and disconnected tip ornaments
  remain visible; geometry cleanup is outside this material-only task.
- UVs and color: yes, crimson blades now read red instead of copper-orange. The
  haft has longitudinal grain; no stretched faces or exposed split caps observed.
  detail.png records the rear/underside chain joins at yaw 142, pitch 28, zoom 2.2;
  xray.png records overlap inspection at yaw 35, pitch 15.
- Inventory: yes for the red axe outline at 16/32/64px. Chains, ruby facets and socket
  stars are not individually legible at 16px. inventory.png uses nearest-neighbor
  downsampling of the 32px GUI render for 16px, then enlarges for inspection.
- Hands: both first/third-person hands remain invisible because the original authored
  transforms have scale [0,0,0]. This is preserved, not repaired by the material pass.

## Verifier

All 164 exported cuboids' non-face properties, face sets and all display transforms
are exactly equal to the previous texture pass (compared against this change's parent).
Relative to the original 163-cuboid asset, only the same 20-unit haft split remains;
internal split faces are omitted. No new geometry or texture painting was introduced.
The before board is reproduced directly from the required original commit and is
byte-identical to the previous evidence's original before.png.

Forge validation: zero errors. Original warning counts: 297 AABB overlaps, 5 contact
candidates, 1 component report. After: 299 / 6 / 1, identical to the earlier pass.
These conservative candidates do not prove visible z-fighting. No view regression
was observed in the reviewed boards. 35 browser captures completed without errors.

Native verification: opened client.png from the real 26.3 client. Deep-red blades,
wine-red haft, iron chains and ruby are distinct; no missing-model cube or missing
texture pattern. The native red is darker than the offline render. Both held contexts
remain hidden, as expected from the zero scales. The screenshot uses the exact updated
JSON in the minimal pack, fixed item_display context, scale 1.7, FOV 70 and GUI hidden.
The capture setup initially exceeded chat's command length, then omitted required
rotation keys; the final display transformation was corrected with a separate data
merge before the screenshot. No model change was needed.

## Reproduction

From the repository root, with a fresh Forge directory:

```sh
git show 36743b70:content/models/items/crimson-chain-axe.model.json > /tmp/crimson-chain-axe-original.json
python3 tools/model-forge/batches/vanilla-material/crimson-chain-axe/remake.py --project /tmp/crimson-chain-axe-fresh --before /tmp/crimson-chain-axe-original.json
python3 tools/model-forge/context_preview.py --out build/crimson-chain-axe-contexts crimson-chain-axe
python3 tools/model-forge/context_preview.py --viewer --out build/crimson-chain-axe-viewer crimson-chain-axe
bun tools/model-forge/review.mjs build/crimson-chain-axe-viewer/viewer.html build/crimson-chain-axe-review crimson-chain-axe
python3 tools/model-forge/batches/vanilla-material/crimson-chain-axe/capture-pack.py --out build/crimson-chain-axe-client/resourcepacks/vanilla-material
```

Use the installed portablemc at ~/.cache/astera-vfx-shot/venv/bin/portablemc with
--main-dir ~/.cache/astera-vfx-shot/mcdir, start 26.3, --mc-dir pointing to a unique
crimson-chain-axe directory, --resolution 1280x720, software GL and LP_NUM_THREADS=4.
Wrap the entire client launch/capture lifetime in
`flock /tmp/claude-1000/astera-mc-client.lock bash <session-script>`; use Xvfb
-displayfd for a unique display. Enable file/vanilla-material, FOV 70, GUI scale 2,
view bob off. Use an isolated creative superflat with commands enabled, noon, clear
weather and peaceful. The redo uses a private copy of the existing isolated superflat
world, clears its showcase and creates a fresh exact-model item_display.

Give `minecraft:iron_axe[minecraft:item_model="astera:crimson-chain-axe"]`, then teleport
to `0 -60 3 180 0` and run these two commands (each stays below the chat input limit):

```text
/summon minecraft:item_display 0 -59 0 {item:{id:"minecraft:iron_axe",count:1,components:{"minecraft:item_model":"astera:crimson-chain-axe"}},item_display:"fixed"}
/data merge entity @e[type=item_display,limit=1,sort=nearest] {transformation:{translation:[0f,0.6f,0f],scale:[1.7f,1.7f,1.7f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]}}
```

Hide GUI with F1; capture the 1280×720 Xvfb root window using ImageMagick import.
Do not resize, paint or composite client.png. The entity's showcase scale does not
change the model's fixed transform.

## Remaining risks

Original invisible held contexts and small ornament gaps persist. Red concrete is
matte; it prioritizes the crimson identity over a metallic surface. Offline light
and hand guides differ from native rendering. Production pack compilation and manager
CI remain unexecuted; native evidence uses a minimal exact-model capture pack.
No Gradle tests, E2E, Docker, standalone server or push was run.
