# Independent frame analysis — x6sc9JkW77c

The normal-form weapon is **joined white-gloved hands in rectangular metal restraints**, linked to the collar by chains. The white triangular shape is the cape. Procuration uses **paired black branching claw-like arm extensions**, not a conventional sword. All attack frames were reviewed at native cadence after whole-video segmentation; old notes were read afterward.

The source is 30000/1001 fps (29.97003), 2341 frames, 854×480. JSON uses zero-based source frames and end-exclusive intervals. Timestamps follow frame×1001/30000. Exact VFX frames are distinguished from hidden game damage timing. Body-height distances and hex palettes are visual estimates. See `segments.json` for continuous whole-video coverage and each skill JSON for sheets, camera cuts, effects, motions and contact bounds.

| Skill | Wind-up to cut/idle (s) | Visible equipment and range | Hits / supplied coins | Signature |
|---|---|---|---|---|
| S1 Skull Crushing | 12.045–14.014 | Bound hands/cuffs; dash melee then melee | 2 / 2, high mapping | Two overhead joined-hand blows, reddish echo; three-frame face/cuff close-up |
| S2 B-be Thee Silent | 14.882–19.286 | Bound hands/cuffs; melee and dash melee | 3 / 3, high | Two initial blows, somersault across target, reverse-side third blow with blue acceleration |
| S3 T-the Prescript… | 19.753–24.224 | Bound hands/cuffs; dash melee | 2 / 2, high | Blue echo, sweep then aerial slam, camera roll and ground ellipse |
| S3 repeat | 30.964–35.903 | Same normal equipment and choreography | 2 / 2, high repeat match | Extra initial blue flare; still normal form |
| Procuration S1 (`extra-1`) | 42.976–44.845 | Branching arm claws; extended melee then dash melee | 2 candidate groups / 2, medium | Raised black fan and two pale multi-line rakes |
| Procuration Engrave (`extra-2`) | 45.379–48.649 | Branching claws; dash melee and extended melee | 3 candidate groups / 3, medium | Reorienting rake bundles, horizontal thrust, tight two-frame X burst |
| Procuration Annihilate (`extra-3`) | 50.350–56.890 | Branching claws; dash melee → distant target-local effects | 4 candidate groups / 4, medium; final timing low | Alternating crossing passes, stationary distant rakes, five-frame blackout and white-band finisher |
| Form entry (`ego`) | 35.903–38.171 | Claws emerge around crouching body | 0 / not applicable, high | Eye cut-in, one black frame, prismatic claw cage and CLEAR glyph |
| Defense (`def`) | Not observed | No attributable defensive action | Not observed / 3 in either form | Do not invent a counter from a matching coin count |

Engrave's exact S1/S2 designation is ambiguous in the supplied kit itself. The video supports a separate second transformed attack but does not show a readable slot label. Likewise, four phases of Annihilate are a proposed kit-compatible grouping: repeated rake strokes within a phase are visible, but separate game coin registrations cannot be proven. Its final target contact is obscured between f1650 and f1672; f1672 is the first resolved star, not a claimed engine damage frame. Remote rakes begin f1594 (53.186 s), while Don remains left of the target, approximately 3–5 body heights away. `aoe_at_target` describes effect placement only, not multi-target mechanics. There is no resolved projectile flight or sustained beam.

Approach runs are separately segmented; skill starts are the first definite preparation motion. Some sequences hold crouched recovery until an edit, so their end is the cut, not an invented idle return. Persistent blue target flowers, floating Prescript glyphs and the gold aura are recorded in `extra-4.json`; their exact passive triggers cannot be derived from this trailer. No independent enemy attack was observed.

## What the previous analyses got wrong

- The alleged thin longsword/greatsword template is unsupported. Full-resolution f361/f395 show white fingers, rectangular cuffs and chains; f1313/f1509 show branching black arm extensions.
- The approximately 11–16.7 s five-hit “S1” combined different sequences. Normal S1 ends at f420; S2 starts at f446 and contains three blows, including the leap-over finisher. A cut/approach/idle separates them.
- The flipbook's 14.8–16.8 s “second S1” is the opening portion of S2. Conversely, merging 16.8–23.5 s as S2 crosses the reset into the separate two-hit S3.
- The 30.96–35.90 s sequence is a repeat of normal S3, not a three/four-hit Procuration move. Its blue cape echo and shrinking impact burst are not extra contacts; claws only emerge after f1090.
- The 43–56 s block contains three transformed attacks, with explicit resets and idle/approach gaps. It is not one eight-plus-hit standalone E.G.O strike.
- Annihilate includes distant target-local rakes while the caster stays put. Calling every flash melee, a travelling projectile, or a separate coin is unsupported.
- The enemy is still standing at f1704. The closing art transition is not evidence of death.
- Claimed numerical bonuses (including a gold-aura +20%), extra coins from marks, guaranteed triggers and invented status mechanics are not established by the frames. The aura is present before S3 starts.
- Flash lengths must use native frames: transformation has one black frame f1093; the final attack has f1644 one-frame cut-in, f1645–1649 five black frames, f1668 one black frame, f1669–1670 two white frames, and f1671 one-frame extreme effect view.

All retained sheets in `sheets/` were visually inspected. Native sheets contain every frame of normal combat f325–725, repeat f914–1076, transformation f1077–1152 and Procuration f1255–1705. Overview sheets cover the full clip at 6 fps; targeted boundary sheets refine cuts and wind-ups. Native labels take precedence over overview sampling times. Colors, small particles and attachment anatomy obscured by bloom are described conservatively rather than asserted as exact model geometry.
