# R Corp. 4th Pack Reindeer Rodion — fresh frame analysis

Source: 854×480, 1,828 decoded frames at **30000/1001 fps** (29.97003), duration 60.994267 s. All timestamps use source PTS; frame numbers are zero-based. JSON intervals have an exclusive end. The full video was inspected at 6 fps; every combat frame from 12.012 through 45.512 was inspected at native rate. All 50 generated contact sheets were viewed and retained. Body-height distances and colors are visual estimates.

## Skills

- **S1 / Energy Strike, 12.2122–16.5832:** cable-connected mechanical energy rod/prod with a black rectangular emitter, not a sword. Two short-range energized sweeps, first following an approach/lunge (`dash_melee`), second `melee`. First visible impacts **f402 / 13.4134** and **f448 / 14.9483**; 2 hits / 2 coins. First contact may begin in f400–402 while target is cropped. Signature: segmented gold crescents and orange/violet target rings. Mapping high confidence.
- **S2 / Concentration, 17.2172–22.5559:** same rod raised overhead after circular charging. Two remote target-centered impacts (`aoe_at_target`), **f569 / 18.9856**, **f607 / 20.2536** (second contact onset bounded f605–607 by foreground/crop). 2 hits / 2 coins; medium mapping confidence. No connecting projectile or beam is visibly resolved for these impacts. Signature: revolving segmented rings, purple pose echo, overhead orb and giant foreground curved ribbon.
- **S3 / Mind Whip, 23.5235–33.7337:** same mechanical rod aimed as beam emitter; `beam`, 4 main hits / 4 coins. First visible impacts **f773 / 25.7924**, **f795 / 26.5265**, **f813 / 27.1271**, **f919 / 30.6640**. First contact is cropped, bounded f770–773. Three brief beams, overhead recharge, then one sustained fourth beam. Medium mapping confidence; group presentation is consistent with Unfocused Volley but cannot prove it.
- **S3 repeat / extra-1, 34.2008–45.4454:** same four-beat beam choreography; impacts **f1085 / 36.2028**, **f1110 / 37.0370**, **f1126 / 37.5709**, **f1234 / 41.1745**. 4 / 4. Recovery settles by **43.0764**, then low held pose/electrical residuals persist to cut-away. S3 repetition high confidence; exact Charge threshold low. Green luminous panel is visible, including a two-frame torso zoom, but is not a readable numerical resource meter.
- **Defense / E.G.O:** no identifiable gameplay demonstration. Their JSON files explicitly record absence; no invented weapon, hit timing, or animation.

The weapon has a narrow segmented shaft, squared black emitter head, metallic gold/brown edging, and a dark cable returning toward equipment. It resembles an industrial energy prod/staff; its exact lore designation cannot be read. The orb and crescent are effects rather than a permanent lantern or blade. Close views are collected in `sheets/weapon_contacts.jpg`.

## Corrections to the previous analyses

1. The previous files actually called this a staff/spear/halberd or caster-lancer, rather than a greatsword. Those specific spear/halberd/lantern claims are unsupported: no blade, spear tip, or fixed lantern is resolved. The mechanical head and cable are plainly visible at f446.
2. The first analysis counted S2 charge rings as hits and invented a third hit plus finisher. Only two main target impact onsets are visible. The flipbook improved this but still suggested an extra weak hit; later ring pulses are residual effects.
3. Neither S2 foreground ribbon nor its diagonal tangent is a demonstrated summoned giant lance. It is a curved segmented energy effect; do not build a thrown trident from that image.
4. S1 does include an approach and short lunging motion; the flipbook's “never leaves her spot / no dash” is too absolute. Camera tracking obscures exact travel, which is now reported as approximate.
5. The long beam is coin 4, with visual flicker and a late muzzle bloom. Ten arbitrary damage ticks, three-times-three-hit ult patterns, and extra “nova” skills were implementation inventions, not observed kit events.
6. The group sequence does not visually prove hits on all three opponents, piercing every ally, or damage to multiple recipients. Already-present antlered figures are allies, not summoned clones. Numerical Charge thresholds and Unfocused Volley remain uncertain without HUD.
7. The alleged rising/levitation phase is not demonstrated: the caster straightens, raises equipment, and is enlarged by camera motion while the feet remain near their ground anchor. A faint vertical glow is not evidence of a tall physical pillar.
8. Short scene-sprite close-ups were missed or loosely timed: S1 face f444–445 and weapon f446–447; repeated S3 torso f1164–1165 and caster f1202–1203. These are camera punches, not new illustrated attacks.
9. S2's gold-filled finisher view is foreground effect plus extreme zoom, not a uniform full-screen white flash. White editorial flashes are separately identified at 10.010 and 45.4454.
10. 45.4454–57.1905 is identity promotional art, not established “E.G.O key art” or an E.G.O finisher. The supplied kit contains no such skill.
11. Earlier coarse skill start/end guesses mixed approach, impact, held recovery and cut-away. `segments.json` now covers the entire clip contiguously and identifies the source frames used. End times include held recovery until cut-away when neutral idle is not resumed visibly.

## Reading the evidence

`detail_01.jpg` starts at f360; each sheet has 30 consecutive source frames in row-major order (5×6), except the padded final sheet. These are native-rate frames, not resampled 30 fps. Overview sheets are sampled at 6 fps and their labels are sampling timestamps; use detail frames for contact timing. `boundaries_01/02.jpg` contain selected native frames around opening, dissolve and outro cuts. No audio onset is treated as proof of a hit. Tight crops sometimes hide the exact first contact; bounds in the JSON preserve that uncertainty rather than fabricate precision.
