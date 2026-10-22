# Gossamer Katana — source comparison report

Branch: `codex-gossamer-katana`. Source video: `LkLhzmoJZ6Y`. Content-only implementation; no Kotlin changes, no unit tests or test suites. All five item bindings are present; Q costs 100 E.G.O and zero energy.

## Attribution and scope

The supplied wiki kit says base S1/S2/S3 have 3/3/1 coins and defense is an evade. It also lists one- and two-coin alternative skills. The montage has no legible skill labels, so S1/S2/S3 segment attribution remains provisional. The earlier analyses' hit counts were not accepted as evidence: native frames distinguish decorative echoes from damage beats. The grayscale passage is not established as an E.G.O activation, and the late gold-aura sequence may be an alternate identity skill. Neither is asserted to be E.G.O footage.

| Binding / skill | Video range | Wiki coins → damage hits per target | Range and movement | Final pairs | Completed capture iterations |
|---|---|---|---|---|---|
| Left / Thread Cut | 10.300–14.800 s | 3 → 3 (ticks 34, 43, 55) | Dash-melee, 3.6 b approach; 3.3 b forward reach | `build/pairs/gossamer-katana-s1.png` (pending capture) | 0 |
| Right / Unseen Line | 15.300–20.600 s | 3 → 3 (15, 25, 66) | AoE at target; caster stationary, double at 6 b, hit radius 2.2 b | `build/pairs/gossamer-katana-s2.png` (iteration 1, rejected) | 1 |
| Sneak-right / Web Cyclone | 43.000–50.500 s | 1 → 1 (87) | Dash-melee into 4.2 b forward finishing area; echo flurry has no extra damage | `build/pairs/gossamer-katana-s3.png` (pending capture) | 0 |
| Sneak-left / Spun Ward | Not identified | 1 evade coin → 0 attack hits | Backward evasion with pale afterimage; no damaging self AoE | Not applicable: no source case fabricated | 0 |
| Q / Silent Loom | Not identified; original adaptation | No E.G.O kit supplied → 1 original finishing hit (60) | 7 b forward area, stationary charge | Not applicable: no source case fabricated | 0 |

## Timing and budget

The source clips include approach, cinematic holds and recovery. Those durations (90/106/150 ticks) deliberately exceed the art guide's normal ≤60-tick recommendation to preserve the requested source timeline. Individual VFX last 12–40 ticks; Q completes within 100 ticks.

Per crescent: 190° over four out-quad ticks gives angular steps 83.125°, 59.375°, 35.625°, 11.875°. At 20° subdivisions: ceil(83.125/20)+ceil(59.375/20)+ceil(35.625/20)+ceil(11.875/20)=5+3+2+1=11 intervals. Each interval has a two-sided navy slab and a white edge: 11×2+3 weapon parts=25 displays. S2's conservative overlap is two crescents + seven-part echo + three-part held blade = 2×25+7+3=60. The first echo fan's trail expires before the paired fans develop. Other casts have fewer overlapping elements. This is the iteration-3 authoring bound; live count monitoring is queued alongside captures. Hit particles are eight per target per beat; no continuous particle flood.

All Gossamer VFX omit `glow_color`. Bright surfaces use brightness 15, navy/black contrast, and a translucent cyan line bloom. Multi-hit damage bypasses invulnerability. VFX are caster/position anchored rather than spawned once per enemy, so their display count does not multiply with target count.

## Remaining limits to verify or document

Poise-on-use is represented (3/3/5). Exact critical-roll, Bladetrail, Entanglement, Burn activation, alternate-form and ally-execution rules are outside this visual adaptation and are not claimed as implemented.

- Source body animation (deep crouch, kneeling, exact limbs and sheathing) cannot be reproduced by the available weapon-rig content alone. The blade moves, but the player skeleton remains vanilla.
- The double is a six-display geometric approximation, not a textured animated character clone. Exact clone silhouettes and magenta scan distortion are unresolved.
- True scene desaturation/inversion, painted slash textures and cinematic arena replacement are not available as content parameters. Existing screen overlays approximate the flash, not an inverse-color world.
- Screen flashes target the caster; the side spectator capture does not receive caster-only title overlays. Do not widen targeting to everyone merely to improve a screenshot.
- Evade uses backward movement and the existing `evasive` speed status, not guaranteed invulnerability (there is no registered immunity status available to this content); Poise is granted on activation rather than on a confirmed successful evade. The kit's evade-success trigger needs an engine hook.
- S2's third damage is placed on the 18.60 s payout. Coin contact could instead be at the 17.03 s initial line with a later critical echo; source UI does not settle this.
- S3's source has several visible sub-slashes but only one base coin; these remain cosmetic. Alternative-form identification would change that mapping.
- The long thin slash extends beyond the damage cone as a decorative after-trail. It is not a beam damage mechanic.

## Validation

Initial `./gradlew :tools:content-lint:run`: 344 passed, 0 failed. No tests were run. Real-client capture and comparison results will be recorded below; a successful content build alone is not visual acceptance.

## Capture checkpoint

S2 iteration 1 was recorded and inspected. It does **not** match: opening frames are pixel-identical for 30 ticks, the line was centered behind the caster, the lattice was above the target, and the close-up camera was too wide. Coordinate and impact-shape corrections are authored; a warmed capture is pending. Original pair is retained at `build/pairs/iterations/gossamer-katana-s2-1.png`.

Runtime boot rejected a `targets` field on `dash_strike` in S1/S3 despite schema lint accepting it; removed that field. A subsequent boot must confirm registration.

Shared capture slots were held by other weapons' bounded review batches. The retry uses a blocking lock on an existing global slot, a private inner slot to avoid double-acquiring the held lock, and a symlink to the shared subnet lock. This does not raise global concurrency.
