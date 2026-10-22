# Crimson Chain-Axe — source comparison report

Branch: `codex-crimson-chain-axe`. Scope: this weapon's content and comparison data only. No Kotlin changes, unit tests, test suites, or push.

## Evidence and interpretation

Source `Gpj-lvOnAgs` is a trailer, not a labelled skill showcase. The supplied wiki contains multiple skill variants. Mappings below are hypotheses; visible contacts are distinguished from music onsets, caster charge flashes and cosmetic repeated arcs. Source MP4 is silent: supplied M4A was muxed to `build/reference/Gpj-lvOnAgs.mp4` for native-frame/audio case preparation. Original supplied media was not changed.

Manual measurement tables, arithmetic, camera keys, actor/weapon poses and source timestamps live in each case's `notes.md` / `intent.json`. Source is 854×480, 30000/1001 fps. Dimensions estimated from 384×216 sheet tiles have approximately ±15% uncertainty; 1 H = 1.8 blocks. Times are not accelerated to fit the art guide's usual 60/100 tick recommendations: the requested side-by-side source clock takes priority.

| Slot / binding | Video seconds | Wiki coins → authored hits (ticks) | Range / movement | Final pairs | Iterations / status |
|---|---|---|---|---|---|
| S1 / left_click | 89.10–92.30 | tentative three-coin S1 → 3 (9,17,36) | long melee sweeps then chain thrust; stationary | `build/pairs/crimson-chain-axe-s1.png` | capture pending |
| S2 / right_click | 96.00–101.50 | tentative three-coin S2 → 3 (30,56,81) | descending lances, chain sweep, aerial rising cut; upward propel at 68 | `build/pairs/crimson-chain-axe-s2.png` | capture pending |
| S3 / sneak_right | 79.80–86.80 | five-coin enhanced S3 candidate → 5 (10,15,32,49,70) | long melee chain arcs, blast and orbital recovery | `build/pairs/crimson-chain-axe-s3.png` | capture pending |
| DEF / sneak_left | not identified | one evade coin → 0 damage; 8-tick immunity | self defensive stance, 0.05-block step; 25-tick speed buff | no source case (not shown) | wiki-based implementation |
| Q / drop | 125.50–141.70 | no confirmed E.G.O kit; five grouped contacts adapted from enhanced S3 → 5 (46,80,186,211,278) | flurry then self ground AoE, planted chain and final slash | `build/pairs/crimson-chain-axe-ego.png` | capture pending |

Q costs `ego_cost: 100`, energy 0. All five item bindings exist. Existing weapon and four skill names are preserved. New Q name is original: Crimson Requiem / 緋の鎮魂歌. Content contains no source character names or quotations.

## Mechanics and VFX changes

- All offensive skills use sequence timelines: no invisible immediate melee param damage; every multi-hit damage step ignores invulnerability and shares its tick with contact feedback.
- Old S2 seven-block dash was removed: the inspected caster is stationary during the diagonal lances. S2's final upward movement uses the existing propel mechanic.
- S3's previous nine-block self AoE/forest was replaced by the observed five-contact chain sequence. Forest columns moved to Q. Q's last three checks cover a radius-5.4 ground region centred on the caster (source-wide column field); its first two are forward five-block cones. S1's thrust and S2's lances use single-target forward rays.
- Six-piece bone/dark/red chain-axe rigs drive filled fan trails with dark rims. No large-shape glow outlines remain. Quads, red concrete, pink/white cores, translucent bloom and short particles supply the layers. Vanilla floating iron-axe items were removed.
- DEF is a defensive window, not an invented damaging counter. Existing dash immunity supports eight ticks with negligible displacement; successful-evade coin recycling is not reproduced.

## Display budget

Per slash: 6 rig pieces + 10 sampled intervals × (1 fan + 1 edge + 1 dark rim) + 4 emitted petals = 40 displays over its lifetime (not all simultaneously). Adjacent S3 slashes begin five ticks apart: the oldest trail slots expire as the next spawn. Conservative overlap at t=15 is 30 trail slots + 12 rig parts + 8 petals + 6 contact glyphs = 56 displays. Blade-tip dust is 6 particles × 6 ticks per slash, not displays. Contact markers use 3 displays irrespective of victim count; crit particles do not allocate displays. No per-victim display multiplication.

Other maxima: thrust 10; lances 6; spokes/coil 56; orbit 36 (Q uses one continuous 58-tick instance, no overlapping repeats); quake 14; forest ≤48 (18 staggered shafts + 18 heads + 12 dark accents); ward 16; plant 6. Forest overlaps one 3-display contact marker = 51. Orbital recovery starts after slash rigs expire. These are authored counts; runtime peak verification remains tied to capture review.

## Known limitations to evaluate in captures

- A segmented rigid haft is not a deforming chain; stock player body cannot articulate the cape/ribs/limbs or reproduce animated facial cut-ins. No Kotlin was changed.
- Compare camera supports position/target/FOV but no roll. Screen flashes target the caster, so spectator captures do not see the caster's title overlays; in-world stars remain visible.
- Source S3 hovers during recovery; the player is not held in an authored flying pose. S2 uses ballistic motion rather than a full body pose track.
- DEF uses a short invulnerable window instead of Limbus's reactive evade roll/reuse. Source defense was not identified, so there is deliberately no fabricated DEF video compare case.
- Trailer Q has repeated ornamental flurries/orbit flashes; five damage groups are a design adaptation, not proof of an official five-coin E.G.O. The full cinematic cannot currently be called a verified match.

## Validation

`./gradlew :tools:content-lint:run`: initial pass 348 files, 0 failures. Latest pass after particle / continuous Q orbit addition: 351 files, 0 failures. No test suites executed.

## Capture / ACT log

Real-client capture requests queued through the shared two-slot semaphore. No preview render is substituted for a real client, and no match is claimed before the pairs have been inspected.

Capture queue note: initial ordinary capture processes ended with code 130 before producing frames. Requeued under one shared slot for a serial batch; no capture or comparison success is inferred from content validation.

Pre-capture geometry audit: opaque black/red/white cuboids originally nested and would hide the inner fills. Beam layers now increase camera-facing thickness as the visible stripe narrows; the dark coil follows a separate radius-3.75 path outside the radius-3.6 red coil. This is a content correction, not a claimed visual CHECK.

Emitter audit: `drag` is the fraction of velocity lost per tick. Petal drag is 0.06 (retains 1−0.06=94%), not 0.94; this preserves the measured outward debris motion.

## Confirmed equipped-weapon engine blocker

All initial equipped captures kept the husk at 200 HP and spawned unrequested S1 swings inside other skills. `PaperEntityDamageGateway.apply` emits ENTITY_ATTACK attributed to Caster; `PaperItemInputListener.onAttack` (lines 50–55) dispatches the held item's LEFT_CLICK and cancels the event. It does not distinguish skill-origin damage from physical input. Removing the held item before the S2 cast yielded exactly three HP transitions (200 → 196.064 → 191.636 → 184.256), confirming the isolation diagnosis. Kotlin was not changed.

Iterations 3–4 use a runtime-only capture-script copy that calls `clear Caster` immediately after `give_item Caster`. The real client and normal skill dispatcher remain in use. These diagnostic pairs isolate authored effects; **they are not an equipped-weapon acceptance pass**. Equipped iteration-1 images remain alongside them. S1 iteration 1 also stalled for 44 frames; subsequent captures reduce resolution/distance and wait 20s after teleport.
