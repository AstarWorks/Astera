# Crimson Settlement — PLAN / MEASURE

Source: wbu2viInM3w, 104.4–117.5s. Native **24 fps** (ffprobe); 30 fps micro-sheets duplicate native frames, never add precision. Frame times are rounded to ±1 native frame. Character standing height = 1.8 blocks. Sizes below use **same-shot** reconstructed standing height estimates (uncertain where crouched or occluded). Pixel measurements below are manual bounding-box estimates on the **854×480 native frame**, inspected separately for each skill. No automated segmentation was used.

Range: **dash-melee**. No ranged projectile / beam damage. Straight pink streaks are blade wakes. Caster closes in, swings at arm’s length, then settles; heavy footage also reverses sides and leaps.

## Native-frame measurements

Frame 116.500s completely obscures the actors. A body-height conversion from this shot is **not measurable**; screen fractions are recorded instead. The authored 2.5-block fan is a provisional scale inherited from the S3 shot, not a measured Q dimension.

| Element | Native pixels | Formula / result | Angle / count / palette |
|---|---|---|---|
| Final eruption | x≈0–854,y≈0–480 | 854 / 854 = 1.00 frame widths; 480 / 480 = 1.00 frame heights | saturated red/pink mass, gold slash, white sparks |
| Central dark lobe | x≈360–505,y≈0–330 | 145 / 854 = 0.170 frame widths; 330 / 480 = 0.688 frame heights | black/red central taper |
| Bright sparks | ~8–25px diameter | 8 / 854 = 0.0094; 25 / 854 = 0.0293 frame widths | white and gold; ~20 visible |

## Authored dimensions and timing

Current shared geometry (revised after S1 capture): physical ribbed blade ≈1.5 blocks (1.5/1.8 = 0.83 bodies); trail tip 2.1 blocks (2.1/1.8 = 1.17 bodies) includes drawn overshoot. Pivot height 1.05 blocks (1.05/1.8 = 0.583 bodies), nominal 200° swing, A tilt65°/B tilt25°. The fan fills only the outer quarter of its 1.55-block radial span: 1.55×0.25 = 0.3875 blocks; dark inner band 1.55×0.07 = 0.1085 blocks. Gold edge0.055 blocks. Sampling≤30°; six-tick life plus two-tick contact freeze. Source irregular crescents remain an approximation. Crimson petals use vanilla particles. Heavy burst has five red lobes + five dark accents and eight petals, maximum height2.5 blocks (2.5/1.8=1.39 bodies). Punctuation is3.1 blocks (3.1/1.8=1.72 bodies). Defense excludes the enemy’s yellow crescent.

Hit arithmetic: (107.05 − 104.4) × 20 = 53.00 → 53t, (107.95 − 104.4) × 20 = 71.00 → 71t, (109.8 − 104.4) × 20 = 108.00 → 108t, (111.45 − 104.4) × 20 = 141.00 → 141t, (115.95 − 104.4) × 20 = 231.00 → 231t.
Recovery ends: (117.5 − 104.4) × 20 = 262t. Damage counts: 5 coins / 5 damage mechanics, each ignores invulnerability. Decorative bursts never add damage.

Importance: 10/10. Windup precedes each hit; 2–3 tick mutual hit-stop; gold/orange dominant, red/maroon subordinate. No glow outlines. Rig swept fan + short petals; heavy flashes only for S3/Q. Effects expire before the next long hold. Full source pacing intentionally exceeds the generic 60/100t guideline to permit direct same-time comparison.

## Mapping confidence
S1/S2 are identified by consecutive 2/3 contact groups; S3 uses three heavy groups separated by caster-centered charge eruptions. Q maps the enhanced five-coin skill, NOT a separate canonical E.G.O. The held ribbed blade was previously misread as a floating gun rack.

## CHECK / ACT
Awaiting real-client iteration 1.

Audio onsets (seconds): 104.47, 104.69, 105.039, 105.7, 106.06, 107.837, 108.765, 108.847, 109.613, 110.623, 111.412, 112.434, 113.073, 113.851, 114.303, 114.524, 115.847, 115.929, 117.043. Music/voice onsets are not automatically damage events. Visual contact is the hit-count authority.

Current display budget: see REPORT.md for sampling/overlap arithmetic. Nine samples×three solid layers+11 rig parts=38 per cut; worst heavy overlap38+18+3=59. These are analytical counts, not runtime measurements. Earlier source-correction counts below describe the superseded four-sample revision.

Pair command (after capture):
```sh
SKILL_COMPARE_VIDEO_DIR="$PWD/tools/skill-compare/cache/videos" make skill-pairs CASE=vendetta-rifle-ego SKILL=vendetta-rifle-ego AT="104.9:windup 107.25:hit1 108.1:hit2 109.95:hit3 111.65:hit4 116.25:hit5 117.15:recovery"
```

### Source correction before capture
A clearer 854×480 frame at **56.333s** shows seven pale ribs on a curved blade, not five rifle barrels. Tip≈(295,90), grip≈(300,252): sqrt(5²+162²) = 162.08px; reconstructed standing body≈220px; 162.08/220×1.8 = **1.33 blocks** of visible weapon. The rig now has three curved spine sections + grip + seven ribs = **11 parts**, and ~1.5-block blade height. The 2.1-block trail tip includes drawn overshoot beyond the physical blade. Slash petals now use 16-particle crimson bursts instead of quad entities: 11 parts + 4 samples×4 layers = **27 displays**; S1 ready+cut <=38, S2 <=57, heavy <=48, DEF <=12. Earlier five-rib / 2.1-block weapon measurements above are superseded by this clearer frame.

Final approach now uses the existing `leap` mechanic at tick 213, arc height 1.4 blocks (=1.4/1.8=0.78 body heights). It lands before the fixed-anchor finisher rig starts at tick 226; trajectory timing is an intent estimate until client capture. Side reversal remains an implementation approximation.
