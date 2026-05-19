---
user-invocable: true
allowed-tools: Read, Write, Bash, Edit, Glob, AskUserQuestion, WebFetch
description: Generate Minecraft Java SFX (OGG Vorbis, mono, 44.1kHz) via fal.ai Stable Audio 2.5, normalize loudness with ffmpeg EBU R128, and place under content/sounds/. For weapon shots, skill cast cues, status effect blips, match phase stings. Use when user says "generate sound", "make SFX", "sound effect for X", or invokes /generate-sounds.
---

# /generate-sounds

Driver for the sound pipeline defined in [[adr/0018-ai-asset-creation-policy]]
and [[adr/0026-sound-generation-policy]]. Produces OGG Vorbis files committed
under `content/sounds/<category>/`.

Quality QA is done by Claude (metadata + waveform PNG inspection) by default;
ask the user only when uncertain. Sound playback is not in scope — we verify
that `ffprobe` reports the correct format (mono, 44.1kHz, codec=vorbis) and
that the waveform looks plausible.

## Trigger

```
/generate-sounds <category> <prompt> [--duration 0.7] [--target PATH] [--dry-run] [--local]
/generate-sounds --spec tools/sound-gen/specs/<file>.yaml
```

Examples:

```
/generate-sounds weapons "pistol gunshot, short, punchy" --duration 0.5
/generate-sounds skills "magic wand cast, ascending shimmer" --duration 1.2
/generate-sounds --spec tools/sound-gen/specs/example.yaml
```

Categories: `weapons`, `skills`, `status`, `match`, `ui` (subdirs under
`content/sounds/`). Auto-creates the dir if missing.

## Workflow

### Step 0: Validate inputs and detect mode

1. Parse `category` (or read spec yaml). Refuse unknown categories with a
   helpful error suggesting `weapons|skills|status|match|ui`.
2. Default `--duration` to a sensible value based on category:
   - `weapons` → 0.5s (gun/sword swing)
   - `skills` → 1.2s (cast cue)
   - `status` → 0.6s (effect blip)
   - `match` → 1.5s (phase sting)
   - `ui` → 0.3s (click)
3. Resolve `--target`:
   - if absent → `content/sounds/<category>/<slugified-prompt>.ogg`
   - validate it's under `content/sounds/` (refuse otherwise)
4. Detect mode:
   - if `--dry-run` → use `tools/sound-gen/scripts/dry_run.sh` (sine tone, no API)
   - if `--local` → reserved for future Stable Audio Open Small path (Phase B
     of Wave _assets-sfx; not implemented in this skill version, fall back
     to dry-run with warning)
   - else → call `fal_generate.sh` (requires `FAL_KEY` env)
5. **Pre-flight**:
   - `command -v ffmpeg` — fail with "install ffmpeg ≥4.2 (loudnorm filter required)"
   - `command -v ffprobe` — fail with similar message
   - if not dry-run: `[ -n "$FAL_KEY" ]` — fail with "set FAL_KEY in shell rc; see tools/sound-gen/README.md"

### Step A: Generate (fal.ai Stable Audio 2.5)

```bash
WORK=$(mktemp -d -t soundgen.XXXX)
bash tools/sound-gen/scripts/fal_generate.sh \
    "$PROMPT" "$DURATION" "$WORK/raw.wav"
```

On non-zero exit, surface `$WORK/fal.stderr.log` and abort. The wrapper
handles the queue submit → poll → download flow against
`https://queue.fal.run/fal-ai/stable-audio-25/text-to-audio`.

Cost reminder: $0.20/clip flat at fal.ai (clip length does not change price).
Drop a one-line note in skill output: `[fal.ai] estimated cost: $0.20`.

### Step D: Dry-run path (no API)

```bash
WORK=$(mktemp -d -t soundgen.XXXX)
bash tools/sound-gen/scripts/dry_run.sh "$DURATION" "$WORK/raw.wav"
```

Produces a sine-wave sweep at the given duration. Useful for smoke testing
the post-process pipeline without burning credits.

### Step 1: Post-process → mono 44.1kHz OGG Vorbis

