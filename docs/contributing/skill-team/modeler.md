# Modeler knowledge base

Agent: `.claude/agents/limbus-modeler.md`. Team overview: [README.md](README.md).

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
