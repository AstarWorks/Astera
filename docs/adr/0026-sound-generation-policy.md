---
status: Superseded
date: 2026-05-19
deciders: ryuzu
supersedes: null
superseded_by: 0256
---

# ADR-0026: Sound generation — Stable Audio 2.5 (fal.ai) for the Wave _assets sound track

## Status

Superseded by [[adr/0267-free-sound-sourcing-and-one-cue-path]] (2026-10-26): sourcing is ffmpeg synthesis and CC0 only; no paid generation.

## Context

[[adr/0018-ai-asset-creation-policy]] carved out **texture / 3D model / sound**
generation as a pre-Phase-6 exception to [[adr/0010-postpone-generative-ai-to-phase-6]].
Texture (Codex CLI) and model (ASDSL DSL via Opus 4.7 + studio) tooling has
been operational for several months. **Sound generation tooling had not been
chosen.**

Astera needs Minecraft Java SFX (OGG Vorbis, mono, 44.1 kHz) for ~20–40
gameplay-specific events that vanilla Minecraft does not cover:

- weapon fire variants (gun shots, wand cast, bow types beyond vanilla)
- skill cast cues (6+ archetypes × charged variants)
- status-effect ticks (burn, shock, etc. — vanilla covers a few, not all)
- match-phase stings (countdown, sudden death, victory)

Vanilla Minecraft already ships ~1000+ sound events and covers UI, ambient,
blocks, common entity sounds adequately. The Astera-specific gap is narrow
(20–40 files).

2026-05 web research (sources in §References) surveyed the production AI
SFX landscape:

| Tool | Quality | License for resource-pack use | Notes |
|---|---|---|---|
| **Stable Audio 2.5** (Stability AI, fal.ai-hosted) | ★★★★★ | Commercial OK explicitly (fal.ai TOS) | $0.20/clip flat regardless of duration |
| **Stable Audio Open Small / 1.0** (open weights) | ★★★★ | Stability Community License — free if org revenue <$1M, attribution required | Local CPU/GPU; 11s / 47s caps |
| **ElevenLabs Sound Effects v2** | ★★★★★ | **Standalone redistribution prohibited** per Use Policy §9(c) — Minecraft resource pack is the exact pattern the clause targets | 100 SFX/month free tier (auto-duration) |
| **TangoFlux / UniAudio / WavTokenizer-LM** | ★★★★ | All open weights inherit NC license from training data (WavCaps, AudioSet) | Production-ready architecturally, license-blocked |
| **Meta AudioGen / AudioCraft** | ★★★ | CC-BY-NC | NC, plus 16 kHz quality dated |
| **jsfxr / bfxr (procedural)** | ★★ (retro only) | MIT-style | 8-bit chiptune use cases only |
| **freesound.org CC0 mapping** | varies | CC0 | Complementary, not generative |

The "LLM-style audio token" school (autoregressive transformers over neural
codec tokens — TangoFlux ICLR 2026, UniAudio ICML 2024, WavTokenizer ICLR 2025)
is architecturally SOTA but every public open-weights model is non-commercial.
This branch is production-shippable in 2026-late or 2027 when someone retrains
on CC0-only data.

## Decision

Adopt **Stable Audio 2.5 via the fal.ai HTTP API** as the primary sound
generation tool for the Wave _assets sound track.

Concretely:

- New skill `.claude/skills/generate-sounds/` mirrors the
  `.claude/skills/generate-textures/` pattern (env-var auth + CLI wrapper + content/ output sink).
- New tooling under `tools/sound-gen/`:
  - `fal_generate.sh` — fal.ai queue submit → poll → download flow (curl + jq).
  - `to_ogg.sh` — ffmpeg post-process: force mono, resample to 44.1 kHz, EBU R128 loudnorm, OGG Vorbis encode at `-q:a 5`, format assertion via ffprobe.
  - `dry_run.sh` — sine-wave WAV via sox/ffmpeg for offline pipeline smoke (no API).
  - `specs/example.yaml` — batch spec template.
- New output sink `content/sounds/` with categories `weapons/`, `skills/`, `status/`, `match/`, `ui/` (extend as needed).
- Auth env var: `FAL_KEY` (per fal.ai docs; lives in shell rc, never in repo).
- ffmpeg ≥4.2 required (for the `loudnorm` filter EBU R128 mode).

Stable Audio Open Small (local CPU path) is **deferred** to a follow-up: the
skill accepts a `--local` flag but currently routes to dry-run with a warning.
The full local PyTorch path lands in a later Wave _assets-sfx wave if cloud
dependence becomes a problem.

This wave lands **the tool only**. Actual SFX generation (20–40 OGG files
filling `content/sounds/`) is a separate task that will invoke the skill
with the example spec yaml or analogues.

## Consequences

### Positive

- **License clarity**: fal.ai commercial-use is explicit. Astera can ship
  generated OGGs inside the JAR or as a resource pack without legal grey area.
- **Cost is bounded and predictable**: $0.20/clip × 100 SFX = $20. Even a
  full Wave _assets-sfx run is under $10.
