---
status: Accepted
date: 2026-10-27
deciders: founder, Claude
supersedes: null
superseded_by: null
---

# ADR-0282: Keyframed item model on the performance weapon

## Status

Accepted. Extends [[adr/0276-skill-performance-one-body-one-weapon-one-lease]] (does not edit it).

## Context

ADR-0276 makes the skill weapon exactly one `ItemDisplay` showing the real main-hand stack. A weapon with moving parts (Couturier's
Shears: a scissor hinge) must open and close during a skill. A second display for a blade half would break the one-weapon
invariant; the modeler instead ships whole-weapon models at fixed open angles.

## Decision

The weapon element of a rig takes an optional list of step keys:

```yaml
weapon: main_hand
weapon_model:            # step keys, hold until the next key
  - { t: 0,  model: astera:items/couture-rapier }
  - { t: 2,  model: astera:items/couture-rapier-open-60 }
  - { t: 17, model: astera:items/couture-rapier }
```

- Domain: `VfxRigWeapon.models: List<VfxWeaponModelKey(t, model)>`; `t >= 0`, strictly increasing, `model` a namespaced id. The
  YAML DTO (`weapon_model`, `VfxWeaponModelKeyYaml`) converts with `require`, which `VfxYamlConfig.toDomain` already reports as
  `VfxConfigError.Invalid`; `weapon_model` without `weapon: main_hand` is invalid.
- `VfxBodyPlanner` emits each key as a transform-free `VfxUpdate(keepTransform = true, itemModel = ...)` on the arrival tick of
  its rig tick (key 0 on the spawn tick) on the SAME weapon element. No new element, never a second display; these updates are not
  cut ticks.
- Runtime: `VfxPlayer` calls `IDisplayEntityGateway.setItemModel(handle, model)`; the Paper gateway sets the display's item stack
  to a copy of the real main-hand clone with the `item_model` component overridden (entity metadata through the tracker, so a
  late-tracking viewer receives the current stack in its pairing data). A retained passenger (replacement performance) is reset
  to the real stack with `setItemModel(handle, null)`. At the end the ADR-0276 hand-over shows the real held item unchanged.

## Consequences

- Any weapon can animate between pre-baked poses; the cost is one model per pose in the pack.
- The model swap is sent through the entity tracker, not inside the cut bundle: on a tick that is also a cut the hinge angle can
  land one client frame from the hand jump. Author model keys one tick off cuts if that is visible; measure in the 60 fps capture.
- `tools/skill-viewer` preview still draws the single static weapon model; it ignores `weapon_model`.
