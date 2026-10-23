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
- 2026-10-24 bastion-greatsword-s1 (pilot r1): **Smooth hero sprites route**: code painters (`draw:`) are 4-tone, and `PixelConverter` (`from:` sources) quantises to a palette with binary alpha, so neither gives anti-aliased gradients. Use a Python generator (`tools/item-art/scripts/bastion_s1_sprites.py`: analytic shapes at 4x, premultiplied LANCZOS down to 256/128) that writes `content/textures/sources/images/<id>.png`, and list the sprite in `sprites.yaml` as `{ from: <source>, raw: true, frames: N, size: S }` (new `Origin.Raw` in `SpriteSources.kt`: strip must be exactly `frames*size` x `size`, used unchanged). `:tools:item-art:run` then emits png/font/meta and `checkItemArt` stays green; hand-written generated files would be treated as orphans.
- 2026-10-24 bastion-greatsword-s1: **Sprite elements cannot fade or move.** Keyframe `color`/`alpha` is rejected for sprite kinds (`keyframe color / alpha needs kind quad`; content-lint does not catch it, vfx-preview does), and a sprite is placed once at its FIRST keyframe `translate` (`spawnOffset`; transformation translation is zero), later translates are ignored. Grow/shrink with `scale`, fade by collapsing scale or by thinning frames in the strip; centre long streaks at their midpoint. Static tint via `kind.color` (8-digit ARGB accepted by the parser; real-client alpha of the text colour unverified).
- 2026-10-24 bastion-greatsword-s1: a sprite flipbook always starts at frame 0 at `start_tick` (`frame_ticks >= 1`, no start-frame option): to show "frame 2 on the contact tick" start the element 2 ticks earlier. Three-variant strips (slivers) flip through the variants over their life.
- 2026-10-24 bastion-greatsword-s1: **vfx-preview draws sprites at the origin** (Scene.kt `Text` prim uses `t.translation`, not `d.offset`), so every sprite looks like it sits on the caster. A one-line local patch (`center = d.offset + t.translation + shift`) shows real positions; not committed (outside this role's territory), director please fix. Display budget count: `start..end` inclusive plus `rate_per_tick` stagger (`spawn = start + slot / rate`); with the 16-per-fx cap a heavy hit affords about 10 sprites and 4-6 display particles, so put volume in vanilla particles.
- 2026-10-24 bastion-greatsword-s1: the worktree base lacked `wp/pilot-bastion-s1` (no briefs, no 256px support, no skill-team docs); `git switch -c <new> wp/pilot-bastion-s1` fixed it when `reset --hard` was denied. This branch carries a copy of `docs/contributing/skill-team/vfx.md` from `a58afeb9` plus these bullets (expect a trivial add/add merge). Measured gold crescent (sin^2 width profile, 40 px max at 256, outer rim 1-3 px `#3A2A06`, inner hot band `#FFFBE0`): radial thickness 38 px at the middle, 1 px at s=0.88, inner-edge luma 248, rim luma ~45.
- 2026-10-24 bastion-greatsword-s1 r2: vanilla particle life is NOT lifetime_ticks (not enforced). end_rod lives ~60 ticks, small_flame/smoke/poof up to ~40, dust = 8/U x scale (U 0.2..1), so dust at scale 0.4-0.7 dies in ~5-20 ticks: use small dust_color_transition (data: {kind: dust_color_transition, from, to, scale}) for sparks/embers/ground grain. Any vanilla particle spawned beside the caster camera is magnified: a small_flame became a 2x2 red/orange/yellow block filling the caster view.
- 2026-10-24 bastion-greatsword-s1 r2: sprite strips advance one frame per tick from element start; an ink strip growing 12% -> 52% never reached full width in its 3-tick window (looked like small streaks): put the full-width frame by the 2nd-3rd tick. Hero sprites 4-5 blocks wide at 0.6 blocks from the caster fill the caster view: place at z >= 2.7. Trail step_deg 40 gives visible straight segments (hoop): use one smooth ellipse sprite (bastion-s1-ring) for floor rings. :tools:item-art:run also rewrites unrelated sprite PNGs and the preview on this machine: do not commit those.
