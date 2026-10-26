---
status: Accepted
date: 2026-10-26
deciders: founder, Claude (design: Fable, one-sword architecture review 2026-10-25)
supersedes: null
superseded_by: null
---

# ADR-0276: Skill performance — body and weapon in one plan, passenger transport, one presentation lease

## Status

Accepted. Partially supersedes [[adr/0250-skin-rig-player-animations-display-rig-and-skin-uv-shader]] for **skill playback**
(its Follow transport, its equipment hiding and its 7th held-item display); the emote path (`/astera emote`, `play_animation`
outside a performance) keeps ADR-0250's design except that it now goes through the lease below. Extends
[[adr/0220-display-choreography-vfx-and-combo-mechanics]] (a rig can now carry the body and a weapon element) and replaces the
`hide_held_item` / rig-staging hand-over of the weapon-rig rounds r16-r26; reuses the shading decision of
[[adr/0275-weapon-rig-shading-marker-and-item-shader-patch]] only for rigs that still use `parts`.

## Context

The founder hates seeing two swords. During a Limbus-style skill exactly ONE weapon must be visible on every frame, for every
viewer, including start, end, interruption, death, logout, world change and a viewer that starts tracking mid-skill, and the
weapon must stay in the character's right hand while the body animates. Scope (founder 2026-10-25): the third-person side view;
first person is out of scope.

Before this ADR three independent systems each owned a piece of the picture:

1. the weapon rig: 22 `BlockDisplay` parts riding the caster as native passengers, in a cast-time frame, hand-over timed by
   `HeldItemHider` (needed `hide_held_item` and `play_vfx` in the same tick and a hand-kept `57` next to `58`);
2. the skin rig (ADR-0250): six `ItemDisplay` bones teleported to the feet every tick with a body-yaw estimate, `isInvisible`
   set a tick away from the equipment packets, AIR re-sent to every viewer (the owner too) every two ticks, plus an
   unconditional 7th display of the held stack (the second sword);
3. nobody owning the viewer side: quit and world change left orphan passengers, a hotbar scroll or a late-tracking viewer
   showed the real weapon next to the stand-in.

Two transports (passenger vs teleport) separate the hilt from the hand in a dash; two owners of the same slots break the
atomic hand-over.

## Decision

A skill is one **performance**: one `VfxPlayer` instance plans and plays the body bones and the weapon together, from one clock,
in one frame (the cast-time yaw), on one transport (native passengers of the caster), revealed and removed by one packet bundle
per viewer.

### 1. The weapon is one `ItemDisplay` of the real main-hand stack

`VfxRig` gains `weapon: main_hand`, `parent: anchor | right_hand`, optional `weapon_tip` / `weapon_edge` (plan dump only) and
`parts` becomes optional (it stays for props that are not the held item; a weapon rig must not have parts, a right-hand rig
cannot have parts). One display instead of 22 frees 21 of the 60 displays of a cast and removes the part-matching, nest-drift
and per-part shading work for the held weapon: the real model has the real materials and silhouette at both hand-overs.

Composition per rig tick `t`, in the anchor frame (anchor = caster feet, rotation = cast-time yaw):

```
hand(t)   = right-arm frame of the body at t               (SkinRigTransforms.rightArm: joint + root x arm rotation)
frame(t)  = hand(t) . Q(t)        Q(t) = tilt . euler(swing(t)) . rest   -- the wrist, authored in the body-rest frame
origin(t) = joint(t) + arm(t) . (HELD_GRIP_OFFSET + pivot)               -- the grip
weapon(t) = origin(t), rotation frame(t) . Q(0)^-1 . rotX(HELD_GRIP_PITCH)
```

