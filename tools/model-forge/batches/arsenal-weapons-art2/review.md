# Arsenal weapons art 2 — review and handoff

31 simple volumetric held models; five native use renderers remain untouched.
No existing `content/models` file was edited. No item YAML or manifest edit is needed:
the new generated-model registrations select same-named authored models in the existing builder.

## Assets

Each custom model uses vanilla block texture references and constant tints; no new material PNG.
The generated 32px PNG is the locked concept-sheet conversion, retained for the existing registration pipeline.

| Item | Held model | Generated icon |
| --- | --- | --- |
| iron-blade | content/models/items/iron-blade.model.json | content/textures/generated/items/iron-blade.png |
| war-axe | content/models/items/war-axe.model.json | content/textures/generated/items/war-axe.png |
| spear | content/models/items/spear.model.json | content/textures/generated/items/spear.png |
| mace | content/models/items/mace.model.json | content/textures/generated/items/mace.png |
| twin-daggers | content/models/items/twin-daggers.model.json | content/textures/generated/items/twin-daggers.png |
| katana | content/models/items/katana.model.json | content/textures/generated/items/katana.png |
| halberd | content/models/items/halberd.model.json | content/textures/generated/items/halberd.png |
| rapier | content/models/items/rapier.model.json | content/textures/generated/items/rapier.png |
| cleaver | content/models/items/cleaver.model.json | content/textures/generated/items/cleaver.png |
| scythe | content/models/items/scythe.model.json | content/textures/generated/items/scythe.png |
| club | content/models/items/club.model.json | content/textures/generated/items/club.png |
| longbow | unchanged vanilla | unchanged vanilla |
| hand-crossbow | unchanged vanilla | unchanged vanilla |
| shortbow | unchanged vanilla | unchanged vanilla |
| repeater-crossbow | unchanged vanilla | unchanged vanilla |
| revolver | content/models/items/revolver.model.json | content/textures/generated/items/revolver.png |
| smg | content/models/items/smg.model.json | content/textures/generated/items/smg.png |
| sniper | content/models/items/sniper.model.json | content/textures/generated/items/sniper.png |
| harpoon | content/models/items/harpoon.model.json | content/textures/generated/items/harpoon.png |
| grappling-hook | content/models/items/grappling-hook.model.json | content/textures/generated/items/grappling-hook.png |
| throwing-knives | content/models/items/throwing-knives.model.json | content/textures/generated/items/throwing-knives.png |
| sticky-bomb | content/models/items/sticky-bomb.model.json | content/textures/generated/items/sticky-bomb.png |
| venom-flask | content/models/items/venom-flask.model.json | content/textures/generated/items/venom-flask.png |
| smoke-bomb | content/models/items/smoke-bomb.model.json | content/textures/generated/items/smoke-bomb.png |
| delay-mine | content/models/items/delay-mine.model.json | content/textures/generated/items/delay-mine.png |
| frag-grenade | content/models/items/frag-grenade.model.json | content/textures/generated/items/frag-grenade.png |
| flash-bang | content/models/items/flash-bang.model.json | content/textures/generated/items/flash-bang.png |
| shock-wand | content/models/items/shock-wand.model.json | content/textures/generated/items/shock-wand.png |
| ember-rod | content/models/items/ember-rod.model.json | content/textures/generated/items/ember-rod.png |
| frost-rod | content/models/items/frost-rod.model.json | content/textures/generated/items/frost-rod.png |
| fireball-charge | content/models/items/fireball-charge.model.json | content/textures/generated/items/fireball-charge.png |
| glacier-wand | content/models/items/glacier-wand.model.json | content/textures/generated/items/glacier-wand.png |
| sanctuary-staff | content/models/items/sanctuary-staff.model.json | content/textures/generated/items/sanctuary-staff.png |
| chain-rod | content/models/items/chain-rod.model.json | content/textures/generated/items/chain-rod.png |
| gale-fan | content/models/items/gale-fan.model.json | content/textures/generated/items/gale-fan.png |
| shield-bulwark | unchanged vanilla | unchanged vanilla |

## Review evidence

Root: `/tmp/claude-1000/codex/arsenal-weapons-art2/`.
- `hand-gui-final-0.png`, `hand-gui-final-1.png`: all 31 authored props, first person / third-person side / GUI.
- `player-final-0.png` through `player-final-7.png`: both hands, both third-person hands, side, ground/spin, frame and GUI.
- `inventory-final.png`: actual model read at 16/32/64 pixels.
- Per item: locked `concept.json`, Forge `project/project.json` with named round snapshots, operation/diff logs and canonical boards.
- `viewer-final/viewer.html` and `angles-batch-*`: final 26-direction sweeps and nine display contexts; embedded vanilla pixels stay in scratch.
- `pack-verification.txt`: actual ZIP selection, tints, geometry, native-model absence and no copied vanilla item/block PNGs.

