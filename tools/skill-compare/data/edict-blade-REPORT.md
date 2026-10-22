# Edict Blade — final comparison report

**Result: partial implementation; strict video fidelity NOT achieved.** All five bindings are authored and real-client captured. S1/S2/S3/E.G.O reached the requested four-capture limit; their final pairs were opened and inspected. DEF received two smoke captures and has no invented reference case. Validation succeeds, but “built” is not reported as “matches.”

## Per-skill results

| Binding / skill | Source range | Wiki coins → scheduled damage beats | Range / caster movement | Captures | Verdict / final committed pairs |
|---|---|---|---|---|---|
| left_click / edict-s1 | 11.0–14.1s | 2 → 2, ticks 29/44 | dash-melee; 5.4-block approach, two sweeps | 4 | Partial: [S1 pairs](edict-blade-s1/pairs-final.png) |
| right_click / edict-s2 | 16.8–23.6s | 3 → 3, ticks 29/66/118 | dash-melee; leap/dive, reverse sweep, vault/descent | 4 | Partial: [S2 pairs](edict-blade-s2/pairs-final.png) |
| sneak_right / edict-s3 | 30.4–35.2s | normal form 2 → 2, ticks 20/71 | dash-melee; sweep, vault/descent | 4 | Partial: [S3 pairs](edict-blade-s3/pairs-final.png) |
| sneak_left / edict-def | not identified | 3 → 3, ticks 8/22/36 | close melee; stationary brace/timed cuts | 2 | Wiki-derived approximation; no video fidelity claim |
| drop / edict-ego | 35.8–39.2s | non-damaging form entry → 0 | self buff; stationary transformation | 4 | Partial: [E.G.O pairs](edict-blade-ego/pairs-final.png) |

Final generated paths are also `build/pairs/edict-blade-{s1,s2,s3,ego}.png`. Final raw captures and health diagnostics remain under `build/edict-pdca/<slot>/iteration-4/`; DEF is `build/edict-pdca/def/iteration-2/`, including its inspected `sheet.png`. The four committed `pairs-final.png` files survive build cleanup.

## Remaining mismatches

- **S1:** first burst is late at the selected contact frame (visible at 12.65s); the glass fan sits lower and is more segmented than the painted arc; the burst reads as a cog rather than sharp luminous spikes. Camera cut timing and low recovery pose do not match. Final dummy health 194.12/200; earlier iteration 3 reached 188.19199, so consistent landing of both scheduled beats is not established.
- **S2:** upside-down dive and turning body are missing; first contact/cut timing lags; target-relative crossing is approximate. Later hits show a filled, centered burst, but its rectangular rays, brightness and ring texture remain unlike the source. Final dummy health 189.2/200.
- **S3:** the approach rig can appear detached before the caster reaches it; body inversion and blue cape recovery are absent. The signature burst is visible at 34.2s, but the 34.0s white cut-in is missing from the spectator capture. Burst shape, brightness, scale/framing and camera-cut timing remain mismatched. Final dummy health 192.14/200.
- **DEF:** timed attacks are not a reactive clashable counter or percentage-HP shield. The smoke sheet shows three red damage reactions; final health 191.18/200. The world-oriented burst is edge-on from this side view, a remaining authoring limitation.
- **E.G.O:** cipher and black/violet silhouette are now visible, but limbs are angular/disconnected while growing, the upright body does not crouch into the source mass, violet shards are not luminous ribbons, and the cut-in/cipher timing remains late. Health stayed 200/200. Q spends 100 E.G.O and grants three Poise; it does not implement the source's SP system.

The source's later transformed attacks at approximately 42.8–56.2s are **not implemented**. The old flipbook misidentified the earlier normal S3 as the transformed four-coin attack; native frames and the wiki support two contacts in 30.4–35.2s. The blue fan at 32.1s is recovery/cape motion, not a third hit. Persistent form replacement, Unlock progression, shield/counter rules and the later alternate kit remain coverage limitations; this report does not claim they are all impossible in the engine.

## Runtime gaps and content workarounds