`HELD_GRIP_OFFSET` / `HELD_GRIP_PITCH` (72 degrees = the 90 degree `ItemDisplay` tilt minus vanilla's 18 degree item-arm lift) are
the constant grip that used to be folded into `SkinRigTransforms.heldItem`; the hand frame itself is anatomical. Swing key 0 is
by definition the vanilla held pose on a body at rest, later keys are rotations away from it, so the end hand-over to the real
item is exact. In `parent: anchor` mode the hand is the identity frame and `pivot` is the absolute fist position (the previous
semantics, so existing rigs only swap their parts for `weapon: main_hand`). Trails, boards and emitters follow the same frame
(`VfxRigMotion.pointAt`), so every effect that follows the blade follows the hand.

### 2. One planner, pose-to-pose grammar

`VfxDefinition.body: <animation id>` names a `content/animations/<id>.yaml` skin-rig animation, sampled with no blend on the
weapon rig's clock (so `respect_hit_stop` freezes body and weapon identically; its `length_ticks` must equal `duration_ticks`).
A body requires `follow`, `attach_to_anchor` and `sample_ticks: 1`, and a `weapon` rig with `parent: right_hand`; at most one
weapon rig per effect.

`VfxBodyPlanner` (pure, `core.domain.effect.vfx`) turns the six `SkinBone` elements and the one `MainHandWeapon` element into
ordinary `VfxElementPlan`s: a state is sent once per rig tick and only when it differs (a hold emits nothing). A key with
`ease: step` (new `Easing.STEP`, body channel or wrist) is a **cut**: every element jumps with interpolation 0 on the arrival tick
and no intermediate chunk is drawn (the reference never draws the swing). The plan carries `cutTicks`; on those ticks, and on
the reveal tick, the runtime applies the transform updates inside a presentation bundle at server tick end instead of through
the entity tracker, whose per-entity sends can straddle a client frame (hand a step ahead of the hilt). A normal move that would
start in a cut's tick becomes a cut on its own arrival tick.

### 3. One transport, one frame

All performers ride the caster as passengers; display entity yaw is 0 and never rotated; interpolation delay 0, duration 1
(0 on a cut). The attachment height is captured once when the display is attached, and the caster's pose is **pinned
STANDING** for the performance (`PresentationMask.pinPose`), so a sneak cannot move the client attachment while the compensation
lerps. Side effect: sneaking does not crouch the hitbox during a skill; skills with a body must not use the old `crouch`
mechanic. The body-yaw estimate, head look and `teleport_duration` are emote-only. A performer that cannot be attached as a
passenger aborts the performance (a second transport would separate hilt and hand in a dash).

### 4. One owner of hiding: `IPlayerPresentation` (leases)

`core.application.port.outbound.presentation.IPlayerPresentation` is the only code that sends equipment, invisibility or pose
changes for a player. A performance, an emote and a timed `hide_held_item` each `claim` a lease with a `PresentationMask`
(hand, offhand, armor, body, pinPose). The union of a player's leases is what viewers see; a slot is restored only when the last
lease covering it is released, which also makes a chained skill free of a restore: the new lease is claimed before the old one is
released. A performance preempts an emote; an emote is refused (`SkinRigError.Busy`) while a performance runs.

Transitions are bundles built from `PaperPacketBundler` (moved from combat to `core.adapter.paper.net`, now also building
multi-slot equipment, entity-data and removal packets): `reveal` = hide + the staged displays' entity data (+ the caster's
invisibility, taken from the player's own dirty entity data) in ONE `ClientboundBundlePacket` per viewer at server tick end;
`release` is split into the **owner leg** (hand back and displays scaled to zero in the same client step, a tick before the
entities go) and the **others leg** (restore + visible + removal of the displays in one bundle, sent before the server removes
them). The packet sequence of r16-r26 is kept, not rewritten. The periodic two-tick equipment re-send of ADR-0250 is gone;
the events that made it necessary are handled instead:

| Event | Handling |
|---|---|
| `PlayerTrackEntityEvent` (late tracking) | the mask is sent to that viewer at the end of the same tick (one frame of real equipment in the pairing data is the accepted residual) |
| `PlayerItemHeldEvent`, `PlayerSwapHandItemsEvent` | cancelled while the hand is leased (Paper re-syncs the slot) |
| `PlayerArmorChangeEvent` | armor mask re-sent |
| quit, world change, vanilla death | lease force-released (`onRevoked` tells the holder to drop its displays) |
| Astera death (`PlayerKilled`), `PlayerQuitServer`, `PlayerChangedWorldEvent` | `VfxPlayer.endFor` in that tick: one `finish` path, immediate removal |
| plugin disable | `VfxPlayer.shutdown` (combat) before the lease service (core) |

The lease is derived from the rig: a performance claims it and needs no `hide_held_item` step (the step stays for rig-less
skills, implemented as a timed lease by `HeldItemHider`). `IEntityEffectsGateway.setHeldItemHidden/restoreHeldItem`,
`RigReveal` and `HeldItemAudience` are deleted. `VfxPlayer` has a single exit (`finish`) for every end: normal end (owner leg a
tick before, others leg with the removal), replacement, death, quit, world change, shutdown.

### 5. Retired

The 22-part `bastion-s1-rig` is replaced by `weapon: main_hand` (pre-release: parts deleted, no shim). The skin rig's unconditional
7th display, its `isInvisible` toggling and its AIR re-send are deleted; the emote's 7th display exists only while it holds the
lease. `play_animation` of a skin-rig id from a skill still plays as an emote through the lease (the `anim-shot` review stack
uses it) and is refused while a performance runs, so a skill cannot layer an emote body under its performance.

## Plan dump for `skill-lint`

`VfxPerformanceSampler` evaluates the planner math without entities, in caster-local space (+x right, +y up, +z forward, the
cast-time facing fixed, origin at the feet; the VFX frame's +x = left is mirrored). Run:

```
make skill-lint-plan SKILL=bastion-greatsword-s1      # ./gradlew :tools:game-sim:skillLintPlan -Pskill=<skill>
make skill-lint SKILL=bastion-greatsword-s1 PLAN=build/skill-lint/bastion-greatsword-s1/plan.json
```

This casts the real skill headlessly (`SkillLintPlanDump`) and writes `build/skill-lint/<skill>/plan.json`:
`{ skill, ticks: [ { tick, bones: { <bone>: {pos, rot} }, weapon: {pos, rot, tip, edgeNormal}, visibleWeapons } ] }`. Bones:
`head, body, right_arm, left_arm, right_leg, left_leg` (box centres) plus the landmarks `hips` (hip pivot with the root
rotation) and `right_hand` (the grip point of the right arm). `weapon.pos` is the grip origin, the blade axis is `tip - pos`
(`weapon_tip`, rig-local, default the first trail's `to`), `edgeNormal` the direction of the cutting edge (`weapon_edge`).
`tick` is the skill tick (the `play_vfx` start is detected from the first weapon spawn). `visibleWeapons` is the worst viewer's
count from the lease model (`PerformanceProbe`, the same one the scenarios assert on). `PerformanceScenarioTest` asserts
count == 1 per tick and per viewer (start, end, interruption, death, quit, world change, late tracking) and the hand lock.

## Consequences

- The founder's invariant holds by construction: one weapon entity, every transition one atomic client step per viewer.
- The viewer-side guarantees are verified headlessly through the lease model; the packet-level timing (bundles, owner-leg
  restore, cut flush, first frame of late tracking) still needs the 60 fps real-client capture (`FPS=60 make vfx-shot`, count and
  hand-contact rows) before it counts as proven.
- Measured-not-built: bundling every tick (not only cuts and the reveal) and hiding the head bone from the owner's first-person
  camera. Both are deferred with first person.
- Content: Bastion S1 keeps its old absolute swing keys on a body at rest (`parent: anchor`) until the body animation and the
  wrist keys are authored against the reference (`parent: right_hand`, `body:`).
- Bone transforms of a performance use the caster's own profile; slim skins are detected when the performance starts.
