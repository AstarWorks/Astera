# content/textures/items/swords/

PoC artifacts from Step 0 of the texture pipeline (`/generate-textures`).

Generated 2026-05-17 in one Codex CLI image-gen call:
- input: a single `4×4` grid prompt for 16 distinct fantasy blade variants
- raw: 1254×1254 PNG → `slice_grid.py` (4×4) → `palette_quantize.py` (32×32, 32 colors, bg=remove)
- QA: Claude vision reviewed all 16; 15 directly accepted, 1 borderline (`kris_v01.png` — wavy-blade signature lost at 32×32, see ADR-0018 note about resolution-bound features)

These are **proof-of-concept assets**, not yet wired to any weapon YAML
under `content/weapons/`. When weapon definitions adopt them, the `_v01`
suffix can be dropped (single canonical) or kept (multiple variants
selected by weapon spec).

To regenerate or extend: `/generate-textures items swords --grid 4x4`
(see `.claude/skills/generate-textures/SKILL.md`).
