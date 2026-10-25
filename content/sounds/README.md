# content/sounds/

Output sink for SFX (ffmpeg-synthesised or CC0, see below) and the YAML sound cues the game plays
(see also [`tools/sound-gen/`](../../tools/sound-gen/)). Files here are
committed as the deliverable; they ship with Astera.

## Naming convention

```
content/sounds/<category>/<kebab-case-name>.ogg
```

Categories (extend as needed; keep flat — no nested subdirs):

| Category | Used for | Example |
|---|---|---|
| `weapons/` | weapon-specific fire / swing / impact | `pistol-fire.ogg`, `katana-swing.ogg` |
| `skills/` | skill cast cues, sustain loops, end stings | `wand-cast.ogg`, `dash-launch.ogg` |
| `status/` | status effect ticks, apply / expire blips | `burn-tick.ogg`, `shock-apply.ogg` |
| `match/` | match phase changes, countdown, victory | `phase-change.ogg`, `victory.ogg` |
| `ui/` | GUI / inventory / menu interactions | `confirm.ogg`, `cancel.ogg` |

Naming rules:

- **kebab-case ASCII only**: lowercase letters, digits, `-`. No `_`, no
  uppercase, no non-ASCII characters.
- **Descriptive but short**: `pistol-fire.ogg`, not `the-pistol-firing-noise.ogg`.
- **Variant suffix with `-NN`**: when multiple takes of the same SFX
  (e.g., gun fire variations) → `pistol-fire-01.ogg`, `pistol-fire-02.ogg`.

## Format

**Hard requirements** (enforced by `tools/sound-gen/scripts/to_ogg.sh`):

| Field | Value | Why |
|---|---|---|
| Container | OGG | Minecraft Java reads only OGG Vorbis for resource packs |
| Codec | Vorbis | (same) |
| Channels | **1 (mono)** | 3D-positional sounds in Minecraft require mono for proper attenuation. Stereo defeats distance falloff. |
| Sample rate | 44100 Hz | Standard; mismatches cause pitch shift |
| Bitrate | ~96 kbps (`-q:a 5`) | Sweet spot for SFX clarity vs size |

Quick check on a single file:

```bash
ffprobe -v error -show_streams content/sounds/weapons/pistol-fire.ogg \
    | grep -E 'codec_name|channels|sample_rate'
# expect: codec_name=vorbis, channels=1, sample_rate=44100
```

**Stereo allowed only for**: non-positional ambient or music (none expected
in Phase 1.5). If you find yourself wanting stereo for an SFX, the answer
is almost always "no — make it mono".

## Duration guidelines

Per-category typical durations:

| Category | Typical | Hard cap |
|---|---|---|
| `weapons/` | 0.2 – 0.8 s | 1.5 s |
| `skills/` | 0.5 – 1.5 s | 3.0 s |
| `status/` | 0.3 – 0.8 s | 1.0 s |
| `match/` | 0.8 – 2.0 s | 3.0 s |
| `ui/` | 0.1 – 0.4 s | 0.6 s |

If you need a longer clip (boss BGM, ambient zone loop), it's almost
certainly out of scope for this directory — open a separate ADR first.

## File size sanity

Mono 44.1 kHz OGG Vorbis at `-q:a 5`:

- 0.5 s → 8 – 10 KB
- 1.0 s → 15 – 20 KB
- 3.0 s → 40 – 55 KB

If a committed file is dramatically larger (≥200 KB for a 1-second clip),
something's wrong — re-encode via `tools/sound-gen/scripts/to_ogg.sh`.

## sounds.json registration

Automatic. `:tools:resource-pack` (`ResourcePackBuilder.addSounds`) packs every `*.ogg` as
`assets/astera/sounds/<path>.ogg` and writes `assets/astera/sounds.json`: a cue yaml with a sibling OGG of the same
basename becomes the event named by its `id` (`astera:match/win` -> event `match/win`); a bare OGG is its own event.
A cue yaml without an OGG is skipped.

## Sound cues (ADR-0256)

Game code plays a **cue id**, never a raw sound key. Each `*.yaml` here defines one cue:
`id` (`astera:<area>/<name>`), a primary `resource_key`, `default_volume` / `default_pitch`, and optional
`layers` (more sounds that start together). A cue whose `resource_key` is its own `astera:` id plays the Astera OGG next to it
(synthesized by `tools/sound-gen/scripts/synth.sh`); cues still on a `minecraft:` key are vanilla placeholders.
Swapping one for the other is a yaml edit, no code. Cue ids are listed in `SoundCues.kt` (core); `MilestoneSoundScenarioTest` proves each one plays.

## License & attribution

Sourcing is **ffmpeg synthesis or CC0 only** ([[adr/0256-free-sound-sourcing-and-one-cue-path]]); no paid or
credit-metered generator. Keep the synthesis script (or the CC0 source URL and licence) in the same commit as the
OGG. All shipped OGGs are synthesized (`tools/sound-gen/scripts/synth.sh`, ffmpeg formulas, no samples) and
owned by the project; no third-party (CC0) recordings ship today. Vanilla sound events used as placeholders are the client's own and are not redistributed.

## See also

- `tools/sound-gen/README.md` — script-level docs
- `.claude/skills/generate-sounds/SKILL.md` — skill driver
- [[adr/0256-free-sound-sourcing-and-one-cue-path]] — sourcing + cue path (supersedes ADR-0026)
- [[adr/0018-ai-asset-creation-policy]] — policy
