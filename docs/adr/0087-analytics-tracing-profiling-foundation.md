---
status: Accepted
date: 2026-05-20
deciders: Astera maintainers (Wave 5u)
supersedes: null
superseded_by: null
---

# ADR-0087: Analytics + Distributed tracing + Profiling foundation

## Status

Accepted

## Context

[ADR-0023] (Wave _ops) shipped a `Micrometer + Prometheus` axis used purely
for **technical** signals: TPS, JVM heap, GC pause, scaling readiness gauge
`astera_match_active` consumed by KEDA. That covers infra-ops needs but
leaves three holes:

1. **Business-KPI metrics**: how many kills per item, per-currency shop
   revenue, per-skill cast counts, per-match duration histograms. Game
   designers and product care about these, and they don't belong on the
   same gauge surface as JVM GC pauses (different consumer, different
   dashboards, different alert thresholds).
2. **Cross-server distributed tracing**: Phase 2+ runs multiple game-pods
   coordinating via the Wave 5r `IProxyMessenger`. A reward credit that
   crosses pods needs a way to be stitched into a single trace in Jaeger
   / Zipkin — otherwise debugging cross-server flow drift is "grep four
   pod logs and squint".
3. **Profiling hot-path identification**: at 20 TPS Paper has a 50 ms
   budget per tick. When `ApplyDamageUseCase` consumes 30 ms we want to
   know *before* a Phase 3 Siege event under load. spark plugin
   integration is the eventual goal (Phase 2; see
   `.claude/plans/idea-observability-depth.md` §A) but a lightweight
   per-method timing surface lands now so the instrumentation marks are
   sprinkled while the design context is fresh.

These three slot together because they share a single MeterRegistry on
the Prometheus side, share i18n + admin command plumbing on the operator
side, and share the same "observability is a side-channel, not a
business-flow gate" silly-knuth §5 posture (no `Result<T, E>`, no
exceptions surfaced to callers).

[Foundation idea] `.claude/plans/idea-observability-depth.md` calls out
Wave 5u as the foundation commit; spark adapter / chunk-level tracing /
live admin dashboard are Phase 2-4 follow-ups (idea-md §A/B/C).

## Decision

Land three sibling sub-features under one Wave / one ADR / one commit:

### 1. Analytics + KPI definition (`IAnalyticsExporter`)

- Port: `core.application.port.outbound.analytics.IAnalyticsExporter`.
- Adapter: `core.adapter.paper.analytics.MicrometerAnalyticsExporter`
  forwards into the same Micrometer `MeterRegistry` that backs the
  Wave _ops Prometheus scrape. KPI dashboards + KEDA scaling read **one
  process-wide source of truth**.
- Metric surface:
  - `astera_match_duration_seconds` (histogram, label: `winning_team`)
  - `astera_match_kill_count` (counter, label: `item_id`)
  - `astera_match_player_count` (gauge, label: `match_id`; per-match
    auto-evicted on `MatchEnded`)
  - `astera_player_session_duration_seconds` (histogram)
  - `astera_player_kill_count` / `astera_player_death_count` (counters)
  - `astera_shop_revenue` (counter, label: `currency`)
  - `astera_marketplace_listing_count` (gauge)
  - `astera_skill_cast` (counter, label: `skill_id`)
- Manifest at `content/observability/metric-definitions.yaml`: a *manifest*
  not config. Runtime never consults it; operators read it to know what
  to dashboard against without source-diving.
- Listener at `core.application.analytics.AnalyticsEventListener`:
  subscribes to `MatchStarted` / `MatchEnded` / `PlayerJoinedServer` /
  `SkillCastRequested` from the in-process `IDomainEventBus` and
  republishes as KPI metrics. Feature-plugin-owned events
  (`PlayerKilled`, `CurrencyChanged`) are recorded by their owning
  plugins via `services.get<IAnalyticsExporter>()` — ADR-0022 sibling
  isolation forbids Core from importing those types.

