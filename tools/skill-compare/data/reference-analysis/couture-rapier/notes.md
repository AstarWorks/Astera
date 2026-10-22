# Frame-based re-analysis — Haute Couture::Alteration Shop Rodion

Source: 854×480, **30000/1001 fps (29.97)**, 2062 frames, 68.802067 s. Frame numbers are zero-based; JSON intervals are end-exclusive. Every source frame from 11.011 through 55.022 s was inspected, plus a 6 fps whole-video pass and enlarged weapon/boundary sheets. Contact times refer to earliest visible contact/impact; obscured events have explicit frame bounds. Colors, counts under overlap, and distances are visual estimates.

| Mapping | Wind-up → recovery/cut | Weapon / range | Hits versus kit | Signature / confidence |
|---|---|---|---|---|
| S1 Retouche, enemy | 12.446–14.281 s | Ornate long shears; dash melee | 1 / 1 | Purple wind-up, white X, pink crescent after passing target; medium |
| S1 Retouche, ally | 15.482–16.383 s | Raised floral shears; nearby ally contact | 1 support contact / 1 | Small orange snip, pink spiral, upright ally markers; high |
| S1-2 Patterning | 21.388–24.258 s | Detached shears; ranged projectile | 2 / 1 coin + reuse | Repeated overhead rotation and remote diagonal cutting strike; high |
| S2-1 Pin and Trim | 26.593–31.498 s | Held/remote shears; ranged strokes then dash melee | 3 / 3 | Sweep → converging detached blades → purple-prepared dash-through; high |
| S3 Coupez dans le Biais, provisional | 32.399–39.773 s | Held/remote shears; remote cuts | 3 / 3 | Two white sweeps then long chest charge, remote closure, target-centered jagged ring; medium |
| S2-2 Cutout, probable passive follow-up | 47.180–53.987 s | Five airborne shear shapes then giant detached scissors; projectile / AoE at target | 2 / 2 | Curving fan, falling blades, giant vertical scissor closure; medium |
| Defense Trimming | Not independently confirmed | No fabricated animation | Candidate 35.202–39.773 s | See ambiguity below; low |
| E.G.O | Not identified | None assigned | N/A | Portrait outro starts 55.055 s |

Approach movement is segmented separately and remains in each skill JSON. For reproduction, include it if desired: enemy S1 11.578 s, ally S1 14.948 s, Patterning 20.687 s, S2 25.726 s, provisional S3 31.932 s. The first and third dash-through hits have flash-occluded contact windows, not invented exact blade-touch geometry.

## Mapping ambiguity that the footage cannot settle

No skill names or coin HUD appear. The continuous 32.399–39.773 s Rodion action has three target contact episodes and no evident idle/reset before the long final charge, favoring S3. An alternative is two-hit Cutout followed by one-hit Trimming. There is **no visible incoming enemy attack** supporting a counter trigger, so the old high-confidence counter assignment is unjustified. The final two-hit sequence follows an ally action, matching passive-triggered Cutout; this supports but does not prove the preferred mapping. These mappings are deliberately medium confidence.

The final giant scissors are a detached object over the target. Rodion stays on the left ground; neither the blade's upward motion nor the camera's upward tracking proves a caster leap. At 50.350 s the flash is above the target as the fan begins descending; target impact is at approximately 50.517 s. At the final closure, target overlap is visible at 52.920 s and the major closure flash starts 52.953 s.

## Other character, not Rodion

A different hat-wearing, striped black-clad ally performs gold attacks at 17.751–20.420 s (two contacts) and 41.341–46.613 s (two sweeps plus a dome impact). Its narrow held implement looks cane/rod-like; no greatsword is established. The three upright white markers follow the supported actor. The latter action is not Rodion S3 or an E.G.O, and the dome is not a fourth Rodion coin. See extra-3.json for both actions.

## Corrections to the supplied earlier analyses

- Both reports ended gameplay too early (~46–47 s), missing the full 47–54 s falling-shears/giant-scissors sequence. Gameplay continues through f1649; portrait transition starts f1650 (55.055 s).
- The 15–20 s block merges Rodion's ally-directed support snip with the hat-wearing ally's subsequent two attacks. Calling all of this Cutout or a Rodion leap is wrong.
- The 41–46 s gold sequence is the same distinct ally, not Rodion's ultimate/S3. Its gold dome is an actual third contact episode, not evidence for invented extra Rodion damage.
- The opening attack has one contact. The supposed second hit at 13.67–14 s is fading particles and held follow-through.
- The 26–31 s sequence has three hits. Overhead crescents around 28 s are preparation; counting them creates a false fourth hit.
- The 21–24 s two repeated remote strikes fit Patterning's one coin plus reuse. A base coin count alone cannot distinguish this from a two-coin skill.
- The 33–35 s sweep rings are two impact episodes, not a rapid series of individually confirmed thrusts. Continuous glow and camera motion do not establish extra hits.
- The 36–39 s charge does not show an enemy dashing toward Rodion. The shears travel to the target; Rodion remains left. A counter/guard label was asserted without a visible trigger.
- Closed shears look spear-like in small thumbnails, but the enlarged frames show paired blades, angular handle loops and a floral hinge. Do not replace these with a generic needle-spear or greatsword template.
- Remote linked trails are attached to visible tools; they are not automatically beams, hand-held melee reach, or summoned swords. Several skills mix range classes per hit.
- Many pale suspended scissor outlines are static salon scenery already present in the initial idle shot. Distinguish them from newly moving props and the three actor-following status markers.
- Old Minecraft damage, launch, root, dome healing, invented Stitch stacks and timings are design proposals, not observed footage or supplied kit facts. They are not carried into these outputs.

## Evidence files

- `sheets/overview_01.jpg`–`overview_14.jpg`: whole video at 6 fps. Overview timestamps are sampling-grid labels; use detail source PTS for exact events.
- `sheets/detail_01.jpg`–`detail_44.jpg`: every source frame f330–f1649, 30 frames per sheet, source PTS and frame number printed. No fps resampling.
- `sheets/weapon_01.jpg`–`weapon_03.jpg`: enlarged selected source frames establishing shears, remote tools, floral hinge, fan count and giant closure.
- `sheets/boundaries.jpg`: gameplay/portrait/logo transition frames.

All 62 contact sheets were viewed. The audio onset list was consulted as secondary context only; contacts were not assigned from soundtrack peaks. No external lore lookup was used; kit mappings use the supplied kit text. JSON has complete per-beat camera entries, including the one-frame burgundy flash, three-frame black interruption, and short extreme-close inserts.
