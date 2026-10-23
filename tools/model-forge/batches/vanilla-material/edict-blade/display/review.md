# edict-blade — display correction

The inherited first-person rotation/placement left the cuffs as oversized white
blocks cut off at the screen bottom. GUI scale 0.65 and Y=-3 made the pair tiny and
low in its slot. First-person scale is now 0.55, rotation [0,-25,0], translation
[-1.5,4,-6], bringing both gloves, cuffs and their chain into view. Third-person
scale 0.65, rotation [0,0,0] and translation [0,1,0] keep the pair beside the hand.
GUI scale 1.6 and Y=0.35 center and enlarge the pair.

Opened both context boards and all five native screenshots. Both gloves and cuffs
are readable in first person without covering the screen bottom or crosshair;
the pair remains visible in either third-person hand. The inventory/hotbar icon
now shows two distinct white gloves with copper cuffs. Ground/fixed are unchanged.

## Evidence

- [Before context board](context-before.png) / [after context board](context-after.png):
  nine views, including both first/third-person hands, side, GUI, ground and fixed.
- [Main-hand first person](client-firstperson.png), with native hotbar icon.
- [Inventory](client-inventory.png), with the actual GUI slot and held mannequin.
- [Offhand first person](client-firstperson-lefthand.png).
- [Third-person main hand](client-thirdperson-righthand.png) /
  [offhand](client-thirdperson-lefthand.png).
- `evidence.json`: source hashes, exact display changes, image hashes and validation.

Native images are unedited 1280×720 Xvfb root captures from portablemc Minecraft
Java 26.3, llvmpipe, FOV 70, view bob off, GUI scale 2. A fresh commands-enabled
creative superflat (seed 20261024, structures off, peaceful, noon, clear weather)
is isolated in this worktree's `build/display/client`; survival mode is used only
for the inventory UI. LAN remains off. A dynamic display and the shared client
flock cover launch through teardown. The capture pack uses the exact authored
JSON and any existing custom textures; no Mojang texture pixels are shipped.

Reproduce all three models with the shared
[capture script](../../crimson-chain-axe/display/capture-client.py), after preparing
the save described in its docstring:

```sh
flock /tmp/claude-1000/astera-mc-client.lock python3 tools/model-forge/batches/vanilla-material/crimson-chain-axe/display/capture-client.py
python3 tools/model-forge/context_preview.py --out build/display/context-replay edict-blade
```

Only the model's `display` block changed. Geometry, faces, materials, UVs and
textures compare exactly with baseline `a58afeb98e5be0af9975c124c257f6637e25efbc`.
Forge import validation reports zero errors; inherited geometry warnings remain.
No tests, Gradle, Docker, E2E, deployment or push was run.

Risks: idle FOV-70 views are verified; swing/use animations, other FOV settings,
shaders and the assembled production resource pack were not exercised.
