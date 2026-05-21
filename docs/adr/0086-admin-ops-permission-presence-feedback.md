---
status: Accepted
date: 2026-05-20
deciders: Astera maintainers
supersedes: null
superseded_by: null
---

# ADR-0086: Admin/Ops + Role/Permission two-tier + Voice rich presence + Slow query + Player feedback

## Status

Accepted

## Context

Wave 5t bundles five mid-priority "edge tooling" concerns that share a common
shape: each is a port-first abstraction where the **default impl is a Minimal
core implementation** and a **second-tier vendor adapter** is reserved for a
later wave (Wave 6b for LuckPerms / Discord game_sdk; Phase 2+ for richer
back-ends). The pattern matches our existing two-tier abstractions:

| Surface | Minimal core | Vendor adapter |
|---|---|---|
| NPC          | ArmorStand + listener (Wave 5l)     | Citizens (Phase 4)         |
| Notification | Log-only (Wave 5h ideation)         | Discord webhook (Phase 4)  |
| Permission   | Wave 3 `IPermissionService` + V4    | **LuckPerms** (Wave 6b)    |
| Rich presence | Log-only (this wave)               | Discord game_sdk (Wave 6b) |

Wave 5t commits the **richer surfaces** for permission (priority + expiry +
sealed Allow/Deny + per-role title key) so the LuckPerms wrap in Wave 6b
plugs in without churning the Core domain. Same for rich presence: the
Discord skeleton is committed now so the gateway shape is locked.

The other three Wave 5t scopes (Admin / Ops 4-port suite, Slow query audit,
Player feedback queue) are mainline Core domain additions; they don't need a
two-tier shape because no vendor is involved.

### Why Phase 1 lands all five in one commit

The five subfeatures are independently small (~2-3h each) but share a lot of
"admin tooling surface" wiring (`/astera report`, `/astera feedback list`,
`/astera slow-query report`, `/astera config edit …`). Splitting them into
five separate waves would pay the wave-protocol overhead 4× and create a
tangle of cross-wave dependencies (the feedback queue Brigadier command
needs the report event, the report event needs the audit log, the audit log
needs the permission service to gate it, etc.). One wave = one commit =
one Phase A/B/C cycle.

### Persistence shape decisions

Existing V4 (`role_definitions` + `role_assignments`) backs the **legacy**
`IPermissionService` (Wave 3, `RoleDefinition`-shaped, no priority, no
expiry). V22 (`permission_role_assignment`) backs the **Wave 5t**
`IPermissionBackend` / `MinimalPermissionBackend` (`RoleAssignmentRecord`-
shaped, with `expires_at` + `granted_by`). Both stay in place; the legacy
surface keeps the Wave 3 PermissionGating decorator + AsteraTestHarness
scenarios green, while the Wave 5t admin tooling reads V22 exclusively.

Phase 1 PoC posture (CLAUDE.md §"Pre-release posture") says break compat
freely, but here the breaking change has zero leverage: both surfaces have
their own callers and own tests. Splitting them costs ~50 LoC of duplication
that's well-bounded; merging them would require rewriting the Wave 3 tests.
We pick split.

### Why slow-query auto-install was deferred

Exposed 1.3.0's `SqlLogger` surface only exposes `log(context, transaction)`
(after-execution); per-statement timing requires the richer
`StatementInterceptor` whose registration story (via `DatabaseConfig` or
`GlobalStatementInterceptor` ServiceLoader) is awkward to thread through our
existing `connectAndMigrate` factory. Wave 5t commits the
`SlowQueryAuditLogger : SqlLogger` impl + V23 table + `/astera slow-query
report` command; auto-install onto every `Database.connect` lands in Wave
5u / Phase 2 once we settle on the Exposed API shape
(`idea-observability-depth.md` §A foundation).

## Decision

Wave 5t introduces, in a single commit on branch `wave-5t`:

