---
status: Accepted
date: 2026-10-29
deciders: ryuzu
supersedes: 0308
superseded_by: null
---

# ADR-0309: Intent-preserving chat translation and bounded dev inference

## Status

Accepted (founder direction 2026-10-29; follow-up task permits the next free number).
ADR-0308 remains immutable; this decision replaces its model defaults and prompt
location. Its relay, safety, preferences and separate-process design still apply.

## Context

Founder: 「マジで意図が伝わるくらいの翻訳精度でいいから」.
CPU benchmarks found literal translations of gaming slang, including 草 → Grass
and nice clutch → a bag. In the pinned llama.cpp build, Jinja is enabled by default:
`--chat-template gemma` supplies literal text rather than selecting turn framing.
The remote dev watcher did not build, ship or start the optional translator service.
Dev has eight cores and needs resources left for Minecraft.

## Decision

Use HY-MT1.5-1.8B Q4_K_M by default on CPU/dev and TranslateGemma 4B Q4_K_M in
the RTX 3060 overlay. Remove LFM2 from supported profiles. Select Gemma framing
with `--no-jinja --chat-template gemma`, verified through `/apply-template`.
Keep model URLs/revisions and image digests pinned as in ADR-0308.

Store casual Minecraft PvP context, per-profile prompt templates, direction-specific
few-shot examples and a small pair-specific slang dictionary in the shipped chat
YAML. Match longest whole messages or Unicode-bounded whole tokens, ignoring case.
Perform substitution after name/URL protection and before inference. Dictionary-only
messages bypass inference; mixed messages pass substituted target terms to the
model. Fullwidth laughter `ｗ` is Japanese for source detection. Do not maintain
per-sentence fixes or model-specific slang code. The 60-line benchmark reads the
same configuration and reports keyword PASS/FAIL, uncached model and pipeline
latencies. Keyword success does not establish complete semantic correctness.

The remote dev overlay clears the translator profile, selects HY-MT/four threads,
limits it to four CPUs and 3 GiB and sets backend translation URLs. The watcher
builds and ships its image on the build machine, then starts it without dependencies
or a remote build before recreating backends. Model readiness never gates Minecraft.
Refresh the watcher runtime copy before the manager's push; no worker deployment.

## Consequences

### Positive

- Short unambiguous slang preserves its gaming intent without inference latency.
- Profiles, prompts and glossary remain configurable without changing relay logic.
- Dev translation receives a bounded allocation and the normal autodeploy workflow.

### Negative / Trade-offs

- Unicode token boundaries deliberately leave words embedded in Japanese sentences
  to the model. Mixed-language model results still require human review.
- CPU Gemma can exceed the plugin's 1500 ms budget; GPU throughput is unmeasured.
- Bench needs PyYAML in addition to Python. Remote overlay needs Compose `!reset`.
- The active watcher is copied outside Git and needs one manager-controlled refresh.

### Mitigations

- Original-text fallback, hover, typed failures and per-viewer off switch remain.
- Scenario tests load real shipped config and cross two backend relays; the bench
  scores slang, iron/lag and Korean/Spanish directions through both real models.
- Manager runs the live probe and records deployed SHA and model readiness.

## Alternatives Considered

- **LFM2:** lower latency did not compensate for lost gaming intent.
- **Model-only slang handling:** unreliable even after fixing turn framing.
- **Dedicated placeholders for substituted slang:** Gemma dropped them in mixed
  messages; direct term substitution is smaller and preserves the pre-model glossary.
- **Build on dev:** competes with the running Minecraft servers; reuse image shipping.

## References

- [[adr/0308-chat-translation-server]]
- [Operator guide and benchmark](../../docker/translator/README.md)
