---
status: Accepted
date: 2026-05-31
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0135: Proxy InMemory backends — register baseline in AsteraCorePlugin

## Status

Accepted

## Context

Round 17 audit (3 parallel Explore agents verifying Velocity / knockback /
custom HP / damage control abstractions before Wave 6 Paper sweep)
surfaced a critical silent-drop in the proxy abstraction:

- `InMemoryProxyMessenger` (`plugin/core/.../application/service/`) and
  `InMemoryServerTransferGateway` (same package) are fully implemented
  Wave 5r / ADR-0084 deliverables, but **never registered** in
  `AsteraCorePlugin.onEnable`.
- Result: `services.get<IProxyMessenger>()` and
  `services.get<IServerTransferGateway>()` return null on a live server.
  Any use case that resolves the proxy port via the registry fails.
- Same family of silent-drop as Wave Q 5port-fill / ADR-0133 §A —
  PostgresBlockDurabilityRepository (impl shipped, registration forgotten).
  Same root cause: KDoc on the impl class implies "registered by
  AsteraCorePlugin / priority 0" but the registration line is absent.

The 3 cross-server domain events (PlayerArrivedOnServer /
PlayerTransferRequested / ProxyHeartbeat) had their subscribers wired by
Wave O 5cross-server-wire / ADR-0132 (log-only stubs), but the
publisher side (`InMemoryServerTransferGateway.transfer()`) cannot fire
because the gateway itself is not registered to the services registry,
so any caller that wants to invoke the publisher needs to construct the
gateway manually (test fixtures do this today; live runtime cannot).

This is a Wave 6b blocker: `VelocityProxyMessenger` /
`VelocityServerTransferGateway` (Wave 6b) are designed to overlay at
priority 100. Without the priority-0 baseline registration, the
priority-overlay convention has no fallback when Velocity is not
configured (the operator-friendly path for staff demos / single-server
deployments).

Konsist Rules 54 (ADR-0131) and 55 (ADR-0134) do not catch this class
of bug — the listener-vars exist (Wave O wired them), the events have
publishers in the InMemory impl source files (allowlisted as Wave 6b
deferred), and the silent gap is at the **ServiceRegistry registration**
layer, not the event/listener wiring layer.

## Decision

Register both InMemory proxy impls in `AsteraCorePlugin.onEnable` at
priority 0, immediately after the existing in-memory
`InMemoryPermissionService` registration (`InMemoryServerTransferGateway`
depends on `IPermissionService`).

```kotlin
val proxyMessenger: IProxyMessenger = InMemoryProxyMessenger(
    localServer = ServerName("astera-local"),
)
services.register<IProxyMessenger>(proxyMessenger, priority = 0)

val serverTransferGateway: IServerTransferGateway =
    InMemoryServerTransferGateway(
        messenger = proxyMessenger,
        eventBus = eventBus,
        permissionService = permissionService,
    )
services.register<IServerTransferGateway>(serverTransferGateway, priority = 0)
```

`localServer = ServerName("astera-local")` is a sensible Phase 1 default —
Phase 1 has no live multi-server topology, and the InMemory impl uses
the localServer name only for self-loopback. Wave 6b's
`VelocityProxyMessenger` will read the actual server name from the
Velocity proxy's `getConfiguration().getServers()` mapping.

Matches the existing AsteraCorePlugin pattern (`services.register<I>(impl)`
without holding the `Registration` return value — Core has no per-plugin
lifecycle teardown; the registry survives for the JVM lifetime).

## Consequences

### Positive

- `services.get<IProxyMessenger>()` and
  `services.get<IServerTransferGateway>()` now return non-null on a live
  server.
- The 3 Wave O log-only listeners
  (`LogOnlyPlayerArrivedOnServerListener` / `LogOnlyPlayerTransferRequestedListener` /
  `LogOnlyProxyHeartbeatListener`) can now actually fire when a use case
  invokes `InMemoryServerTransferGateway.transfer()` (publisher path
  end-to-end live).
