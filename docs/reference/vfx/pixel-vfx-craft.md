# Pixel VFX craft principles (P1-P12)

[README](README.md) / tags: see README. Distilled 2026-10-28 from two public posts, restated in our own words. We cite URLs only; nothing from the source tool (no licence) is copied here.

- Sprite cleanup pipeline for pixel art: https://x.com/asynkimo/status/2114796665307578732
- Layered pixel-VFX forge and its recipe idea: https://x.com/Delroy715/status/2114910501721489827 (quoting https://x.com/emmanuel_2m/status/2114763793284857865), tool page https://www.scenario.com/explorations/pixel-vfx-forge/

Status: hypotheses to apply and check, not accepted art direction. The rules of record stay in [vfx-art-direction.md](../../contributing/vfx-art-direction.md). If this page disagrees with it, propose the change there.
Generative AI stays deferred ([ADR-0010](../../adr/0010-postpone-generative-ai-to-phase-6.md)): P10 applies to any sprite we draw or convert, not to a generator.

Procedures that cite these P-numbers: [director](../../contributing/vfx-team/director.md), [beat-sheet](../../contributing/vfx-team/beat-sheet.md), [vfx](../../contributing/vfx-team/vfx.md), [particles](../../contributing/vfx-team/particles.md), [pixel-art](../../contributing/vfx-team/pixel-art.md), [reviewer](../../contributing/vfx-team/reviewer.md).

Astera knobs used below: `lane: core|accent|ambient` on a VFX element, per-viewer `vfx_intensity: full|reduced|minimal` (ADR-0290), skill mechanics `hit_stop` (`ticks`, `delay_ticks`) and `camera_shake` (`intensity`, `ticks`, `delay_ticks`), sprite strips from `content/textures/sources/sprites.yaml` (`size`, `colors`, `outline`), element `billboard`, `scale`, keyframes, `emit` over-life curves, `play_vfx` ticks. Tools: skill-compare, `make vfx-shot`, `make skill-preview`.

## P1 Banded heat, not gradients
An effect is a heat field cut into 4-7 hard colour bands, dark to light. Hue-shift the ramp: shadows lean blue or purple, highlights lean yellow or white. The darkest band is also the outline colour. A soft gradient or an alpha ramp reads as a blurred glow, not as drawn pixel art.
- In Astera: sprite painters and `colors:` in `sprites.yaml` (palette size); `color_over_life` steps instead of smooth curves for display particles; `outline` on item-art sprites. Smooth 128-256 px hero shapes that the art direction asks for are a separate class (see [pixel-art.md](../../contributing/vfx-team/pixel-art.md) "Two classes").
- Check: view the sprite zoomed; count distinct colours per frame (should be 4-7 bands plus transparent); in skill-compare the core must show visible steps, not a smooth fade. Future lint: colours per sprite frame.

## P2 Shape first
One dominant shape per effect (disc, ring, crescent, column, cone). It must read with every secondary layer hidden.
- In Astera: the `lane: core` elements alone are the shape. This equals what a `vfx_intensity: minimal` viewer sees (minimal culls accent and ambient).
- Check: capture or preview at `minimal` (or hide non-core lanes) and ask "which shape is this?". If you cannot name it, the core is wrong, not the extras.

## P3 Timing template
Optional anticipation (2-4 frames of implosion or contracting ring), then impact (a 1-2 frame flash, the brightest frames), then expansion (3-6 frames, fast, ease-out), then dissipation (the rest, slower). In dissipation the shape breaks into chunks and cools down the ramp; it does not just shrink. Smoke starts late, about 20% in. The flash comes first on screen and is painted last.
- Rough lengths to start from (frames, then treat one frame as about one tick at 20 tps): hit 8-10, slash 12, explosion 15-20, loops 8-24. Tune against the reference, not these numbers.
- In Astera: beat-sheet columns for anticipation / impact / expansion / dissipation ticks (see [beat-sheet](../../contributing/vfx-team/beat-sheet.md)); `play_vfx` start ticks, element `start_tick` / `end_tick`, `frame_ticks` on sprites, `scale` and `color_over_life` keyframes for cooling. Sprite elements cannot fade or move (vfx.md learnings): break-up and cool-down are done with the strip's frames and `scale`.
- Check: step the capture tick by tick; the last third must show fragments and darker bands, not a smaller copy of the first frame.

## P4 The impact frame drives game feel
The impact frame is the frame with the biggest jump in coverage. Hit-stop and screen shake belong on that tick.
- In Astera: the `hit_stop` and `camera_shake` mechanics (`delay_ticks` or the tick they fire on) must land on the tick where the flash element first shows; the existing rule "hit VFX must peak on the damage tick" (reviewer.md capture facts) is the same idea.
- Check: server trace (`ASTERA_VFX_TRACE=1`, `[vfx-trace] spawn ... tick=`) against the skill's `hit_stop` / `camera_shake` ticks; they must be equal. Future lint: compare the first-flash tick of the vfx with the mechanic ticks of the skill.

