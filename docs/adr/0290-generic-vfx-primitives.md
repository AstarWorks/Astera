---
status: Superseded
date: 2026-10-27
deciders: founder, Claude
supersedes: null
superseded_by: 0301
---

# ADR-0290: Generic VFX primitives: sound arc, audience/intensity/lane, sprite + line shorthands, telegraph, stage palette, windup, shake profile, hit-stop clock

## Status

Superseded by [[adr/0301-skill-engine-v2-frame-tree]] (was Proposed, track `vfx-primitives`).

## Context

`docs/reference/vfx/patterns.md` section 5 lists the gaps between the Limbus-style effects we author and what the skill/VFX
YAML can express: no pitch glide or scale on `play_sound` and no way to stop a sound; `camera_shake` is a linear-decay jitter
with no impulse shape or hit direction; `hit_stop` holds the target but not its animation clock, and elements cannot opt in or out
of the freeze; particles, displays and flashes have no viewer scoping (everyone in range sees everything, and there is no
reduced-effects setting); sprite dots and thin lines must be hand-built per effect; there is no ground warning (telegraph) primitive;
a stage cannot re-colour an effect; and a wind-up (sound + warning + vfx before impact) is hand-wired per skill.

Pre-release posture applies: no shims, YAML shapes may change freely, existing content keeps decoding because every new field has a default.

## Decision

Add generic primitives, all data-driven (no per-skill code):

1. **Sound.** `play_sound` gains `pitch_arc {from,to,ticks,ease}` and `scale {root,steps}`; both expand into the existing
   `ScheduledSoundSpec.Sequence`. New `stop_sound {targets, sound_id}` mechanic and `IPlayerSoundGateway.stop`. Pitch stays clamped
   to the vanilla 0.5..2.0 range.
2. **Shake and hit-stop.** `camera_shake` gains `shape: decay|impulse`, `direction: none|from_hit`, `frequency`.
   `hit_stop` gains `freeze_animation`; `VfxElement.respect_hit_stop` and `VfxDefinition.body_respect_hit_stop` opt a display or the
   skin-rig body clock into the `play_vfx` freeze windows. Port: `IPlayerFeedbackGateway.cameraKick(target, dx, dy)`.
3. **Audience.** One shared `Audience` (`all|caster|target|others`, YAML `viewers`) replaces `ParticleViewers` and is added to
   `play_vfx`, `play_sound`, `spawn_display`, `screen_flash`, `camera_shake` (and `telegraph`, `screen_veil`). Display spawning
   takes an audience. A per-player `vfx_intensity: full|reduced|minimal` preference scales effects per viewer.
4. **Lane.** `VfxElement.lane: core|accent|ambient`; `reduced` culls ambient, `minimal` also accent, for that viewer only.
5. **Shorthands.** Element kinds `dotted_line {from,to,spacing,sprite}` and `thin_line {from,to,width}` expand at load into the
   existing sprite/quad element plus line formation (no domain change). New item-art painters supply the dot / thin-line /
   sparkle / smoke sprites.
6. **Telegraph.** `telegraph {shape: circle|cone|line, radius, ticks, impact_tick, team_color: enemy|ally}`: a planner builds a
   `VfxDefinition` (outer footprint at tick 0, inner fill scaling to impact). Team colour comes through `ITeamColorLookup`
   (kernel/runtime port, implemented by the match side). The warning colour is used for enemy telegraphs only.
7. **Palette.** `palette: {name: {variant: "#RRGGBB"}}` plus `$name` colour references in vfx YAML, expanded at load into
   `id@variant` definitions; the stage's `vfx_palette` tag selects the variant at play time (default = first variant).
8. **Windup.** Skill-level `windup {ticks, sound, telegraph, vfx}` desugars in `SkillYamlConfig` into cast-phase mechanics at
   ticks 0..n and shifts the damaging mechanics by `ticks`. Skill-lint caps the length.

Mechanic variants `StopSound`, `Telegraph`, `ScreenVeil` are routed by `MechanicDispatcher` into `service/primitives/`
(`PrimitiveMechanics`), one handler file each, so parallel territories do not edit the dispatcher.

### Out of scope: core shaders

Custom core shaders (additive blend, UV scroll, colour keying) were considered and are explicitly out of scope: they collide with
other resource packs that replace the same core shader files, break across Minecraft versions, and add a QA surface (every
client/GPU/mod combination). Display-entity composition plus the sprite font covers the needed looks.

## Consequences

### Positive
- Limbus-style windups, warnings, glides and viewer-scoped effects become YAML, not Kotlin.
- Per-viewer quality scaling gives players a reduced-effects option without per-effect work.

### Negative / Trade-offs
- Wider `Mechanic` surface and schema; more fields for authors to learn.
- Audience-scoped displays cost extra per-entity visibility calls (Folia: scheduled per entity).

### Mitigations
- Defaults keep all existing content unchanged; skill-lint and the generated schemas document the new fields.
- Viewer resolution happens once in the caster's region; handlers never loop players mutating them.

## Alternatives Considered

- **Core shaders**: rejected, see above.
- **Per-skill Kotlin hooks**: rejected, not data-driven and does not scale to new skills.
- **Keep `ParticleViewers` and add a second enum for displays**: rejected, two audience vocabularies for the same concept.

## References

- ADR-0291 (screen veil), ADR-0220 (vfx choreography), ADR-0284 (ghost bodies), ADR-0262 (Folia ownership)
- `docs/reference/vfx/patterns.md`