### 2. Distributed tracing (OpenTelemetry)

- Port: `core.application.port.outbound.tracing.ITraceContextPropagator`
  + opaque `SpanHandle`. Application code never imports the OTel SDK
  (Konsist enforced — new rule `OpenTelemetry imports allowed only in
  core adapter paper tracing`).
- Adapter: `core.adapter.paper.tracing.OpenTelemetryTraceContextPropagator`
  using W3C TraceContext propagator. `TracingBootstrap.buildOpenTelemetry`
  consults env (`ASTERA_TRACING_ENABLED`, `ASTERA_TRACING_BACKEND`,
  `ASTERA_TRACING_OTLP_ENDPOINT`) to decide between `OpenTelemetry.noop()`
  / `LoggingSpanExporter` / `OtlpGrpcSpanExporter`.
- Cross-server propagation: `injectIntoMessage` / `extractFromMessage` on
  a `MutableMap<String, String>` headers carrier. Wave 5r's
  `IProxyMessenger` integrates by calling `injectIntoMessage` before
  enqueuing and `extractFromMessage` on dequeue. A single
  `X-Astera-Trace-Id` header derived from the W3C trace id is also
  written so consumers that only need correlation (not full propagation)
  can read it without a W3C parser.
- Default backend in production env is **console / logging exporter** so
  spans appear in the Paper log without a Jaeger collector running. Flip
  via `ASTERA_TRACING_BACKEND=otlp` once a collector is in place.

### 3. Profiling hook (`@Profiled` + flamegraph)

- Annotation: `core.application.profiling.Profiled(category: String =
  "default")`. **No auto-instrumentation** — no Spring AOP / AspectJ /
  KSP rewrite. The annotation is a Konsist enforcement hook + human
  discoverability mark; production callsites explicitly wrap with
  `ProfiledRunner.run(collector, category, methodName) { … }`.
- Port: `core.application.port.outbound.profiling.IProfilingCollector`
  (`record(category, methodName, durationNanos)` / `export()` /
  `reset()`).
- Default impl: `core.application.profiling.InMemoryProfilingCollector`
  — lock-free per-bucket `(AtomicLong total, AtomicLong count)` via
  `ConcurrentHashMap`. Lossy under high contention but flamegraphs are
  approximation tooling, not stopwatches.
- Export: `core.application.profiling.ProfilingExportService` writes
  Brendan Gregg `flamegraph.pl` collapsed format to
  `<dataFolder>/profiling/profiling-<yyyyMMdd-HHmmss>.txt`. The
  `/astera-profiling export` Brigadier admin command gates on the new
  `astera.profiling.export` permission.
- New Konsist rule `@Profiled annotation forbidden in domain layer`:
  domain is pure game rules; profiling marks belong in application/ or
  adapter/.

## Consequences

### Positive

- **Three observability sub-features land together** — single ADR, single
  commit, single set of Konsist rules. Future debug sessions don't have
  to triangulate across three half-shipped tracks.
- **Shared MeterRegistry** keeps KEDA-scaling signals and business KPIs
  on one Prometheus surface — no per-feature Prometheus port collisions.
- **OpenTelemetry is the de facto cross-language tracing standard**;
  picking it preempts a future "but Jaeger / Zipkin / Honeycomb"
  decision. OTLP exporter ships in the jar; switch backend by env
  without rebuild.
- **No auto-instrumentation framework** keeps the codebase free of
  AspectJ / KSP rewrite footguns. `@Profiled` + `ProfiledRunner.run` is
  a 12-line idiom that does what the code says.
- **Default-disabled by env**: tracing wires `OpenTelemetry.noop()` and
  metrics wires only when `ASTERA_METRICS_ENABLED=true`. Local Paper
  runs cost zero observability overhead.

### Negative / Trade-offs

- **`@Profiled` is purely advisory** — Konsist enforces "no @Profiled in
  domain" but does **not** enforce "every method tagged with @Profiled
  is actually called inside `ProfiledRunner.run`". A drift case is a
  no-op rather than a build break; treat it as a code-review concern.
