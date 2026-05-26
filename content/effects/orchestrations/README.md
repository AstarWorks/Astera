# `content/effects/orchestrations/`

Wave 5effects (ADR-0109). YAML samples for compound effect chains
consumed by `IEffectOrchestrator.play(EffectChain)`.

**Status (Wave 5effects)**: the wire shape and demo YAMLs ship here as
**reference only**. A YAML → `EffectChain` decoder lands in a follow-up
wave that wires the per-mode trigger points (Wave 5pve-boss phase
transition, Wave 5combat-depth combo finisher, Wave 5h match-event
cinematic, ...). Today's plugins programmatically construct
`EffectChain` instances via the sealed hierarchy in
`plugin/core/src/main/kotlin/.../domain/effect/chain/`.

Why ship the YAMLs now: they pin the schema so the follow-up wave
authoring the decoder has a concrete target shape, and the demos serve
as documentation for the `EffectChain` / `EffectStep` vocabulary.

## Demos in this directory

| File | Author | Description |
|---|---|---|
| `boss-phase-transition.yaml` | Wave 5pve-boss target | Particle burst + sound sting + slowness AOE chained in parallel + sequence. |
| `combat-finisher-chain.yaml` | Wave 5combat-depth target | Staggered triple particle/sound hit terminated by a damage application. |

## Wire format (forward-only — Phase 1 PoC closing posture)

```yaml
id: <kebab-case-id>
description: <short prose>
chain:
  # Exactly one of: sequence | parallel | emit | loop
  sequence:
    - emit: { kind: particle | sound | status | damage, ... }
    - delay_ticks: <long>
  parallel:
    - <chain>
    - <chain>
  loop:
    chain: <chain>
    duration_ticks: <long>
    interval_ticks: <long>
```

Both leaf shapes (`emit`) carry the same fields as the matching
`EffectStep` data class. Field names are SerialName-able when the
decoder lands; no migration of these demos is required.
