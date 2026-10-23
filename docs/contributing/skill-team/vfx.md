# VFX knowledge base

Agent: `.claude/agents/limbus-vfx.md`. Team overview: [README.md](README.md).

## What the VFX artist produces

Sprite strips (2D art as font glyphs), particle definitions, and a layering proposal (core / flash / spark / smoke / ground) that the director wires into the composite `content/vfx/<id>.yaml`. The previous pass built effects from block displays alone with almost no particles and low-res jagged sprites; fixing that is this role's job.

## Principles ([vfx-art-direction.md](../vfx-art-direction.md) section 0, [sakurai.md](../../reference/vfx/sakurai.md))

1. Do not be fuzzy. Additive-only glow loses punch and vanishes on bright screens. The main body is crisp flat-painted shape (opaque core); glow is an accent. Coloured glass at brightness 15 only for rims and aftermath.
2. Mix opposites: put dark with the bright (dark rim or dark particles on every slash/flash); straight lines with curves.
3. Big hits: 4 stages. Flash (instant, larger than the blast, with black mixed in) -> fire (bursts from the centre, still crisp) -> hand over to black smoke -> convergence (not too short). A small flash on the final frames so it "pops out". Screen shake changes feel a lot. On ground hits: debris upward and a ground shockwave. Do not apply damage continuously during the fire.
4. Particles: few and clever. Short lifetimes, **staggered spawn times**, no overlapping positions, several grains drawn in one board (one TextDisplay with `✦ · ✧ ·`), white and black mixed. Precomputed coordinate patterns are cheap.
5. Wind slash: a **fan face along the blade's swept path** (anchor at the hand, follows with slight lag, translucent tail fades). Tilt a true horizontal slash. A very fast swing is one board for one moment plus hit stop. The motion (animator's job) is fixed before adding more effect.
6. Review in slow motion (skill-compare, Shift+arrows).

Selection table (video vocabulary -> Display / particle): [vfx-art-direction section 2](../vfx-art-direction.md). Do not make fire from blocks; do not make jets from poles.

## Verified tools and schemas

