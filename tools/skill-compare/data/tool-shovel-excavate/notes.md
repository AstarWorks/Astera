# shovel-excavate — round 3 presentation review

No reference video. Branch: `wp/tool-skills-polish3c`.

Brown dirt clumps rise on a slow upward velocity and fall with coarse-dirt falling_dust. Smaller bursts and a sparse brown/tan cloud replace the noisy t11 debris and white sparkle ring. No electric sparks or end rods.

The core occupies the same position and tick as a material emission on the actual affected footprint, rather than a shared fixed offset. The shatter core uses the same live aim-point expression as its mechanical row. Particle counts, spread, velocity, colour and dust scale are presentation only. All 18 skills retain their original metadata, cooldown, dimensions, state durations, damage and break/place rows verbatim (validated against 0c044c264).

Stone → iron → diamond increases core density and dust scale (0.30 → 0.45 → 0.60). Pickaxe: grey → beige → cyan. Axe: brown → warm wood → blonde wood. Shovel: dark brown → brown → tan; dust density is only 2 → 3 → 4 per lane.

Round-2 sheet: `/tmp/claude-1000/tool-skills/round-2/tool-shovel-excavate.png`.
Round-3 sheet: `/tmp/claude-1000/tool-skills/round-3/tool-shovel-excavate.png`.
All-tier captures: `/tmp/claude-1000/tool-skills/round-3/captures/shovel-excavate-{stone,iron,diamond}/compare/`.

Review status: pending final real-client capture and tier comparison. The first exploratory axe capture was invalidated by edits during capture and is excluded from evidence.

## Sound measurement

Each skill now plays one matching vanilla material block-break cue, volume 1.0 and pitch 1.0, on its mechanical impact tick. Generic attack/wind, anvil, chime and delayed step layers were removed. Measured all four vanilla sample variants per material with ffmpeg `ebur128=peak=true,astats=metadata=1:reset=0`; one-second zero padding supplies the measurement window for short samples. Cue/ref delta is 0.0 LU for all twelve source samples. This measures source audio with authored gain/pitch, not a microphone recording of the client mix.

Raw ffmpeg logs, WAVs, original asset hashes and measurements: `/tmp/claude-1000/tool-skills/round-3/audio/`. Final per-skill sound/onset table: `/tmp/claude-1000/tool-skills/round-3/report.md`.

## Integration verification

`ToolSkillPresentationScenarioTest.tool families propagate material effects and tier light through the real cast path` casts all 18 shipped skills using the production loader, inheritance, player and gateways. It checks material motion, forbidden shared sparkles/crescents, footprint-aligned accents, core size, one unmodified vanilla cue and sound/impact op schedule equality. It writes `tool-op-<skill>.txt` alongside the per-tick scenario JSON traces.

The existing `:tools:game-sim:regenSkillOracle` path is used with a temporary content tree containing only the 18 tool skills. Only the resulting 18 tool goldens are copied back; no non-tool golden is generated or rewritten. Final class: `ShippedV3ContentScenarioTest`. PASS: scoped regenSkillOracle; normal ShippedV3ContentScenarioTest (2 tests, 0 failures); updated ToolSkillPresentationScenarioTest (1 test, 0 failures); compileKotlin; :tools:e2e:compileTestKotlin; :tools:content-lint:run (39 categories, 0 failures; 969 pack files, 0 unresolved); :tools:architecture-test:test (80 tests). XML/logs: `/tmp/claude-1000/tool-skills/round-3/validation/` and `required-checks-3c.log`.

## Documentation drift / owner requests

- `docs/vision/VISION.md` says CEL is unimplemented; the shipped engine and these existing aim-point expressions use CEL.
- `docs/contributing/vfx-team/sound.md` documents `type: play_sound` / `delay_ticks`; shipped v3 content uses `op: sound` / `at`.
- `make skill-trace` and `docs/contributing/vfx-team/skill-preview.md` reference the absent `SkillTraceExportScenarioTest`. This pass exports its op schedules and runtime JSON from the owned presentation scenario instead.
- `tools/vfx-shot/README.md` describes frame indices as ticks after cast, while captures include pre-cast frames. Comparisons must use `meta.json` / `framestamp.json` mapping.

依頼: ops-harness に `docs/vision/areas/skills-vfx.md` の現状へこの18ツールの素材別演出・音量測定・oracle更新を追記。Area spec is outside this task's owned paths.

## Required particle registry dependency



Sourced owner proposal: `/tmp/claude-1000/tool-skills/round-3/falling-dust-dependency.md` (`mean: 57.0273`, vanilla 26.3 FallingDustParticle constructor).

## polish3c: replay and eye clearance

The initial blade-trail emission stays in windup; subsequent emissions are in cast at relative tick 1, every 2, for trail_span-2. This removes the old-state detached trail / later-state particle ordering ambiguity while preserving every emission tick and count. No shared test/engine source changed. Only the 18 tool goldens moved; all 105 non-tool goldens remain byte-identical to 0c044c264. Detailed row classification and mechanical equality: `round-3/validation/golden-classification-3c.json`, `rig-golden-classification-3c.json`, and `mechanics-3c.json`. Rig/frame pose differences against the stale round-2 golden already exist in the inherited b60ed3c84 and are unchanged here.

Near-eye minimum for shovel-excavate, all three tiers: **0.954425 blocks**. Measured every particle `at=` in all four golden poses against eye=(0,1.62,0), matching the incoming ShippedNearEyeScenarioTest metric (held main_hand rigs are excluded and have a separate camera test). All 18 tool minima are >=0.8. Pickaxe's inner blade trail starts at v=0.5 rather than 0.4, retaining five samples; axe's inherited round-3 sweep already clears the rule. The owned real-cast scenario asserts every emission's distance. The shared new class is not on origin/main as of this verification, so it was not added or edited here.

The explicitly authorized `falling_dust: { mean: 57.0273, source: jar-26.3 }` lifetime registry entry is now present; all six shovel fall_* rows use vanilla falling_dust with coarse_dirt block data. The scenario asserts this real particle type. The 24-bit random / float32 expectation is 57.02730482816696 ticks; derivation: `/tmp/claude-1000/tool-skills/round-3/falling-dust-dependency.md`.

Round-0 sheet: `/tmp/claude-1000/tool-skills/round-0/tool-shovel-excavate.png`.
Final-source visual review is pending; final per-tier comparison sheets will be in `round-3/comparisons/tool-shovel-excavate-{stone,iron,diamond}.png`. The earlier axe-plank-wall-stone shot with -24% idle brightness is excluded; it is being reshot.