- **Setup is minimal**: one env var (`FAL_KEY`), one CLI dependency
  (`ffmpeg`), one `curl` POST per clip. No PyTorch / VRAM / driver pain.
- **Pattern parity**: identical to texture-gen (env-var + script + content/
  sink). Future contributors recognize the shape immediately.
- **Format assertions in `to_ogg.sh`** guarantee every committed OGG is
  Minecraft-spec-compliant (mono / 44.1 kHz / vorbis). Bad files cannot
  silently sneak in.

### Negative / Trade-offs

- **Cloud dependence**: if fal.ai is down or rate-limits us, sound generation
  stops. Texture-gen has the same property (Codex CLI). Acceptable for a
  pre-release iteration tool.
- **Key custody**: `FAL_KEY` leakage drains the owner's fal.ai balance. The
  same applies to `OPENAI_API_KEY` for texture-gen — mitigation pattern is
  the same (shell-rc only, never committed, `.gitignore` covers `.env`).
- **Non-determinism**: Stable Audio 2.5 is stochastic. Re-runs produce
  different outputs even with the same prompt. Accepted SFX are committed
  as OGG files so the project does not depend on regeneration reproducibility.

### Mitigations

- Skill ships `--dry-run` flag so the post-process pipeline can be exercised
  without API access (sox/ffmpeg sine source).
- `content/sounds/README.md` documents the regeneration cost (per-clip flat)
  so contributors understand the trade-off before running a batch.
- ADR-0026-bis will be drafted if any of:
  - fal.ai changes pricing model away from flat per-clip
  - Astera org revenue crosses $1M (re-evaluate Stability Community License
    angle for a Stable Audio Open Small local path)
  - LLM-style audio LM open weights ship with permissive licenses (revisit
    TangoFlux / UniAudio / successor)

## Alternatives Considered

- **ElevenLabs Sound Effects v2**: best UX, mature API, free tier covers
  ~100 SFX/month at auto-duration (~ enough for Wave _assets-sfx). **Declined**:
  Use Policy §9(c) prohibits "standalone basis… libraries, or other
  collections of sounds" — a Minecraft resource pack is the exact pattern
  targeted. Game-runtime integration is grey but resource-pack distribution
  is high risk. Not worth the legal ambiguity for a $20 alternative.
- **Stable Audio Open 1.0 / Small (local)**: free + commercial OK
  (Stability Community License if revenue <$1M). **Deferred** rather than
  declined — the cloud path is faster to bootstrap; the local path can be
  added later behind `--local` without an API change.
- **TangoFlux / UniAudio / LLM-style audio LMs**: SOTA per benchmarks, but
  every viable open-weights model is non-commercial-licensed (training data
  contamination). **Deferred** to late 2026 / 2027 watch.
- **Meta AudioGen / AudioCraft**: CC-BY-NC and 16 kHz quality. **Declined.**
- **jsfxr / bfxr** (procedural 8-bit): MIT-licensed and instant. Kept as a
  complementary option for explicitly retro/chiptune SFX, but not the
  primary tool. May be added under `tools/sound-gen/scripts/` later
  without a new ADR (procedural tools are unambiguously fair game).
- **freesound.org CC0 mapping pipeline**: complementary, not generative.
  Out of scope here.
- **Skip sound generation entirely; use vanilla MC sounds + Astera-internal
  Foley recording**: vanilla covers ~80% of needs but the gun/wand/specific-skill
  niche remains. Foley recording is a separate skill stack the org does not
  have. **Declined** — the gap is real and Stable Audio 2.5 closes it for $20.

## References

- 関連 ADR:
  - [[adr/0010-postpone-generative-ai-to-phase-6]] — original deferral
  - [[adr/0017-adopt-datadriven-contents-pattern]] — data-driven content philosophy
  - [[adr/0018-ai-asset-creation-policy]] — generative-AI carve-out for assets
- 関連 docs:
  - `tools/sound-gen/README.md` — operator-facing setup
  - `.claude/skills/generate-sounds/SKILL.md` — skill driver
  - `content/sounds/README.md` — output conventions
- 外部リンク (web research, fetched 2026-05-19):
  - https://fal.ai/models/fal-ai/stable-audio-25/text-to-audio
  - https://stability.ai/news-updates/stability-ai-introduces-stable-audio-25-the-first-audio-model-built-for-enterprise-sound-production-at-scale
  - https://stability.ai/license — Community License terms (Stable Audio Open path)
  - https://huggingface.co/stabilityai/stable-audio-open-1.0
  - https://huggingface.co/stabilityai/stable-audio-open-small
  - https://elevenlabs.io/use-policy — §9(c) standalone-distribution clause (declination rationale)
  - https://elevenlabs.io/sound-effects-terms
  - https://github.com/declare-lab/TangoFlux — LLM-style audio LM (NC license, deferred)
  - https://github.com/jishengpeng/WavTokenizer — neural codec for AR audio LMs
  - https://minecraft.fandom.com/wiki/Sounds.json — Minecraft Java sounds.json contract (mono requirement for positional sounds)
