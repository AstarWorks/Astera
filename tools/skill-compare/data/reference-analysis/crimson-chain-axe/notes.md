# Gpj-lvOnAgs — frame-led re-analysis

Sinclair is the small pale-haired, olive-cloaked caster **and** the later blond skeletal red figure. The direct transformation at72.472–74.641s establishes continuity. The orange-haired fighter is a separate ally. The source is30000/1001fps (29.970), not exact30fps. JSON frame indices are zero-based; frame n occurs at n×1001/30000 seconds.

| Candidate / file | Time(s) | Visible weapon / range | Contacts versus kit coins | Mapping |
|---|---|---|---|---|
| La Pulsion / s1 |11.144–13.280;13.747–15.949;22.400–24.500|Bare-hand red bolt; ranged. Gold ally effect appears at recipient.|1 enemy contact or1 support activation /1|Medium|
| Je vous aide or Pulsion repeat / s1 |19.800–21.822|Bare-hand red bolt; ranged projectile|1/1|Low distinction|
| Je m’expulse / s1 |88.455–92.400|Linked crescent-blade chain; extended melee|3 separated contacts /3|Medium|
| L’Appétit / s2 |30.530–34.268;35.330–37.538|Two red bolts; ranged. Ally mode uses local red ground patches.|2/2 enemy;1 continuous support episode /2 unresolved|Medium|
| Je m’abjecte / s2 |95.262–101.602|Detached needle spokes + chain; projectile then melee/airborne melee|3 target groups /3; self launch flash excluded|Medium|
| Le Baptême rouge / s3 |48.916–55.922;64.900–71.371|Hand/head red focus emits repeated needles; ranged|Continuous barrage; exact3 coin boundaries unresolved|Medium name, low coin timing|
| Le Manque-à-être / s3 |40.330–43.440;115.200–118.300|Hand orb, red glow at ally; target-local support|1 support episode /1|Medium|
| La Voracité candidate / s3 |102.135–107.708|Attached linked chain, flurry/thrust/orbit/crescent; melee|4 proposed phases /4; individual coins unresolved|Low|
| La Castration candidate / s3 |124.491–142.175|Chain, aerial ground effects, descending linked columns; melee + target AoE|5 proposed large phases /5; exact coin allocation unresolved|Medium|
| La Fuite / def |57.791–59.693 (enemy lead-in56.650)|No outgoing weapon use; two backward evasions|0 outgoing hits;2 evades /1 reusable coin|Medium|
| Transformation / extra-1 |72.472–76.176|Base caster becomes skeletal claw/chain form|0 target contacts|High visual continuity; low passive name|
| Ally preparation / extra-2 |45.178–48.916;61.250–64.900;76.176–78.145;93.427–95.262;120.687–124.491|Hand/chest glow and local allied flares|Not counted as enemy coins|Medium interaction; low exact mechanic|
| First transformed combo / extra-3 |78.145–86.920|Linked chain sweeps then aerial floor effects|5 visible episodes; could group to4 coins; unresolved|Low name|
| E.G.O / ego |No independently identified sequence|No separate E.G.O established|N/A|See ego.json|

## Corrections to the previous analyses

1. Excluding the pale-haired caster was a major identity error. Base shots, support, evade and barrages are Sinclair content. Orange-haired ally attacks at16–19s,24–29s and109–115s must not be assigned to Sinclair.
2. Transformation begins near72.47s, not114s. The transformation itself ends before the78.145s attack windup; the earlier notes merged reveal, allied interaction and attack into one cinematic.
3. Calling this an E.G.O transformation is unsupported. The supplied kit describes form/passive/skill variants and gives no independent E.G.O identifier here.
4. Base attacks are ranged red needle/bolt emissions from a bare-hand magical focus. There is no sword swing or rifle in these sequences.
5. The transformed weapon is visibly a flexible linked blade chain plus skeletal claws. The straight streak at90.891s and103.837s overlays attached chain geometry; describing it as a detached laser establishes more than the frames show.
6. The96–98s spokes become detached projectile shafts with diagonal red trails. This is different from the attached chain lashes; a blanket melee or beam label loses that distinction.
7. The three-contact sequence88.455–92.400 fits a3-coin candidate better than the previous2-coin counter mapping. The95.262–101.602 sequence has three target groups: its99.232s self flash is a launch effect, not an independently visible fourth hit on the enemy.
8. Repeated white stars, ring pulses and multi-target flashes do not establish additional mechanical coins. The earlier claim that the final skill has many more than5 hits confused VFX repetition with coin events. This re-analysis leaves ambiguous coin boundaries unresolved rather than replacing them with invented timestamps.
9. The late forest is made of giant **linked columns with repeated crescent nodes**, entering from above/outside the shot. Forty independent axes erupting upward from the ground is not supported. Exact column count is occluded.
10. A rigid greatsword, planted sword, named laser, exact40-blade count, arbitrary Minecraft damage values and fixed block distances were not grounded in the frames. Here sizes/distances are approximate body heights and cameras are described shot by shot.
11. Defense and ally restoration are visible: base evasions near57.79/58.76s and support at40–43s/115–118s were missed or excluded.
12. Brief inserts matter: f2236 transformation face; f2721–2723 smear/face before thrust; f2958 chain close; f2995–2997 dark/face/torso; f3898–3899 dark; f4155–4159 link/face. These are not additional attack contacts.

## Evidence and limits

`segments.json` partitions the entire174.107267s video without gaps. Every sequence has an actor, candidate mapping, confidence, boundary uncertainty and sheet references. Skill files separate visible contact events from proposed mechanical coin groups. `native/index.json` indexes103 native-frame windows, assembled in `sheets/native_01.jpg` through `native_26.jpg`; each window is12 consecutive source frames. All these composites were visually inspected. The entire video was also inspected at6fps, base combat at15fps and transformed combat at15fps. All generated sheets were viewed; the individual native-window images are the same cells as the viewed composites.

The native windows resolve listed critical contacts and short inserts to source frames. They do not constitute a continuous native-frame inspection of every frame in the movie. Boundaries outside those windows remain sampled estimates, and unknown coin contacts remain unknown. Thus this is a frame-referenced visual analysis, **not a claim that every mechanical coin or every one-frame edit has been proven**. The trailer has no readable skill/coin UI; the supplied kit has ambiguous variant labels. Colors and BH distances are visual estimates. Audio onsets/cut lists may locate candidate events but were not treated as hit evidence. No outside web material was used; the supplied kit is the naming reference.