1. `PaperPlayerPoseLookup.poseOf` resolves players, not mobs. Mob VFX anchors through `VfxMechanics` fall back to the caster origin. These skills use fixed caster-forward contact locations; effects do not precisely track a knocked-back mob and can appear on a miss.
2. `TargeterResolver` does not apply NearbyEntities.max_count. Each damage beat instead uses Random(count=1) over a 1.7-block sphere centered 2.6 blocks forward at chest height. Damage, status and hit-stop resolve independently and may choose different victims in a crowd. This is a single-target melee volume, not AoE damage.
3. Screen-flash is caster-only; the spectator camera does not receive the source-like full-screen cut-in. Independent weapon rigs do not animate the normal player's articulated limbs/cape or reproduce the inverted sprite poses.
4. In these captures, billboard quad centering/orientation produced displaced bars and broad faceted trails. The final content uses centered block fills and finely sampled glass slabs instead. This avoids that observed rendering issue but still needs better art; it is not a claim that rectangular bursts are the best the engine can express.
5. Native frames are 33.37ms; Minecraft ticks are 50ms. The capture director uses wall-clock camera cuts while skill execution/rendering has variable delay. Final pairs use the tool's unmodified 60ms render-lag setting and 6 pre-ticks; no selective per-hit offset was used to disguise lag.

No Kotlin or tracked tool changes were made. The remaining geometry/framing defects are explicitly authoring limitations as well as engine/runtime constraints, not blanket engine excuses.

## Display budget and validation

Final attack bound: 3 sword parts + at most 24 trail slabs + 30 finisher displays + 2 mark displays = **59 simultaneous displays**. For the 160-degree out-quad swing, actual subdivision is ceil(70/8)+ceil(50/8)+ceil(30/8)+ceil(10/8)=22 slabs, giving 57 at that overlap. Trails expire before the next fan begins. The nine-display approach trail finishes before contact; DEF's one-display guard finishes before its peak. E.G.O: 24 limb segments + 8 thorns + 3 mantle blocks + 1 cipher + 8 shards + 8 translucent particles = **52**. These are static bounds: server entity-selector counts do not measure packet displays.

Large shapes have no glow_color. Brightness-15 pale fills, translucent glass/bloom, particles, and dark accents remain. Multi-hit damage uses ignore_invulnerability. Aggregate health confirms damage occurred, not that every authored beat hit; knockback remains enabled. Reused clients retain caster statuses between cases, so these health totals are not normalized damage-balance measurements.

`./gradlew :tools:content-lint:run`: **365 passed, 0 failed**, BUILD SUCCESSFUL. `git diff --check` passed. No unit tests or test suites were run. Source durations are retained even where the whole skill exceeds the general 60-tick art guideline.

## Reproduction and audit trail

Each filmed case includes case.json, intent.json (camera/actors/weapon/effects), frame-time measurement tables with conversion formulas, and a CHECK/ACT ledger in notes.md. Measurements were estimated from native-rate sheets; inferred body heights and camera matrices remain approximate.

The supplied MP4 lacked audio. Its separate M4A was muxed without re-encoding into `/tmp/edict-reference/x6sc9JkW77c.mp4`; source assets were not changed. Pair commands use `SKILL_COMPARE_VIDEO_DIR=/tmp/edict-reference`.

Captures use the real Minecraft client and the existing vfx-shot pipeline. Temporary ignored wrappers waited on the same global slot locks, reused one isolated server for later rounds, copied only Edict content into that server and reloaded it. Resolution was reduced to 854×480 and render distance to 2. The dummy's movement/follow/attack attributes were explicitly set to zero; NoAI was not applied, preserving knockback. The capture server was stopped after the final round.

S3 iteration 3 retained the old VFX after a rejected reload (block keyframes still had quad-only alpha). That was corrected and successfully reloaded before S2 iteration 3. All final iteration-4 captures used the successfully reloaded final content. Earlier images are archived under `build/pairs/edict-blade-<slot>-iteration<N>.png`.

Final pair selections:
- S1: 11.3 approach, 12.3 wind-up, 12.5 hit 1, 12.65 signature, 13.1 cut-in, 13.3 hit 2, 13.6 recovery.
- S2: 17.2 leap, 18.3 hit 1, 20.2 hit 2, 21.5 vault, 22.9 finisher, 23.4 recovery.
- S3: 31.1 charge, 31.4 hit 1, 32.1 recovery fan, 33.2 vault, 34.0 finisher, 34.2 signature, 34.6 recovery.
- E.G.O: 35.95 cipher, 36.5 cut-in, 36.65 unfurl, 37.1 resolve, 37.8 hold, 38.5 recovery.
