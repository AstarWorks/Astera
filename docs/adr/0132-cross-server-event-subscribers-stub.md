---
status: Accepted
date: 2026-05-31
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0132: Cross-server event subscribers — Phase 1 PoC stub

## Status

Accepted

## Context

Round 15 audit α#4 (verified) surfaced 3 cross-server domain events
(ADR-0084 / Wave 5r) that have **publishers but 0 subscribers** — a textbook
silent-drop pattern identical to Wave I 5match-wireup-fix (Annihilation 永遠
に終わらない):

| Event | Publisher (today) | Subscriber (today) |
|---|---|---|
| `PlayerArrivedOnServer` | Wave 6b `VelocityProxyMessenger` (TBD) + tests | 0 |
| `PlayerTransferRequested` | `InMemoryServerTransferGateway.transfer()` | 0 |
| `ProxyHeartbeat` | Wave 6b `VelocityProxyMessenger` (TBD) | 0 |

Round 15 Konsist Rule 54 (ADR-0131) catches the **listener** side of
silent-drop (`val xxxListener = ...` declared but never wired), but the
**event** side (event published into an empty room) is not yet
mechanically enforced (Rule 1 deferred — see ADR-0131 §Alt A). For the
3 cross-server events specifically, audit α#4 verified by direct grep
that no `subscribe(...EventName::class)` callsite exists in any of the 7
plugins; only the publisher path is wired.

Phase 1 PoC has no live multi-server topology (the InMemoryProxyMessenger
is a test-driven loopback). Wave 6b will land VelocityProxyMessenger as
the real publisher, and downstream waves (Wave 5e / _persist-1) will land
the production subscribers (inventory restore on arrival, audit log, lobby
HUD update, server-browser refresh, matchmaker peer-down filtering).
**Between now and Wave 6b**, the 3 events publish into empty rooms — any
subscriber semantics added by Phase 1 PoC code that touches transfer or
heartbeat would be silently dropped.

The minimum-viable-fix is to wire **stub log-only subscribers** so that:

1. The silent-drop pattern is closed (Konsist Rule 54 invariant satisfied
   via the new `val xxxListener = ...; eventBus.subscribe { xxx.handle(it) }`
   wiring in `AsteraCorePlugin.onEnable`).
2. Operators see proxy traffic in the server log when running tests / dev
   sessions that exercise `InMemoryServerTransferGateway.transfer()`.
3. Future waves can `git grep` the new listener classes to find the
   canonical extension points for richer behavior (instead of having to
   re-derive the wiring from the event class itself).

Stub-only is correct for Phase 1 because:
- No persistence-side inventory snapshot restore is yet on
  `PlayerArrivedOnServer` (Wave 5e / _persist-1 scope).
- No lobby HUD render path exists for "you are being moved to…"
  notifications (Wave 6+ UI adapter scope).
- No server-browser HUD or matchmaker peer-down filter exists
  (Wave 6+ Velocity adapter + UI scope).

## Decision

Add 3 log-only `*Listener` classes in
`plugin/core/src/main/kotlin/com/astarworks/astera/core/application/eventbus/listeners/proxy/`
(new package), each with a single `handle(event)` method that logs at INFO
(for transfer/arrival, low-volume) or DEBUG (for heartbeat, high-volume):

- `LogOnlyPlayerArrivedOnServerListener`
- `LogOnlyPlayerTransferRequestedListener`
- `LogOnlyProxyHeartbeatListener`

Wire all 3 in `AsteraCorePlugin.onEnable` under the existing
`wave5completeSubs` subscription holder list (ADR-0123 lifecycle pattern).
Each instantiation is followed by an
`eventBus.subscribe(EventName::class) { listener.handle(it) }` block, so
the new `val proxyXxxListener` declarations satisfy Konsist Rule 54
(ADR-0131).

Unit tests for each listener verify `handle(event)` does not throw on a
representative event instance (3 trivial `@Test` methods, ~12 lines each).

