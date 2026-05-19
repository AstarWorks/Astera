# content/sounds/

Output sink for SFX produced by the [`/generate-sounds`](../../.claude/skills/generate-sounds/SKILL.md)
skill (see also [`tools/sound-gen/`](../../tools/sound-gen/)). Files here are
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

**Not in scope here.** This directory is the raw-asset sink. Each feature
plugin that wants to reference a specific sound event registers it in
`assets/<namespace>/sounds.json` (resource-pack convention) when the
plugin lands a runtime hook for it.

For now: drop OGGs here, document the new file in your commit message,
and let the downstream feature plugin wave wire up `sounds.json` when it
adds the listener / play call.

## License & attribution

OGG files committed here are generated via **Stable Audio 2.5 on fal.ai**
under the fal.ai commercial license — redistributable as part of Astera
without per-file attribution. When Astera ships a resource pack to end
users, the pack credits should include:

```
Sound effects: Stable Audio 2.5 (Stability AI / fal.ai)
```

See [[adr/0026-sound-generation-policy]] for the tool-choice rationale and
[[adr/0018-ai-asset-creation-policy]] for the broader carve-out policy.

## See also

- `tools/sound-gen/README.md` — script-level docs
- `.claude/skills/generate-sounds/SKILL.md` — skill driver
- [[adr/0026-sound-generation-policy]] — tool decision
- [[adr/0018-ai-asset-creation-policy]] — policy
