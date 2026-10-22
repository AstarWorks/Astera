# Bayonet Double — PLAN / MEASURE

Source: wbu2viInM3w, 47.5–50.2s. Native **24 fps** (ffprobe); 30 fps micro-sheets duplicate native frames, never add precision. Frame times are rounded to ±1 native frame. Character standing height = 1.8 blocks. Sizes below use **same-shot** reconstructed standing height estimates (uncertain where crouched or occluded). Pixel measurements below are manual bounding-box estimates on the **854×480 native frame**, inspected separately for each skill. No automated segmentation was used.

Range: **dash-melee**. No ranged projectile / beam damage. Straight pink streaks are blade wakes. Caster closes in, swings at arm’s length, then settles; heavy footage also reverses sides and leaps.

## Native-frame measurements

Frame 48.400s: reconstructed standing-height reference ≈205px. Crouch/occlusion makes this uncertain by roughly 20%; the full standing silhouette is not visible at the impact. Conversion is pixels / 205 × 1.8 blocks. Do not mistake these estimates for exact dimensions.

| Element | Native pixels | Formula / result | Angle / count / palette |
|---|---|---|---|
| Crescent envelope | x≈340–690, y≈100–380 | width 350 / 205 × 1.8 = 3.07; height 280 / 205 × 1.8 = 2.46 | arched, ~65° tilted plane; 1 dominant arc; orange/gold |
| Bright band | ~40px thick | 40 / 205 × 1.8 = 0.35 | gold outer edge with dark red fill |
| Contact | x≈640, y≈280; ground≈350 | (350 − 280) / 205 × 1.8 = 0.61 high | red petals, ~20–30 visible flecks |
| Wake | x≈20–450; ~12px thick | 430 / 205 × 1.8 = 3.78 long; 12 / 205 × 1.8 = 0.105 thick | low horizontal dark red / pink |

## Authored dimensions and timing

Current shared geometry (revised after S1 capture): physical ribbed blade ≈1.5 blocks (1.5/1.8 = 0.83 bodies); trail tip 2.1 blocks (2.1/1.8 = 1.17 bodies) includes drawn overshoot. Pivot height 1.05 blocks (1.05/1.8 = 0.583 bodies), nominal 200° swing, A tilt65°/B tilt25°. The fan fills only the outer quarter of its 1.55-block radial span: 1.55×0.25 = 0.3875 blocks; dark inner band 1.55×0.07 = 0.1085 blocks. Gold edge0.055 blocks. Sampling≤30°; six-tick life plus two-tick contact freeze. Source irregular crescents remain an approximation. Crimson petals use vanilla particles. Heavy burst has five red lobes + five dark accents and eight petals, maximum height2.5 blocks (2.5/1.8=1.39 bodies). Punctuation is3.1 blocks (3.1/1.8=1.72 bodies). Defense excludes the enemy’s yellow crescent.

Hit arithmetic: (48.35 − 47.5) × 20 = 17.00 → 17t, (49.2 − 47.5) × 20 = 34.00 → 34t.
Recovery ends: (50.2 − 47.5) × 20 = 54t. Damage counts: 2 coins / 2 damage mechanics, each ignores invulnerability. Decorative bursts never add damage.

Importance: 4/10. Windup precedes each hit; 2–3 tick mutual hit-stop; gold/orange dominant, red/maroon subordinate. No glow outlines. Rig swept fan + short petals; heavy flashes only for S3/Q. Effects expire before the next long hold. Full source pacing intentionally exceeds the generic 60/100t guideline to permit direct same-time comparison.

## Mapping confidence
S1/S2 are identified by consecutive 2/3 contact groups; S3 uses three heavy groups separated by caster-centered charge eruptions. Q maps the enhanced five-coin skill, NOT a separate canonical E.G.O. The held ribbed blade was previously misread as a floating gun rack.

## CHECK / ACT
Awaiting real-client iteration 1.

Audio onsets (seconds): 47.604, 49.091, 49.404, 49.961, 50.101. Music/voice onsets are not automatically damage events. Visual contact is the hit-count authority.

Current display budget: see REPORT.md for sampling/overlap arithmetic. Nine samples×three solid layers+11 rig parts=38 per cut; worst heavy overlap38+18+3=59. These are analytical counts, not runtime measurements. Earlier source-correction counts below describe the superseded four-sample revision.

Pair command (after capture):
```sh
SKILL_COMPARE_VIDEO_DIR="$PWD/tools/skill-compare/cache/videos" make skill-pairs CASE=vendetta-rifle-s1 SKILL=vendetta-rifle-s1 AT="47.8:windup 48.4:hit1 48.8:recoil 49.25:hit2 49.55:wake 49.95:recovery"
```

### Source correction before capture
A clearer 854×480 frame at **56.333s** shows seven pale ribs on a curved blade, not five rifle barrels. Tip≈(295,90), grip≈(300,252): sqrt(5²+162²) = 162.08px; reconstructed standing body≈220px; 162.08/220×1.8 = **1.33 blocks** of visible weapon. The rig now has three curved spine sections + grip + seven ribs = **11 parts**, and ~1.5-block blade height. The 2.1-block trail tip includes drawn overshoot beyond the physical blade. Slash petals now use 16-particle crimson bursts instead of quad entities: 11 parts + 4 samples×4 layers = **27 displays**; S1 ready+cut <=38, S2 <=57, heavy <=48, DEF <=12. Earlier five-rib / 2.1-block weapon measurements above are superseded by this clearer frame.

## Iteration 1 — real client inspected
Pairs: `build/pairs/vendetta-rifle-s1-iteration-1.png`. Source moments from the command above. FAIL: initial held rig is occluded, first fan is four oversized rectangular panels extending above the frame, second fan fill is invisible from this side (only edges read), punctuation core is hidden by its opaque dark shell, second face insert is too distant, and sky occupies the upper third of the frame. Source contact silhouettes are compact curved strokes.
ACT: move rig hand to camera-facing side; reduce radial fill to outer quarter, sample at 30° rather than 90°, use opaque crisp panels so reverse sweep remains visible; reduce trail life to six ticks to keep overlap below budget. Move dark line below rather than around the emissive core. Pitch camera down to remove sky and tighten second-contact insert. Generic Burn damage removed after first build. Next capture must verify these edits.
