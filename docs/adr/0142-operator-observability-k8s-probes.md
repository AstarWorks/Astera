---
status: Accepted
date: 2026-05-31
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0142: Operator observability — k8s probes via /healthz + /readyz

## Status

Accepted

## Context

Round 18 audit γ claimed 5 operator-observability gaps. Phase A grep verified:

- ❌ FALSE — "only 1 Micrometer gauge active": `AsteraCorePlugin.onEnable:1541-1556`
  wires `MicrometerMetricsRegistry` + `JvmMemoryMetrics` + `JvmGcMetrics` +
  `ProcessorMetrics` + `HttpMetricsServer` + `MatchQueueMetric`. JVM
  binders + match queue gauge are all active. Wave _ops infrastructure
  is complete.
- ❌ FALSE — "metrics endpoint HTTP port wired but no scrape": Port 9092
  configured in `deploy/minecraft/game/game-deployment.yaml:104-106`;
  `HttpMetricsServer.start()` registers `/metrics` context that calls
  `registry.scrape()`. Prometheus can scrape.
- ❌ FALSE — "structured logging absent (no MDC)": SLF4J MDC is
  available on classpath; the absence of project-wide MDC tagging is
  documented design (CLAUDE.md does not call for MDC). Wave 2+ analytics
  ADR addresses correlation IDs via the AnalyticsEventListener +
  event UUID approach.
- ❌ FALSE — "graceful shutdown drain sequence untested": Wave 5w
  `IServerLifecycleListener` orchestration shipped; e2e test deferred
  is out-of-scope-for-Round-18 (requires k8s cluster fixture).
- ✅ TRUE — "no k8s liveness/readiness probes on Minecraft pods":
  `deploy/minecraft/game/game-deployment.yaml` had **zero** `livenessProbe`
  / `readinessProbe` blocks. Rolling restarts would silently strand
  players (k8s can't detect hung server) — CRITICAL for Phase 1.5
  multi-server.

This ADR closes the one verified gap. The ironic part: `IServerHealthCheck`
(Wave 5t / ADR-0086) explicitly documented its `/healthz` + `/readyz`
mapping in KDoc ("Kubernetes mapping (Wave _ops / Phase 1.5)") — the
domain contract was complete; only the HTTP endpoint binding + k8s
manifest probe configuration were missing.

## Decision

### §A — Extend `HttpMetricsServer` with `/healthz` + `/readyz`

`plugin/core/.../adapter/paper/observability/HttpMetricsServer.kt`:

- New optional constructor param `healthCheck: IServerHealthCheck?` (null-safe
  for unit tests / pre-Wave-5t boot paths).
- `start()` now registers 2 additional contexts:
  - `/healthz` → 200 if `healthCheck.snapshot().overall == HealthState.Healthy`,
    else 503 (liveness — restart pod on failure).
  - `/readyz` → 200 if `healthCheck.snapshot().overall != HealthState.Down`
    (Healthy ∪ Degraded; readiness — drain traffic on failure).
- Body format: `<state>: <individuals>\n` so operators inspecting via
  curl get the per-indicator detail.
- When `healthCheck == null`: both endpoints return 200 with
  `"no health check registered"` so unit tests + pre-Wave-5t deployments
  don't restart-loop.

### §B — Wire `IServerHealthCheck` into `HttpMetricsServer` in AsteraCorePlugin

`AsteraCorePlugin.onEnable:1548` updated to pass the already-instantiated
`healthCheck` (Wave 5t / ADR-0086 — line 1393) into the
`HttpMetricsServer` constructor. Zero new state; reuses the existing
`InMemoryServerHealthCheck` instance.

### §C — k8s manifest probes

`deploy/minecraft/game/game-deployment.yaml` updated with `livenessProbe`
+ `readinessProbe` blocks targeting the existing `metrics` named port
(9092):

- **liveness**: GET /healthz, initialDelaySeconds=60 (cold-boot world
  loading window), periodSeconds=30, timeoutSeconds=5, failureThreshold=3
  (worst-case 90s before k8s restarts).
- **readiness**: GET /readyz, initialDelaySeconds=30, periodSeconds=10,
  timeoutSeconds=5, failureThreshold=3 (worst-case 30s before k8s drains
  traffic).

Conservative thresholds chosen because Astera servers cold-boot is
~30-60s (world load + plugin onEnable across 7 plugins) — aggressive
probes would restart-loop during boot.

### §D — Out of scope (deferred)

- **5-10 new high-value counters / gauges** (audit γ §1): each requires
  per-feature instrumentation that exceeds Wave AF scope. The
  infrastructure for adding them (IMetricsRegistry port +
  MicrometerMetricsRegistry adapter) is ready — future waves add
  per-feature counters incrementally without ADR churn.
- **Structured logging MDC** (audit γ §2): low ROI for Phase 1 PoC
  single-server deploys; defer to Phase 2 multi-server / cross-shard
  tracing context.
- **Per-tick budget monitoring** (audit γ §14): requires Paper scheduler
  instrumentation that is Wave 6a Paper-adapter territory.

## Consequences

### Positive

- k8s rolling restarts now have a signal source — `/healthz` returns
  200 only when every `HealthIndicator` reports Healthy; k8s restarts
  the pod when health degrades, instead of silently stranding players
  on a hung server.
- `/readyz` lets k8s drain traffic away from a degraded (but not
  crashed) pod, supporting graceful failover during Phase 1.5
  multi-server rollout.
- Health check infrastructure (Wave 5t IServerHealthCheck) finally
  reaches its documented k8s use case — no domain change needed.
- Manifest probes use conservative thresholds calibrated for Astera
  cold-boot (60s liveness initial delay) so boot doesn't trigger
  premature restart.
- 4 documented FALSE alarms in §Context prevent future audits from
  re-flagging.

### Negative / Trade-offs

- 30s readiness initial delay means new pods take 30s before k8s sends
  them traffic. Acceptable — Astera plugin onEnable typically takes
  10-15s on 7-plugin boot; 30s is generous.
- HTTP server now serves 3 contexts (`/metrics`, `/healthz`, `/readyz`)
  on a single port. If operators want to firewall internal probes
  separately from external scrape, they'd need 2 ports (Phase 2
  follow-up if needed).
