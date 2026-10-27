---
status: Proposed
date: 2026-10-27
deciders: founder, Claude
supersedes: null
superseded_by: null
---

# ADR-0289: Screen veil: camera-anchored display, viewer-scoped, screen-area cap

## Status

Proposed (track `vfx-primitives`). Companion: ADR-0288.

## Context

`screen_flash` shows a font glyph as a title, so it replaces any gameplay title, cannot be shaped, and is a flat full-screen
overlay. Limbus-style hit/impact effects need a shaped sprite that sits in front of the camera for a few ticks (a slash smear, a
vignette crack, a burst) visible to the casting player. The `camera_overlay` component would require swapping the head slot item.
`docs/reference/vfx/patterns.md` (section 4.2, G-4) caps screen coverage to protect readability and photosensitive players.

## Decision

New mechanic `screen_veil {sprite, distance, scale, ticks, color, viewers, max_screen_fraction}`. Each viewer gets a large display entity
(TextDisplay sprite quad / ItemDisplay) 0.3..1.0 blocks in front of their camera, re-anchored every tick (it follows the camera), visible
only to the selected viewers (`viewers: caster` by default, using the shared `Audience`). The projected area is capped by
`max_screen_fraction` (default 15 percent, hard maximum 18 percent): `scale` is clamped from the viewer's FOV and `distance`. The veil is
dropped for viewers with `vfx_intensity: minimal`. Handler: `PrimitiveMechanics.screenVeil` (`ScreenVeilHandler`).

## Consequences

### Positive
- Shaped, per-viewer overlays without touching the head slot, titles or the resource pack's core shaders.
- Screen coverage is bounded by construction.

### Negative / Trade-offs
- A display riding the camera lags a tick behind fast head turns; Display interpolation hides most of it.
- Per-viewer display entities cost spawn/visibility calls (Folia: entity-scheduled).

### Mitigations
- Short lifetimes, cap on concurrent veils per viewer, area clamp.

## Alternatives Considered

- **`camera_overlay` component / head-slot swap**: rejected, it needs equipment juggling and conflicts with armour/presentation leases.
- **Font title (current `screen_flash`)**: rejected, cannot be shaped and displaces gameplay titles.
- **Core shader overlay**: out of scope, see ADR-0288.

## References

- ADR-0288, `docs/reference/vfx/patterns.md` (4.2, G-4)
