# Modeler knowledge base

Agent: `.claude/agents/model-artist.md`. Team overview: [README.md](README.md).

## What the modeler produces

A weapon model (Java cuboid item model + textures) under `content/models/` that matches the reference silhouette, uses several materials, and sits correctly in the hand. Quality target from [model-forge AGENT.md](../../../tools/model-forge/AGENT.md): 80-200 elements for a hero prop, deliberate stepped bevels, separate ornament pieces, 64-128 px textures. The 40-element imported hammer and 42-element API demo are tooling evidence, not finished hero assets. Never add invisible cubes to reach a count.

## Verified facts and where they live

### Tool
- Tool: `python3 tools/model-forge/forge.py --project <dir> call <operation> '<JSON>'` (also REST / MCP). Operation table (`scene.get`, `element.*`, `batch`, `texture.*`, `uv.auto`, `board`, `compare`, `measure`, `validate`, `history.*`, `export`): [README](../../../tools/model-forge/README.md). Never edit `project.json` by hand.
- Round protocol (observe -> look -> compare with the locked concept -> CRITIC with at most 3 fixes -> one `batch` -> look again -> separate VERIFIER -> stop after plateau of two rounds or six rounds): [AGENT.md](../../../tools/model-forge/AGENT.md). A successful render call that you did not open is not a visual review.
- Recipe flow for staged builds (blockout, shape, details, texture, polish): [RECIPES.md](../../../tools/model-forge/RECIPES.md) and `docs/art/3d-model-pipeline.md`.
- Hand/context finish check, mandatory: `python3 tools/model-forge/context_preview.py --out <dir> [item ids]` (needs `bun install --frozen-lockfile` in `tools/skill-viewer` and local Chromium). It renders first-person R/L, third-person R/L + side, ground at two spin angles, frame and GUI. Check model-space grip vs the hand anchor, blade direction, and the Java left-hand signs. Client-only animation/FOV cannot be proven offline: record that explicitly.
- All-angle review: `context_preview.py --viewer` then `review.mjs`; the 26-direction sweep is only a first screen. Z-fighting: remove duplicate/covered faces, restructure cuboids, or move detail into texture before inflating; never hide it with viewer depth bias.
- Example batches and evidence format: `tools/model-forge/batches/` (`readability/` has before/after, boards, `check_coplanar.py`, `review.md`).

### Vanilla-material direction (another session is piloting; align)
- Branch `wp/vanilla-material-uv` (commit `0ee4829f`, "texture verdict hammer with vanilla materials"). Per that branch's `tools/model-forge/AGENT.md`/`README.md`: reference vanilla `minecraft:block/...` / `item/...` textures; pick semantic names from `materials.json` (e.g. `gem.diamond_block`, `cloth.brown_wool`, `wood.stripped_dark_oak_log`); `material.apply` crops 1 texel per model unit with deterministic varied offsets and grain along the longest axis, splitting cuboids over 16 units; custom atlases only for emblems, glow lines, runes; one model may mix both; vanilla slots are read-only.
- Its evidence lives at `tools/model-forge/batches/vanilla-material/` (`review.md`, before/after boards) **on that branch only**. The directory is not on main and was not present in the branch listing I could inspect from this worktree (`git ls-tree` of that path was empty), so read it by checking out or `git show wp/vanilla-material-uv:<path>` before relying on details. Do not fork a competing texturing scheme; if the pilot has not merged, follow it and flag conflicts to the director.

### Grip orientation
- The weapon appears through three paths: held item (Paper item display transforms), the rig `parts` boxes during the swing (animator's rig, local blade axis +Y at `rest` pitch 90 -> forward), and the vanilla hand. A model that is "slightly rotated" in hand is usually a display-transform problem (`display.firstperson_righthand` etc.), not geometry. Check R and L, first and third person.
- Previous pass flaw: "slightly wrong sword orientation in hand". Test with `context_preview.py` and confirm the blade axis points along the reference direction in all four hand contexts.

## Quality checks to run every round

1. 8-view board against the reference crop per view (`compare.references` with matching crops).
2. Context boards opened (hands, GUI, ground, fixed).
3. 16/32/64 px inventory read test.
4. `measure` ratios vs the brief tolerances; `validate` clean.
5. Silhouette: no hoop-like uniform boxes; edge highlights on blade edges; at least three materials (metal, trim, cloth/gem/emissive) unless the reference is monochrome.

## Pitfalls (from the repo's accepted facts)

- Rejected looks that apply to models: a single-colour slab, uniform-box curved shapes (hoops/crescents from uniform cubes), poles standing in for tapered blades.
- A rig that borrows the model as BlockDisplay boxes (vfx-art-direction section 7) uses `block:` parts in the documented example; coordinate with the director on whether the swing uses the model or a box approximation.
- Only one weapon may be visible while a skill plays (mechanic `hide_held_item`, only on `wp/pilot-bastion-s1`, not main). Model changes must not assume the held item and the rig weapon both show.

## Learnings log

(Append dated bullets after each skill. Format: `- YYYY-MM-DD <case>: <fact / pitfall / number>`.)

- 2026-10-23 seed: created from repo docs; no skill run yet.
- 2026-10-24 bastion-greatsword S1 pilot (modeler): the hand/GUI display transforms are anchored by a point, not by the model. When a model grows from 30 to 44 units, keep the accepted rotation and recompute only the translation so the old grip point (hands) or old bbox centre (gui/ground/fixed) stays put: `T_new = T_old + R*s_old*d_old - R*s_new*d_new`, with `R = Rx*Ry*Rz` (degrees XYZ), d = offset from model centre (8,8,8). Script: `tools/model-forge/work/bastion-greatsword/display_s1.py`. Model coords must stay in -16..32, so the grip origin of a 44-unit sword sits at model y=-10 (`OFF` in `build_s1.py`).
- 2026-10-24 Rig Euler gimbal: `Quaternion.fromEulerDegrees` is `Ry(-yaw)*Rx(pitch)*Rz(roll)`. At the usual `rest: [0,90,0]` (blade +Y -> forward +Z) yaw and roll are degenerate (both spin about the face normal), so you CANNOT roll the blade about its own axis through `rest`. Verified in vfx-preview: `[0,90,0]` = broad face UP/DOWN (edge-on from the side); `[0,90,90]` or `[90,90,0]` only swing the blade sideways in its plane. The 90-degree roll about the forward axis must come from the outer `tilt: [0,0,90]` (composition is `tilt*swing*rest`) or a constant swing roll; `rest [0,90,0] + tilt [0,0,90]` gives the REST look (broad face to the camera in the side view, edges up/down). Check the `side` view; the inlay window must be visible.
- 2026-10-24 Coplanar warnings: Forge's `validate` listed 237 `coplanar_contact_possible_zfight` for stacked boxes whose faces merely touch with opposite normals; the reviewer viewer's pair detector (`data.json` `pairs`) found the one real same-direction pair (a trim sitting on the grip-collar top). Use the viewer pairs list to decide, then shift one box by its own thickness instead of adding an epsilon.
- 2026-10-24 Material crop at 1 texel/unit: a 0.8-unit-thick, 2.7-unit-wide blade face shows 1-texel horizontal bands from `metal.iron_block`; pale steel reads fine, but dark detail needs separate boxes (rim `stone.polished_blackstone`, fuller `stone.polished_deepslate`, edge `stone.smooth_quartz`, window `concrete.lime_concrete`). `metal.anvil` crops are too light to read as a dark fuller.
- 2026-10-24 Sandbox: in a worktree-isolated agent, shell lines with a variable in command position or heredoc text containing "git" get refused; use literal absolute paths and the Write tool for scripts.
