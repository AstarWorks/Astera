# Pixel-art knowledge base

Reference for the pixel-artist helper (`.claude/agents/pixel-artist.md`): effect sprites, item-art sprites and font glyphs. Principles (P-numbers) live once in [pixel-vfx-craft.md](../../reference/vfx/pixel-vfx-craft.md); this page is the procedure. Team overview: [README.md](README.md). The skill's author ([director.md](director.md)) integrates what you hand over.

## Scope

- `content/textures/sources/sprites.yaml` entries (the source of truth) and the generated outputs `content/textures/generated/sprites/<id>.png`, `content/fonts/vfx/<id>.json`, `content/sprites/<id>.yaml` (never hand-edit generated files).
- Painters: `tools/item-art/src/main/kotlin/com/astarworks/astera/itemart/` (`Painters.kt`, `ParticlePainters.kt`, `PersonaPainters.kt`, `ParticleSkinPainters.kt`); converter `PixelConverter.kt`; sheet specs `tools/item-art/sheets/`; helper scripts `tools/item-art/scripts/`.
- Item icons and tooltip art through the same tool (`Icons.kt`, `TooltipArt.kt`).
- Not yours: skill YAML, vfx composites (`content/vfx`), models, sounds, rig keys. Hand ids and usage notes to the author.

## Limits

- No generative AI ([ADR-0010](../../adr/0010-postpone-generative-ai-to-phase-6.md)) and no paid services. `tools/item-art/scripts/gen_sheet.py` (Codex image tool) exists for sheets; use it only if the brief allows it and it costs nothing. Hand-drawn, code-painted or converted CC0 material only.
- Do not copy third-party sprites or the source tool's recipes; the repo is public.

## Two classes of sprite

Existing art direction asks for smooth, 2x2-supersampled 128-256 px hero shapes (slash arcs, rings) and rejects the pixel staircase at close-up ([vfx.md](vfx.md) "Resolution"). Do not break that. Declare the class in your report:

| Class | Use | Rules |
|---|---|---|
| smooth | hero shapes viewed close | existing vfx.md rules; P6 and the hard-alpha check of P10 do not apply; P1 applies as hard-edged colour steps in the interior |
| pixel | small sparks, flashes, hit marks, icons, glyphs, anything meant to sit next to skins and blocks | this page in full: P1, P6, P10 |

## Procedure (5 steps)

1. **Reference.** Look at the reference frames (or the beat-sheet / inventory row you were given). Write what the shape is (P2: one dominant shape) and where it sits in the timing (P3: anticipation, impact, expansion, dissipation) before drawing.
2. **Ramp and palette (P1, P5).** Pick 4-7 bands, dark to light, hue-shifted (shadows toward blue or purple, highlights toward yellow or white). The darkest band is the outline colour. At most two hue families plus smoke; non-core sprites use mid-ramp bands. Set `colors:` in `sprites.yaml` to the band count (default 12 is a ceiling, lower it), and `outline` only where the rule says (items yes, particle sprites no).
3. **Draw at native resolution (P6).** Draw at the final pixel grid, not big then blurred down. Keep the texel density near 16 per block at the spawned scale. From the calibration in vfx.md (1 text px = 0.025 block, 40 px per block at scale 1) the density is about `40 / scale`; so a pixel-class sprite should be spawned around scale 2.5 (acceptable band about 1.25-5). Integer scale factors only; never resample.
4. **Hygiene checklist (P10).** Tick every line:
   - one colour per pixel cell on a true grid; no anti-aliased edge pixels
   - alpha is 0 or 255 only (no semi-transparent fringe)
   - no stray isolated pixels (unless an intentional ember) and no one-pixel holes
   - outline closed, no gaps
   - total colours in the strip within the declared `colors`; same palette across all frames
   - all frames aligned (same key, crop, palette, scale); animate from one clean keyframe; the strip has a blank frame 0 where the flipbook rules require it (vfx.md "Core elements that follow a weapon rig")
   - for an ending, the last frames are fragments cooling down the ramp (P3), not a shrunken copy
5. **View in game.** Run the generator (`./gradlew :tools:item-art:run` only if the brief allows gradle; otherwise state "not run"), then look at it: skill-preview or `make vfx-shot` (one capture at a time) and the skill-compare page. Look at 1:1 and zoomed 8x, once next to a player or a block for density, and once in grayscale.

## Display lessons

- A `sprite` element defaults to `billboard: fixed` and is invisible edge-on from the side. For effect sprites that must face the viewer set `billboard: center` (one demo in `content/vfx` says this in a comment). `fixed` stays right only for a slash arc on a real swing plane; a horizontal `fixed` TextDisplay does not render in the real client at all.
- Sprite elements cannot fade or move after spawn (vfx.md learnings): fade by frames in the strip, grow with `scale`.
- Font glyph sprites top out at 256 px.

## Hand-back report

```
Sprite ids | class (smooth/pixel) | size px, frames | colours per strip | intended billboard and scale | texel/block estimate
Hygiene checklist: each line ticked or the reason
Evidence: zoomed PNG paths, vfx-shot or preview stills
Not verified in the real client: ...
```

## Learnings log

- 2026-10-28 initial version from the P1-P12 distillation (SKL-04). Texel-per-block formula is derived from the quad calibration and not yet verified in the real client.
