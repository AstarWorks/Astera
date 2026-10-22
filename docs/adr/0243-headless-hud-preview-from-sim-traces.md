---
status: Proposed
date: 2026-10-23
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0243: Headless HUD preview from simulator traces

## Status

Proposed

## Context

HUD verification currently needs a Minecraft client. Simulator HUD summaries lose
custom-font strings, layout metadata and resolved colours, so they cannot reveal
misplaced HP bars, negative-space drift or overlapping cooldown glyphs.

## Decision

Add a non-shipped `tools:hud-preview` Kotlin/Java2D CLI. Record structured,
viewer-localized HUD layouts alongside existing trace summaries and replay their
per-player region deltas through the requested tick. Render the built pack's font
providers over official vanilla assets at 1920×1080, GUI scale 2 or 3. Use the
production status anchor formula and text-width estimate, but measure glyphs from
actual bitmap/space providers and reject a recorded status width that disagrees
with the pack. CI generates four production-wired scenarios and uploads PNG frames.

## Consequences

### Positive

- Humans and AI can inspect glyph positioning without Paper, a client or a GPU.
- Pack transparency, nonzero-alpha marker pixels and signed spaces affect previews.
- Synthetic metric/placement tests and fixed simulator scenarios make drift visible.

### Negative / Trade-offs

- Official vanilla assets require a separate hash-checked download; they are not
  redistributed in this repository or the frames artifact.
- This renders HUD layout, not world graphics or inventory packets. Empty hotbar,
  full food, default font settings and a neutral background are explicit baselines.
- Tab heads/ping/wrapping, action-bar fades/backdrops and some text decorations are
  omitted. A real-client screenshot comparison remains necessary for final fidelity.

### Mitigations

Missing structured data/glyphs/providers fail clearly. The README documents the
supported inputs and visual limits. Source assets are pinned by the catalog's
Minecraft version and verified against Mojang hashes; unit tests cover bitmap
advances/ascent, negative spaces, region replay and screen placement at both scales.

## Alternatives Considered

- **Automated Minecraft client screenshots:** broader fidelity, but require a
  client runtime, graphics setup and server session for each inspection.
- **System-font HTML/canvas mockups:** convenient, but fail to reproduce the pack's
  atlas alpha bounds, bitmap ascent and negative-space advances.

## References

- [ADR-0174](0174-host-neutral-wiring-and-headless-game-simulator.md)
- [HUD preview usage and limits](../../tools/hud-preview/README.md)
- [MiniMessage format](https://docs.advntr.dev/minimessage/format)
- [Official client version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
