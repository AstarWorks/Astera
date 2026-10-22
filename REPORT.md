# Model Forge report

Built designer-only Python concept, render, validate and staged-authoring tools; four volumetric
held-item models and pixel atlases; final comparison sheets with locked targets and review records.
Reused the seed hammer geometry/atlas and raster math, corrected its texture namespace, removed the seed.
No paid service, API image generation, Gradle, Docker, test suite, push or GitHub CI was used.

The concepts are Codex-authored code-native construction drawings informed by item YAML and the existing
AI weapon art, not newly diffusion-generated paintings. The supplied recipes cover these four items;
new items need the agent to author their design polygons/geometry. Research and the repeatable visual
procedure are in [docs/art/3d-model-pipeline.md](docs/art/3d-model-pipeline.md); commands and limitations
are in [tools/model-forge/README.md](tools/model-forge/README.md).

| Item | Rounds | Elements | Final IoU front / side / top / iso | Verdict |
|---|---:|---:|---|---|
| verdict-hammer | 5 | 40 | 0.913 / 0.855 / 0.918 / 0.892 | Pass local gate |
| couture-rapier | 5 | 17 | 0.823 / 0.869 / 0.821 / 0.874 | Pass local gate |
| fire-staff | 5 | 15 | 0.802 / 0.897 / 0.868 / 0.884 | Pass local gate |
| bastion-greatsword | 5 | 20 | 0.918 / 0.952 / 0.944 / 0.960 | Pass local gate |

Every final asset passed the production validator against its real content texture path: conservative
Java schema/coordinates/angles/UVs, exactly 2 texels per unit (floating-point tolerance), <=64 elements,
required display slots and unclipped reference previews. The pack builder was inspected, and each item
has one same-named Studio model plus its existing generated fallback. No pack was built or loaded.
Final JSON uses `astera:items/<id>`; content filenames trigger ADR-0193 section 4 without build changes.

Each `tools/model-forge/work/<id>/final-sheet.png` includes source art, all construction and display
views, silhouette overlays and inventory sizes. `final-review.json` retains final hashes and the five
observed rounds' metrics/hashes. `review.md` records defects and edits. Intermediate work stays ignored.

Weaknesses: 16px loses the hammer haft/rapier needle; flame is a deliberately angular ember crystal,
not animated or emissive; greatsword bevel seams may show under movement. IoU measures a simplified
independent construction target, not fidelity to the original AI icon. Concept iso colors use a basic
painter. The software renderer omits engine perspective/hand transforms, glint, tint, AO and blending.
Conservative angle limits are an Astera policy, not a statement that modern 26.x requires 22.5 steps.

**Real-client release gate:** load the pack and confirm all overrides; check first/third person in both
hands, grip alignment and camera occlusion at multiple FOVs, swing/use animation, GUI scales, ground
bobbing, item frames, back faces, glint and z-fighting. Finished authoring assets are committed locally;
in-client appearance is not certified.
