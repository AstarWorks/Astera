# Particles knowledge base

Agent: `.claude/agents/particle-artist.md`. Team overview: [README.md](README.md). Display core (sprites, display elements): [vfx.md](vfx.md). Applies to any Astera VFX; Limbus skill reproductions are one source of reference targets and Learnings.

## Founder bar

Founder 2026-10-24: "Display builds the VFX core; particles reinforce it for polish and presence. Custom particles need far more animation: vanilla particles look different every tick and move on their own. TextDisplay custom particles have far too little expressive power vs vanilla; the reason is that the foundation isn't built. Accumulate knowledge: a dedicated agent."

Consequences: the core always comes from `vfx-artist`; particles are attached to a core element and never substitute for it; vanilla first; the custom-particle foundation is a long-running goal tracked below.

## Vanilla particle catalog

Only facts verified from repo docs or earlier runs are filled in. `?` = unknown, to be filled by a measured capture (note date and case when you do). "Server-controllable" = what `content/particles/*.yaml` can drive (`data`, `velocity`, `speed`, `size`, `shape`).

| Type (`vanilla_particle`) | Client motion / physics | Lifetime control | Sprite animation | Server-controllable params | Looks square near camera? | Notes |
|---|---|---|---|---|---|---|
| `end_rod` | ? | not `lifetime_ticks`; lingers ~60 ticks | ? | `velocity`, `speed`, `shape`; no colour | ? | bastion-s1 r2: far too long for a short spark; use only where a lingering glow is wanted |
| `small_flame` | ? | not `lifetime_ticks`; up to ~40 ticks | ? | `velocity`, `speed`, `shape`; no colour | **yes**: beside the caster camera it became a 2x2 red/orange/yellow block grid filling the view | cartoon flame sprite; replaced by `dust_color_transition` embers in bastion-s1 |
| `smoke` / `poof` | ? | not `lifetime_ticks`; up to ~40 ticks | ? | `velocity`, `speed`, `shape`; no colour | ? (same magnification risk beside the camera) | ground poof / smoke: keep count low (smoke-dark count 3) |
| `dust` | ? | **scale**: life ~ 8 / U x scale with U random 0.2..1, so scale 0.4-0.7 lives ~5-20 ticks | ? | `data` colour, `data.scale` (dust size max 4) | grows with scale; keep small | use for sparks / grain / ground specks |
| `dust_color_transition` | ? | **scale**, as `dust` | ? | `data: {kind: dust_color_transition, from, to, scale}`: colour A -> B over life | as `dust` | the preferred spark / ember carrier (ember: orange -> dark red, scale 0.5, count 3) |
| `crit` | ? | ? | ? | `velocity`, `speed`, `shape` | ? | used as hit confetti (`bastion-s1-hit-crit`); motion not yet recorded |
| others (`flash`, `explosion`, `lava`, `block`, `item`, `vibration`, ...) | ? | ? | ? | schema `data` has `block`, `item`, `power`, `target_offset`, `toward`, `arrival_ticks`, `roll`, `delay` for typed particles | ? | add rows as you test them |

Cross-cutting facts:

- `lifetime_ticks` in the particle file is **not enforced for vanilla particles**; the client decides life per type (rows above). Treat it as documentation for the director.
- Vanilla particles carry no per-particle colour/size/alpha except through `data` (dust family, `power`, etc.).
- Direction by the `count=0` trick (offset becomes velocity): technique T-18 in [minecraft-techniques.md](../../reference/vfx/minecraft-techniques.md); for vanilla particles it is **unconfirmed** there. The particle file exposes `velocity {direction, speed, spread_degrees, custom}` instead; record in the Learnings log how the runtime maps it before relying on it.
- Spawn shapes (`shape.kind`: `point`, `burst`, arc/ring fields `radius_blocks`, `points`, `from_deg`, `to_deg`, `spread_blocks`, `length_blocks`) are in `content/schemas/particle.schema.json`; `viewers` and `force` are there too.

## Techniques