## P5 Heat budget
If every layer runs at full brightness the result is a white blob. Only the core takes the hottest bands; secondary layers sit mid-ramp (about 50-80% of full). At most two hue families per effect, plus smoke.
- In Astera: layer colours per `lane`; `accent` and `ambient` use mid-ramp colours. Fits the "dark mixed with bright" rule in [patterns.md](patterns.md) C1 and [sakurai.md](sakurai.md).
- Check: the grayscale pass (`ffmpeg -vf format=gray`) must show a bright core on darker surroundings; if the frame is mostly the top band it is a blob. Count hue families.

## P6 Texel density matches the world
Minecraft is 16 px per block; skin texels are 1/16 block. A sprite's texels per block at its spawned size should stay near 16 (warn below about 8 or above about 32). Scale by integer factors only. Never resample or blur pixel art.
- In Astera: sprite `size` (px) with the element `scale`. From the calibration in vfx.md (1 text px = 0.025 block, so 40 px per block at scale 1), texels per block is roughly 40 / scale for the sprite's own pixel grid. Derived, verify in the real client. Applies to sprites meant as pixel art; the 128-256 px smooth class is exempt.
- Check: in vfx-shot, put the sprite next to the player skin or a block and compare texel size by eye; the reviewer lists texel density as a row for pixel-class sprites. Future lint: `size` and `scale` from the vfx YAML.

## P7 Two-frame white silhouette on the target
A white silhouette of the hit target for two frames at impact sells a hit better than a bigger explosion.
- No knob yet -> candidate (Astera has no primitive that tints the target). Listed in [next-improvements.md](next-improvements.md).
- Check once it exists: two ticks only, at the P4 impact tick.

## P8 Ground effects
The pivot sits on the ground line. Rings and circles are squashed to about 0.3 in height so they lie flat.
- In Astera: ground rings and flashes are quads or `billboard: center` sprites (a horizontal `fixed` TextDisplay does not render in the real client); place the element's y at the floor and squash with the `scale` y component; telegraph example `content/vfx/bastion-s3-telegraph.yaml`.
- Check: side camera in vfx-shot: the ring is a thin ellipse on the floor, nothing floats or sinks.

## P9 Seamless loops
Every periodic parameter (spin, pulse, flicker) completes whole cycles within the loop length, so the loop point has no jump.
- In Astera: sprite strips with `loop: true`, `frame_ticks`, `emit` curves, `spin_over_life`, rig repeats.
- Check: in skill-compare or preview, watch frames N-1 and 0 side by side; for a sprite strip frame N-1 -> 0 must be as close as any other pair.

## P10 Sprite hygiene
Find the true pixel grid and keep one colour per cell. Hard alpha only (0 or 255, no semi-transparent fringe). Repair outline gaps; remove isolated stray pixels and one-pixel holes. Lock to a small project palette (tens of colours at most, not hundreds). Animate from one clean keyframe.
- In Astera: `PixelConverter` in `tools/item-art/src/main/kotlin/com/astarworks/astera/itemart/PixelConverter.kt` already does palette quantisation, per-cell voting, orphan/pinhole cleanup and outline; `colors`, `outline` in `sprites.yaml`; `convertFrames` keeps frames aligned. The 5-step procedure and checklist are in [pixel-art.md](../../contributing/vfx-team/pixel-art.md).
- Check: zoom 8x; no half-alpha edge on pixel-class sprites, no lone pixels, no unintended colours (palette count per strip). Future lint: alpha histogram per sprite PNG.

## P11 Deterministic recipes
An effect is a small declarative recipe plus a seed; same recipe and seed give identical frames, and the authoring tool and the agent share it.
- In Astera: already true. VFX YAML, `emit` RNG uses the cast seed, item-art output is deterministic and drift-checked (`:tools:item-art:checkItemArt`), skill-compare replays. Keep it: never add a hand-edited generated file.
- Check: regenerate and see no diff.

## P12 Missing layer vocabulary (candidates, not mandates)
Ideas that Astera cannot express as one element today: a magic circle that draws on then spins, ground cracks, erupting crystal spikes, a hex-cell shield dome, lightning regenerated every 2 frames, pop-and-float damage text, three-line claw streaks, a tornado funnel.
- No knob yet -> candidate. Listed in [next-improvements.md](next-improvements.md); many can already be approximated with sprite strips plus `formation` / `emit`, try that first.
- Check: a skill that needs one of these says so in its beat sheet instead of faking it with blocks.
