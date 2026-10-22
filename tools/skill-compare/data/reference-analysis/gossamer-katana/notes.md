# Frame-led re-analysis: Blade of the House of Spiders Ryōshū

All times refer to the video-only MP4. It contains **3088 frames at 30000/1001 fps**, not exact 30 fps. Native-frame indices are zero-based. Ends are exclusive. The audio onset list was used only as a cue list; no onset was equated with a hit. All 21 overview, 31 detailed and 14 native-neighborhood contact sheets were viewed. The supplied kit snapshot is the mapping authority; old notes were read only after the frame pass.

**Important limit:** source-frame contact/effect times are observable; hidden engine damage ticks and coin assignments are not. No skill-name/coin HUD establishes the ambiguous variants. Exact-looking timestamps do not make those mappings certain. Detail sheets sample every second source frame, with native neighborhoods around 73 selected events. Unchecked one-frame inserts elsewhere remain possible. Colors and body-height measurements are estimates. Native sheets jump between timestamp windows; their adjacency does not imply continuous footage.

| File / tentative skill | Primary range (s) | What is wielded / delivery | Visible contacts vs kit coins | Signature / confidence |
|---|---|---|---|---|
| s1 / Tripleslash | 11.845–14.948 | Long narrow curved blade + scabbard; three dash passes | 3 / 3 | Cyan/violet arcs then red radial burst; medium |
| s2 / Tripleslash–Echoshear | 15.649–20.654 | Same curved blade; dispatched double, crossing cut, remote lattice | 3 / 3, final event could be follow-up | One cyan humanoid with magenta scanlines; medium |
| s3 / Rend Space **or Tiansha** | 38.572–41.642 | Curved blade; stylized dash and slash plane | 1 / 1 | Black/white diagonal scar; low name confidence |
| extra-1 / unresolved arc chain | 21.155–23.824 | Curved blade; alternating near-target passes | 4 visual groups / unknown | Repeated violet loops and vertical crosses; low |
| extra-2 / S3-2 candidate | 30.197–34.935 | Curved blade; dash then distant target lattice | 2 / 2 | Horizontal cut then angular grid; medium |
| extra-3 / Inevitable Termination candidate | 34.935–35.669 | Blade held at distance; effect local to target | 1 candidate / 1 | Blue knot/flower burst; medium mapping, low damage confirmation |
| extra-4 / unresolved two-contact form | 36.336–38.572 | Curved blade; ground approach then aerial descent | 2 / unknown (S3-2 candidate: 2) | Navy crescent then vertical cyan fan; low |
| extra-5 / unresolved flurry | 43.610–46.346 | Curved blade; dashes, spin and leap | 4 groups, third uncertain / unknown | Diagonal cyan finisher; low |
| extra-6 / String Connection candidate | 51.218–62.296 | Curved blade/scabbard; kneeling pass and distant horizontal cut | 2 / 2 | Gold aura, overhead hold, blue fracture field; medium |
| extra-7 / spectral montage | 71.605–78.779 | Real body kneels; spectral bodies carry matching curved blades | 6 major contact groups / unknown | Multiple summon resets and repeated attack motifs; low |
| def / Phantom Step candidate | 25.259–27.694 | Blade carried during side-switch; no outgoing strike | 0 outgoing / 1 reusable evade coin | Red/black afterimages during two incoming attacks; medium |
| ego | — | No identifiable E.G.O activation | — | Arena edit alone is not E.G.O evidence |

Repeated demonstrations are retained inside their files: spectral sequence 66.633–71.605; monochrome finisher 46.346–50.984; gold two-release pattern 78.779–83.684. The 28.3–29.8 exchange includes an opponent's red crescent and Ryōshū's blue response; it remains in segments.json without a forced skill assignment. The gold aura is already active at 50.984. Fire is already present at the 62.296 arena edit. Neither proves a particular passive trigger or Burn activation.

The visible weapon is narrow, slightly curved and silver, roughly 1.2–1.6 standing sprite heights including hilt. Its dark patterned scabbard/handle and thin cord are clearly visible in 51 s and 78.8 s close-ups. A broad blue crescent is an effect, not the physical blade's width. The enlarged sword near 78.8 s is a camera crop; it does not establish a giant summoned weapon. No gun, muzzle, ammunition, shield or thrown solid sword is visible for Ryōshū in these sequences.

## Corrections to previous analyses

- The first report's fourth S1 hit counted a caster-local preparatory crescent as target contact. The target is still elsewhere. The three supported contacts are 12.446, 13.046 and 13.780 s; the red final burst accompanies a new return pass, not a proven delayed critical of the preceding pass.
- The spectral sequence's paired crescents are centered on the separated caster and double, not on the target. Counting both as direct melee damage invented contacts. The body double's first overlap, the later crossing cut and the delayed target burst must be separated.
- The arc chain at 21–23 s has four fresh contact presentations; forcing it to one or two coins, or assigning five damage ticks, is unsupported. It is explicitly unresolved here.
- The entire 23.824–43 s span is not established as an E.G.O. It contains an arena edit, another character's attacks, a defense candidate, an exchange, and several separate Ryōshū animations. Skipping it discarded evidence.
- The red/white attack and jagged red weapon at 25–27 s belong to the white-haired opponent. They are not Ryōshū's weapon or self-damage effect.
- The 43–51 s sequence has a blue flurry followed by a distinct white wind-up/monochrome finisher. The white part repeats the earlier 38.6 s motif. A single guessed damage count for the entire span is unjustified.
- The 51–83.7 s footage is not one securely identified two-coin ultimate. There is a clear arena edit and long idle, a repeat of the earlier spectral sequence, multiple summon resets, and a repeat of the gold two-release pattern.
- A spectral body can make contact while the real caster remains 3–6 H away. Describing every cyan arc as real-caster melee would produce wrong Minecraft movement. Range is annotated per event, including dispatched doubles and target-local/beam-shaped cuts.
- Large fan strokes and a close-up of a normal sword were turned into a giant armored spirit/giant sword slam in old notes. The frames do not establish those objects or sizes.
- Old notes invented delayed damage schedules, damage values, 15% E.G.O HP cost, heals, Silence stacks, roots/Rupture and 16–25 damage hits. These are design inventions, not frame evidence or supplied kit facts, and are excluded.
- Gold aura does not prove that Shin just activated, and a burning background does not prove the skill caused the arena fire.
- The end at 83.684 s is a white editorial transition to illustration, not a demonstrated final attack hit. Artwork continues until about 99.9 s, then logo outro.

## Reproduction details

The JSON carries camera framing, per-event range and distance, moving body versus detached effect, approximate palettes, effects with lifetimes, and source-frame evidence. No physical projectile velocity, hidden coin tick or exact world-space camera lens is invented. Use same-shot H estimates when reconstructing distances. `extract_contacts.py` is the native-neighborhood extractor; `write_analysis.py` regenerates and validates JSON from this manually observed annotation set. All outputs are inside this work directory.
