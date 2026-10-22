# Zwei Assoc. West Section 3 Ishmael — fresh frame analysis

All 48.533 s were segmented from fresh frames. Combat was reviewed at native 30 fps (every frame from 11–32 s); 34 inspected sheets are retained in `sheets/`. `segments.json` covers the full video and records incoming enemy actions separately. All frame indices in JSON are zero-based **source** frames. On combat sheets, burned `fN` is a selected-frame ordinal: add 330. Use JSON/`frame_pts.json` for exact PTS; the source has millisecond timestamp rounding.

| Skill | Wind-up → cut-away | Weapon and range | Impact proxies / coins | Signature / mapping confidence |
|---|---|---|---|---|
| S1, Zwei Knight's Greatsword Form | 11.633–14.233 s | Broad rectangular greatsword; two stationary melee sweeps | 11.967 (f359), 13.200 (f396): 2 / 2 | Elevated gold crescent, then low tilted ellipse; **high** mapping |
| S2, Can't Let You Through | 14.633–18.400 s | Same blade, melee sweeps and overhead slam | 15.300 (f459), 16.233 (f487), 17.300 (f519): 3 / 3 | Two sweeps then tall arch and smaller floor fan; **medium** mapping |
| S3, Ward | 18.900–23.700 s | Same blade; hilt/shaft-leading first sweep, advancing held-weapon lunge, close slam | 19.400 (f582), 20.900 (f627), 22.033 (f661): 3 / 3 | Scrape sparks → dash_melee → large vertical/radial ground burst; **medium** mapping |
| Defense, Your Shield | 23.700–31.767 s, with resets | Greatsword stays low/obscured; translucent gold shield graphic, stationary guard | 0 outgoing attack hits; 1 guard coin, not comparable to enemy contacts | Four clear enemy-sweep contacts plus one guard-resolution flash; **high** mapping |
| E.G.O | Not observed | — | — | The long illustration after 31.767 is outro |

“Contact” times are first impact-flash/spark frames, with uncertainty brackets in each JSON. The target is often cropped or hidden, so these are not claims of directly observed blade-to-body overlap or game damage ticks. S2 and S3 each have three coins, and skill names are absent onscreen; ordered showcase structure and the larger final burst favor the listed mapping but cannot make it certain. All official attacks are **Blunt**, even when their light trails look like slashes.

The first two attacks do not show a detached projectile or a continuously fired beam. Long horizontal light is tied to the swing. Their stylized sprite gap is approximately 2–3.5 H; the exact contact surface is obscured. S3 closes that gap to approximately 1 H during its second hit. A broad blade behind the shoulder and narrow forward shaft/hilt are especially visible in S3's first close-up; do not model every impact as an edge cut.

Guard contacts: 24.767 (f743), 26.233 (f787), 28.700 (f861), 31.234 (f937). At 26.800 the shield flares in close-up; at 27.000–27.033 a glyph/star appears, then target-centered circles at 27.067. This is compatible with a guard/Tremor resolution; a separate physical enemy swing at that instant is not cleanly resolved. There is no visible outgoing counter-dash. Fades/resets occur at 28.367, 29.567 and 30.467; their setup can be truncated by the edit. No separate offensive Defensive Stance variant or uniquely identifiable passive activation is shown.

Short inserts matter: S1 has one/two-frame cropped motion and impact views at 13.133–13.267. S2 has a two-frame face/grip close-up at 16.167–16.233 and a one-frame blade insert at 17.267. S3 has a one-frame flat gray field at source PTS 20.866 (nominal 20.867), an orange/white frame at 20.900, and distinct 1–2-frame cuts at 22.000–22.200. Do not turn all these edits into extra hits.

## Corrections to the supplied old analyses

- The coarse analysis merged S1 and S2 into a seven-hit “S1”, called Ward a four-hit “S2”, and called Guard “S3”. Fresh evidence gives three separate offensive demonstrations: 2 + 3 + 3, then Guard.
- The flipbook corrected the count but still let S1 run to 14.9 and started S2 at 15.0. The reset cut is 14.233; the next first wind-up is 14.633. S2 ends on the 18.400 cut; S3 starts at 18.900 and ends at 23.700.
- Lingering crescents, camera punches and impact inserts are not extra coin contacts. S1 second contact is approximately 13.200, not a later ring frame; S3 third contact begins at 22.033, not the already-developed floor burst.
- “Huge oval tower shield” should describe a **translucent barrier effect**, not an established physical secondary weapon. It has a nested shield outline inside a curved glow; a separate handle/solid shield is not resolved. The physical blade remains visible during much of guard.
- The weapon is indeed a broad greatsword in this video, but “Cloud-like”, arbitrary block sizes, weapon scaling to 4.5, and template-derived moves are not observations. The close-ups show specific slab geometry, inset and grip; apparent scaling is mostly camera zoom.
- “Enemy scythe” is not securely established. The opponent carries a thin hooked/curved weapon; trail shape alone does not identify a scythe. Enemy crescent strikes must not become Ishmael attacks.
- The S3 second-hit streak is not a detached beam: caster and held weapon advance into close range. Its scrape preparation is mostly anchored; old multi-H movement values and conversions between H/blocks conflict and are not measurements.
- “Perfect block”, a timed counter window, counter-dash/teleport, three outgoing counter hits, 80% damage reduction, guard gauge, exact damage percentages and cooldown behavior are not shown by these frames or supplied kit. They were design inventions and have been removed.
- Gold target circles support a possible guard/Tremor result; they do not establish a new projectile, three counter hits, a specific stat change, or a distinct passive activation.
- The final gameplay contact is at 31.234, with Ishmael still braced behind the barrier. The full-white frame at 31.767 begins the outro illustration; it is not an extra attack hit or E.G.O.

Colors, body-height dimensions, camera framing and travel distances are visual estimates from a 640×360 compressed source. Frame indices are exact selections; physical-contact and skill-name uncertainty is explicit. The JSON camera arrays provide shot-by-shot framing without inventing camera FOV or game mechanics.
