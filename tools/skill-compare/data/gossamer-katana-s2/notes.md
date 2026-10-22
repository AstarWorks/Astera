# Measurement method

Source: LkLhzmoJZ6Y, 854×480, native 30000/1001 fps. Inspected 30 fps sheets (nearest native frame; uncertainty ±1 native frame) plus the 4 fps strips. Audio was supplied separately: muxed losslessly into `/tmp/gossamer-reference/LkLhzmoJZ6Y.mp4` for the compare tool. Cached frames contain no audio originally.

Measurements are manual pixel estimates, not object tracking. H = the local upright character height in the same shot; crouched sprites are not a valid height reference. Conversion: blocks = measured pixels / H_pixels × 1.8. Ticks = round((frame_time − case_start) × 20). Camera zoom changes H; never reuse H across cuts. Colors are approximate observed sRGB swatches, not exact sampled palette entries. Small decorative flakes are counted only when separable.

Skill attribution is provisional: the montage does not expose readable skill labels. Wiki coins constrain damage, but visible echoes and critical bursts are not independent coins. Audio includes music and voice, so onsets alone do not prove hits.

# PLAN — Unseen Line

Video range 15.300–20.600 s. RANGE: AoE at target (projected melee/target-local echo), not a physical caster dash or self AoE. Caster stays behind while the double reaches and passes the target. Three wiki coins / three damage beats; the thin line is a delayed third-cut tell. Importance 7/10. Exact third-coin attribution remains uncertain: the 17.03 s line may be initial coin contact followed by a critical payout at 18.60 s. This implementation puts the third damage on the payout.

| Element / measured frame | Pixel observation | H / converted blocks, formula | Angle/count/color | Tick window |
|---|---|---|---|---|
| Echo body, 15.833 s | ~180 px upright height | 180/180×1.8=1.8 b | 1 double, cyan with magenta scan streaks | 8–24 |
| Echo travel, 16.033 s | ~300 px from caster, H ~90 px | 300/90=3.33H; ×1.8=6.0 b | forward, caster stays | 14–16 |
| Opposed crescents, 16.600 s | ~150 px diameter, H ~100 px | 150/100=1.5H; ×1.8=2.7 b | 2 simultaneous arcs, cyan/violet | 25–31 |
| Suspended line, 17.300 s | ~800 px length, H ~110 px | 800/110=7.27H; ×1.8=13.09 b | thin horizontal cyan | 36–65 |
| Burst, 18.600 s | ~400 px core, body not visible | no reliable H in cut-in; do not invent world scale | cyan/white core, ~12 visible flakes, radial streaks | 66–70 |
| Lattice, 19.000 s | ~360 px line, H ~95 px | 360/95=3.79H; ×1.8=6.82 b | 3 primary diagonal cuts ~±40° | 74–86 |
| Return to wide, 19.700 s | H ~60 px | camera span=480/60×1.8=14.4 b vertically | static caster and collapsed target | 88–106 |

Hit conversion: (16.033−15.300)×20=14.66→15; (16.567−15.300)×20=25.34→25; (18.600−15.300)×20=66. Recovery (20.600−15.300)×20=106. Audio onsets 15.846/16.124/18.771 s support the echo launch and release, with voice/music overlap. Cuts 17.150/18.619/18.719 s bracket the line and impact closeups.

# CHECK / ACT

First content pass submitted to real-client capture. Iteration results are recorded below as captures complete.

## Iteration 1 — real client, rejected

Pairs: `build/pairs/iterations/gossamer-katana-s2-1.png`. The double and early fans are absent or barely visible. The horizontal line extends mostly behind the caster instead of through the target. Lattice is too high; shot scale is much too wide for the echo and payoff closeups. No visual match claimed.

ACT: inspected coordinate handling: normal element `translate` is a **center**, not a block corner; `caster_forward` is **eye-relative**, not foot-relative. Corrected line center to +3 b and position anchors with height −1.62. Corrected diagonal center likewise. Added echo closeup and tightened payoff camera. Investigate absent early rigs in a retained capture server before blaming the engine.

The first capture also shows the release as bare thin lines. Added a solid pale four-point impact, a dark silhouette and a translucent bloom at the crossing (3 displays; release total 14), keeping the linework as a secondary layer.

Capture diagnostic: pixel differences at t0,2,…,30 are all zero; the entire image only changes at t32. This is captured evidence of an opening-frame stall, not evidence that the first two skill beats rendered correctly. The next capture warms one block and one text display before recording and removes both.

Range correction after iteration 1: damage resolves in a 2.2 b sphere centered 6 b ahead at foot height, matching the projected double; the decorative long line is not a damaging beam or a 7 b caster cone.

## Iteration 2 — warmed real client, rejected

Pairs: `build/pairs/iterations/gossamer-katana-s2-2.png`. Opening motion now renders (pixel differences are nonzero at t4,10,15,25,30). The line now passes through the target at waist height. Remaining: cyan glass double nearly disappears; one fan is solid but the reverse fan loses its fill; fan rectangles are coarse and overly broad. First echo cut needs a horizontal plane. Release flare is absent at the exact hit frame, and crossing lines fade too soon and sit below the chest. Camera teleport arrives roughly two ticks after its key.

ACT: use two-sided crisp navy bands with white edges, narrow the fan band to 0.85 b and subdivide at 20° (11 intervals; 25 displays per rig). Cyan solid double with one purple scan streak = 7 displays. Worst conservative simultaneous budget: 2×25+7+3=60. First echo cut gets a horizontal rig. The target-local release is limited to one anchor, starts at 64 for a visible hit at 66, has an immediate solid flare, 6.8 b diagonals centered on the chest, and a 20-tick tail. Keep source camera timestamps honest; capture teleport latency remains recorded rather than silently moving the source keys.

The suspended line gets a white solid spine, with its glint and center moved onto the projected target rather than leaving a vertical pole by the caster.
