---
status: Accepted
date: 2026-10-23
deciders: founder, Claude
supersedes: null
superseded_by: null
---

# ADR-0250: Skin-rig player animations — Display-entity body rig, skin UVs remapped by a core shader

## Status

Accepted (PoC). Builds on [[adr/0231-skill-timeline-semantics-live-pose-and-sequence]] (`play_animation` is a timeline
mechanic), [[adr/0113-player-entity-block-animation-foundation]] (`IPlayerAnimationGateway.playCustomAnimation`) and
[[adr/0182-resource-pack-pipeline-and-hp-status-hud]] (pack builder). Unverified in a real client at the time of writing: see
"Verification".

## Context

Vanilla player animations (swing, crouch) are bland. We want freely posed, keyframed animations (a custom sword slash
played from a skill, emotes) that keep the **player's own skin**, with no client mod: server plus resource pack only.

## Decision

### Rig

While an animation plays, the player's body is replaced by Display entities:

- six bones (`head`, `body`, `right_arm`, `left_arm`, `right_leg`, `left_leg`), each an `ItemDisplay` holding a
  `player_head` stack that carries the player's profile (so the client fetches the real skin) and
  `item_model = astera:skinrig`;
- a seventh `ItemDisplay` (`THIRDPERSON_RIGHTHAND`) shows the main-hand item, riding the right-arm transform;
- the real player is `isInvisible`; armor and hand items are blanked to tracking viewers with equipment packets
  (re-sent every 2 ticks) and restored at the end.

Rest pose = the vanilla `PlayerModel` boxes and joints (`SkinRigBone.rest`), including slim arms (1 px narrower,
joint 0.5 px lower), so blend in / out has no pop. Blend out ends at weight 0 = rest pose, then the rig is removed.

### Following the player

Each tick every display is teleported to the player's feet with the player's **body yaw** and `teleport_duration = 1`;
the pose is re-sampled and written as a new transformation with `interpolation_duration = 1`. Passenger-riding was
rejected: the vanilla attachment height depends on pose, and passengers do not follow body yaw. The cost is up to one
tick of lag while moving, which `lock_movement` (movement-speed modifier `astera:skinrig_lock`, -85 %) keeps small.
The head additionally applies the live camera pitch and head-to-body yaw as an outer rotation.

### Skin parts: one cube, remapped in the shader

A `player_head` renders the vanilla 8x8x8 head cube plus the 8.5 px hat cube from a 64x64 skin. The display transform
stretches that cube to the bone's box (`scale = size / 8`). The core shader override
(`content/pack-assets/assets/minecraft/shaders/core/entity.{vsh,fsh}`, shipped verbatim by `ResourcePackBuilder`)
remaps the **fragment's** UV from the head-box layout to the same face of the bone's own skin box:

1. the server marks a rig display with `brightness = (block 15, sky = payload)`, payload = bone id (0..5) + 8 for a
   slim arm (`SkinRigMarker`); the vertex shader decodes it from the light UV into a flat `rigPart` (-1 = ordinary
   entity) and lights the rig as `(15, 15)`;
2. the fragment shader finds the face (top, bottom, right, front, left, back) and the 0..1 position inside it from the
   interpolated UV (per fragment, because box-UV corner vertices are shared between three faces and cannot be
   classified per vertex), then samples `texOffs + face origin + position * face extent` of the target box; hat-layer
   UVs (`u >= 32`) map to the outer layer (jacket, sleeves, pants, hat).

The table of `texOffs` / sizes lives in the shader (`rigRemap`); the bone ids live in `SkinRigBone`. Change both together.

### Content contract: `content/animations/<id>.yaml`

One animation per file, `id` = file stem (lower-kebab-case, shared with `CustomAnimationId`):

