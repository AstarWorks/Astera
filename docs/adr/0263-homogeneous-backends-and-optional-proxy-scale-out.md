---
status: Accepted
date: 2026-10-26
deciders: ryuzu
supersedes: 0179
superseded_by: null
---

# ADR-0263: Homogeneous backends and the proxy as optional scale-out

## Status

Accepted. Supersedes [[adr/0179-velocity-proxy-and-lobby-game-split]] (fixed lobby/game server roles). **Amends, and keeps
in force**, [[adr/0084-cross-server-proxy-commitment]] and [[adr/0221-live-stack-bots-via-velocity-forwarding]] (the proxy
commitment and Velocity forwarding for bots); those files are not edited.

## Context

ADR-0179 split servers into `lobby` and `game` roles. With many concurrent instances per server
([[adr/0253-gamescope-gameinstance-strand-and-handoff]]) and sharded lobbies ([[adr/0255-world-slots-and-arenapaster]]),
roles are an operational burden: they force at least two processes and strand capacity in the wrong role.

## Decision

1. **Backends are homogeneous**: any backend hosts any instance kind (lobby shard, PvP, PvE, survival, siege). The fixed
   lobby/game role switch goes away; kinds are scheduled by the instances jar.
2. **One server minimum, N when scaling.** A single backend with no proxy is a supported deployment. The proxy is
   **optional scale-out**, not a prerequisite.
3. **Keep the proxy** (Velocity) for four reasons:
   - **fault isolation**: a crashing/stalling backend does not take all players down;
   - **rolling restarts** for jar updates ([[adr/0258-independent-jar-graph-and-skills-bundle]]): drain one backend at a time;
   - **scale beyond one machine**;
   - **per-game config**: different backends can run different region/thread/view-distance settings.
4. The `proxy` jar ([[adr/0258-independent-jar-graph-and-skills-bundle]]) provides `IProxyMessenger` /
   `IServerTransferGateway` (from ADR-0084). A placement service picks a backend per instance request (least loaded with
   free slots/warm pool). Cross-backend matchmaking still goes through the proxy channel.
5. Player state follows [[adr/0259-async-persistence-and-write-behind]]: flush on transfer, one owning backend at a time.
6. T11 implements the port; live-stack bot forwarding ([[adr/0221-live-stack-bots-via-velocity-forwarding]]) remains.

## Consequences

### Positive
- Simple single-process dev and small deployments; capacity is pooled across backends.
- Rolling updates and fault isolation without role juggling.

### Negative / Trade-offs
- Placement logic is new; backends must be configured identically (same content).
- A busy backend can host noisy mixes (siege next to lobby).

### Mitigations
- Placement considers per-kind load; siege can be pinned by config ([[adr/0261-siege-budget-and-fronts-layout]]).
- Content version is checked on backend registration.

## Alternatives Considered

- **Keep lobby/game roles**: wastes capacity, forces two processes.
- **Drop the proxy entirely (single big server)**: loses fault isolation, rolling restarts and horizontal scale.
- **Mandatory proxy**: raises the bar for dev and small servers.

## References

- [[adr/0084-cross-server-proxy-commitment]], [[adr/0221-live-stack-bots-via-velocity-forwarding]],
  [[adr/0253-gamescope-gameinstance-strand-and-handoff]], [[adr/0255-world-slots-and-arenapaster]]