1. **Admin / Ops 4-port suite** (`core.application.port.outbound.admin.*`):
   - `ILiveConfigEditor` — text-replace + atomic reload via the existing
     `IReloadableContentsRegistry` (ADR-0065 extension).
   - `IServerBackupGateway` — per-player JSON envelope export / import
     (distinct from Wave 5w restic which is server-scoped).
   - `IScheduledAnnouncementSystem` — Once / Interval / OnEvent triggers,
     publishes `ScheduledAnnouncementFired`.
   - `IServerHealthCheck` — pluggable `HealthIndicator` aggregation for
     k8s `/healthz`+`/readyz` (Wave _ops integration).

2. **Role / Permission two-tier** (`core.domain.permission.*` +
   `core.application.port.outbound.permission.*`):
   - New `RoleRecord` (priority + `MessageKey` title + sealed
     `PermissionRule.Allow|Deny`) alongside the legacy `RoleDefinition`.
   - New `IRoleRegistry : ReloadableContents`, backed by `RoleContents` +
     `content/permissions.yaml`.
   - New `IPermissionBackend` (priority + isAvailable + grant/revoke with
     duration). Two impls: `MinimalPermissionBackend` (default) +
     `LuckPermsPermissionBackend` (skeleton, `isAvailable = false` until
     Wave 6b).
   - New `DefaultPermissionService` selector composes the backends in
     priority order; emits `PermissionBackendSwitched` on flip.
   - V22 migration: `permission_role_assignment` table.
   - 5 domain events: `RoleGranted` / `RoleRevoked` / `RoleExpired` /
     `PermissionDenied` / `PermissionBackendSwitched`.

3. **Voice rich presence** (`core.domain.presence.*` +
   `core.application.port.outbound.presence.*`):
   - `RichPresenceState` sealed: `Idle` / `InMatch` / `InLobby` / `InSurvival`.
   - `IRichPresenceGateway` port with `backendId() / isAvailable() / publish() / clear()`.
   - Two impls: `LoggingRichPresenceGateway` (default) +
     `DiscordRichPresenceGateway` (skeleton, Wave 6b lands the actual
     game_sdk wrap).
   - `SessionLifecycleRichPresenceListener` bridges match/session domain
     events to the gateway.

4. **Slow query audit** (`core.domain.observability.*` +
   `core.application.port.outbound.observability.*`):
   - `SlowQueryRecord` + `SlowQueryThresholdMs` value classes.
   - `ISlowQueryAuditRepository` port + `InMemorySlowQueryAuditRepository`
     default + `PostgresSlowQueryAuditRepository` (V23 migration).
   - `SlowQueryAuditLogger : SqlLogger` (Exposed) — Phase 1 PoC commits the
     class + threshold env var; per-`Database` auto-install deferred to
     Wave 5u / Phase 2.
   - `/astera slow-query report` admin Brigadier command (last 7 days).

5. **Player feedback queue** (`core.domain.feedback.*` +
   `core.application.port.outbound.feedback.*`):
   - `PlayerFeedback` + `FeedbackCategory` sealed (Cheating / Bug /
     Harassment / Suggestion / Other) + `FeedbackTriageDecision` sealed.
   - `IPlayerFeedbackQueue` + InMemory + V24 Postgres.
   - `SubmitFeedbackUseCase` publishes `PlayerFeedbackSubmitted`.
   - 3 Brigadier commands: `/astera report <target> <reason> [comment]`
     (player tier), `/astera feedback list [status]` (GM tier),
     `/astera feedback triage <id> <decision>` (GM tier).

6. **Konsist rules** (3 new):
   - `IPermissionBackend implementations declare a unique backendId()`.
   - `core.domain.permission.* MUST NOT import net.luckperms.api.*`
     (vendor isolation; sets up the Wave 6b adapter location).
   - `IRichPresenceGateway implementations declare a unique backendId()`
     (bonus: enforces the same shape for the presence two-tier).

### Why LuckPerms over a homegrown solution

LuckPerms (`net.luckperms.api`) is the **de-facto Paper permission system**
— 99%+ of Paper servers either use it or its predecessor LP-equivalent. It
solves:

