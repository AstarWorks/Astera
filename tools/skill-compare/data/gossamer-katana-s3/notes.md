# Measurement method

Source: LkLhzmoJZ6Y, 854×480, native 30000/1001 fps. Inspected 30 fps sheets (nearest native frame; uncertainty ±1 native frame) plus the 4 fps strips. Audio was supplied separately: muxed losslessly into `/tmp/gossamer-reference/LkLhzmoJZ6Y.mp4` for the compare tool. Cached frames contain no audio originally.

Measurements are manual pixel estimates, not object tracking. H = the local upright character height in the same shot; crouched sprites are not a valid height reference. Conversion: blocks = measured pixels / H_pixels × 1.8. Ticks = round((frame_time − case_start) × 20). Camera zoom changes H; never reuse H across cuts. Colors are approximate observed sRGB swatches, not exact sampled palette entries. Small decorative flakes are counted only when separable.

Skill attribution is provisional: the montage does not expose readable skill labels. Wiki coins constrain damage, but visible echoes and critical bursts are not independent coins. Audio includes music and voice, so onsets alone do not prove hits.

# PLAN — Web Cyclone

Video range 43.000–50.500 s. Provisional mapping to base multi-target space-rending skill (wiki: one coin). RANGE: dash-melee followed by a forward finishing area; not the previous self-centered cyclone. Visible repeated slashes are preparation/echoes; one damage event on final severance. Importance 9/10. Alternative variant identification cannot be established from this montage alone.

| Element / measured frame | Pixel observation | H / converted blocks, formula | Angle/count/color | Tick window |
|---|---|---|---|---|
| Red echo rush, 43.600 s | ~180 px path; H ~55 px | 180/55=3.27H; ×1.8=5.89 b | ~3 red silhouettes, leftward on screen | 11–18 |
| Crescent flurry, 44.300 s | ~110 px arc diameter; H ~60 px | 110/60=1.83H; ×1.8=3.30 b | alternating cyan/violet arcs | 21–41 |
| Blue slash cut-in, 45.500 s | fills cropped screen | no intact body; cannot derive block size | diagonal cyan with flakes | 48–58 |
| White swirl, 46.933 s | ~700 px width; H ~180 px in close shot | 700/180=3.89H; ×1.8=7.0 b | near horizontal white sweep | 77–83 |
| White flash, 47.267 s | full frame, 2 native frames | 2/29.97×20=1.33 ticks → 1 tick | white then grey, no body scale | 85–87 |
| Persistent diagonal, 47.433 s | full 854×480 diagonal clipped | sqrt(854²+480²)=979.65px; H ~160px → 979.65/160×1.8=11.02 b | ~35° to horizontal, black/white | 87–130 |
| Recovery, 49.500 s | caster crouched, H not usable | retain adjacent upright calibration | grey scene returns, line fades | 130–150 |

Finish tick: (47.350−43.000)×20=87. Flash precedes it: (47.267−43.000)×20=85.34→85. Total (50.500−43.000)×20=150 ticks. Source faces left; camera must be on negative z so the caster appears right of target. Audio onset 47.319 s and cuts 47.281/47.347/47.381 s corroborate the final flash.

# CHECK / ACT

First content pass submitted to real-client capture. Iteration results are recorded below as captures complete.

## Capture setup rejection (before iteration 1)

The harness rejected the correct mirrored camera at world z=284.5 as outside the arena, although the caster and watcher remained at their requested positions. Its `in_arena` string check expects another coordinate in the 300s. No skill was filmed. Added a positive-z camera key at t=−6 for pre-roll setup; t=0 remains the source's negative-z camera. The director changes to the correct side at cast. No engine or capture-tool code changed.
