# Couture rapier — restored palette

The source palette is `36743b70:content/models/items/couture-rapier.model.json`, sampled from its original 32×32 authored atlas. `before.png` renders that revision through Forge. Current HEAD geometry and display are imported separately before recolouring.

| Part | Original RGB | Vanilla pattern | Tint |
|---|---|---|---|
| Blade bodies (`e0001`, `e0003`, `e0005`, `e0012`, `e0014`, `e0016`) | 52,58,72 | iron_block | #3c4353 |
| Cutting edges (`e0002`, `e0004`, `e0006`, `e0013`, `e0015`, `e0017`) | 213,193,203 | iron_block | #f5deea |
| Open metal handles (`e0007–e0011`, `e0018–e0022`) | 28,31,39 | iron_block | #20242d |
| Flower pivot (`e0023–e0026`) | 242,230,236 | iron_block | #fff3fa |

All parts are metal. Light iron supplies the vanilla pattern without restricting the custom hue. Multiplication approximates the original atlas colours; the pearl pivot is slightly darker than the original painted swatch. No custom or derived PNG textures were added. Thin blades retain longitudinal, interior iron crops at one texel per local model unit. The blackened metal handles replace stone and remain opaque.

## Visual review

Opened original `before.png`, final `after.png`, `contexts.png`, `forge-contexts.png`, `inventory.png` and the 26-direction `all-angles.png`. The dark blue-gray blade bodies, rose-silver cutting edges and pearl flower restore the original scissor identity. Pale edges and flower separate the dark blade bodies in the GUI; 16px necessarily reduces the very thin outline to a few pixels. Both hands, third-person side, ground/spin and fixed retain the original silhouette. The current display puts most of the handle below the first-person screen and presents the blades edge-on; these transforms are preserved exactly.

Critic: flat white blades had lost the original dark gunmetal/rose edge separation; stone handles also lost their metal surface. Fix: four constant tints on light iron, with blade crops oriented along the length. Verifier: original/after cameras show restored part hues, continuous opaque handle loops and unchanged joins; no newly stretched pixels or geometry regressions. No concept image was supplied; the old model is palette/regression evidence only. Forge validation has zero errors and the same 54 conservative overlap warnings; intentional pre-existing intersections remain.

## Reproduction

```sh
git show 36743b70:content/models/items/couture-rapier.model.json > /tmp/couture-original.json
git show <pre-recolour-parent>:content/models/items/couture-rapier.model.json > /tmp/couture-current.json
(cd tools/skill-viewer && bun install --frozen-lockfile)
python3 tools/model-forge/batches/vanilla-material/couture-rapier/remake.py \
  --project /tmp/couture-fresh --before /tmp/couture-original.json --current /tmp/couture-current.json
python3 tools/model-forge/context_preview.py --viewer --out build/couture-rapier-reviewer couture-rapier
bun tools/model-forge/review.mjs build/couture-rapier-reviewer/viewer.html build/couture-rapier-review couture-rapier
flock /tmp/claude-1000/astera-mc-client.lock python3 tools/model-forge/batches/vanilla-material/couture-rapier/capture-client.py --world-source <isolated-creative-superflat-world>
```

`remake.py` asserts all 26 element geometries, face sets and current display are preserved. `operations.json` records imports, snapshots, material and UV operations, validation and export. The viewer embeds cached vanilla pixels and stays in ignored `build/`. `cameras.json` records 35 exact viewer camera/context captures.

## Native client and limits

The minimal pack mirrors `ResourcePackBuilder.addModels/itemDefinition`: strips `astera_tints` from the cuboid JSON and emits each RGB as a `minecraft:constant` item tint in palette order, retaining all face tint indices. Vanilla references remain native block-atlas sources; no Mojang PNGs are shipped. This is builder-equivalent assembly, not a production pack build or server deployment.

Native capture uses the installed portablemc 26.3 runtime, dynamically allocated Xvfb display, llvmpipe, 1280×720, FOV 70, bob off and GUI scale 2. The entire launch/capture holds the shared client flock, using `build/couture-rapier-client`. Only the isolated copy of the source world has commands enabled. Screenshots are unedited Xvfb root captures. Opened both `client.png` and `client-thirdperson.png`: the gunmetal bodies, rose edges, opaque black loops and pearl pivot all render. The broad face visible in front of the third-person character belongs to the stationary fixed-context item display, not the held model.

The native log has no missing-model, texture, tint or mixed-atlas errors. Offline-account authentication/Realms and unavailable audio-device errors are unrelated to model rendering. The initial optional item-display scale command was rejected by the 26.3 transformation codec; the display rendered at its default unit scale. The reproducer now omits that optional transform, reproducing the captured effective state without that warning.

Remaining risks: production pack/integration checks remain with the manager; offline lighting/mannequin differs from native perspective; swing, FOV changes and shaders are unverified; pre-existing 54 overlap candidates were retained. No catalog wishes: tintable iron covers all four metal finishes. No Gradle, tests, E2E, Docker or push was run.
