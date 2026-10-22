# Rack Volley — PLAN / MEASURE

Source: wbu2viInM3w, 50.55–54.8s. Native **24 fps** (ffprobe); 30 fps micro-sheets duplicate native frames, never add precision. Frame times are rounded to ±1 native frame. Character standing height = 1.8 blocks. Sizes below use **same-shot** reconstructed standing height estimates (uncertain where crouched or occluded). Pixel measurements below are manual bounding-box estimates on the **854×480 native frame**, inspected separately for each skill. No automated segmentation was used.

Range: **dash-melee**. No ranged projectile / beam damage. Straight pink streaks are blade wakes. Caster closes in, swings at arm’s length, then settles; heavy footage also reverses sides and leaps.

## Native-frame measurements

Frame 52.375s: reconstructed standing-height reference ≈215px. Crouch/occlusion makes this uncertain by roughly 20%; the full standing silhouette is not visible at the impact. Conversion is pixels / 215 × 1.8 blocks. Do not mistake these estimates for exact dimensions.

| Element | Native pixels | Formula / result | Angle / count / palette |
|---|---|---|---|
| Crescent envelope | x≈15–635, y≈65–380 | 620 / 215 × 1.8 = 5.19 wide; 315 / 215 × 1.8 = 2.64 high | elliptical overhead return sweep; ~200°; gold/orange |
| Bright band | ~55px thick | 55 / 215 × 1.8 = 0.46 | gold jagged edge, dark crimson inner face |
| Grip | x≈315,y≈230; ground≈385 | (385 − 230) / 215 × 1.8 = 1.30 high | weapon crossing shoulder |
| Streak | ~360px long × 18px thick | 360 / 215 × 1.8 = 3.01; 18 / 215 × 1.8 = 0.151 | horizontal gold core / pink bloom |

## Authored dimensions and timing

Current shared geometry (revised after S1 capture): physical ribbed blade ≈1.5 blocks (1.5/1.8 = 0.83 bodies); trail tip 2.1 blocks (2.1/1.8 = 1.17 bodies) includes drawn overshoot. Pivot height 1.05 blocks (1.05/1.8 = 0.583 bodies), nominal 200° swing, A tilt65°/B tilt25°. The fan fills only the outer quarter of its 1.55-block radial span: 1.55×0.25 = 0.3875 blocks; dark inner band 1.55×0.07 = 0.1085 blocks. Gold edge0.055 blocks. Sampling≤30°; six-tick life plus two-tick contact freeze. Source irregular crescents remain an approximation. Crimson petals use vanilla particles. Heavy burst has five red lobes + five dark accents and eight petals, maximum height2.5 blocks (2.5/1.8=1.39 bodies). Punctuation is3.1 blocks (3.1/1.8=1.72 bodies). Defense excludes the enemy’s yellow crescent.

Hit arithmetic: (51.8 − 50.55) × 20 = 25.00 → 25t, (52.2 − 50.55) × 20 = 33.00 → 33t, (53.85 − 50.55) × 20 = 66.00 → 66t.
Recovery ends: (54.8 − 50.55) × 20 = 85t. Damage counts: 3 coins / 3 damage mechanics, each ignores invulnerability. Decorative bursts never add damage.

Importance: 6/10. Windup precedes each hit; 2–3 tick mutual hit-stop; gold/orange dominant, red/maroon subordinate. No glow outlines. Rig swept fan + short petals; heavy flashes only for S3/Q. Effects expire before the next long hold. Full source pacing intentionally exceeds the generic 60/100t guideline to permit direct same-time comparison.

## Mapping confidence
S1/S2 are identified by consecutive 2/3 contact groups; S3 uses three heavy groups separated by caster-centered charge eruptions. Q maps the enhanced five-coin skill, NOT a separate canonical E.G.O. The held ribbed blade was previously misread as a floating gun rack.

## CHECK / ACT
Iteration 1 inspected; not matched.

Audio onsets (seconds): 50.794, 50.968, 51.525, 52.187, 52.373, 53.023, 53.499, 54.254. Music/voice onsets are not automatically damage events. Visual contact is the hit-count authority.

Current display budget: see REPORT.md for sampling/overlap arithmetic. Nine samples×three solid layers+11 rig parts=38 per cut; worst heavy overlap38+18+3=59. These are analytical counts, not runtime measurements. Earlier source-correction counts below describe the superseded four-sample revision.

Pair command (after capture):
```sh
SKILL_COMPARE_VIDEO_DIR="$PWD/tools/skill-compare/cache/videos" make skill-pairs CASE=vendetta-rifle-s2 SKILL=vendetta-rifle-s2 AT="50.9:windup 51.85:hit1 52.375:hit2 53.4:charge 53.95:hit3 54.4:recovery"
```

### Source correction before capture
A clearer 854×480 frame at **56.333s** shows seven pale ribs on a curved blade, not five rifle barrels. Tip≈(295,90), grip≈(300,252): sqrt(5²+162²) = 162.08px; reconstructed standing body≈220px; 162.08/220×1.8 = **1.33 blocks** of visible weapon. The rig now has three curved spine sections + grip + seven ribs = **11 parts**, and ~1.5-block blade height. The 2.1-block trail tip includes drawn overshoot beyond the physical blade. Slash petals now use 16-particle crimson bursts instead of quad entities: 11 parts + 4 samples×4 layers = **27 displays**; S1 ready+cut <=38, S2 <=57, heavy <=48, DEF <=12. Earlier five-rib / 2.1-block weapon measurements above are superseded by this clearer frame.


## Iteration 1 — real client
`build/pairs/vendetta-rifle-s2-iteration-1.png`: wind-up rig absent, action/camera late by roughly 8–10 ticks, crescent disconnected into radial teeth, two overlapping held blades, charge absent and final shot too wide. ACT: lower trail fade exponent from2 to0.15 so old chords remain joined; end old held rig at8 ticks to prevent duplicate weapons; schedule opening VFX at1 tick to probe immediate-effect issue. Next capture uses lower client resolution and longer settle to reduce renderer startup delay; hit arithmetic stays unchanged pending timing calibration.
