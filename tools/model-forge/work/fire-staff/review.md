# fire-staff: visual review

Five observed rounds: blockout -> shape -> details -> texture -> polish.
The concept remained locked. Scores compare construction diagrams, not the diagonal source icon.

| Round | Front | Side | Top | Iso | Observation and next action |
|---|---:|---:|---:|---:|---|
| 1 | 0.637 | 0.681 | 0.437 | 0.692 | Blockout: floating crystal box is not a finished staff; fork and flame contour missing. Top IoU was only 0.437. |
| 2 | 0.737 | 0.779 | 0.859 | 0.811 | Shape: added forks and shard silhouette. Crystal still reads as tilted lumber, not flame. |
| 3 | 0.749 | 0.797 | 0.869 | 0.839 | Details: changed the heart to a diamond section, added bright core, bindings and foot. Shaft coverage and top shard depth remain too narrow. |
| 4 | 0.806 | 0.899 | 0.869 | 0.884 | Texture: increased shaft to 1.95 units and deepened the rising shard for readability. Front IoU passes, but atlas highlights make the crystal too gold. |
| 5 | 0.802 | 0.897 | 0.868 | 0.884 | Polish: reduce pale highlight coverage, strengthen orange/red ramps and shrink the white core. Final iso/hand plus inventory reviewed; reads as ember crystal. Flame contour remains angular, with a blunt upper shard. |

Verdict: **pass local authoring gate; client verification pending**. Final validation: 15 elements, 2 texels/unit, no errors/warnings, no clipped preview.

Reviewed images: round-1 through round-4 comparison sheets; round-5 native-size inventory panels and final iso/first-person contact sheet. Fire staff round-5 full comparison was also inspected after its palette correction.

No Minecraft client or Blockbench execution was performed. Check both hands, actual grip alignment, FOV, swing/use animation, frame/ground placement, back faces and overlapping surfaces under motion.
