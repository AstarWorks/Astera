# crimson-chain-axe — display correction

All four held contexts had scale [0,0,0], so neither first-person nor
third-person hands could show the axe. Replaced those four contexts with the
knight-sword reference transforms: first-person scale 0.56, third-person scale 0.75,
with reference grip translations and rotations. Identical authored left/right
transforms let Minecraft apply its left-hand signs once.

Opened both context boards and all five native screenshots. The red axe head is
fully visible above the lower-right grip in first person; the offhand mirrors its
placement. Third-person screenshots show the haft at the hand with the head clear
of the body. GUI, ground and fixed transforms are unchanged.

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
python3 tools/model-forge/context_preview.py --out build/display/context-replay crimson-chain-axe
```

Only the model's `display` block changed. Geometry, faces, materials, UVs and
textures compare exactly with baseline `a58afeb98e5be0af9975c124c257f6637e25efbc`.
Forge import validation reports zero errors; inherited geometry warnings remain.
No tests, Gradle, Docker, E2E, deployment or push was run.

Risks: idle FOV-70 views are verified; swing/use animations, other FOV settings,
shaders and the assembled production resource pack were not exercised.
