---
status: Accepted
date: 2026-11-01
deciders: founder, manager
supersedes: null
superseded_by: null
---

# ADR-0316: Playable sim and the BotHost seam

## Status

Accepted

## Context

Bot behaviour is only checkable through `make e2e` (real Paper + MCProtocolLib bots, ~12 min per window). Claude and the bot-ai track need a fast, deterministic way to play a match and measure it. `tools/game-sim` already wires the real plugins headlessly, but `BotBrain` and `Navigator` were welded to the protocol client `e2e.Bot`.

## Decision

The playable sim is `tools/game-sim` used as a headless match arena that Claude and bots play. The bot brain runs unchanged against a `BotHost` interface (`tools/bot-runner/.../bots/BotHost.kt`): `ProtocolBotHost` delegates to the MCProtocolLib client (live bots, e2e), a sim host (later step) backs it with game-sim state. One brain, two hosts. `BotHost` signatures carry no MCProtocolLib or `e2e.*` types; time is `BotHost.now()`, `BotBrain.tick()` is public so a sim driver steps it per sim tick, and the RNG is injected through the constructor.

The sim deliberately does not answer: packet latency and ordering, client interpolation, chunk visibility and load order, exact hitbox/collision shapes, and how VFX look. Those stay with e2e and the real client.

Drift between sim and live is tracked, not assumed away: both hosts feed the same match metrics (time to first sight/attack, kills, falls, stuck escapes), and a divergence in a shared metric is a bug in one of the two.

## Consequences

### Positive
- Bot tuning iterates in seconds, deterministically, without Paper.
- The live path is unchanged (pure delegation).

### Negative / Trade-offs
- A sim host must reproduce locomotion closely enough that metrics stay comparable.
- `BotHost` is a wide interface (about 45 members).

### Mitigations
- Shared metrics and a brain-parity scenario against the e2e numbers.

## Alternatives Considered

- **Fake MCProtocolLib server for the sim**: keeps packet-level fidelity nobody asked for and is slow.
- **Fork the brain for the sim**: guaranteed drift.

## References

- 関連 docs: `docs/contributing/testing-policy.md`
