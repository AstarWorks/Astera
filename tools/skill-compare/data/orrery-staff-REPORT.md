# Orrery Staff — source reproduction report

Source: supplied `0DYvUxa24wo.mp4` and separate `.m4a`, native 29.97 fps.
Kit: supplied `pm/wiki/0DYvUxa24wo.md`. No external replacement reference.
Original weapon/skill names retained; Q adds original **Orrery Zenith**.
Only owned content and comparison artifacts changed. No Kotlin, tests or push.

| Binding | Source range | Coins / damage beats | Range | Comparison status |
|---|---|---|---|---|
| S1 / left_click |12.800–16.400|2 /2 at12,43t|melee|4 passes; both arcs/contacts visible, residual differences |
| S2 / right_click |17.200–22.400|2 /2 at36,61t|ranged target impact (implemented forward cone6)|4 art rounds; residual differences below |
| S3 / sneak_right |34.300–43.200 (also23.900–33.800)|4 /4 at38,55,66,136t|beam|4 art rounds; residual differences below |
| DEF / sneak_left |not shown|1 guard coin /0 damage|self guard|Kit-based; real-client guard smoke reviewed; no source comparison |
| E.G.O / drop |not shown, kit has no E.G.O|unspecified /1 at24t|beam|Original extrapolation; beam/clock smoke reviewed; no source comparison |

Per-skill measured pixel/body-height tables, timing formulas, camera assumptions,
and iteration logs are in the three case directories. Do not treat schema
validation as visual acceptance. Reference art at45.3s is an end card, not an
E.G.O cast; no fabricated DEF/E.G.O video cases were created.

## Mechanics and scope

- Removed S2's accidental fourth hit and S3's repeated damage pulse stream.
- Multi-hit damage ignores invulnerability; 2t contact hit-stop; S1/2 staff freezes
  at contact. Knockback remains normal damage knockback (dummy is AI-free).
- S1 banks3+2 Charge; S2 banks3+4; S3's last hit consumes Charge and scales damage.
  Guard now grants physical resistance and3 Charge instead of speed plus damage.
- Sinking is applied on contacts. Turn-based potency/count/fragility, clash and
  conditional charge tiers are not exactly reproduced. Charge gains require a
  target in the strike cone at the scheduled beat; turn-based tiers remain approximate.
- Q costs100 E.G.O,0 energy. No source E.G.O gameplay/kit exists in these inputs;
  its one beam is an explicit extrapolation using this weapon's gold visual language.
- Live-display budget is bounded by fixed formations and at most one impact
  display target. Other targets receive particles. Conservative peak S3<=59;
  S2<=56; S1<=54; DEF25; Q<=59. All large glow outlines removed.

## Remaining engine / source limitations

- Beam targeting is a6° cone, not constant-radius ray/cylinder: at13 blocks,
  half-width=13×tan(3°)=0.681 blocks. Exposed forward_ray returns a ray selection,
  not a configurable piercing cylinder; changing Kotlin was out of scope.
- Block player body cannot reproduce source's drawn torso/coat/face poses or
  ghost sprite. Staff rig moves independently; the capture actor remains vanilla.
- Caster-only screen_flash cannot be seen by the side spectator; do not interpret
  its absence in pairs as missing YAML. No overlays sent to a crowd.
- Source background, stylized additive lighting and exact hand-drawn segmented
  art are approximated with emissive solid faces, translucent bloom, dark accents
  and sparse particles. No custom copyrighted texture or name in content.
- First S1/S3 capture attempts were interrupted with exit130 while queued;
  retries are tracked separately from successful visual iterations.

## Visual results and final media

Four art PDCA rounds per filmed skill; S3 additionally retried its fourth-round
capture after a loader-only correction (five completed S3 captures including
the failed-load capture). This is the requested
iteration cap, **not a claim of exact visual acceptance**. See each case's
`notes.md` for measured frame times, conversion formulas and per-pass changes.
Final pairs are local generated artifacts (source video frames are not committed):

| Skill | Final pairs | Remaining authored / visual differences |
|---|---|---|
| S1 | `build/pairs/orrery-staff-s1.png` | Both contacts and lower/upper arcs visible. Gold remains polygonal and dimmer than source; wind-up and recoil differ; rig grip floats above the vanilla arm. Cone reach4.5 exceeds forward fan extent≈2.4: further range tuning remains. |
| S2 | `build/pairs/orrery-staff-s2.png` | Upright staff and independent diagonal arc verified. Both contacts exist in full sheet, but render around44/64t rather than36/61t (400/150ms late); nominal pair rows miss them. Cubic orb, weaker spin, sparse impact ring and dim polygonal fan remain. Cone targeting approximates target AoE. |
| S3 | `build/pairs/orrery-staff-s3.png` | All four muzzle clocks/beams, planted channel pose and recovery verified after loader fix. Clock remains too regular/dim, orb cubic, fixed beam overshoots target. Shards appear but nominal samples lack hurt tint; precise damage-feedback timing is unverified. |

DEF and Q have no source gameplay: only real-client smoke captures, not compare
cases. These do not verify resource gating because the harness force-casts skills.
Q smoke shows the gathering orb, muzzle clock and persistent beam, then recovery;
its default four-block dummy is not at the nine-block impact-display anchor.
This confirms rendering only, not impact placement across arbitrary ranges.
DEF smoke: `build/vfx-shot/orrery-def/side/sheet.png`: gold cage appears and
clears, target remains unaffected. Q smoke: `build/vfx-shot/orrery-ego/side/sheet.png`.
Historical pairs remain under `build/pairs/iteration-{1,2,3}/`.

The fixed11-block beam continues beyond the target; ideal measured intent ends
at the target. Dynamic target-ended display length is not exposed in this YAML
path. Smaller fixed lengths could improve one staging but not arbitrary ranges.
Curvature, brightness, fan/target reach and timing are still authoring/tuning
limitations, not all engine impossibilities. The reference body's poses and
additive illustrated lighting remain outside these vanilla display rigs.

Capture limitations: native29.97fps reference is scheduled at20tps; cast-lag
compensation210ms after the initial pass. Occasional repeated client frames made
one early S1 pass and S3 pass3 miss a sampled early effect. The cause was not
established; later captures were reviewed rather than assuming success. Caster
screen flashes are absent from spectator pairs by design.

## Validation

Final content validator: **368 passed,0 failed,0 schemas missing**.
`./gradlew :tools:content-lint:run` succeeded. No unit tests or test suites run.
`git diff --check` passed. No owned content contains ProjectMoon names or
`glow_color`. Display budgeting includes overlapping effects and limits impact
displays to one target; additional hit targets receive particles.

Runtime validation caught an unknown particle-kind `count` field that schema
lint missed; removed it and retried the fourth S3 design. Subsequent server
loads109 VFX with0 failures. Interrupted/failed-start captures and this failed
load are reported separately from the four art iterations.