- **`AnalyticsEventListener` lives in Core** and so cannot subscribe to
  feature-plugin events (`PlayerKilled`, `CurrencyChanged`). Those
  plugins must record metrics via `services.get<IAnalyticsExporter>()`
  themselves. Reasonable per ADR-0022 sibling isolation but adds one
  extra wire-up call per plugin.
- **OpenTelemetry SDK fattens the Core shadow jar** by ~3 MB. Acceptable
  trade-off — Astera ships one Core jar per server, not per request, and
  the Phase 2 spark integration would have brought in similar bytes
  later anyway.
- **Match player-count gauge is per-match labelled** which is naturally
  high-cardinality across server lifetime. Mitigated by removing the
  gauge entry on `MatchEnded` so the time-series count stays bounded by
  concurrently-active match count, not by historical match count.

### Mitigations

- Konsist rule `OpenTelemetry imports allowed only in core adapter paper
  tracing` catches the most likely drift ("sprinkle a `Span.current()`
  somewhere convenient") at build time.
- The metric manifest `content/observability/metric-definitions.yaml`
  documents the surface so operators don't need to source-dive to
  build Grafana panels.
- spark plugin adapter, chunk-level tracing, and live admin dashboard
  are explicitly **deferred to Phase 2+** per
  `.claude/plans/idea-observability-depth.md` §A / §B / §C — Wave 5u
  intentionally lands lightweight foundation, not the depth.

## Alternatives Considered

- **Bundle into Wave _ops instead**: rejected — Wave _ops already
  shipped (`a08b716`) and re-opening it for KPI additions would either
  bloat that commit retroactively or split the change into a follow-up
  ops mini-wave that's still missing the tracing + profiling slots. One
  combined Wave 5u is the cleaner forward-only landing.
- **Reuse `IMetricsRegistry` for everything**: rejected — `IMetricsRegistry`
  is the *technical* metric surface (TPS, JVM heap, KEDA gauge), and
  business KPIs share a registry but want a separate typed entry point
  so test fakes / future Postgres-backed long-term storage don't have
  to subclass an infra-flavoured port.
- **Spring Boot Actuator metrics**: rejected — Astera has no Spring
  context (ADR-0001 / coding-posture.md). Pulling Spring into Paper
  would torch the manual-DI posture.
- **Java Flight Recorder for profiling**: deferred — JFR is JDK-native
  and zero-overhead, but `jcmd <pid> JFR.dump` ergonomics from inside a
  Paper plugin are awkward and the eventual spark adapter (Phase 2)
  gives us better flamegraphs anyway. `@Profiled` + in-memory
  collector is the right "while the iron is hot" instrumentation level.
- **AspectJ load-time weaving for `@Profiled`**: rejected — requires a
  `-javaagent` JVM flag, which means every operator must edit
  start-server.sh. Explicit `ProfiledRunner.run` wrap is more typing at
  hot-path callsites but those are written rarely and read often.

## References

- 関連 ADR: [[adr/0023-secrets-secrets-management]],
  [[adr/0024-provider-route]] (Wave _ops baseline),
  [[adr/0001-clean-architecture-with-minecraft-as-an-edge]],
  [[adr/0022-multi-plugin-portable-architecture]]
  (sibling-isolation; constrains where AnalyticsEventListener lives)
- 関連 docs:
  [[architecture/principles]] §"silly-knuth §5",
  `.claude/plans/idea-observability-depth.md`
  (spark / chunk / dashboard depth deferred to Phase 2+),
  `.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §"Wave 5u"
- 外部リンク:
  [Brendan Gregg flamegraph](https://www.brendangregg.com/flamegraphs.html),
  [OpenTelemetry Java SDK](https://opentelemetry.io/docs/languages/java/),
  [Micrometer Prometheus](https://micrometer.io/docs/registry/prometheus)