Round 1 critic: three neutral masses did not yet identify most heads; no ornament or materials claimed complete.
Round 2 verifier: full category-specific shapes and material crops read in front/side/back and hand contexts.
Round 3 critic: fan had open slots, bottle lacked a neck, rapier/shock/glacier grips missed concept blue; corrected these and crystal tips.
Round 4 verifier: solid fan, bottle neck and blue grips improved; same-plane join candidates remained.
Rounds 4–6: restructure flanges, bow, chain links and overlaps; remove only fully covered opaque faces.
Verifier caught wrong collar/band element selections during cleanup, restored them and checked the same cameras again.
Final surface detector: zero parallel same-facing overlap candidates for every custom model.
All 1,085 final captures completed in seven browser batches without browser errors; all 26-direction montages (`orbit-final-0.png` through `orbit-final-15.png`) and final context boards were opened and inspected.
Bottom-view dark rectangles on canisters are opaque dark end caps with visible material pixels, not holes.
Intentional cuboid intersections and opposite-normal touching joints remain (Forge AABB warnings); they are continuous joins, not floating decoration.

Single image-generated icon per asset is locked by SHA, not a complete orthographic turnaround.
No fabricated per-view IoU is claimed. Reverse/side target fidelity remains unmeasured; eight construction views were inspected for volume and joins.
Simple silhouettes use 4–16 cuboids, below 64. No invisible detail was added to chase the hero-prop 80–200 recommendation.

## Checks

- `:tools:item-art:run` succeeded against a scratch content copy; only this lane’s 31 outputs copied back. Unrelated generated outputs and five native-use icons were excluded.
- `:tools:architecture-test:test` passed (structural checks explicitly allowed by the common brief).
- `:tools:resource-pack:buildResourcePack` and `:tools:e2e:compileTestKotlin` passed (see scratch build log).
- `python3 tools/model-forge/batches/arsenal-weapons-art2/verify_pack.py build/resource-pack/astera.zip` passed.
- Added `ArsenalWeaponArtE2eTest#arsenal gives select custom art while vanilla use items retain native models`; compile only, manager runs E2E.

## Open gates and owner requests

- Real client: pack loading, first-person swing/use/FOV, glint, dynamic motion and exact hand occlusion remain unverified. Offline boards are not live-client evidence.
- Keep longbow, shortbow, hand-crossbow, repeater-crossbow and shield-bulwark vanilla. **Do not regenerate item art globally until the item-art owner applies `native-use-items.patch`.** Current `ItemArt.weapons` otherwise generates static icons for these skill-bearing native items, removing their state renderer. The new E2E asserts no custom item_model for these five.
- `pack-assets` cannot override generated item definitions: `addVerbatim` rejects path collisions. No such colliding files are retained.
- Owner `wp/ops-harness`: append the 31 custom / five native status and pending real-client review to the modes-combat area spec. Ownership rules prevented editing it here.

## Documentation/code differences encountered

- `.claude/rules/ai-delegation.md` / `wave-execution.md` say the model/texture tooling was extracted, but the requested `tools/model-forge` and `tools/item-art` pipelines exist in this checkout; used the existing code.
- `docs/art/3d-model-pipeline.md` describes custom atlases, two texels/unit and <=64 elements; current Forge material authoring uses native block references, one texel/unit and no validator element cap. Used the current `material.apply` implementation and simple-prop budget.
- The modeler learning log says the manifest is required for item definitions; the current builder also selects same-named Studio models for generated registrations. Used that existing route.
- The pilot review says model bytes copy verbatim; the current builder strips `astera_tints` into item-definition constant tints. Verified both outputs in the built ZIP.

## Reproduce

Use a fresh scratch directory. Generate the spec with `gen_sheet.py` only for a deliberate new concept version.
Run `build.py blockout --out <scratch>`, inspect the images, then `shape`, inspect, then `polish`.
Run `cleanup.py --out <scratch>`, inspect, then `cleanup.py --finish --out <scratch>`.
Run shared `context_preview.py`, `review.mjs` in small item batches, inspect captures, build the pack and run `verify_pack.py`.
Existing model overwrite guards intentionally require a fresh worktree for a full rebuild.
