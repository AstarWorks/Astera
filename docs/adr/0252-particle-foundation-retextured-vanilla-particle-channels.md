---
status: Accepted
date: 2026-10-24
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0252: Particle foundation — retextured vanilla particle channels

## Status

Accepted. Complements [[adr/0239-custom-particle-sprites-as-textdisplay-glyphs]] (display sprites stay the hero shapes),
builds on [[adr/0220-display-choreography-vfx-and-combo-mechanics]] (`kind: particle` elements) and
[[adr/0182-resource-pack-pipeline]] (pack builder). Unrelated to the rig shader of [[adr/0250-skin-rig-player-animations-display-rig-and-skin-uv-shader]]
(route B below would reuse that mechanism).

## Context

Founder 2026-10-24: "Display builds the VFX core; particles reinforce it for polish and presence. Vanilla particles win
because every tick looks different and they move on their own; build a custom-particle foundation."

Measured on the real client (bastion-s1 r7-r12, `docs/contributing/vfx-team/particles.md`):

- vanilla `dust` renders as `#` splats near cameras and barely moves; `electric_spark` is a lavender blocky `+`;
- display-sprite particles (ADR-0239) are capped by the 60-displays-per-cast budget, so a hero skill shows ~8 visible
  sparks where the bar is dozens.

The client's particle classes were read in the 26.3 client jar (Mojang ships it unobfuscated). Many types pick their
sprite by age (`setSpriteFromAge`), and the sprite list of every particle type is `assets/minecraft/particles/<type>.json`,
which a resource pack may replace. Animated frames, any length, work in the particle atlas. So the *body* of a particle
(motion, lifetime, billboarding, per-tick flicker) can stay vanilla while the *picture* becomes ours.

## Decision

**Route A: a particle channel is a vanilla particle type whose sprite list the pack replaces with hand-drawn
frames.** The display core stays for hero shapes (ADR-0239/0220); particles become the plentiful animated polish.
Deferred: route B (a core shader that makes a particle render arbitrary geometry) and route D (animated item displays
as particles); neither is needed to reach the bar.

1. **Authoring** - `content/particle-skins/<vanilla-type>.yaml`: `type`, `channel` (texture names), `source` (an
   `:tools:item-art` painter, `ParticleSkinPainters`), `frames`, `tint: grey|baked`, optional `size` (default 64).
   Decoded by `ParticleSkinYamlConfig` (kaml DTO, `application/config`) into the domain `ParticleSkin`;
   `ParticleSkinContents` validates at boot and in `ShippedContentDecodesTest`; schema `particle-skin.schema.json`.
2. **Generation** - `:tools:item-art` draws smooth, anti-aliased, 4x4-supersampled frames (continuous colour + alpha, a
   transparent border, no outline, no palette; greyscale for `grey`) into
   `content/particle-skins/generated/astera_<channel>_<i>.png` plus the vanilla-format list `<type>.json`; both committed
   and covered by `checkItemArt`; contact sheet `docs/assets/particle-skin-preview.png`. The art is original (no Mojang
   pixels; the repo is public).
3. **Shipping** - `ResourcePackBuilder.addParticleSkins` puts the PNGs at `assets/minecraft/textures/particle/` (the
   particle atlas already reads that directory) and the lists at `assets/minecraft/particles/<type>.json`; a list naming a
   missing PNG fails the build.
4. **Colour** - `grey` skins are multiplied by the particle's own colour: `entity_effect`/`flash`/`tinted_leaves` take a
   server `data: {kind: tint}` colour, `dust` family colour+scale. `baked` skins keep the painted colour (the vanilla
   type's fixed multiplier, if any, still applies).
5. **Emitter** - `kind: particle` fx elements already plan N particles per call through `ParticleEmitter` (shapes,
   `velocity` with the `count = 0` trick, per-particle cone jitter, `every_ticks` / `start..end` for rate and bursts, no
   display cost). Added: `velocity.speed_jitter` (each particle's speed scaled by `1 +- jitter`), so a 40-100 particle
   burst has a depth of speeds instead of one shell.
6. **Lint** - `contentLint` validates the skin files and prints every skinned type with the `content/particles` files that
   emit it.

### First three channels

| Channel | Vanilla type | Tint | Frames | Why this type |
|---|---|---|---|---|
| `ember` | `entity_effect` (SpellParticle) | grey, server `tint` | 8 | translucent layer; sprite by age; colour (RGB) from the server; rises slowly (gravity -0.1, friction 0.96), 10-40 ticks; unused by Astera content (only potion swirls in vanilla) |
| `spark` | `firework` (FireworkParticles.SparkParticle) | baked | 32 (frames 0-7 drawn, 8-31 clear) | sprite by age; white (1,1,1) so baked colour is true; flies with the given velocity, friction 0.91, gravity 0.1; life is a fixed 48-60 ticks, so the drawn part is the first 8 of 32 frames and the particle is gone after ~12-15 ticks; unused by Astera content |
| `smoke` | `cloud` (PlayerCloudParticle) | baked | 12 | translucent layer (the `smoke`/`large_smoke`/`white_smoke` family is OPAQUE and cannot dissolve); grey multiplier 0.7-1.0 random per particle = free variation; used only by `dash-cloud` |

Rejected: `electric_spark` (fixed lavender multiplier (1, 0.9, 1) and 2-4 tick life: no room to animate), `poof`/`explosion`
(OPAQUE layer), `squid_ink` (fixed black), `end_rod` (60+ tick life, fixed fade).

## Consequences

- **A reskin is server-wide per type**: every particle of that vanilla type the client spawns (Astera's or vanilla's:
  potion swirls for `entity_effect`, fireworks for `firework`, `cloud` from area-effect clouds and similar) shows our frames.
  Choose rarely used types; `contentLint` lists the Astera users so a change can be reviewed.
- The sprite is chosen by the particle's own age/lifetime fraction, and lifetime is type-specific (the client, not
  `lifetime_ticks`, decides): a channel's frames are paced by the type. A short effect needs clear tail frames (spark).
- Billboards cannot orient along the velocity: streaks are drawn as non-axis-aligned glints, not directional lines.
- No per-particle size/rotation control from the server (vanilla constants); `dust` is the exception (scale).
- Frame count and size cost atlas space only (64 px x 52 frames today).
- Particle-skin PNGs ship to every client in the pack; a type reskinned later changes its look everywhere at once.
