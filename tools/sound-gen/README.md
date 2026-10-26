# tools/sound-gen/

Designer-side scripts that produce Astera's sound effects. **Not part of the Astera runtime or `./gradlew build`.**
Outputs are committed OGG Vorbis files under `content/sounds/`; the scripts are what make them reproducible.

Policy ([[adr/0267-free-sound-sourcing-and-one-cue-path]], supersedes ADR-0026): **ffmpeg synthesis and CC0 only.**
No paid or credit-metered generator (fal.ai, ElevenLabs, ...), no API keys, no network.

## Scripts

| Script | Use |
|---|---|
| `scripts/synth.sh [name...]` | Synthesizes every shipped SFX from formulas (sine / bell partials / brass-ish / filtered noise, envelopes, sweeps, echo), normalizes loudness and writes `content/sounds/<name>.ogg`. No args = all. `ASTERA_SOUNDS_DIR=/tmp/x` writes elsewhere to audition. |
| `scripts/music_synth.py [id...]` | Synthesizes the BGM tracks of `content/music/` (numpy + ffmpeg, seamless loops) and prints each length in ticks, which must equal `duration_ticks` in the track yaml. |
| `scripts/to_ogg.sh IN OUT.ogg` | Converts a CC0 recording (any format) to the required OGG: mono, 44.1 kHz, loudnorm, Vorbis `-q:a 5`, with a format assertion. |
| `scripts/dry_run.sh DUR OUT.wav` | Sine test tone, for smoke-testing the ffmpeg install. |

Requirements: ffmpeg >= 4.2 with `libvorbis` and `loudnorm`, `jq` (`ffmpeg -filters | grep loudnorm`).

## Loudness

`synth.sh` normalizes with EBU R128 two-pass linear loudnorm to -18 LUFS integrated, true peak -1.5 dBTP. Clips under
0.6 s (clicks, ticks) cannot be gated by BS.1770, so they are peak-normalized to -3 dBFS. The relative level of each
cue is set by `default_volume` in its yaml, not by the file.

## Adding a synthesized sound

1. Add a function to `synth.sh` named after the path (`weapons/gun-shot` -> `weapons_gun_shot`) and add the path to
   `ALL`. Use `gen` (aevalsrc formula; helpers `bell`, `pluck`, `brass`, `sq`, `sweep`) and `noise` (seeded) for layers,
   `mix out dur file:delay_ms:gain ...` to stack them, `post` for echo / filters, then `finish <path> <leaf>`.
2. `scripts/synth.sh <path>`.
3. Add `content/sounds/<path>.yaml` (cue: `id: "astera:<path>"`, `resource_key` = id). `:tools:resource-pack` generates
   the `sounds.json` event from the cue yaml + the sibling OGG.

## Third-party (CC0) sounds

Allowed only if CC0 / public domain. Convert with `to_ogg.sh`, and list file, source URL, author and licence in
`content/sounds/README.md` (no such sound ships today; everything is synthesized).

## Boundaries

- Do NOT add anything here to `plugin/**` build dependencies, and do not have CI invoke it. CI builds the pack from
  the committed OGGs.
- Do commit OGGs together with the `synth.sh` change that produces them.
- No binary without a generator or a recorded CC0 source.

## See also

- [[adr/0267-free-sound-sourcing-and-one-cue-path]] - policy and cue path
- `content/sounds/README.md` - output-side conventions
- `.claude/skills/generate-sounds/SKILL.md` - the skill driver