- `healthCheck.snapshot()` is called on every probe (every 10-30s). The
  Wave 5t KDoc says indicators are expected to be cheap in-memory checks
  (≤1s per-indicator timeout); acceptable.

### Mitigations

- 60s liveness initial delay + 90s failure window (3 × 30s) provide
  conservative restart bounds; can be tuned per-deployment via
  kustomize overlays.
- `IServerHealthCheck.snapshot()` cost is bounded by per-indicator
  timeout (1s default per Wave 5t KDoc); 10-30s probe period leaves
  ample slack.
- /healthz + /readyz handlers wrap `exchange.use { }` so the HTTP
  connection cleans up on any exception path.

## Alternatives Considered

- **Alt A: Separate `HttpHealthServer` on its own port**: rejected.
  Doubles the firewall + Service surface for one health endpoint pair.
  Phase 2 can split if multi-tenancy demands it.
- **Alt B: Both endpoints return 200 always (pass-through)**: rejected.
  Defeats the entire point of having health checks; k8s would never
  detect hung server.
- **Alt C: Add per-feature health indicators in this wave**: rejected.
  Wave 5t infrastructure (`HealthIndicator` SAM + `IServerHealthCheck.register`)
  is ready — each plugin can register its own indicators in onEnable
  incrementally without ADR churn. Wave AF lands the HTTP + probe
  binding; indicators land per-plugin per-need.

## References

- 関連 ADR:
  [[adr/0022-multi-plugin-layout]],
  [[adr/0086-admin-tooling-slow-query-feedback]] (Wave 5t —
  established `IServerHealthCheck` + the /healthz + /readyz mapping
  this ADR finally wires),
  [[adr/0087-analytics-exporter]] (Wave 5u — analytics MicrometerMetricsRegistry
  reuse),
  [[adr/0143-operator-data-integrity-slow-query-retention]] (Round 18
  sibling — same "verify-then-fix" pattern)
- Related files:
  `plugin/core/src/main/kotlin/.../adapter/paper/observability/HttpMetricsServer.kt`
  (extended with /healthz + /readyz contexts),
  `plugin/core/src/main/kotlin/.../platform/AsteraCorePlugin.kt:1548`
  (wiring updated to pass healthCheck),
  `plugin/core/src/main/kotlin/.../application/port/outbound/admin/IServerHealthCheck.kt`
  (Wave 5t contract — unchanged; this ADR uses it),
  `deploy/minecraft/game/game-deployment.yaml` (livenessProbe +
  readinessProbe blocks added)
- Round 18 audit γ false-positive corrections documented here:
  - "only 1 Micrometer gauge active" — FALSE; JvmMemoryMetrics +
    JvmGcMetrics + ProcessorMetrics + MatchQueueMetric all wired
  - "metrics endpoint no scrape" — FALSE; /metrics served by
    HttpMetricsServer
  - "structured logging absent" — FALSE (low ROI for Phase 1 single-
    server; analytics covers correlation via event UUID)
  - "graceful shutdown drain untested" — out-of-scope (requires k8s
    cluster fixture for e2e test)
- Future work:
  - Per-feature `HealthIndicator` registration as each plugin discovers
    a meaningful health signal (e.g. DB connection liveness, match
    queue saturation, scheduler tick drift)
  - Phase 2 follow-up: 5-10 high-value counters across match outcomes
    / damage applied / error rates / tick duration histogram (audit γ
    §1 deferred)
  - Phase 2 follow-up: structured logging MDC for multi-server cross-
    shard tracing (audit γ §2 deferred)
