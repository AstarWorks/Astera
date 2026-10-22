# :tools:game-sim — headless game simulator

ADR-0174 / WP-S. A **non-shipped** module that boots Astera without Minecraft:
`core -> items -> combat -> match -> stats -> economy` through the *same*
`*Wiring.install(ctx, ports)` functions the Paper plugins call, with simulator
ports instead of Paper adapters. Scenarios are Kotlin DSL JUnit 5 tests; every
run writes a JSON trace a viewer (AsteraStudio) can replay.

No Bukkit/Paper is on the runtime classpath (`paper-api` is `compileOnly` in the
plugin modules). If a wiring function ever needs a Paper type, the Konsist rule
`platform wiring must stay free of Bukkit and Paper adapters (ADR-0174)` fails.

```
./gradlew :tools:game-sim:test        # run scenarios, write build/sim-traces/<scenario>.json
```

## Writing a scenario

```kotlin
@Test fun `pistol kills`() {
    scenario("pistol-kills-player") {              // boots GameSim from the real content/
        val shooter = player("shooter", team = "red")
        val victim  = player("victim", at = Vec3(0.0, groundY, 10.0), team = "blue")
        shooter.holds("pistol")             // GiveItemUseCase -> held item
        shooter.faces(victim)
        repeat(20) { if (eventsOf<PlayerKilled>().isEmpty()) { shooter.click(); advance(10) } }
        assertThat(eventsOf<PlayerKilled>().single().victim).isEqualTo(victim.id)
    }                                               // trace is always written, even on failure
}
```

Verbs: `player`, `mob`, `holds`, `faces`, `click` (left click through the real
Items trigger sink), `cast(skillId)` (publishes `SkillCastRequested`, what a bound
item emits), `advance(ticks)`, `advanceUntil`, `hp`, `eventsOf<T>()`. Everything
`moveTo(pos)` (one `PlayerMoved`, like the Paper move adapter, for players in a match),
`breaks(cell)` (digs the block and publishes `BlockBroken`), `blockAt(cell)`,
`startMatch(mode, mapOf(player to "red", ...))` (real lifecycle facade, countdown skipped,
match ACTIVE on return; see `SiegeModesScenarioTest`). Everything
else is reachable through `sim` (`GameSim`): `sim.services`, `sim.match`, ...

## Simulator ports (what replaces Paper)

| Port | Simulator impl | Notes |
|---|---|---|
| `IScheduler` | `FakeScheduler` (test-fixtures) | virtual time; `GameSim.step` advances one tick at a time |
| `IWorldGateway` | `RecordingWorldGateway` | tick-stamps particles, sounds, teleports; Core's in-memory particle / sound *schedulers* run on top of it, so scheduled effects are recorded at emission tick |
| `IProjectileService` | `SimProjectileService` | per-tick `ProjectileStepper` (the same stepping Paper runs) with a flat-world `ProjectileWorldProbe`; calls `ProjectileImpactResolver` once per projectile (`onHitEntity` / `onHitBlock` / `onExpire`) |
| `INearbyTargetQuery`, `IPlayerPoseLookup` | `SimNearbyTargetQuery`, `SimPoseLookup` | over `SimEntity` positions. NB: no Paper impl of `INearbyTargetQuery` is registered in production yet |
| `IDamageGateway` | `SimDamageGateway` | ADR-0149/0151: reads/writes `CURRENT_HP` in the attribute container, `Killed` at 0, Bukkit-style 20-tick invulnerability frames |
| `IStatusEffectContainer` | `SimStatusEffectContainer` | + the 20-tick DoT / expiry loop of Paper's `StatusEffectTickListener` |
| Core ports (`IMcServer`, GUI, broadcaster, commands) | `SimMcServer`, `FakeGuiAdapter`, `FakeBroadcaster`; commands: the real `AsteraCommandRegistry` + `CommandTreeRunner` (`sim.command(...)`, `sim.complete(...)`, ADR-0184) | |
| `IExplosionGateway`, `ISummonGateway`, `IDisplayEntityGateway` (WP5) | `SimExplosionGateway`, `SimSummonGateway`, `SimDisplayGateway` | explosion: linear falloff damage through the production `ApplyDamageUseCase` (armor / friendly fire apply), radial velocity, terrain *not* simulated (policy is recorded); summon: an inert `SimEntity` mob carrying the owner's team; display: record only. All write `effects` into the trace. Temporary blocks go through `RecordingWorldGateway` (`block_place` / `block_remove`), lightning is taken from the `LightningStrike` event |
| `IItemDelivery`, `IPaperItemIdResolver` | `SimItemDelivery`, `SimItemIdResolver` | first item given is the held (main-hand) item |

The post-wiring "Paper step" is replicated for Combat only (projectile stepper,
status tick, and the `PlayerHealthChanged -> PlayerKilled` publisher for DoT
kills). Simulated players / mobs are plain data (`SimEntity`: id, position, look
direction, team); mobs are addressed by id like players and have a 0.6 x 1.8 hitbox.

