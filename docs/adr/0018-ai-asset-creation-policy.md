---
status: Accepted (Studio extracted to AsteraStudio repo on 2026-05-20 per ADR-0079; this file is preserved here for historical cross-reference, primary copy now lives in AsteraStudio/docs/adr/)
date: 2026-05-17
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0018: AI Asset Creation Policy (Complement to ADR-0010)

## Status

Accepted

## Context

[[adr/0010-postpone-generative-ai-to-phase-6]] deferred "generative AI
pipelines" to Phase 6. Re-reading that ADR's body shows the concern was
specifically about **API utilization cost** and **safety review** for a
runtime/build pipeline that fabricates content automatically. It was not
intended to ban contributors from using AI image generators *as a designer
tool* on their own machines.

The ambiguity has become load-bearing now that the texture work is starting:

- Pixel Composer (PXC, MIT-licensed source, paid Steam binary) is a
  node-based procedural pixel-art tool that maps cleanly to AI-driven editing.
  A designer running PXC on their laptop and committing the output `*.png`
  is indistinguishable, from Astera's standpoint, from using Photoshop.
- The 2026 state of AI image generation has shifted: ChatGPT (GPT-5.4 pro)
  can produce 100 pixel-grid-aligned 32×32 RPG icons in a single prompt with
  consistent style (see Step 0 PoC). spritecook.ai exposes sprite-sheet
  generation via an MCP server. Codex CLI (OpenAI) is a usable local
  invocation surface. The "AI Overviews of Minecraft texture generation"
  landscape moved while ADR-0010 was being written.
- The Minecraft texture surface area for Astera is large (weapons + items +
  blocks + entities + animations, eventually hundreds of PNGs at 32×32) and
  hand-pixeling is not a credible plan for a "100% AI-developed Minecraft
  server."
- ADR-0010's core constraint — that the *runtime* must not depend on
  generative AI — remains valid and should not be relaxed. A vanilla-Paper
  install of Astera must still boot and play with zero ML model loads, zero
  API keys, zero GPU.

The boundary between "AI as a designer's tool" and "AI as a runtime
component" was implicit in ADR-0010 but never stated. Without an explicit
boundary, the texture work either stalls (waiting for Phase 6) or proceeds
ad-hoc with the policy unclear to future contributors.

## Decision

Astera adopts the following four-rule policy. The rules apply from **Phase
1** onward (i.e., now), without waiting for Phase 6.

1. **Asset creation tooling is unrestricted.** Contributors may use AI image
   generators (ChatGPT / Codex CLI / Stable Diffusion / spritecook.ai / …),
   procedural tools (PXC, Aseprite, Blender, …), or traditional DCC
   software (Photoshop, GIMP, …) at their discretion on their own machines.
   The deliverable is the resulting `*.png` / `*.ogg` / `*.json` committed
   to `content/`. **The tool used to author an asset is not part of
   Astera's dependency surface.**

2. **The runtime dependency invariant from ADR-0010 is preserved
   verbatim.** No module loaded at runtime — `:plugin:domain`,
   `:plugin:application`, any adapter, any provider — may import an
   image-generation library, ML inference framework, model loader, or
   require an API key for generative services. A Konsist rule (see
   *Mitigations* below) enforces this.

3. **The build pipeline is also off-limits to generative AI.**
   `./gradlew check` / `./gradlew build` MUST not invoke any AI image-gen
   API, download any model weights, or require any API key. CI runs on
   vanilla JVM + standard toolchain. Designer-side regeneration scripts
   under `tools/texture-gen/scripts/` are explicitly out of the build
   graph; they are run manually by humans and their outputs are committed.

4. **`tools/texture-gen/` is permitted and is the only sanctioned home for
   asset-creation scaffolding.** It may contain (a) PXC project files
   (`*.pxc`, text-format), (b) post-processing scripts (grid slicing,
   palette quantization, transparency normalization), (c) wrappers
   invoking external CLIs (Codex CLI, PXC headless mode) via
   user-configured environment variables (`PXC_BIN`, `OPENAI_API_KEY`,
   …). Nothing in this directory may be referenced from any runtime or
   build module. This is enforced by directory placement (it is not under
   `:plugin:*`) and by a Konsist rule.

## Boundary Test

For any change that touches AI / generative tooling, ask:

| Question | If YES |
|---|---|
| Does a vanilla-Paper install of Astera require this to boot or play? | **Forbidden.** ADR-0010 / ADR-0018 rule 2 violation. |
| Does `./gradlew build` need this to succeed? | **Forbidden.** Rule 3 violation. |
| Is the artifact committed as a static file under `content/`? | **Permitted.** Rule 1 applies. |
| Is the script run manually by a designer and never by CI? | **Permitted.** Rule 4 applies. |

If the answer to all four is "no, none of these," the change is outside
Astera's scope — open an issue rather than commit.

## Consequences

### Positive

- Texture / sound / model work can begin in Phase 1 without waiting for the
  Phase 6 "generative AI" milestone. The rest of ADR-0010 (architecture,
  DI, persistence focus) is unaffected.
- ADR-0010's actual intent — "no API costs in the build, no model
  dependency at runtime" — is preserved and now testable via Konsist.
- Contributors get a clear yes/no for the question "can I use ChatGPT to
  make a texture?" without having to re-derive the ADR-0010 intent from
  context.
- The line drawn here generalizes: when sound / model / stage generation
  becomes relevant (Phase 3+), the same four rules apply, no new ADR
  needed.

### Negative / Trade-offs

- Two ADRs (0010 and 0018) now have to be read together to understand the
  full policy. Future contributors may read only one. Mitigation: ADR-0010
  has a `References` link added to ADR-0018, and `tools/texture-gen/README`
  links both.
- PR reviewers must check that new dependencies don't sneak ML libraries
  into runtime modules. The Konsist rule catches obvious violations
  (package-name matches) but not transitive cases (a wrapper library that
  internally pulls a model loader). Manual review is the residual
  safeguard.
- "Asset creation is unrestricted" includes asset-license concerns
  (training-data provenance, model output licenses). This ADR does not
  attempt to legislate those — that's a separate question for any future
  commercial release. For Astera's current Phase 1 internal-development
  status it is acceptable to defer.

### Mitigations

- Add a Konsist rule under `tools/architecture-test/` asserting that no
  runtime module (`:plugin:**`) imports any package starting with:
  `org.deeplearning4j.`, `org.tensorflow.`, `ai.djl.`, `com.openai.`,
  `io.modelcontextprotocol.`, `org.pytorch.`, `org.onnx.`, and that no
  runtime module references the strings `OPENAI_API_KEY` /
  `ANTHROPIC_API_KEY` / `STABILITY_API_KEY` in source. Extendable.
- `tools/texture-gen/README.md` explicitly states it is not a build target
  and lists the boundary test above.
- The next contributor guide
  (`docs/contributing/add-a-texture.md`, to be written when Step 0 PoC
  validates the flow) will reference this ADR and the boundary test.

## Alternatives Considered

- **Hold ADR-0010 literally; do no AI texture work until Phase 6.**
  Rejected. The constraint as written was "no generative AI" but the
  intent was "no runtime dependency on generative AI." Holding the literal
  reading blocks all texture progress and pushes the project into a
  hand-pixeling crunch that contradicts the "100% AI-developed" mission.
- **Supersede ADR-0010 entirely.** Rejected. ADR-0010's runtime invariant
  is still correct and load-bearing; ripping it out would invite drift
  ("if 0010 is gone, what stops us from calling ChatGPT at runtime?").
  Adding ADR-0018 as a complement preserves both the rule and the
  exception in immutable history.
- **Allow AI tools in build pipeline (e.g., `./gradlew generateTextures`
  invokes ChatGPT API).** Rejected. Makes CI flaky (rate limits, API
  outages, key management), introduces cost per CI run, breaks
  reproducibility (model versions drift). Commit-based artifact flow is
  strictly superior for this use case.
- **Whitelist specific tools (e.g., "PXC and Codex CLI only").**
  Rejected. The actual constraint is about boundary, not specific
  vendor. Whitelisting requires per-tool ADR amendments and ages badly as
  the AI tooling landscape changes monthly.

## References

- [[adr/0010-postpone-generative-ai-to-phase-6]] — original deferral
  decision; this ADR complements but does not supersede
- [[adr/0001-clean-architecture-with-mc-as-edge]] — runtime invariant
  philosophy ("Minecraft is an edge, AI is an edge")
- [[adr/0002-vendor-neutral-mc-adapter-layer]] — Konsist enforcement
  precedent
- Pixel Composer (MIT source, paid binary):
  https://github.com/Ttanasart-pt/Pixel-Composer
- PXC CLI docs: https://docs.pixel-composer.com/misc/command_line.html
- spritecook.ai (MCP server for sprite generation): https://spritecook.ai
- Step 0 PoC artifact: `tools/texture-gen/README.md` (to be written)