- Wave 6b `VelocityProxyMessenger` and `VelocityServerTransferGateway`
  can overlay at priority 100 with a guaranteed priority-0 fallback for
  single-server / staff-demo deployments.
- Closes the silent-drop pattern at the abstraction layer; Wave 6b can
  proceed with proxy adapter implementation without re-checking the
  baseline.

### Negative / Trade-offs

- `InMemoryServerTransferGateway` constructor's `init` block calls
  `eventBus.subscribe(PlayerJoinedServer::class)` and
  `eventBus.subscribe(MatchEnded::class)` without capturing the
  `Subscription` — those subscribers cannot be cancelled on `onDisable`
  / `/reload`. This is a **separate, pre-existing bug** in the InMemory
  impl that Konsist Rule ADR-0123 (subscribe-must-be-assigned) does not
  catch because the rule scopes to `Astera*Plugin.kt` files only, not to
  application-layer impls.
- Known limitation; out of scope for Wave R. A follow-up wave (or Wave 6b
  Velocity adapter design) should refactor the InMemory impl to expose
  an explicit `subscribe(bus): List<Subscription>` helper the way
  `CarryTNTListener` and similar Astera listeners do.

### Mitigations

- The InMemory impl's `init { }` subscribe leak only matters on `/reload`
  scenarios; Phase 1 dev sessions typically run a single boot per
  process. The double-fire risk on `/reload` is documented here for
  future reference.
- Wave 6b Velocity adapter design should explicitly account for clean
  shutdown / subscription cancellation per ADR-0123 lifecycle
  contract.

## Alternatives Considered

- **Alt A: Add the registration to a new `WaveRWiring.kt` helper instead
  of inline in `AsteraCorePlugin.onEnable`**: would require introducing
  a new wiring abstraction Core doesn't currently have. Inline matches
  the established Astera convention (every InMemory impl registered
  directly in `*Plugin.onEnable`).
- **Alt B: Defer registration to Wave 6b when the Velocity adapter
  ships**: leaves the Phase 1 PoC closing window with a known broken
  abstraction. The priority-overlay convention assumes the priority-0
  baseline exists; without it, Wave 6b would need to register a
  duplicate fallback path.
- **Alt C: Refactor InMemoryServerTransferGateway to use an explicit
  `subscribe(bus)` helper in this wave**: scope expansion. The `init`
  block subscribe leak is real but rare in practice (Phase 1 single-boot
  sessions). Defer to a follow-up wave or Wave 6b adapter design.
- **Alt D: Add a Konsist rule that flags ports with InMemory impls but
  no registration**: would catch this class of bug mechanically (better
  than rule 55 publisher check which only sees events). Defer to Round
  18+ Konsist hardening expansion (already on the audit β residual
  candidate list).

## References

- 関連 ADR:
  [[adr/0022-multi-plugin-layout]],
  [[adr/0084-cross-server-proxy-architecture]] (Wave 5r — established
  the IProxyMessenger / IServerTransferGateway port contract),
  [[adr/0123-plugin-lifecycle-subscription-pattern]] (the rule the
  InMemory impl `init` block violates — out of scope for this ADR),
  [[adr/0132-cross-server-event-subscribers-stub]] (Wave O — wired the
  subscriber side; this ADR wires the publisher side baseline),
  [[adr/0133-pre-paper-port-fill]] (Wave Q — same family of
  registration-forgotten silent-drop fix)
- Related files:
  `plugin/core/src/main/kotlin/.../application/service/InMemoryProxyMessenger.kt`,
  `plugin/core/src/main/kotlin/.../application/service/InMemoryServerTransferGateway.kt`,
  `plugin/core/src/main/kotlin/.../platform/AsteraCorePlugin.kt`
- Future work:
  - Wave 6b: `VelocityProxyMessenger` + `VelocityServerTransferGateway`
    overlay at priority 100
  - Round 18+: refactor `InMemoryServerTransferGateway` to expose
    explicit `subscribe(bus)` helper (close the `init { }` subscribe
    leak); add Konsist rule for port-has-InMemory-impl-but-no-registration
    silent-drop class