- Web UI for permission management (Phase 4 admin UX win)
- Context-aware permissions (per-world, per-server, per-time-of-day)
- Multi-server permission sync via MySQL/PostgreSQL
- Track + group hierarchies that match what Phase 1 sealed `Allow`/`Deny`
  approximates

Phase 1 PoC ships with the Minimal backend because LuckPerms install is
optional; Phase 4 deployments will install LuckPerms and the selector flips
transparently. The skeleton lives in Wave 5t so the Wave 6b LuckPerms wrap
can plug into a frozen shape.

### Why Discord game_sdk over a webhook for rich presence

Webhooks update Discord channels (one-way broadcast). The game_sdk (or its
HTTP-IPC equivalent `discord-rpc`) updates a **specific player's** Discord
status to show "Playing Astera • In match: Annihilation • 5 kills" the same
way Steam shows Steam rich presence. The webhook path is already covered by
`IBroadcaster` (Wave 3) for channel notifications; rich presence is the
player-side surface and needs the per-player Discord IPC.

The actual wire-up needs the player's Discord launcher to be running
locally — Phase 1 PoC commits the abstraction; Wave 6b lands the wrap; the
selector falls back to the LoggingRichPresenceGateway when no Discord IPC
is reachable (graceful no-op).

## Consequences

### Positive

- 5 mid-priority concerns close in one commit, freeing Wave 5u (Analytics)
  and Wave 5q (Anti-cheat) to start without role-check dependencies.
- Permission/presence two-tier shape locks vendor adapter targets for
  Wave 6b without a third migration.
- Slow query infrastructure committed even if auto-wire deferred — the
  V23 table + repository + command are usable today (call sites attach the
  logger manually).
- Konsist vendor-isolation rule prevents future drift where a feature
  plugin imports `net.luckperms.api.*` directly.
- Player feedback queue is the foundation for Phase 4 admin tooling
  (`8f`: moderation tooling); the V24 table joins cleanly with the Wave 5e
  audit log for per-player history.

### Negative / Trade-offs

- Two coexisting permission persistence shapes (V4 legacy + V22 Wave 5t).
  Defended in §"Persistence shape decisions" above; would be cleaner to
  merge into a single shape, but the migration cost on existing tests is
  high and the Phase 1 PoC posture explicitly favours leaving both in place.
- Slow query auto-install deferred. Operators must explicitly attach the
  logger to a transaction (`transaction { addLogger(slowQueryLogger); ... }`)
  to capture statements; the V23 table fills only via that path. Wave 5u /
  Phase 2 will close this gap once we settle on the Exposed registration API.
- The `LuckPermsPermissionBackend` and `DiscordRichPresenceGateway` ship as
  always-unavailable stubs. They participate in the Konsist uniqueness
  rule and selector test, but a developer scanning the impls might be
  surprised the gateway returns success-shaped nothing. The class kdoc
  states the deferral explicitly.

### Wave 6b deliverables (deferred, locked here)

- `PaperLuckPermsBackend` (`core.adapter.paper.permission.*`) wrapping
  `LuckPermsProvider.get()`, with `PluginEnableEvent` listening to flip
  `isAvailable()` dynamically. Registered at priority 50 (between the
  always-on Minimal at 0 and the Web admin UI at 100).
- `PaperDiscordRichPresenceGateway` (`core.adapter.paper.presence.*`)
  wrapping the Discord game_sdk JNI binding (or the rest IPC equivalent),
  with `isAvailable()` testing reach to the local Discord client.

## Related

- ADR-0022 — multi-plugin layout (this wave adds ports under `core.*` only).
- ADR-0065 — per-category reload dispatcher (extended by `ILiveConfigEditor`).
- ADR-0068 — cross-server persistence boundary (slow query + feedback queue
  both join cleanly via Postgres).
- Plan: `wave-5g-core-hardening-giggly-sunset.md` §"Wave 5t".
- Idea md: `idea-voice-social.md` §A (Discord rich presence) /
  `idea-observability-depth.md` (Slow query foundation) /
  `idea-notification-gateway.md` (Phase 4 follow-up).
