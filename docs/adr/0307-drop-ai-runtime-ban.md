---
status: Accepted
date: 2026-10-29
deciders: ryuzu
supersedes: ADR-0010
superseded_by: null
---

# ADR-0307: Drop the AI runtime ban and generative asset deferral

## Status

Accepted

## Context

ADR-0010 deferred generative AI pipelines until Phase 6. The workspace's
AI delegation policy also prohibited AI/ML clients in `:plugin:*` runtime
modules and AI service calls while handling requests. These restrictions
prevent runtime features such as chat translation without establishing a
useful architectural boundary.

Founder decision, 2026-10-29: 「ン？その謎方針要らないな。削除。」
This withdraws the runtime ban and the generative AI deferral.

## Decision

Supersede ADR-0010. Plugins may call AI/ML services, local or remote, at
runtime, including while handling requests, and may depend on their client
libraries (including Anthropic, OpenAI and other ML clients). Generative
asset pipelines are no longer deferred to Phase 6 and may be introduced in
authoring tools or the build graph as needed. The first consumer is chat
translation through a separate local translation server, specified in
ADR-0308 (in progress).

## Consequences

### Positive

- Runtime AI features and generative asset pipelines can be built when needed.
- AI clients follow the same architecture rules as other external services.

### Negative / Trade-offs

- Features using AI services must account for their latency, availability
  and any usage costs.

### Mitigations

- Existing layer boundaries, configuration and error-handling rules continue
  to apply; service integration details belong to the consuming feature.
- AsteraStudio remains a separate repository. This decision
  introduces no runtime dependency on Studio and does not repeal independent
  free-only asset sourcing rules.

## Alternatives Considered

- Keep the blanket ban until Phase 6: rejected by the founder; it blocks
  currently requested features.
- Permit only local translation while retaining the broader ban: rejected;
  the founder withdrew the policy itself, rather than requesting an exception.

## References

- [ADR-0010](0010-postpone-generative-ai-to-phase-6.md) — superseded;
  its historical body is preserved.
- [Content authoring rules](../../.claude/rules/ai-delegation.md) — Studio repository boundary.
- ADR-0308 — local translation server and chat translation (in progress).
