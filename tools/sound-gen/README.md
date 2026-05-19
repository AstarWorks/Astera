# tools/sound-gen/

Designer-side scaffolding for producing Minecraft sound effects. **Not part
of the Astera runtime or `./gradlew build`.** Outputs are committed OGG
Vorbis files under `content/sounds/`.

See [[adr/0018-ai-asset-creation-policy]] for the policy that authorizes
this directory and [[adr/0026-sound-generation-policy]] for the tool choice
rationale.

## Tool roles

| Tool | Used for | Authorization |
|---|---|---|
| **Stable Audio 2.5 (fal.ai)** | Weapon shots, skill cast cues, status blips, match phase stings — anything Stable Audio 2.5 excels at | ADR-0026 |
| **ffmpeg / ffprobe** | Resample to 44.1kHz, force mono, EBU R128 loudness normalize, OGG Vorbis encode, format assertion | ADR-0018 rule 4 (post-process) |
| **sox** (optional) | Sine-wave test signal for `dry_run.sh` | dev-only convenience |
| **Claude (vision)** | Waveform PNG + metadata audit before commit | not a tool, an operator |

## Environment

Set these in your shell rc (`~/.bashrc` / `~/.zshrc`); none of them are
required by the Astera build, only by the `scripts/` here.

```bash
# fal.ai API key — get from https://fal.ai/dashboard/keys
# (NOT committed to repo; lives in your shell only)
export FAL_KEY="..."
```

ffmpeg version requirements:

```bash
# Minimum: 4.2 (for the loudnorm filter EBU R128 mode)
# Recommended: 6.x

# Debian/Ubuntu:
sudo apt install -y ffmpeg
# macOS (Homebrew):
brew install ffmpeg
# Verify:
ffmpeg -version | head -1
ffmpeg -filters 2>/dev/null | grep loudnorm   # must be present
```

Verify:

```bash
./scripts/dry_run.sh 0.5 /tmp/sine.wav     # produces a 0.5s sine wave WAV
./scripts/to_ogg.sh /tmp/sine.wav /tmp/sine.ogg   # transcodes to OGG mono
ffprobe -v error -show_streams /tmp/sine.ogg | grep -E 'channels|sample_rate|codec_name'
# expected: codec_name=vorbis, sample_rate=44100, channels=1
```

## Typical flow (AI generation path)

1. Decide what SFX you need (e.g. pistol gunshot, magic wand cast, burning
   status blip). Write a short, evocative prompt for each.
2. Invoke `/generate-sounds` skill via Claude Code, or run scripts manually:
   ```bash
   bash scripts/fal_generate.sh "pistol gunshot, short, punchy" 0.5 /tmp/raw.wav
   bash scripts/to_ogg.sh /tmp/raw.wav content/sounds/weapons/pistol-fire.ogg
   ```
3. The skill (or you) inspects the resulting OGG via `ffprobe` (metadata)
   and `ffmpeg showwavespic` (waveform PNG) — see [`generate-sounds/SKILL.md`](../../.claude/skills/generate-sounds/SKILL.md).
4. Approved OGGs commit under `content/sounds/<category>/`. PR diff is
   inspected via `ffprobe` (binary diff is not human-readable; rely on the
   filename + commit message).

## Typical flow (batch from spec yaml)

For waves that need many SFX at once (e.g. 20-40 for Wave _assets-sfx):

1. Write a spec yaml listing each entry (`specs/example.yaml` for shape).
2. Run the skill against the spec:
   ```
   /generate-sounds --spec tools/sound-gen/specs/wave-N-sounds.yaml
   ```
3. Skill loops sequentially, prints running cost.

## Cost reference

fal.ai Stable Audio 2.5 charges **$0.20 per generation flat**, regardless of
clip duration (1s and 190s cost the same). 100 SFX ≈ $20. Verified
2026-05-19 at https://fal.ai/models/fal-ai/stable-audio-25/text-to-audio.

## Output size reference

Mono 44.1 kHz OGG Vorbis at `-q:a 5` (~96 kbps):

- 0.5s → ~8–10 KB
- 1.0s → ~15–20 KB
- 3.0s → ~40–55 KB

## Boundary reminders (from ADR-0018 + ADR-0026)

- ❌ Do NOT add any script here to `plugin/**` build dependencies.
- ❌ Do NOT have CI invoke anything here. CI runs `./gradlew check` only.
- ❌ Do NOT commit `FAL_KEY` or any other API credentials. `.gitignore`
  excludes `out/` and `*.tmp.wav`; secrets stay in your shell.
- ✅ DO commit OGG files under `content/sounds/` — they're the deliverable.
- ✅ DO commit spec yamls under `specs/` so batch runs are reproducible.
- ✅ DO commit script changes here so the next contributor reproduces.

## Layout

```
tools/sound-gen/
├── README.md            (this file)
├── .gitignore
├── scripts/
│   ├── fal_generate.sh      fal.ai Stable Audio 2.5 wrapper (curl + jq)
│   ├── to_ogg.sh            ffmpeg pipeline → mono 44.1kHz OGG Vorbis
│   └── dry_run.sh           sine-wave test WAV generator (offline smoke)
└── specs/
    └── example.yaml         template for batch generation specs
```

## See also

- [[adr/0026-sound-generation-policy]] — tool choice rationale (Stable Audio
  2.5 accepted, ElevenLabs declined per ToS, LLM-style audio LMs deferred)
- [[adr/0018-ai-asset-creation-policy]] — the policy
- [[adr/0010-postpone-generative-ai-to-phase-6]] — original deferral
- `content/sounds/README.md` — output-side conventions
- `.claude/skills/generate-sounds/SKILL.md` — skill driver
