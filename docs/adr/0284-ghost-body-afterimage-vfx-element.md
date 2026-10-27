---
status: Accepted
date: 2026-10-27
deciders: founder, Claude (Sonnet 5.5)
supersedes: null
superseded_by: null
---

# ADR-0284: Ghost body — a translucent afterimage of the caster as a VFX element

## Status

Accepted. Extends [[adr/0276-skill-performance-one-body-one-weapon-one-lease]] (one performance body, one weapon, one lease) and
reuses the skin rig of [[adr/0250-skin-rig-player-animations-display-rig-and-skin-uv-shader]]. ADR-0276 is not edited: it still
allows exactly one performance body; the ghost is decoration, not a second performance.

## Context

Limbus Couturier S2 (skill ticks 63-70) shows a violet, translucent copy of the character trailing the real one. It used to be
approximated with five nested purple quad pillars (`gh-0..gh-4`), which read as a flat slab, not as a body.

## Decision

A new VFX top-level list `ghost_bodies` (`VfxGhostBody`): `id`, `animation` (skin-rig animation, default the effect's `body`),
`tint`, `opacity`, `offset` (effect frame, +Z = in front of the caster), `delay_ticks` (0..40), `start_tick`, `end_tick`.

- It plans six more `SkinBone` elements (`ghost = true`) driven by the same clock and keyframes as the real body, sampled
  `delay_ticks` late and shifted by `offset`. They ride the caster like every skin bone (native passengers).
- Ghost bones are **not performers**: no lease, no hiding of the player or the held item, not part of the spine, the reveal
  bundle or the cut bundles. The real performance is unchanged. A ghost needs a performance rig in the same effect (it follows
  its clock and snap ticks).
- **Look = shader constants.** The only per-display channel the shader sees is the brightness override (block 15 + a 4-bit sky
  nibble). Bones use codes 0-5, slim arms 10/11; the unused 6-9 and 12-15 carry the ghost variants of the six bones (slim arms
  14/15). That leaves no room for a per-display tint or opacity, so `entity.fsh.patch` draws every ghost with tint `#8A5CFF` at
  opacity 0.5 (`SkinRigMarker.GHOST_TINT_RGB`, `GHOST_OPACITY`). The domain rejects any other `tint` / `opacity` loudly, so
  content cannot claim a look the pack does not draw. A new look is a shader plus constant change under a new ADR.
- Translucency: the shader lowers the fragment alpha and mixes the skin's shading toward the tint; whether the skull pipeline
  blends it is verified by real-client capture (see the track notes), with solid tint plus emissive light as the fallback.
- Ghost bodies cast no shadow.

## Consequences

- `content/vfx/couture-s2-rig-b.yaml` replaces `gh-0..gh-4` with one `ghost_bodies` entry (offset `[0, 0, -0.7]`, delay 2,
  ticks 63-70).
- `entity.vsh.patch` decodes `rigPart` with the ghost bit (`+16`); the head-enclosing test now covers the ghost head too.
- Covered by the game-sim scenario `PerformanceScenarioTest` "the ghost body afterimage shows six decorative bones on skill
  ticks 63 to 70 and never takes the lease".