### Sprites (ADR-0239)
- Source of truth: `content/textures/sources/sprites.yaml` (tool-only). A sprite is `draw: <painter>` (code painters such as `spark ring burst puff flame glint slash slice`, plus skill-specific painters in `tools/item-art/src/main/kotlin/**/PersonaPainters.kt` and `ParticlePainters.kt`), or `from: <sheet>, row: N, frames: F`, or `from: <image>, frames: N`. Optional `size` (default 32), `colors` (12), `outline` (false), and `quad_font: true` where used.
- `./gradlew :tools:item-art:run` writes `content/textures/generated/sprites/<id>.png` (frame strip), `content/fonts/vfx/<id>.json` (bitmap font, frame i = U+E000+i), `content/sprites/<id>.yaml` (`id`, `frames`, `size`). `./gradlew :tools:item-art:checkItemArt` is the drift check (part of `check`). Generated files are committed. Do not hand-edit them.
- AI sheets: `python3 tools/item-art/scripts/gen_sheet.py tools/item-art/sheets/<name>.yaml [--prompt-only]` asks the Codex CLI image tool for one sheet (shared style) into `content/textures/sources/sheets/<name>.png`; any hand-made sheet in the same grid order works. Sheet specs: `tools/item-art/sheets/{vfx-v1,weapons-v1}.yaml`. (Check cost/permissions of the generator before use: founder forbids paid services.)
- `PixelConverter.convertFrames` keeps all frames aligned (one key, crop, palette, scale), no outline, small blobs kept, green-key despill. Schema: `content/schemas/sprite.schema.json` (`id`, `frames`, `size`).
- Runtime: VFX element `type: sprite` (`sprite`, `frame_ticks`, `loop`, `color`, `see_through`), a TextDisplay with `<font:astera:vfx/<id>>` + char U+E000+i. vfx-preview draws it nearest-neighbour at `height/40*scale` blocks, multiplied by the text colour.
- **Resolution**: font sprites render only up to **256 px; 512 px fails** (font page limit). Working maximum is 256 px with 2x2 supersampled (anti-aliased) edges (commit `ee976100` on `wp/pilot-bastion-s1`: bastion-arc/ink/smoke/rim/crest sprites). The pixel-art staircase at close-up is a rejected look: use 128-256 px smooth edges for hero shapes, small pixel sprites only where the reference is pixel-art.
- Black `#`-like smoke glyphs are rejected: smoke sprite must be a soft painted puff, not a glyph block.
- A TextDisplay with `fixed` billboard laid horizontal does not render in the real client (it does in vfx-preview). Ground flashes/rings: use block/quads (the pilot's "flat ground flash" approach) or a billboard-center sprite; verify in the real client.

### Particles
- Vanilla particle definitions: `content/particles/<id>.yaml` (`id`, `shape {kind: burst|..., spread_blocks}`, `color`, `count`, `lifetime_ticks`, `vanilla_particle`, `speed`, `size`, `data`, `velocity {direction, speed, spread_degrees}`, `force`, `viewers`); schema `content/schemas/particle.schema.json`; examples `content/particles/bastion-s3-*.yaml`. dust size max 4.
- Display particles: element `kind: { type: quad, color, alpha }` or `glyph: "✦"` with `emit` (count <= 40, gravity, `color_over_life`, `alpha_over_life`, `size_over_life`). Quad calibration: 1 text px = 0.025 block; a one-space background box = 0.125 x 0.25 block; sizes in blocks. The client does not interpolate colour/alpha, so changes are sent every `sample_ticks`. Emit RNG uses the cast seed (same seed -> same trajectory). Details: [vfx-art-direction section 5 "Display particles"](../vfx-art-direction.md).
- **`emit.size_over_life` with more than 2 values silently drops the whole VFX at runtime, while content-lint passes it.** Use at most 2 values (start, end). Check every `size_over_life` by grep before handing over.
- Each display particle is one entity and spends the cast's display budget (<= 60 Displays per cast, <= 40 per element). A single sprite Display is usually lighter than hundreds of particles; use sprites for hit sparks and flashes, vanilla particles for grain.
- Particle `drag` is the fraction lost per tick (0.95 loses 95%). `nearby_entities.max_count` excludes the caster; do not scale effect count with the number of enemies in range.
- `DUST_COLOR_TRANSITION` gives a colour transition on one vanilla particle; tangent-direction particles make swirls ([minecraft-techniques.md](../../reference/vfx/minecraft-techniques.md)).

### Preview and capture
- vfx-preview ([README](../../../tools/vfx-preview/README.md)): `./gradlew :tools:vfx-preview:run --args='<vfx-id> --view side|top|eye|front|behind'`. Block colours are approximate; text size assumes 1 font px = 1/40 block. It is not the real client: certain things differ (laid fixed-billboard text). Real client: [vfx-shot](../../../tools/vfx-shot/README.md), one capture at a time.

## Layering checklist per hit

1. Core: opaque, concrete/alpha 1 shape or sprite with dark rim.
2. Flash: one sprite, bigger than the damage radius, peaks on the **damage tick** (hit VFX must peak on the damage tick), 1-2 ticks.
3. Sparks: few vanilla particles (end_rod, crit) or display particles, staggered by tick, positions not overlapping.
4. Smoke: dark soft puffs handed over from the fire; fade out gradually.
5. Ground: debris up and a short flat shockwave; gaze-level tilt.
6. Remove elements gradually in the afterglow; never hide the next move.

## Rejected looks (founder / reviewer)

crescents built from uniform boxes (hoops); fire made of squares (cubes); poles for jets; a static ring standing like a wall; separate floating sprites instead of one continuous sweep; a pixel-art staircase at close-up; black `#`-like smoke glyphs.

Other failure patterns with detection steps: [failure-patterns.md](../../reference/vfx/failure-patterns.md) (e.g. same hit_stop everywhere, a single hit covering 12-18%+ of view, particles on every combo frame, lingering white screen flashes).

## Learnings log

(Append dated bullets after each skill. Format: `- YYYY-MM-DD <case>: <fact / pitfall / number>`.)

- 2026-10-23 seed: created from repo docs; no skill run yet.