```yaml
id: sword-swing
length_ticks: 12          # 20 ticks = 1 s
loop: false
lock_movement: true
blend_in_ticks: 2
blend_out_ticks: 3
bones:                    # any bone may be omitted = rest pose; unknown bone = load error
  right_arm:
    rotation: [ { t: 0, v: [0,0,0] }, { t: 4, v: [-150,0,20], ease: ease_out } ]
    position: [ ... ]     # optional, pixels
    scale: [ ... ]        # optional, multiplier around the pivot
root: { rotation: [...], position: [...] }   # whole rig, pivot = hip (0, 12, 0) px
```

`ease` is the curve into that keyframe: `linear` (default), `step`, `ease_in`, `ease_out`, `ease_in_out`, `catmullrom`.
Keyframes are strictly ascending, `0 <= t <= length_ticks`. Exact frame (the importer must emit this): rig frame is
+Y up, player front +Z, player right hand at -X, positions in px; rotations are degrees, `R = Rz * Ry * Rx` (X applied
first) around the vanilla joint (neck for head, `(0,24,0)` top of torso for body, shoulder for arms, hip for legs), so
a negative X rotation swings an arm forward and up. Rotations are relative to the rest pose.

### Entry points

- `play_animation` mechanic (existing): `animation: <id>` of a file in `content/animations/` starts the rig through
  `IPlayerAnimationGateway.playCustomAnimation`; the mechanic's `ticks` is ignored (the file owns its length).
  Other ids keep their old pose-only meaning (`crouch`, `hide-main-hand`, `swing-main-hand`).
- `/astera emote <id>` and `/astera emote stop` (permission `astera.emote.use`, everyone; tab-completes the ids).

### Ownership

`plugin:core`. Both combat skills (via the core gateway) and lobby emotes use it; combat and lobby only see the core
port `ISkinRigAnimator`, so ADR-0022 isolation is unchanged. Layers: pure domain (`core.domain.animation.skinrig`:
bones, channels, playback state machine, Display-transform math), kaml DTO in `application/config`, `SkinRigContents`
(`/astera reload animations`), `InMemorySkinRigAnimator` (same timeline, no rendering: game-sim) and
`PaperSkinRigAnimator` (p=100).

## Consequences

### Positive
- Any pose, any skin, no client mod; skills and emotes share one content type; the whole timeline is testable headless.

### Negative / limits
- **First-person view is unchanged** (vanilla arm), and the **hitbox is unchanged**.
- Follow lag of up to one tick while moving; the rig is lit full-bright (`(15,15)` lightmap), also in caves and at night.
- Armor is hidden during playback (not drawn on the rig); capes, elytra and the skin-customization toggles (jacket,
  sleeves, pants, hat) are not modelled: outer layers always show. No elbows / knees (PoC six bones).
- The override replaces `core/entity.{vsh,fsh}`, which every entity uses: it must be re-based when the pack format
  changes (`min_format` / `max_format` pin 26.3), and it is a modified copy of Mojang's shader. An entity at block light
  exactly 15 with a sky value in {0..5, 8..13} would be drawn wrongly (only inside light-15 sources such as lava).
- Other resource packs that also replace `core/entity.*` conflict.

## Verification

Needs a real client: `tools/vfx-shot` (`SKILL=anim-demo-sword-swing VIEW=side`). Assumptions to confirm there:
the item-model origin (`translation [0.5, 0.25, 0.5]` in `astera:skinrig` centres the cube; if the rig is half a block
off, adjust that one number), the front direction (+Z at yaw 0), the face-to-skin mapping, and the held-item anchor
(`HAND_OFFSET` in `SkinRigTransforms`).

## Alternatives Considered
- **Passenger rig** — see "Following the player".
- **Model-engine style plugins / per-bone JSON models with the skin as a texture** — a model cannot reference a
  profile skin; only the `player_head` special model can, hence the UV remap.
- **Per-vertex UV remap** — the shared corner vertices of the box UV layout make the face ambiguous.

## References
- ADR-0231, ADR-0113, ADR-0182, ADR-0022