- **Layer on the core**: each particle batch anchors to one core element (sweep tip, flash centre, ring edge, hit point, ambient anchor) and starts on that element's beat tick; the core must read alone with the particles removed.
- **Arc / ring sampling**: sample points along the swept arc or ring (`shape` arc/ring fields, or one particle file per sampled tick) so particles follow the silhouette instead of clouding the origin.
- **Stagger**: few particles per tick spread across several ticks, no overlapping positions (Sakurai: few and clever; [vfx.md](vfx.md) principle 4).
- **Dark + bright**: pair every bright spark/ember with dark smoke/chips; use `dust_color_transition` bright -> dark for embers.
- **Short and small**: dust at scale 0.35-0.5 for sparks (life ~5-20 ticks); big counts only far from the caster camera.
- **Ground impact**: debris up, a short ground poof at the contact tick, dust specks; keep it below camera level for the caster view.
- **Volume goes into vanilla particles**: Display elements are budgeted (<= 60 per cast, <= 40 per element, ~16 per fx); a heavy hit affords about 10 sprites and 4-6 display particles.
- Display particles (`emit` on quads: flat squares) are a last resort; see [vfx.md](vfx.md) for the quad calibration. `size_over_life` / `alpha_over_life` / `color_over_life` / `spin_over_life` are multi-key curves (`[{ t: 0..1, v, ease? }, ...]`, any number of keys, `t` ascending; ease applies to the segment ending at the key). Example: `size_over_life: [{ t: 0, v: 0.2 }, { t: 0.15, v: 1, ease: out_quad }, { t: 1, v: 0 }]`. Sprite elements can emit too (`start_frame: random | <int>`, `frame_ticks: [min, max]`); `color_over_life` is quad-only.

## Custom-particle foundation

Status: not built. Goal: custom particles with enough per-tick animation (movement, spin, scale/colour over life, flipbook by age, randomised variants) to match vanilla particle liveliness. Today's route is TextDisplay glyph sprites ([ADR-0239](../../adr/0239-custom-particle-sprites-as-textdisplay-glyphs.md)): a sprite is placed once at its first keyframe and cannot fade or move ([vfx.md](vfx.md) Learnings 2026-10-24), which is the expressiveness gap.

Placeholder: link the foundation ADR here once written (number TBD). Resource-pack particle retextures and animated particle sprite sheets are documented here when the first one lands.

## Rejected looks

Particle-related, from the team's list ([README.md](README.md)): fire made of squares (cubes), black `#`-like smoke glyphs, poles for jets, a static ring standing like a wall. Plus (bastion-s1 r2): a vanilla particle beside the caster camera becoming a block grid; `small_flame` as a cartoon flame; end_rod sparks lingering ~60 ticks.

## Learnings log

(Append dated bullets after each effect. Format: `- YYYY-MM-DD <case>: <fact / pitfall / number>`.)

- 2026-10-24 seed: created from repo docs and the particle bullets below (copied from [vfx.md](vfx.md) Learnings; the originals stay there for display-core context).
- 2026-10-24 bastion-greatsword-s1 r2 (from vfx.md): vanilla particle life is NOT lifetime_ticks (not enforced). end_rod lives ~60 ticks, small_flame/smoke/poof up to ~40, dust = 8/U x scale (U 0.2..1), so dust at scale 0.4-0.7 dies in ~5-20 ticks: use small dust_color_transition (data: {kind: dust_color_transition, from, to, scale}) for sparks/embers/ground grain. Any vanilla particle spawned beside the caster camera is magnified: a small_flame became a 2x2 red/orange/yellow block grid filling the caster view.
- 2026-10-24 bastion-greatsword-s1 r3 (from vfx.md): `emit` was quad-only at the time (square billboards); sprite elements can now emit with per-particle flipbooks. The smooth readable spark layer = individual sprite elements (tinted `bastion-s1-streak` `#FF8A1E`, roll = flight angle in the y/z plane, 4-tick life, 1 display each); dark debris = `bastion-s1-sliver` tinted `#FF2A2218`; smoke mass = `bastion-s1-smoke` tinted `#E8A0A0A8`.
- 2026-10-24 bastion-greatsword-s1 r1 (from vfx.md): with the 16-per-fx cap a heavy hit affords about 10 sprites and 4-6 display particles, so put volume in vanilla particles. Display budget count: `start..end` inclusive plus `rate_per_tick` stagger (`spawn = start + slot / rate`).
- 2026-10-24 bastion-greatsword-s1 r3 (from `deliver/particle-cues.yaml`): the opponent camera is the target (fx z = `stop_before_target`, 2.4); anything at or beyond that z is invisible in the opponent view; spark streaks may fly to z 2.35 at most. Vanilla accents there were shrunk to dust size 0.35-0.4, 3-4 points, smoke-dark count 3.
