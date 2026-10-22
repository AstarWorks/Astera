# Hairpin Parry — PLAN / MEASURE

Source: wbu2viInM3w, 94–96.8s. Native **24 fps** (ffprobe); 30 fps micro-sheets duplicate native frames, never add precision. Frame times are rounded to ±1 native frame. Character standing height = 1.8 blocks. Sizes below use **same-shot** reconstructed standing height estimates (uncertain where crouched or occluded). Pixel measurements below are manual bounding-box estimates on the **854×480 native frame**, inspected separately for each skill. No automated segmentation was used.

Range: **melee counter/clash**. No ranged projectile / beam damage. Straight pink streaks are blade wakes. Caster holds and recoils at contact.

## Native-frame measurements

Frame 94.708s: reconstructed standing-height reference ≈120px. Crouch/occlusion makes this uncertain by roughly 20%; the full standing silhouette is not visible at the impact. Conversion is pixels / 120 × 1.8 blocks. Do not mistake these estimates for exact dimensions.

| Element | Native pixels | Formula / result | Angle / count / palette |
|---|---|---|---|
| Held weapon | tip≈(140,147), grip≈(218,210) | sqrt(78² + 63²) / 120 × 1.8 = 1.50 visible length | ~−39° diagonal; dark blade / pale ribs |
| Clash ring | x≈345–455,y≈190–285 | 110 / 120 × 1.8 = 1.65 wide; 95 / 120 × 1.8 = 1.43 high | orange ring, gold sparks |
| Gap | caster x≈190, enemy x≈510 | 320 / 120 × 1.8 = 4.80 | post-contact separation; not attack reach |
| Enemy arc (exclude) | x≈325–705 | 380 / 120 × 1.8 = 5.70 wide | yellow arc belongs to enemy, not our weapon |

## Authored dimensions and timing

Current shared geometry (revised after S1 capture): physical ribbed blade ≈1.5 blocks (1.5/1.8 = 0.83 bodies); trail tip 2.1 blocks (2.1/1.8 = 1.17 bodies) includes drawn overshoot. Pivot height 1.05 blocks (1.05/1.8 = 0.583 bodies), nominal 200° swing, A tilt65°/B tilt25°. The fan fills only the outer quarter of its 1.55-block radial span: 1.55×0.25 = 0.3875 blocks; dark inner band 1.55×0.07 = 0.1085 blocks. Gold edge0.055 blocks. Sampling≤30°; six-tick life plus two-tick contact freeze. Source irregular crescents remain an approximation. Crimson petals use vanilla particles. Heavy burst has five red lobes + five dark accents and eight petals, maximum height2.5 blocks (2.5/1.8=1.39 bodies). Punctuation is3.1 blocks (3.1/1.8=1.72 bodies). Defense excludes the enemy’s yellow crescent.

Hit arithmetic: (94.15 − 94) × 20 = 3.00 → 3t, (96.2 − 94) × 20 = 44.00 → 44t.
Recovery ends: (96.8 − 94) × 20 = 56t. Damage counts: 2 coins / 2 damage mechanics, each ignores invulnerability. Decorative bursts never add damage.

Importance: 3/10. Windup precedes each hit; 2–3 tick mutual hit-stop; gold/orange dominant, red/maroon subordinate. No glow outlines. Rig swept fan + short petals; heavy flashes only for S3/Q. Effects expire before the next long hold. Full source pacing intentionally exceeds the generic 60/100t guideline to permit direct same-time comparison.

## Mapping confidence
This defensive section shows repeated clashes against the black-clad enemy, not an isolated successful two-coin counter. Two outgoing hits come from the supplied wiki kit, not a claim that every yellow enemy arc is our attack. An AI-free capture dummy cannot reproduce incoming clash swings.

## CHECK / ACT
Awaiting real-client iteration 1.

Audio onsets (seconds): 94.174, 94.313, 94.615, 94.708, 95.997, 96.264. Music/voice onsets are not automatically damage events. Visual contact is the hit-count authority.

Current display budget: see REPORT.md for sampling/overlap arithmetic. Nine samples×three solid layers+11 rig parts=38 per cut; worst heavy overlap38+18+3=59. These are analytical counts, not runtime measurements. Earlier source-correction counts below describe the superseded four-sample revision.

Pair command (after capture):
```sh
SKILL_COMPARE_VIDEO_DIR="$PWD/tools/skill-compare/cache/videos" make skill-pairs CASE=vendetta-rifle-def SKILL=vendetta-rifle-def AT="94.0:guard 94.2:clash1 94.7:separation 95.5:guard 96.25:clash2 96.65:recovery"
```

### Source correction before capture
A clearer 854×480 frame at **56.333s** shows seven pale ribs on a curved blade, not five rifle barrels. Tip≈(295,90), grip≈(300,252): sqrt(5²+162²) = 162.08px; reconstructed standing body≈220px; 162.08/220×1.8 = **1.33 blocks** of visible weapon. The rig now has three curved spine sections + grip + seven ribs = **11 parts**, and ~1.5-block blade height. The 2.1-block trail tip includes drawn overshoot beyond the physical blade. Slash petals now use 16-particle crimson bursts instead of quad entities: 11 parts + 4 samples×4 layers = **27 displays**; S1 ready+cut <=38, S2 <=57, heavy <=48, DEF <=12. Earlier five-rib / 2.1-block weapon measurements above are superseded by this clearer frame.