**Not in scope**:
- `InMemoryProxyMessenger` is NOT refactored to publish `ProxyHeartbeat`
  on `addNeighbor` (audit α#4 noted this as "Phase 1 infrastructure
  plumbing"). Defer: in Phase 1 there is no need for the InMemory impl to
  emit heartbeats since there is no live consumer that depends on them.
  Wave 6b's VelocityProxyMessenger will be the first real publisher and
  will publish on a fixed interval per its own KDoc.
- No persistence / lobby UI / matchmaker subscribers (see §Context above).
- No new Konsist rule introduced (Rule 54 catches the listener-var side;
  Rule 1 event-side is deferred per ADR-0131 §Alt A).

## Consequences

### Positive

- Closes audit α#4 silent-drop pattern: the 3 cross-server events now have
  observable consumers in Phase 1 dev/test sessions.
- Establishes the canonical extension point for richer cross-server
  subscribers in Wave 5e / _persist-1 / Wave 6+ — future waves
  override / replace these stubs instead of starting from a blank
  `listeners/proxy/` directory.
- Satisfies Konsist Rule 54 wiring contract (3 new `val xxxListener` decls
  each followed by `eventBus.subscribe { xxx.handle(it) }`).
- Zero functional risk: log-only stubs cannot break existing behavior; the
  `handle()` methods have no side effects beyond logging.

### Negative / Trade-offs

- The `Log*Listener` class names are mildly awkward — they advertise their
  stub nature in the public type name. This is intentional (future readers
  immediately know to replace, not extend) but creates a class-rename
  burden when Wave 5e / _persist-1 lands the real subscribers.
- `InMemoryProxyMessenger` still does not publish `ProxyHeartbeat`, so
  the heartbeat path is wired-but-never-fired in Phase 1 dev tests. This
  is acceptable but creates a small "is it actually wired?" doubt that
  Phase 6b will resolve.

### Mitigations

- The `Log*Listener` class names + KDoc lines explicitly mark them as
  Phase 1 PoC stubs and cite this ADR for the rationale + replacement plan.
- A `// TODO Wave 5e / _persist-1: replace with real subscriber.` comment
  on each instantiation in `AsteraCorePlugin.onEnable` makes the
  replacement point obvious from the wiring code itself.
- For the heartbeat doubt: future Wave 5proxy-dev (if needed) can add an
  optional `eventBus` parameter to `InMemoryProxyMessenger` and publish
  on `addNeighbor` — out of scope for this ADR.

## Alternatives Considered

- **Alt A: Land real subscribers now (inventory restore on
  PlayerArrivedOnServer, etc.)**: requires persistence + lobby UI adapters
  that are Wave 5e / Wave 6+ scope. Pulling them forward expands the wave
  by 2-3x and creates premature coupling.
- **Alt B: Refactor InMemoryProxyMessenger to publish ProxyHeartbeat on
  `addNeighbor`**: marginal value in Phase 1 (no real consumer), adds
  eventBus injection burden on the test-driven loopback impl. Defer.
- **Alt C: Do nothing (defer α#4 to Wave 6+)**: leaves the silent-drop
  pattern open across Phase 1 PoC closing. Cost-benefit favors stub now
  (~1h) over carrying the gap into Wave 6 planning.
- **Alt D: Wire a single catch-all `LogProxyEventsListener` for all 3
  events**: simpler wiring but loses per-event extension granularity. The
  3-listener split mirrors the existing Astera convention
  (`*OnEventNameListener` 1-event-per-class).

## References

- 関連 ADR: [[adr/0084-cross-server-proxy-architecture]],
  [[adr/0123-plugin-lifecycle-subscription-pattern]],
  [[adr/0126-match-listener-wireup-fix]],
  [[adr/0131-konsist-event-listener-pairing-rules]]
- Audit α#4 (Round 15) — verification that 3 events lack subscribers
- Wave 5r infrastructure (IProxyMessenger / IServerTransferGateway /
  InMemoryProxyMessenger / InMemoryServerTransferGateway in Core)
- Future work: Wave 5e / _persist-1 = real PlayerArrivedOnServer
  subscriber (inventory restore + party sync); Wave 6+ = lobby HUD +
  server-browser; Wave 6b = VelocityProxyMessenger real publisher
