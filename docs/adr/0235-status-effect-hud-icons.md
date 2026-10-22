---
status: Proposed
date: 2026-10-23
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0235: Procedural status-effect HUD icons

## Status

Proposed

## Context

cx2: combat effects currently occupy the text action-bar readout. The pack already
provides anchored HP/ammo glyphs (ADR-0182) and a cooldown row (ADR-0197).
Adding another channel writer would violate ADR-0177; boss bars remain excluded
by ADR-0194. Every current `content/status/*.yaml` ID needs a distinct symbol.

## Decision

Extend the deterministic HUD generator with hand-pixelled 9x9 effect symbols in
9x12 cells, red harmful / green beneficial frames, white 3x5 ceiling-seconds
and a gold 3x5 stack badge above the seconds. Reserve PUA E300–E33F; stable ID
order is append-only within this glyph family. Render effects sorted by ID,
13 px above cooldowns, returning the pen to the HP/ammo block's original advance
after each overlay. Combat exposes snapshots through Core's `IStatusEffectIconSource`;
`HpStatusHudService` remains the only status-line writer. Combat retains E.G.O
and text effects without a loaded pack (and for unknown runtime IDs).

## Consequences

### Positive

- Reuses pack assembly, scheduling, font and negative-space anchoring.
- All 15 authored IDs have icons; silence also has a reserved runtime symbol.
- Committed atlas/provider and preview freshness, content coverage, frame colors,
  exact glyph advances, fallback behavior and expiry have CI checks.

### Negative / Trade-offs

- Seconds and stack badges saturate at 99. All active authored effects share one
  row; many simultaneous effects can extend beyond the HP bar.
- Position/legibility at different client GUI scales still needs an in-game check.

### Mitigations

- Overlay advance stays constant even when the row exceeds the base width.
- Unknown runtime IDs retain localized text; adding a YAML ID requires authored
  pixels and fails the content-coverage check until provided.

## Alternatives Considered

- **Icons in Combat's action-bar readout:** couples glyph widths to the estimated
  text readout width and competes with transient toasts.
- **Separate boss bar:** violates the HUD direction in ADR-0194.

## References

- ADR-0177, ADR-0182, ADR-0194, ADR-0197.
- `docs/assets/status-icons-preview.png`: row-major order is bleed, burn, charge,
  dash, evasive, freeze, poise, poison; regen, reloading, rupture, shock, sinking,
  tremor, wet, silence. Samples show two stacks and four seconds.
