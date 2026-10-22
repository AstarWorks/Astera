---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0200: Lobby training range (F3-11)

## Status

Accepted. Extends ADR-0180 (sky-island lobby) and ADR-0185 (lobby menu kit).

## Context

Founder: "weapons and skills can't really be used". In the lobby nothing can be hit (the lobby rules cancel all
damage to players, and there are no other targets), and the only place to fight is a queued match.

## Decision

### A training island in the lobby plan

`LobbyTrainingLayout` (core, pure) defines one more satellite island east of the market, joined to the main island
by a fenced bridge (`marker_version` 3 repastes it). Its landmarks are a pure function of the lobby centre and surface
Y, so the island generator (blocks), Core wiring (plan) and tests share one set of numbers:

- firing platform + four lanes with a dummy at 5 / 10 / 20 / 40 blocks (one lane each so a dummy never shields the next);
- melee pit (sunken sand floor, four dummies);
- moving-target track (a dummy walking a ping-pong `TrainingTrack`) with its own firing stand;
- weapon rack: a wall with 3 x 12 item-frame slots, a walkway in the middle (the bridge arrives there).

The island has its own random stream and flattened underside, so the older islands keep their shape and the build stays
above `void_rescue_y`. The lobby footprint test now allows the wider build.

### Where the range is published

Core registers `ILobbyTrainingRange` (world + `LobbyTrainingPlan` incl. region) on backends that host the lobby. The
lobby world itself is created later (ServerLoadEvent), so every consumer is lazy and retries.

### Dummies (Combat)

A dummy is an Astera-controlled entity: a Paper `Mannequin` body (no AI, no gravity, immovable, non-persistent) that
only provides hitbox and name tag. State lives in `TrainingDummy` (HP 500, rolling 5 s DPS, refill after 3 s idle, never
below 1 HP so targeting never skips it as dead). `TrainingDummyEntityDamageGateway` sits in front of the host
`IEntityDamageGateway`: the body id is a dummy id, so skills, projectiles, AoE and status all reach it through the normal
`ApplyDamageUseCase` entity path and the hit lands on the dummy model, never on the Bukkit entity (a guard cancels every
vanilla damage event on a body). `TrainingRangeService` (2-tick loop) spawns/repairs bodies, moves the mover, undoes
knockback, refills idle dummies, keeps the name / `HP x/y | DPS z` tag current (customName + description) and shows a
20-tick text-display damage number per hit (gold for crits).

### Region rules, not a global switch

The lobby keeps cancelling damage to players everywhere. Inside the range region a player is additionally **protected**
(`TrainingRangeProtection`): `ApplyDamageUseCase` refuses damage to protected players (`TargetImmune`) and the nearby-target
query never offers them, so no skill selects another player there. Dummies are never protected. Outside the region nothing
changes. Energy, ammo, cooldowns and statuses work as in a match because the normal pipeline is used.

### Weapon rack (Items)

`TrainingRackService` shows one fixed item frame per weapon of `content/items` (the admin catalog's one-of-each-weapon
kit, so new YAML appears on its own) and hands the weapon out on right-click to any player standing in the range (no
permission). `IItemDelivery.give(..., origin = ItemOrigin.TRAINING)` tags the stack `astera:training_item = <item id>`.
Training stacks are lobby-only: `PaperInventorySnapshotSerializer` encodes them as empty and ignores them on restore (so
no entry/quit/handoff snapshot or vault ever holds one, like the ADR-0185 kit), they cannot be dropped or put into another
container, and they are stripped on quit, when the player leaves the lobby world, on `MatchFormed` / `MatchStarted`, and by a
1 s sweep that removes them from anyone who is in a match.

### Kit button

`content/gui/kit/lobby-kit.yaml` gets hotbar slot 4 `hotbar-training` (item `gui-tile-training`), intent
`lobby.training.travel`, handled by `LobbyTrainingTravelIntentHandler` (teleport to the range spawn).

## Consequences

- The lobby build is repasted once (`marker_version` 3). The range spawns about 9 dummy bodies and 36 rack frames per lobby
  backend; both are non-persistent and swept by tag after a crash or `/reload`.
- Status effects applied directly to a protected player (not through target selection) are not blocked; only damage and
  skill targeting are.
- Dummies are server-global (same view for everyone); the label language is the server default.
- The game simulator keeps the range's world "unloaded" unless a scenario switches `sim.trainingShells.worldLoaded` on, so
  other scenarios' entity lists and traces are unchanged.
