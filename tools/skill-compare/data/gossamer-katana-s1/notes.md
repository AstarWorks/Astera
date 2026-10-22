# Measurement method

Source: LkLhzmoJZ6Y, 854×480, native 30000/1001 fps. Inspected 30 fps sheets (nearest native frame; uncertainty ±1 native frame) plus the 4 fps strips. Audio was supplied separately: muxed losslessly into `/tmp/gossamer-reference/LkLhzmoJZ6Y.mp4` for the compare tool. Cached frames contain no audio originally.

Measurements are manual pixel estimates, not object tracking. H = the local upright character height in the same shot; crouched sprites are not a valid height reference. Conversion: blocks = measured pixels / H_pixels × 1.8. Ticks = round((frame_time − case_start) × 20). Camera zoom changes H; never reuse H across cuts. Colors are approximate observed sRGB swatches, not exact sampled palette entries. Small decorative flakes are counted only when separable.

Skill attribution is provisional: the montage does not expose readable skill labels. Wiki coins constrain damage, but visible echoes and critical bursts are not independent coins. Audio includes music and voice, so onsets alone do not prove hits.

# PLAN — Thread Cut

Video range 10.300–14.800 s, including approach/anticipation. RANGE: dash-melee; caster advances, cuts upward, reverses, then makes a level cut. Three wiki coins / three implemented damage events. The later red burst is cosmetic, not a fourth hit. Importance 5/10.

| Element / measured frame | Pixel observation | H / converted blocks, formula | Angle/count/color | Tick window |
|---|---|---|---|---|
| Blade, 11.800 s | ~165 px blade, upright H ~140 px | 165/140=1.18H; ×1.8=2.12 b → 2.1 | narrow silver blade, one | 30–66 |
| Rising crescent, 12.000 s | ~380 px width; H ~140 px from adjacent uncropped pose | 380/140=2.71H; ×1.8=4.89 b | ~190° arc; navy #14245F, cyan #69DFFF | 32–42 |
| Crescent vertical extent, 12.000 s | >250 px, top clipped | >250/140=1.79H; ×1.8=>3.21 b | lower bound, not full diameter | 34–40 |
| Returning arc, 12.467 s | ~170 px diameter, upright target H ~100 px | 170/100=1.7H; ×1.8=3.06 b | 3 visible cyan/violet nested traces | 43–49 |
| Target cross, 12.500 s | ~170 px vertical, H ~100 px | 170/100×1.8=3.06 b | vertical + horizontal, 2 lines | 44–49 |
| Level cut, 13.100 s | >700 px long, H ~160 px in zoom | >700/160×1.8=>7.88 b → 10 b | near horizontal; cyan/white | 55–66 |
| Contact height, 13.100 s | ~90 px above feet, H ~160 px | 90/160×1.8=1.01 b → 1.05 | waist level | 55 |
| Red critical burst, 13.867 s | ~100 px spread, H ~45 px in wide shot | 100/45×1.8=4.0 b | red petals, count unresolved | 71–78 |

Hit conversion: (12.000−10.300)×20=34; (12.467−10.300)×20=43.34→43; (13.033−10.300)×20=54.66→55. Recovery to tick (14.800−10.300)×20=90. Dash starts tick 25 (ends 28, before the world-anchored rig spawns at 30); 3.6 blocks/3 ticks=1.2 blocks/tick. Initial staging separation 6 b is an approximation because the camera tracks the approach.

Audio candidates: 11.635, 12.181, 13.864 s. They do not align exactly with every visual contact; retained visual times rather than shifting hits to music. The automatic cut at 13.747 s does not detect every zoom, so camera keys also follow observed shot size.

# CHECK / ACT

First content pass submitted to real-client capture. Iteration results are recorded below as captures complete.
