# shovel-earth-bridge — round 3 presentation review

No reference video. Branch: `wp/tool-skills-polish3b`.

Grey clay debris is replaced with brown coarse-dirt clumps and falling block_crumble fragments. Slow upward arcs and a sparse tan cloud sit above the deck; the shared white ring, sparks and end rods are removed.

The core occupies the same position and tick as a material emission on the actual affected footprint, rather than a shared fixed offset. The shatter core uses the same live aim-point expression as its mechanical row. Particle counts, spread, velocity, colour and dust scale are presentation only. All 18 skills retain their original metadata, cooldown, dimensions, state durations, damage and break/place rows verbatim.

Stone → iron → diamond increases core density and dust scale (0.30 → 0.45 → 0.60). Pickaxe: grey → beige → cyan. Axe: brown → warm wood → blonde wood. Shovel: dark brown → brown → tan; dust density is only 2 → 3 → 4 per lane.

Round-2 sheet: `/tmp/claude-1000/tool-skills/round-2/tool-shovel-earth-bridge.png`.
Round-3 sheet: `/tmp/claude-1000/tool-skills/round-3/tool-shovel-earth-bridge.png`.
All-tier captures: `/tmp/claude-1000/tool-skills/round-3/captures/shovel-earth-bridge-{stone,iron,diamond}/compare/`.

Review status: pending final real-client capture and tier comparison. The first exploratory axe capture was invalidated by edits during capture and is excluded from evidence.

## Sound measurement

Each skill now plays one matching vanilla material block-break cue, volume 1.0 and pitch 1.0, on its mechanical impact tick. Generic attack/wind, anvil, chime and delayed step layers were removed. Measured all four vanilla sample variants per material with ffmpeg `ebur128=peak=true,astats=metadata=1:reset=0`; one-second zero padding supplies the measurement window for short samples. Cue/ref delta is 0.0 LU for all twelve source samples. This measures source audio with authored gain/pitch, not a microphone recording of the client mix.

Raw ffmpeg logs, WAVs, original asset hashes and measurements: `/tmp/claude-1000/tool-skills/round-3/audio/`. Final per-skill sound/onset table: `/tmp/claude-1000/tool-skills/round-3/report.md`.

## Integration verification

`ToolSkillPresentationScenarioTest.tool families propagate material effects and tier light through the real cast path` casts all 18 shipped skills using the production loader, inheritance, player and gateways. It checks material motion, forbidden shared sparkles/crescents, footprint-aligned accents, core size, one unmodified vanilla cue and sound/impact op schedule equality. It writes `tool-op-<skill>.txt` alongside the per-tick scenario JSON traces.

The existing `:tools:game-sim:regenSkillOracle` path is used with a temporary content tree containing only the 18 tool skills. Only the resulting 18 tool goldens are copied back; no non-tool golden is generated or rewritten. Final class: `ShippedV3ContentScenarioTest`. Execution results pending shared VM Gradle slot.

## Documentation drift / owner requests

- `docs/vision/VISION.md` says CEL is unimplemented; the shipped engine and these existing aim-point expressions use CEL.
- `docs/contributing/vfx-team/sound.md` documents `type: play_sound` / `delay_ticks`; shipped v3 content uses `op: sound` / `at`.
- `make skill-trace` and `docs/contributing/vfx-team/skill-preview.md` reference the absent `SkillTraceExportScenarioTest`. This pass exports its op schedules and runtime JSON from the owned presentation scenario instead.
- `tools/vfx-shot/README.md` describes frame indices as ticks after cast, while captures include pre-cast frames. Comparisons must use `meta.json` / `framestamp.json` mapping.

依頼: ops-harness に `docs/vision/areas/skills-vfx.md` の現状へこの18ツールの素材別演出・音量測定・oracle更新を追記。Area spec is outside this task's owned paths.

## Required particle registry dependency

`falling_dust` was attempted but the production loader rejects it: no entry exists in `content/engine/particle-lifetimes.yaml`. That file is outside this task’s owned paths. The submitted content uses registered `block_crumble` fragments plus brown dust so the six shovel skills load. Literal vanilla `falling_dust` remains unfulfilled pending the owner’s sourced lifetime entry; this is not a full acceptance claim.

依頼: tool-skills kernel owner に `content/engine/particle-lifetimes.yaml` の vanilla 26.3 `falling_dust` lifetime を出典付きで追加し、ショベルの fall_* rows を falling_dust へ切替。

Sourced owner proposal: `/tmp/claude-1000/tool-skills/round-3/falling-dust-dependency.md` (`mean: 57.0273`, vanilla 26.3 FallingDustParticle constructor).
