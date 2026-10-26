---
status: Proposed
date: 2026-10-26
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0265: Split Core into kernel and feature libraries

## Status

Proposed

## Context

Folia migration task T1 needs smaller compilation and ownership boundaries before
independent feature distribution. Core contains 1,422 main Kotlin files. This
step must preserve packages, source bodies, the seven shipped plugin jars,
plugin descriptors, ServiceRegistry wiring, and runtime behavior.

Implements the first step of [[adr/0258-independent-jar-graph-and-skills-bundle]] (module carve, still shaded into the Astera-Core jar).

## Decision

Extract four `kernel` libraries and eight `core:feature-*` libraries, exposing
explicit project dependencies with Gradle `api` where their public signatures
cross module boundaries. All libraries remain bundled by `plugin:core:shadowJar`.
Keep all domain records together (including the shared event family) in
`kernel:domain`, except command/sign and role records assigned to
`kernel:runtime`, and nine gameplay records whose existing smart casts require co-location with their
consumers. Put `IPlayerGateway` with its MC abstraction implementation in
`kernel:mc-api`, avoiding a runtime/API cycle. Merge the cyclic GUI, lobby,
profile/inventory, world, survival, and HUD clusters into
`core:feature-gameplay`. Retain the internal wiring helpers and their direct
consumers in the plugin compilation boundary rather than changing Kotlin visibility. Preserve the original architecture rules across the
whole Core package tree and extend source/path scanners to the extracted roots.

## Consequences

### Positive

- Core ownership and compilation boundaries are explicit and acyclic.
- Pure kernel modules compile without Paper on their classpaths.
- Existing packages and production source bodies are unchanged.
- The plugin classloader still owns every extracted Core class exactly once.

### Negative / Trade-offs

- GUI, lobby, profile/inventory, world, survival, and HUD remain one library
  because of existing cross-references; independent distribution requires a subsequent design change.
- Core keeps twelve non-platform helper files in addition to platform wiring.
- Other plugin hosts and the simulator still consume `plugin:core` because they
  directly reference its entrypoint or wiring types.
- Library jar metadata changes as Kotlin now compiles multiple modules.

### Mitigations

- Use moves only for production Kotlin; do not introduce bridges or friend paths.
- Compare the complete class-entry lists of old and new Core shadow jars.
- Keep the existing shadow exclusion mechanism: other plugins exclude the full
  Core runtime dependency closure, including the new project libraries.
- See [the module inventory and verification report](../architecture/core-module-split.md).

## Alternatives Considered

- **Distribute feature plugin jars now:** outside T1; changes classloading,
  plugin dependencies, and lifecycle ownership.
- **Force separate cyclic features:** requires source/API changes or compiler
  visibility workarounds, conflicting with move-only scope.
- **Leave Core monolithic:** does not establish the requested Folia migration
  boundaries.

## References

- [ADR-0022](0022-multi-plugin-portable-architecture.md)
- [Module inventory and verification](../architecture/core-module-split.md)
