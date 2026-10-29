---
status: Proposed
date: 2026-10-29
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0305: Declarative display shader effects

## Status

Proposed

## Context

Founder 2026-10-29: 「VFXやシェーダーエフェクト作りやすくして置いて」. Effects previously required editing shared core shader diff patches. SKL-03 / ADR-0298 showed that every legal brightness value can also occur naturally: light alone must not select a new effect. Existing skin-rig winding confirmation and weapon-rig semantics must remain unchanged.

The actual checkout uses engine-v2 skills and mesh displays (ADR-0301/0303), and has no `content/vfx` loader. ADR-0288 and ADR-0296 in this checkout concern Norma and objective modes, rather than rig markers; ADR-0275 and ADR-0298 plus the code govern the rig protocol.

## Decision

Each `content/shader-patches/effects/<id>/effect.yaml` declares core programs, marker claims and numeric constants; adjacent stage snippets use named hooks from `content/shader-patches/hooks.yaml`. The pack builder resolves all insertions against pristine client shaders, composes them once, validates exclusive marker claims (including wildcard light payloads), and emits an inspectable marker ledger. Existing rig snippets move into this registry without changing their shader bodies or marker encodings. There is no legacy core patch path.

An authored pack `mesh` selects `shader_effect: <id>`. The runtime uses an isolated, generated item definition and model copy whose faces carry the registry tint, plus display brightness block 13 / sky 11 (`UV2=(208,176)`). The vertex shader requires BOTH exact tint and exact light, then restores white vertex colour and full brightness on that display. Source models and ordinary held items retain their original definitions. Content loading refuses unknown effect ids and unsupported vanilla/main-hand mesh sources. Text/sprite displays and skin bones are outside this first protocol.

Initial effects:

- **dissolve**: stable per-texel noise, periodically animated threshold and orange surviving edge. Uses discard consistently in colour and OIT alpha-only passes.
- **uv-flow**: a travelling procedural band in texture-pixel coordinates. It flows over the surface without resampling the atlas outside the model's sprite; this deliberately avoids unsafe atlas UV scrolling.
- **emissive-pulse**: unlit colour boost with a smooth pulse, clamped to display colour range. It is not additive framebuffer blending or bloom.

All three use `GameTime * 1200` seconds. The phase is global world time, not elapsed cast age; repeated casts join the existing phase. Their constants are pack-build constants, not server uniforms. Heat distortion needs scene colour/depth access, and fresnel needs additional normals/view varyings and geometry review; neither is required for this safe surface-only set.

Playable examples live under `content/vfx/demo-shader-*.yaml`, using the existing `astera/v2` Skill schema and production compiler. Skill content reload composes this directory alongside skills, rejects duplicate ids, and reloads examples/registry changes. There is no separate VFX execution engine.

## Consequences

### Positive

- A new item-surface effect needs a directory, two snippets and an effect id on a mesh; no Kotlin dispatch entry.
- Pack builds fail on duplicate markers, invalid hooks, upstream anchor changes and replacement conflicts.
- Skin-rig code remains equivalent; its pre-migration shader hashes are asserted by shipped-content integration coverage.

### Negative / Trade-offs

- Exact tint plus light is a reserved protocol, not cryptographic identity. A third-party tinted model with exactly the reserved RGB and the same light can collide. Natural light alone cannot fire it. Reserve these RGBs outside effects too; the ledger includes existing weapon tint and skin/weapon light claims.
- Applying an effect replaces model face tints with the reserved tint. Already tinted materials lose that tint within the effect variant. Cuboids and generated/handheld item parents are supported; special skulls, block parents, composite definitions, vanilla meshes and the real main hand are excluded. Unsupported referenced models fail pack generation.
- The pack builder uses the production skill composer and mesh resolver to generate only referenced model/effect combinations, including patches and variants. This adds a tools-only dependency on kernel runtime.
- Core shaders remain version-sensitive. Named hooks centralize the maintenance but do not remove it.
- Scalar constants are pack-wide per effect; one effect per mesh, no per-cast age or stacking.

### Mitigations

- Reserve block-13/sky-11 plus tint, validate marker ownership centrally and expose the resulting table in the pack.
- Keep original item definitions untouched; effect models are under `shader-effects/<id>/`.
- Capture demos on the throwaway `vfx-shot` stack; manager runs integration tests, worker runs compile and pack assembly only.

## Alternatives Considered

- **Brightness-only dispatch**: rejected because natural lighting reproduces every value (ADR-0298 / SKL-03).
- **Server-supplied custom GLSL uniforms / post-processing**: unavailable through the vanilla display/resource-pack path; would require a client mod or wider uniform hijacking.
- **One shared raw patch edited for every effect**: rejected because marker allocation, unrelated effects and rig changes would remain coupled.
- **Arbitrary atlas UV scrolling**: rejected because neighboring atlas sprites bleed into the effect; the first flow effect moves a procedural band instead.

## References

- [Author guide](../contributing/vfx-team/shader-effects.md)
- [Technique catalog](../reference/vfx/neac-shader-techniques.md)
- ADR-0250, ADR-0275, ADR-0285, ADR-0298, ADR-0301, ADR-0303
