# VFX nodegraph re-land — 2026-10-29

Base `0893624f2`; merged origin/main `4809c5e8f6580a0a0007b76fee36bdc117e12aa8` into `wp/vfx-nodegraph-reland`. No push or live deployment.

## Resolution

- Retained main's v3 engine, current content, single-skill reload, parts, world-clock bodies and line ribbons. Restored ADR-0304 graph renderer/schema/manifests, ADR-0305 shader composition and isolated model variants, sample assets, authoring guides and skill-compare hub.
- Delete/modify and rename/delete conflicts: retained the two authoring guides and the gallery files at their new `tools/skill-compare` paths. `tools/sprite-gallery` and its Make targets stay deleted. README/area-spec conflicts retain hub/graph/shader documentation alongside current engine behaviour.
- Main's reset silently removed changes already in the merge ancestor; restored those explicitly rather than relying on conflict detection. Preserved main's new `quad` source/sprite while restoring graph definitions. Removed the obsolete core shader patches after moving them into named effects.
- Adapted shader selection to v3 `display` with `kind: item`, through decoding, table lowering, MeshLook and Paper DisplaySpec. Unknown effects fail full and single-skill reload. Existing engine configuration gates, part loading and reload APIs remain in place.
- Migrated 14 samples and 3 playable shader demos to v3 machine/display documents; removed sample v2 look documents. Gallery reads current skill YAML and discovers nested display ops. Added only `shader_effect` to the committed skill schema: full regeneration also changed unrelated stale schema sections, which were excluded.

## Verification

- PASS `:tools:item-art:compileKotlin`, `:tools:resource-pack:compileKotlin`, all nine `plugin/*` modules' `compileKotlin` and `compileTestKotlin` (including test-fixtures), plus item-art/resource-pack/game-sim test compilation.
- PASS `:tools:architecture-test:test`: 79 checks, zero failures.
- PASS `:tools:content-lint:run`: 527 YAMLs, zero schema failures; 39 runtime categories, zero decode failures; zero unresolved references over 998 pack files.
- PASS `:tools:item-art:run`: 617 generated files. Retained the intended sprite contact sheet. Restored 16 unrelated existing generated files and removed 14 unrelated newly generated item files; no `content/models` change is included. Those pre-existing generator/content differences are outside this restoration.
- PASS direct old-painter tool comparison: compiled the original ParticlePainters from `0ae4b17b2` in `build/painter-parity`, applied the original strip compositing and invisible width pins, and compared decoded pixels: **30 sprites, 94 frames, zero pixel diff**. Twelve original committed strips differed from the original painter's actual output; direct raw-frame comparison also needs strip compositing, so neither comparison is the renderer oracle.
- PASS gallery-browser, samples-browser and hub-browser against the real hub on free port **18197**, using the installed Playwright/Chromium. Browser checks include 14 v3 samples and all ten navigation tabs. Logs: `/tmp/vfx-reland-{gallery,samples,hub}-final.log`; screenshots: `/tmp/claude-1000/vfx-nodegraph/`.
- Integration tests restored/adapted: `ShippedSpriteGraphsTest`, `ShippedShaderEffectsPackTest`. Added `ShippedShaderDisplayScenarioTest.shippedSamplesAndDemosPlayWithTheirShaderSelection` (shipped content → compiler → table player → shader selection and timed removal). Compiled, not executed; manager runs integration scenarios in its window. No unit tests or make e2e ran.

## Other tracks' reset losses (report only)

Compared every path changed by `9324fc5a7` against its parent and fetched origin/main, excluding this track's graph/shader/sample/hub work and intentional gallery relocation. One other-track path remains absent:

| Path | Last commit holding the file before reset |
|---|---|
| `tools/game-sim/src/test/kotlin/com/astarworks/astera/gamesim/EndedCastWorldEditScenarioTest.kt` | `bdf4bc899` (present in `c073e9ef3`) |

It covered queued world edits after cast end and isolation of a failing world edit. Not restored; its original engine APIs need comparison with the current v3 scenarios.

## Remaining verification and documentation differences

- No current-engine real Minecraft capture or shader visual approval in this task. Earlier sample captures describe the pre-reset implementation. Browser dimension previews are not shader visual evidence; REQUIRE_SAMPLE_CAPTURES was not enabled.
- `tools/vfx-shot/shotstate.py` still omits skill/VFX/frame references from warm-stack pack fingerprint inputs, as the shader authoring guide already records. The QA owner should add them before relying on warm captures after reference changes.
- Updated mutable authoring guides and area spec: v2 mesh/plate/look documents are now v3 machine/display/skill documents. Accepted ADR-0304/0305 were restored verbatim; ADR-0305's historical mesh examples are not current executable syntax.
- Existing harness prose differs from current code: coding-posture says Kotlin 2.2 but the catalog uses 2.3.21; CLAUDE.md calls module-layout a seven-plugin layout while settings declares eight runtime plugins; module-layout's add-skill description still uses the old archetype/damage vocabulary. Harness files were not edited.
- The committed skill schema already trails unrelated current spec fields (e.g. lock move enum/defaults); full schema generation exposed this drift. Only this task's display field was added, preserving main's existing schema otherwise.