```bash
bash tools/sound-gen/scripts/to_ogg.sh "$WORK/raw.wav" "$WORK/final.ogg"
```

This script:
- forces `-ac 1` (mono — required for Minecraft 3D positional sounds)
- resamples to `-ar 44100`
- applies `loudnorm=I=-16:TP=-1.5:LRA=11` (EBU R128 broadcast target)
- encodes OGG Vorbis at `-q:a 5` (~96 kbps)
- assertions: `ffprobe` reports `channels=1`, `sample_rate=44100`, `codec_name=vorbis`

### Step 2: Inspection (Claude vision-style audit on metadata + waveform)

1. `ffprobe -v error -print_format json -show_streams "$WORK/final.ogg"` →
   JSON. Read and confirm metadata matches expectations.
2. Generate a low-res waveform PNG for visual QA:
   ```bash
   ffmpeg -hide_banner -loglevel error -i "$WORK/final.ogg" \
       -filter_complex "showwavespic=s=512x128:colors=0x4A90E2" \
       -frames:v 1 "$WORK/waveform.png"
   ```
3. Read the waveform PNG with the Read tool (multimodal). Evaluate:
   - **Envelope match**: does the shape match the prompt? (gunshot → sharp
     attack + decay; cast → gentle build; explosion → loud peak + tail)
   - **Clipping**: any flat-top regions at ±max? (loudnorm should prevent
     this; if present, abort with stderr context)
   - **Silence**: is the clip mostly silent? (mean amplitude < 0.05 → bad)
4. Decision:
   - **accept** → Step 3
   - **regenerate** (max 1 retry, same prompt + new seed)
   - **reject** → keep `$WORK/` for the user to inspect, abort

### Step 3: Place + summary

1. `cp "$WORK/final.ogg" "$TARGET"` (refuse overwrite without `AskUserQuestion`
   confirmation).
2. Print summary:
   - file path, size, duration, fal.ai cost ($0.20)
   - waveform PNG path (in `$WORK/`, preserved)
3. Suggest commit message:
   `feat(sounds): add <category>/<name>.ogg (Stable Audio 2.5, reviewed)`
4. Do NOT auto-commit; user reviews and commits.

### Spec yaml mode

When invoked with `--spec PATH`, parse the yaml (see
`tools/sound-gen/specs/example.yaml` for shape) and loop over entries.
For batch runs:
- run each generation sequentially (fal.ai rate limits favor 1-at-a-time)
- print running cost total
- on first failure, ask via `AskUserQuestion` whether to continue or abort

## Failure modes & fallbacks

- **`FAL_KEY` not set**: fail clearly, link to README; suggest `--dry-run` for
  pipeline smoke.
- **fal.ai 4xx (auth)** → re-check `FAL_KEY`, abort.
- **fal.ai 5xx / timeout** → 1 retry with 2s backoff, then abort.
- **ffmpeg version too old** (loudnorm filter missing) → README pointer to
  upgrade (Debian: `apt install ffmpeg`, macOS: `brew upgrade ffmpeg`).
- **Output mostly silent** → likely a model degenerate, regenerate once with
  new seed; if still bad, surface to user.

## Constraints (from ADR-0018 + ADR-0026)

- **Never** modify anything under `plugin/**` from this skill.
- **Never** add fal.ai or any audio API as a Gradle dependency.
- **Always** treat the working dir (`$WORK`) as transient; the only
  persistent artifacts are OGG files under `content/sounds/` and (optionally)
  spec yamls under `tools/sound-gen/specs/`.
- **Never** commit FAL_KEY (it lives in shell rc; the `.gitignore` here
  doubly protects against `.env` files).
- **Mono only**: 3D-positional sounds in Minecraft must be mono. Stereo would
  defeat 3D attenuation. `to_ogg.sh` enforces this.

## See also

- `tools/sound-gen/README.md` — script-level docs + env setup
- `content/sounds/README.md` — output conventions + sounds.json hint
- [[adr/0026-sound-generation-policy]] — tool choice rationale
- [[adr/0018-ai-asset-creation-policy]] — policy carve-out
- [[adr/0010-postpone-generative-ai-to-phase-6]] — original deferral
