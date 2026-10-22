# Frame-based re-analysis: Fell Bullet Yi Sang

The actual supplied MP4 is **24 fps, 1035 frames, 43.125 s**, despite the brief saying 30 fps. Times use native zero-based frames. All 32 overview/native sheets plus the enlarged S1 contact sheet were viewed. Native sheets cover every frame from 12.500 to 34.833 s. JSON intervals are end-exclusive. Exact frame timestamps do not imply that obscured contact geometry, hidden world distances, or unlabelled skill names are certain.

- **S1, 13.083–14.958:** long rifle carried into a low dash; **dash_melee**, one impact/one coin. First gold sparks at f330 (13.750), shared caster/target contact view f333 (13.875). Red hooked wind-up and floor-level rush. The exact striking part is concealed; rifle/body strike is plausible, a sword is not visible. Mapping high, contact surface uncertain.
- **S2, 15.250–19.917:** long shouldered rifle, **ranged_projectile**, two impacts/two coins. Release f388 and f430; impact evidence f392 (16.333) and f435 (18.125), wide confirmations two/one frames later. Two single-frame barrel crops and red transit shots. Mapping high.
- **S3, 19.917–26.708:** rifle held upright then shouldered while kneeling. One muzzle discharge, two impacts/two coins: f554 (23.083) primary ranged projectile, f593 (24.708) target-local ring volley (**aoe_at_target** presentation). Three red discs surround the target zone; gold horizontal traces pass through. Mapping high; numerical range unavailable because shooter and targets are in separate shots.
- **S3 variant/repeat, 26.708–34.833:** same rifle and choreography, two impacts/two expected S3 coins: f718 (29.917), f768 (32.000). Red/white eye aura; orange-haired shield-bearing main target; denser circle cluster and longer secondary barrage. **ranged_projectile → aoe_at_target**. Mapping medium: ally-target/kill amplification is consistent with the kit, but exact named synergy cannot be proved. Not sufficient evidence to label this E.G.O Awakening.
- **Defense / separate E.G.O / standalone passive action:** not established in this video. Empty observed=false files explicitly record absence rather than manufacturing animations. Persistent eye aura may reflect a state/passive, but its activation is not shown.

## Corrections to previous analyses

1. The earlier analysis claimed only one attack sequence; there are four performances, including an S3-like repeat with altered target/effects.
2. S1 has one impact, not a second follow-up. Red crescents and lingering contact effects do not prove extra hits or a sword/scythe.
3. S2 ends at the next caster cut, and S3 begins at 19.917. Grouping 15.3–22.7 as one sniper skill conflated two skills. Camera zoom begins before S1 wind-up; it is not the attack start.
4. S3's 20.667 rifle-lowering crescent is a flourish, not a first shot. There is one primary muzzle release, then two target impact phases.
5. Calling 26.7–34.8 E.G.O Awakening with high confidence is unsupported. It repeats S3 choreography; eye aura and two hits do not uniquely identify E.G.O. The orange-haired target matters for the supplied ally-target branch and must not be ignored as a cameo.
6. Red target sigils are at the target, not a lens at the rifle muzzle. The traveling camera and target close-up distinguish their locations. No falling swords, summoned physical blades, or clones are visible.
7. Gold impact streaks are not slashes, shell casings, or extra independently proven coins. Secondary ring barrage is one extended hit phase, not three per-enemy detonations plus a finisher.
8. Do not assert bolt-action cycling, prone stance, a hat on Yi Sang, exclusively one-handed firing, or physical horns: these are not established. The supporting glove is visible in the rifle close-up.
9. Previous tick plans and invented mechanics (marks, stillness bonuses, arbitrary block distances) are not source observations. Body-height estimates here are screen-relative and approximate; offscreen distances are null.
10. The one-frame dark blinks are f528 and f692. White wedges at f718/f768 are local impact graphics, not whole-screen white flashes. The full white frame f836 is the outro transition.

## Reading the artifacts

`segments.json` covers the entire runtime, including idle/reset gaps and post-impact holds. `s1.json`, `s2.json`, `s3.json`, and `extra-1.json` contain native-frame beats, contacts, props, effects and camera cuts. Source kit is `../../wiki/-pOx9fo6A7M.md`; no additional external kit claims were needed. Palette hex values and body-height estimates are visual approximations. Static hall geometry, multilingual subtitles, season watermark and gold border are presentation layers, not spawned attacks. Gold horizontal border is used in S1/S2; diagonal corner framing becomes prominent in S3 and its repeat.
