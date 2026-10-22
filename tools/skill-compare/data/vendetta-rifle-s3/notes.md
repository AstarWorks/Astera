# Reckoning Salvo — PLAN / MEASURE

Source: wbu2viInM3w, 55.8–64.2s. Native **24 fps** (ffprobe); 30 fps micro-sheets duplicate native frames, never add precision. Frame times are rounded to ±1 native frame. Character standing height = 1.8 blocks. Sizes below use **same-shot** reconstructed standing height estimates (uncertain where crouched or occluded). Pixel measurements below are manual bounding-box estimates on the **854×480 native frame**, inspected separately for each skill. No automated segmentation was used.

Range: **dash-melee**. No ranged projectile / beam damage. Straight pink streaks are blade wakes. Caster closes in, swings at arm’s length, then settles; heavy footage also reverses sides and leaps.

## Native-frame measurements

Frame 58.125s: reconstructed standing-height reference ≈190px. Crouch/occlusion makes this uncertain by roughly 20%; the full standing silhouette is not visible at the impact. Conversion is pixels / 190 × 1.8 blocks. Do not mistake these estimates for exact dimensions.

| Element | Native pixels | Formula / result | Angle / count / palette |
|---|---|---|---|
| Heavy fan | x≈205–460,y≈130–350 | 255 / 190 × 1.8 = 2.42 wide; 220 / 190 × 1.8 = 2.08 high | ~5 dominant jagged red/black lobes, caster centered |
| Ground crescent | x≈130–530,y≈310–380 | 400 / 190 × 1.8 = 3.79 wide; 70 / 190 × 1.8 = 0.66 high | gold low elliptical slash |
| Remote contact wake | x≈640–850,y≈170–315 | 210 / 190 × 1.8 = 1.99 long | magenta diagonal ~−30°, target on right |
| Petals | ~20–45px | 20 / 190 × 1.8 = 0.19; 45 / 190 × 1.8 = 0.43 | ~20 red flecks; many foreground, therefore not reliable world scale |

## Authored dimensions and timing

Current shared geometry (revised after S1 capture): physical ribbed blade ≈1.5 blocks (1.5/1.8 = 0.83 bodies); trail tip 2.1 blocks (2.1/1.8 = 1.17 bodies) includes drawn overshoot. Pivot height 1.05 blocks (1.05/1.8 = 0.583 bodies), nominal 200° swing, A tilt65°/B tilt25°. The fan fills only the outer quarter of its 1.55-block radial span: 1.55×0.25 = 0.3875 blocks; dark inner band 1.55×0.07 = 0.1085 blocks. Gold edge0.055 blocks. Sampling≤30°; six-tick life plus two-tick contact freeze. Source irregular crescents remain an approximation. Crimson petals use vanilla particles. Heavy burst has five red lobes + five dark accents and eight petals, maximum height2.5 blocks (2.5/1.8=1.39 bodies). Punctuation is3.1 blocks (3.1/1.8=1.72 bodies). Defense excludes the enemy’s yellow crescent.

Hit arithmetic: (58.05 − 55.8) × 20 = 45.00 → 45t, (59.95 − 55.8) × 20 = 83.00 → 83t, (63.35 − 55.8) × 20 = 151.00 → 151t.
Recovery ends: (64.2 − 55.8) × 20 = 168t. Damage counts: 3 coins / 3 damage mechanics, each ignores invulnerability. Decorative bursts never add damage.

Importance: 8/10. Windup precedes each hit; 2–3 tick mutual hit-stop; gold/orange dominant, red/maroon subordinate. No glow outlines. Rig swept fan + short petals; heavy flashes only for S3/Q. Effects expire before the next long hold. Full source pacing intentionally exceeds the generic 60/100t guideline to permit direct same-time comparison.

## Mapping confidence
S1/S2 are identified by consecutive 2/3 contact groups; S3 uses three heavy groups separated by caster-centered charge eruptions. Q maps the enhanced five-coin skill, NOT a separate canonical E.G.O. The held ribbed blade was previously misread as a floating gun rack.

## CHECK / ACT
Awaiting real-client iteration 1.

Audio onsets (seconds): 55.846, 55.986, 56.531, 56.706, 56.973, 57.495, 59.759, 60.583, 60.699, 60.792, 60.932, 61.106, 61.338, 61.651, 61.779, 63.207, 64.02. Music/voice onsets are not automatically damage events. Visual contact is the hit-count authority.

Current display budget: see REPORT.md for sampling/overlap arithmetic. Nine samples×three solid layers+11 rig parts=38 per cut; worst heavy overlap38+18+3=59. These are analytical counts, not runtime measurements. Earlier source-correction counts below describe the superseded four-sample revision.

Pair command (after capture):
```sh
SKILL_COMPARE_VIDEO_DIR="$PWD/tools/skill-compare/cache/videos" make skill-pairs CASE=vendetta-rifle-s3 SKILL=vendetta-rifle-s3 AT="56.4:windup 57.35:charge 58.125:hit1 60.125:hit2 61.2:eruption 63.45:hit3 64.1:recovery"
```

### Source correction before capture
A clearer 854×480 frame at **56.333s** shows seven pale ribs on a curved blade, not five rifle barrels. Tip≈(295,90), grip≈(300,252): sqrt(5²+162²) = 162.08px; reconstructed standing body≈220px; 162.08/220×1.8 = **1.33 blocks** of visible weapon. The rig now has three curved spine sections + grip + seven ribs = **11 parts**, and ~1.5-block blade height. The 2.1-block trail tip includes drawn overshoot beyond the physical blade. Slash petals now use 16-particle crimson bursts instead of quad entities: 11 parts + 4 samples×4 layers = **27 displays**; S1 ready+cut <=38, S2 <=57, heavy <=48, DEF <=12. Earlier five-rib / 2.1-block weapon measurements above are superseded by this clearer frame.

Final approach now uses the existing `leap` mechanic at tick 133, arc height 1.4 blocks (=1.4/1.8=0.78 body heights). It lands before the fixed-anchor finisher rig starts at tick 146; trajectory timing is an intent estimate until client capture. Side reversal remains an implementation approximation.