### Approximations (vs. real Paper)

- Hit test: segment vs entity box inflated by the projectile's hit radius; the
  world is a flat ground plane at `groundY` (default -60.0, the playtest superflat)
  plus blocks placed through the world gateway.
- `MotionProfile.initialSpeed` is blocks/second; the sim (like Paper) moves
  `initialSpeed / 20` blocks per tick. `Curved` flies like `Linear`; `Beam` is an
  instant ray resolved on the next stepped tick; ballistic `gravity` is blocks/tick^2.
- Randomness in the dispatcher (status `chance`, crits) is not seedable;
  scenarios retry or assert on invariants, never on a single roll.
- Rendering is out of scope: particle keys are content ids, not client particles.

## Trace schema `astera-sim-trace/1`

`build/sim-traces/<scenario>.json` (written by `GameSim.writeTrace`, always at
scenario end). Positions are world coordinates in blocks; ids are UUID strings.

```jsonc
{
  "schema": "astera-sim-trace/1",
  "scenario": "pistol-kills-player",
  "tickRate": 20,
  "groundY": -60.0,                 // flat ground plane (feet level)
  "entities": [                     // static info of every spawned entity
    { "id": "…", "name": "shooter", "kind": "player" /* or "mob" */, "team": "red" /* or null */ }
  ],
  "ticks": [                        // ascending; tick 0 = state before the first step
    {
      "tick": 41,
      "entities":   [ { "id": "…", "x": 0, "y": -60, "z": 10, "dx": 0, "dy": 0, "dz": 1, "hp": 20.0 } ],
                                    // end-of-tick snapshot of every entity (hp = Astera CURRENT_HP, may be null)
      "projectiles":[ { "id": "…", "owner": "…", "x": 0, "y": -58.4, "z": 4.1 } ],
                                    // projectiles still in flight at end of tick
      "projectileEvents": [ { "id": "…", "kind": "spawn|hit_entity|hit_block|expire|cancel",
                              "owner": "…", "target": "…" /* hit_entity only, else null */, "x": 0, "y": 0, "z": 0 } ],
      "particles":  [ { "key": "example-burst", "count": 1, "world": "world", "x": 0, "y": 0, "z": 0 } ],
                                    // optional per particle: color{r,g,b}, scale, extra (speed);
                                    // count 0 = directed: "velocity":{x,y,z} unit direction + "speed";
                                    // "data":{kind: dust|dust_color_transition|tint|spell|block|item|trail|
                                    //   sculk_charge|shriek|vibration|power, ...} (trail/vibration: absolute "target");
                                    // "force": true, "viewers": "caster"|"others"
      "sounds":     [ { "key": "entity.blaze.shoot", "volume": 1.0, "pitch": 1.0, "world": "world", "x": 0, "y": 0, "z": 0 } ],
      "damage":     [ { "attacker": "…"/*null*/, "target": "…", "amount": 11.0, "source": "Skill", "hpAfter": 9.0 } ],
      "status":     [ { "target": "…", "effect": "burn", "op": "applied|removed|expired",
                        "stacks": 1 /* applied only */, "remainingTicks": 80 /* applied only */ } ],
      "deaths":     [ { "victim": "…", "killer": "…"/*null*/, "source": "Skill" } ],
      "teleports":  [ { "id": "…", "world": "world", "x": 0, "y": 0, "z": 0 } ],
      "effects":    [ { "kind": "explosion|lightning|block_place|block_remove|display_spawn|display_remove|summon|despawn|glow|camera_shake",
                        "detail": "radius=5.0 damage=10.0 hit=2 blocks=None", "x": 0, "y": 0, "z": 0 /* absent for camera_shake */ } ],
      "hud":        [ { "player": "…", "region": "SIDEBAR|ACTION_BAR|STATUS_LINE|…", "op": "show|update|clear",
                        "summary": "i18n keys + placeholders of the layout",
                        "layout": { "component": { "text": "…", "font": "astera:default", "widthPx": 80 }, "anchorLeftPx": -91 } } ],
      "events":     [ { "type": "MatchStarted", "summary": "MatchStarted(…)" } ]
                                    // every other DomainEvent (HUD / regen chatter filtered out); summary = toString, truncated
    }
  ]
}
```

Notes for viewers: arrays are always present (possibly empty); effects happen
*during* the tick listed, snapshots are taken *after* it. Events fired between
`step` calls (e.g. a click) are stamped with the last completed tick.

HUD events additionally carry a structured `layout` (null on clear), with messages resolved
at event time using the simulated viewer's locale. `component`, sidebar `lines`/`title`,
tab `header`/`footer`, title fade timings, `anchorLeftPx` and the full, untruncated
custom-font strings support [`hud-preview`](../hud-preview/README.md). Components use
`text` with optional `font`/`widthPx`/RGB `color`, or `parts` with `spacing`.
`summary` remains the compact log label used by the skill viewer. Regenerate old
traces before rendering; HUD preview does not reconstruct layouts from summaries.
