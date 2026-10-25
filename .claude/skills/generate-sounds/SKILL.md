---
user-invocable: true
allowed-tools: Read, Write, Bash, Edit, Glob
description: Make Minecraft Java SFX for Astera with the free route only (ffmpeg synthesis or CC0 recordings), mono 44.1kHz OGG Vorbis, EBU R128 normalized, placed under content/sounds/ with a cue yaml. For weapon shots, skill casts, UI, match and norma stings. Use when user says "generate sound", "make SFX", "sound effect for X", or invokes /generate-sounds. No paid generators.
---

# /generate-sounds

Policy: [[adr/0256-free-sound-sourcing-and-one-cue-path]] (supersedes ADR-0026). Sounds come from exactly two
sources:

1. **Synthesis** with ffmpeg (`aevalsrc`, `anoisesrc`, filters, mixing). Preferred.
2. **CC0** recordings (freesound.org CC0 filter, public domain). Keep the source URL and licence in the commit.

**Never** use fal.ai / Stable Audio, ElevenLabs or any paid / credit-metered generator, and never CC-BY / NC
material. Do not add network calls or API keys to `tools/sound-gen/`.

## Workflow

1. Pick the category dir under `content/sounds/` (`ui`, `star`, `norma`, `match`, `combat`, `weapons`, `skills`,
   `status`) and a kebab-case name. Format and duration rules: `content/sounds/README.md`.
2. **Synthesized sound**: add a function to `tools/sound-gen/scripts/synth.sh` (function name = path with `/` and `-`
   turned into `_`, e.g. `weapons/gun-shot` is `weapons_gun_shot`) and list it in `ALL`. Build layers with `gen`
   (formula via `bell` / `pluck` / `brass` / `sq` / `sweep`) and `noise`, combine with `mix`, optionally `post`
   (echo, lowpass), then `finish <category/name> <wav-leaf>`. Run `tools/sound-gen/scripts/synth.sh category/name`.
   No binary is committed without its generator.
3. **CC0 sound**: download, convert with `tools/sound-gen/scripts/to_ogg.sh IN OUT.ogg`, and record URL + author +
   licence (CC0) in the commit message and in `content/sounds/README.md`'s "Third-party (CC0)" list.
4. **Cue yaml** next to the OGG, same basename: `id: "astera:<category>/<name>"`, `resource_key` equal to the id,
   `default_volume` / `default_pitch`, `category`. `:tools:resource-pack` turns `id` + sibling OGG into the
   `assets/astera/sounds.json` event; nothing else to register. To point an existing cue at the new sound, change only
   its `resource_key` (and drop `layers`).
5. QA: `ffprobe` must report vorbis / mono / 44100; check `volumedetect` (peaks below -1 dBFS, not near-silent);
   `./gradlew :tools:resource-pack:buildResourcePack` then `unzip -l build/resource-pack/astera.zip | grep sounds`
   and `unzip -p build/resource-pack/astera.zip assets/astera/sounds.json | jq .`.
6. Commit the OGGs, the yaml and the `synth.sh` change together.
