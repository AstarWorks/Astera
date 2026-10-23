# Brief: limbus-modeler (3D weapon model) — bastion-greatsword

Read `common.md` first. Role: you build the weapon the caster holds and swings. Two deliverables: (A) the item model (what the player's inventory / dropped item / third-person hand show), (B) a block-display **rig-parts list** (what the S1 swing animation actually flies through the air, because rig parts are block displays only).

## Goal
Make the sword the reference character's greatsword: a **long, broad, flat slab blade** (Zwei-style), pale steel face, dark outlines, tapered point, ornate guard with a yellow-green inlay near the guard — instead of the current short gold blocky sword (see `tools/model-forge/batches/reviewer/bastion-greatsword.jpg`: a single-colour gold stubby sword is exactly what the founder rejected).

## Look at
- `/tmp/claude-1000/-IdeaProjects-Astera/de20caa0-f14b-4c18-b441-07a23b79f448/scratchpad/pilot/frames/`: `a00_t00_windup-start_sword-low-behind.jpg`, `f01_t42_recovery_f412.jpg` (best full-length view: the whole blade, guard and grip), `c01_t19_sword-drops-back_f378.jpg`, `f02_t48_final-pose_f421.jpg`, `d01_t29_windup2-deep_f392.jpg`.
- Beat rows 0, 9, 10, 20 in `case.json` (colours and proportions).
- Parallel pilot (another session): `/IdeaProjects/Astera/.claude/worktrees/vanilla-material-uv` (branch `wp/vanilla-material-uv`), `tools/model-forge/batches/vanilla-material/` (`remake.py`, `review.md`, `after.png`, `contexts.png`) and `tools/model-forge/work/verdict-hammer/`. **Align with it:** model faces use crops of vanilla block textures (`minecraft:block/...`, 1:1 texel density) via `material.apply` (e.g. `metal.iron_block`, `metal.gold_block`, `gem.diamond_block`, `stone.polished_blackstone`), not flat colours. Read its operations and reuse the same materials API; do not copy its hammer shapes. If that branch has merged materials/ops that differ from what you find in your checkout, follow the branch and say so in the handback.
- `tools/model-forge/AGENT.md` (round protocol: observe, board with all eight views, CRITIC, edit, look again, VERIFIER, stop; contexts preview with both hands; reviewer build).

## Measurable targets
| Item | Target | Tolerance |
|---|---|---|
| Total length grip-end to tip | 2.7 block-equivalent (grip 0.45, guard 0.12, blade 2.2) | +-5% |
| Blade width (base) | 0.24 block; tapers over the last 0.35 to a point, tip width <= 0.04 | +-10% |
| Blade thickness | 0.06 (broad face reads as a slab); with a central fuller / ridge visible on both faces | |
| Blade : width ratio | about 9 : 1 (it is a long thin slab, not a wide cleaver) | |
| Guard | wider than the blade: 0.62 across, 0.12 tall, with stepped bevel pieces (3 steps) and 2 end caps | |
| Grip | 0.45 long, dark wrapped, with pommel block | |
| Inlay | lime/yellow-green window (#B7D83A family) on the blade near the guard: 0.09 x 0.20, set into the face, sits 0.01 proud | |
| Colours | blade face pale steel (#C9CFD6 family), edge bevel strip lighter (#E8ECEF) along both edges, dark rim/outline (#2B2F36) at spine and tip, guard darker steel with gold trim, inlay lime | at least **5 distinct materials** in the item model |
| Elements (item model) | 80-200 deliberate elements per model-forge AGENT.md. Stepped bevels along both edges (2 steps), tip taper built from >= 4 stepped pieces, ornament pieces separate | no invisible cubes just to hit the count |
| Textures | 64-128 px where hand-painted, otherwise vanilla-crop at 1:1 texel density; **no flat single-colour faces** on the blade; edge-highlight rows painted | |

Inventory scale: the silhouette (long diagonal slab + guard) must read at 16/32/64 px.

## Orientation facts (get these right; they were wrong before)
- Model-local / rig-local frame: +Y from the grip to the tip, the **broad faces are the +-Z faces**, edges (the cutting edges) run along +-X sides of the blade. In the video at REST the broad face looks at the camera (side view), edges up/down, tip behind the caster.
- Held in third person the blade must lie along the caster's forearm line the way a greatsword is carried (two-hand diagonal), with the tip not poking through the body. Check both hands, first and third person, via `context_preview.py` boards (README) and open them. A model that looks right in the GUI but sticks the wrong way in hand is a fail.
- Display transform: the item is shown as a held greatsword, **not** the vanilla sword's 45-degree diagonal sprite.

## Deliverable B: `rig-parts.yaml`
Write `tools/skill-compare/data/bastion-greatsword-s1/deliver/rig-parts.yaml`: a `parts:` list ready to paste verbatim into a VFX rig (`id`, `block`, `offset` = box centre, `size`, optional `rotate` (yaw,pitch,roll about the part centre), `brightness`). Rules:
- Coordinates in the **sword local frame** (origin = grip point, +Y to tip, thickness along Z), units = blocks, same proportions as the item model (it is the same sword, simplified).
- **<= 20 parts** (cast-wide display budget is 60: sword 20 + trail 24 + fx 16), **>= 4 distinct vanilla blocks** (e.g. `iron_block`, `polished_andesite`, `quartz_pillar`/`smooth_quartz` for edge highlight, `gold_block` trim, `black_concrete`/`blackstone` rim, `lime_concrete` inlay). Choose blocks whose faces carry texture (a flat `*_concrete` everywhere is the rejected look); concrete only for the inlay and dark rim.
- Edge highlights as thin separate boxes (0.02-0.03 wide) along both edges; tip = 3-4 stacked boxes narrowing, optionally with a small `rotate` roll to fake a point; spine ridge box; guard in 3 steps.
- No z-fighting: parts that touch share faces only by 0.002-0.005 inset/overlap, thickness layers differ by >= 0.004.
- Include a header comment with the part count, block list, and a size table (name, size, offset).
- Validate by loading it in a throw-away rig in `tools/vfx-preview` (copy into a temp vfx file in the scratchpad, not in `content/vfx/`) and look at the `side` and `eye` views; save those two PNGs as evidence.

## File territory
You may create/edit only:
- `content/models/items/bastion-greatsword.model.json`
- `content/textures/blocks/bastion-greatsword-*.png` (new textures if a crop is not enough)
- `tools/model-forge/work/bastion-greatsword/**`, `tools/model-forge/batches/bastion-greatsword/**`
- `tools/skill-compare/data/bastion-greatsword-s1/deliver/rig-parts.yaml`
- `tools/skill-compare/data/bastion-greatsword-s1/evidence/modeler/**`
Do NOT touch `content/items/bastion-greatsword.yaml` (the director's skill list), any `content/vfx/**`, or other weapons' models. If the item needs `custom_model_data`/`material` changes, put it in "requests".

## Interface the others rely on
- The sword origin is the grip point; the animator puts the rig `pivot` there.
- Part ids in `rig-parts.yaml` must be stable and snake/kebab case (`blade_face`, `edge_l`, `edge_r`, `tip_1..3`, `guard_1..3`, `inlay`, `grip`, `pommel`...). The animator pastes the list verbatim and may reference no part by id except `blade_face` (brightness flash).
- If you change the geometry after delivery, re-deliver the file and say so; do not leave stale numbers in the header.

## Self-check before handing back
1. `model-forge` round protocol: board of all eight views + GUI + both hands in first and third person + ground + fixed, **open each image**; critic list with yes/no and element ids; at most 3 fixes per round, verifier answer per fix; max 6 rounds or stop at a two-round plateau.
2. `measure`: report the length / width / thickness / guard numbers against the table.
3. `reviewer` build (`context_preview.py --viewer`, `review.mjs`) and open its captures; spot-check the tip, guard joins and edge bevels from arbitrary angles.
4. Rig parts preview (`vfx-preview` side + eye) of `rig-parts.yaml`.
5. Optional but wanted: one real-client frame of the item in hand: `make vfx-shot SKILL=bastion-greatsword-s1 ITEM=bastion-greatsword VIEW=caster TICKS=10` (one capture at a time; the slots are shared).

## Done means
- All targets above measured and inside tolerance, or each miss listed with the reason.
- >= 5 materials, no flat-colour blade faces, bevels and ornaments readable in front, side and oblique views, and at 32 px.
- Item model reads correctly in hand (both hands, 1st and 3rd person) and on the ground; evidence images opened and described.
- `rig-parts.yaml` delivered, <= 20 parts, loads in vfx-preview, looks multi-material from the side view.
- Handback lists what is still wrong. You do not write "passes".
