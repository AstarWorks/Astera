---
status: Accepted
date: 2026-05-31
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0143: Operator data integrity — slow-query audit retention

## Status

Accepted

## Context

Round 18 audit γ claimed 4 operator data-integrity gaps. Phase A grep verified:

- ❌ FALSE — "IServerBackupGateway returns fake success": `IServerBackupGateway`
  (Wave 5t / ADR-0086) is a **per-player export/import gateway**
  (GDPR/migration use cases), NOT server-wide backup. KDoc explicitly
  documents the scope split: server-wide is `IServerBackupOrchestrator`
  in Wave 5w (restic) — deferred. The per-player gateway has full
  `Result<PlayerBackupEnvelope, ServerBackupError>` typing and is not a
  stub.
- ❌ FALSE — "Reload UX broken — no per-asset feedback":
  `IReloadableContentsRegistry.reload(category)` returns
  `Result<ContentLoader.LoadReport, ReloadError>` where `LoadReport`
  carries `loaded: Int` + `failed: Int`. The diagnostic is there.
- ❌ FALSE — "IErrorReporter fan-out 7 sites missing": 5 callers
  verified across `ContentValidatorService`, `RestoreInventoryOnPlayerJoinedListener`,
  `BootValidateContentLifecycleListener`, `BroadcastCriticalErrorListener`,
  `AsteraCorePlugin`. Wave L 5hardening-complete (ADR-0129) infrastructure
  is complete; the 7 vs 5 discrepancy is audit-counting drift, not a gap.
- ✅ TRUE — "Slow-query audit table unbounded": `ISlowQueryAuditRepository`
  had no `deleteOlderThan` / archival method. The `slow_query_audit`
  table (V23 migration) grew unbounded over months — DBA ops would
  silently degrade as the table bloated multi-GB.

This ADR closes the one verified gap (slow-query retention) and records
the 3 false alarms so Round 19+ audits do not re-flag.

## Decision

Add retention infrastructure to `ISlowQueryAuditRepository`:

### §A — `deleteOlderThan(cutoff: Instant): Int` method

New abstract method on `ISlowQueryAuditRepository`. Returns count of
rows removed (0 if table already clean past cutoff).

- `InMemorySlowQueryAuditRepository`: iterates the
  `ConcurrentLinkedDeque` + removes records with
  `capturedAt.isBefore(cutoff)`.
- `PostgresSlowQueryAuditRepository`: single SQL `DELETE WHERE
  capturedAt < cutoff` via Exposed `deleteWhere { ... less cutoff }`.

### §B — `SlowQueryRetentionScheduler` periodic task

New `plugin/core/.../application/service/observability/SlowQueryRetentionScheduler.kt`:

- Constructor: `ISlowQueryAuditRepository` + `IScheduler` +
  `clock: () -> Instant` + retention/initialDelay/period Durations.
- `start()` registers a `scheduler.runRepeating(initialDelay, period)`
  task that calls `auditRepo.deleteOlderThan(clock().minus(retention))`
  and logs removed-count at INFO when > 0.
- Defaults: 30-day retention, 7-day sweep period, 1-hour initial delay
  (gives freshly-started server time to settle).
- Wired in `AsteraCorePlugin.onEnable` right after
  `slowQueryRepo` registration.

### §C — Unit test

`SlowQueryRetentionSchedulerTest`: seeds 5 old (40+ days) + 3 fresh
(1 day) records; calls `sweep()` directly; asserts 5 purged + 3 fresh
preserved. Second test verifies 365-day retention preserves everything.

## Consequences

### Positive

- Slow-query audit table stays bounded indefinitely (rolling 30-day
  window). DBA can `/astera slow-query report` with confidence the
  query planner won't choke on a multi-GB table.
- Reusable infrastructure pattern — future retention listeners (audit
  log, replay capture, etc.) can follow the same scheduler shape.
- 3 documented false-alarms in §Context save future audits from
  re-flagging non-issues.

### Negative / Trade-offs

- Default 30-day retention is policy-by-fiat — operators with stricter
  GDPR / longer regulatory horizons would need to inject a longer
  Duration at wiring time. Configurable via the scheduler constructor;
  acceptable Phase 1 PoC default.
- Sweep period of 7 days means the table can grow up to 37 days' worth
  of slow queries before purge. Acceptable trade-off (DELETE cost
  bounded by being run weekly, not per-record).

### Mitigations

- `SlowQueryRetentionScheduler` constructor takes all timing parameters
  — staging deployments with different SLAs override at wiring.
- Postgres `DELETE WHERE capturedAt < ?` uses the existing
  `slow_query_audit_captured_at_idx` index (V23 migration) so the purge
  is O(rows-to-delete) not O(table-size).

## Alternatives Considered

- **Alt A: Postgres-level retention via `pg_partman` / partition pruning**:
  rejected. Adds an external extension dependency + per-table partition
  migration. The application-layer scheduler is simpler and works for
  both InMemory + Postgres paths uniformly.
- **Alt B: TTL via `pg_cron`**: rejected. Same external-dependency
  argument. Astera owns the cadence at the JVM layer where IScheduler
  is the canonical scheduling abstraction.
- **Alt C: Drop slow_query_audit table entirely (no retention needed)**:
  rejected. The `/astera slow-query report` admin command provides
  real value for DBA triage; the right answer is to bound the table,
  not eliminate it.

## References

- 関連 ADR:
  [[adr/0022-multi-plugin-layout]],
  [[adr/0086-admin-tooling-slow-query-feedback]] (Wave 5t — established
  the slow-query audit infrastructure this ADR adds retention to),
  [[adr/0129-production-hardening-completion]] (Wave L — established
  the IErrorReporter fan-out pattern that audit γ falsely flagged)
- Related files:
  `plugin/core/src/main/kotlin/.../application/port/outbound/observability/ISlowQueryAuditRepository.kt`
  (deleteOlderThan method added),
  `plugin/core/src/main/kotlin/.../application/service/observability/InMemorySlowQueryAuditRepository.kt`
  (deleteOlderThan impl),
  `plugin/persistence/src/main/kotlin/.../observability/PostgresSlowQueryAuditRepository.kt`
  (deleteOlderThan impl via Exposed deleteWhere less),
  `plugin/core/src/main/kotlin/.../application/service/observability/SlowQueryRetentionScheduler.kt`
  (new — scheduled task),
  `plugin/core/src/main/kotlin/.../platform/AsteraCorePlugin.kt:1408+`
  (wiring after slowQueryRepo registration)
- Round 18 audit γ false-positive corrections documented here:
  - "IServerBackupGateway returns fake success" — FALSE; per-player
    gateway (server-wide is intentionally deferred to Wave 5w)
  - "Reload UX no feedback" — FALSE; `LoadReport(loaded, failed)`
    returned
  - "IErrorReporter 7 sites missing" — FALSE; 5 callers verified;
    counting drift not gap
- Future work:
  - Wave 5w (restic): server-wide backup via `IServerBackupOrchestrator`
    (separate ADR per Wave 5w plan)
  - Potential follow-up: similar retention for other audit tables (audit
    log, replay capture) following this scheduler pattern
