---
status: Accepted
date: 2026-11-01
deciders: manager
supersedes: null
superseded_by: null
---

# ADR-0320: Skill example fixtures in the {v, w, s} motion-vector grammar

## Status

Accepted. Supersedes the example fixtures of ADR-0303 (`docs/adr/0303-examples/`); the rest of ADR-0303 is unchanged.

## Context

Motion vectors in the skill engine moved from `{forward, up, left}` to `{v, w, s}` (each a number, `${param}` or `= expr`; `s` points right,
so a legacy `left: x` is `s: -x`). ADR-0303 is Accepted and immutable, but its examples a-j and the rejected cases are loaded and played by
`AdrExamplesLoadScenarioTest` and `AdrExampleBehaviourScenarioTest`, so they must use the current grammar.

## Decision

The fixtures live in `docs/adr/0320-examples/` (a-j and `rejected/`), the motion rows (`move`, `grow`, `keys`, `path`, `orbit`, `per_index`) written
in `{v, w, s}`. The scenario tests read this directory. `docs/adr/0303-examples/` stays as the historical text and is no longer loaded.
