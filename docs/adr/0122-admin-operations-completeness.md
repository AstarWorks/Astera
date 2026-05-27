---
status: Accepted
date: 2026-05-27
deciders: claude-orchestrator
supersedes: null
superseded_by: null
---

# ADR-0122: Admin operations completeness (Wave E 5admin-ops)

## Status

Accepted

## Context

The post-Wave-5lobby-content audit (§Round 10+11 of the active plan) surfaced
**14 missing admin command wireups** plus two correctness gaps:

* Ports for maintenance, health, backup, live-config-edit, role-grant,
  reload, audit, item-give, currency-grant, xp-grant, mob-spawn, and
  stage-select **already existed** from Waves 5h / 5t / 5w / 5pve / _ops, but
  no `CommandSpec` wires them to chat. Admins had no in-game surface to
  drive any of these flows; operations relied on direct repository calls or
  YAML edits + restart.
* No Konsist rule enforced that admin commands actually declare a
  `requirePermission(...)` gate. A `/astera-<verb>` literal that forgot the
  gate would silently be executable by anyone — a worse failure mode than a
  raw `Bukkit.broadcastMessage(...)` (which the existing `IBroadcaster` rule
  already catches).
* `CreateFirstTimeProfileOnPlayerJoinedListener` and
  `RestoreInventoryOnPlayerJoinedListener` (both subscribed to
  `PlayerJoinedServer`) had no double-dispatch guard. A second
  `PlayerJoinedServer` arriving while the player was still tracked
  (`PlayerSession.state != Disconnected`) would re-create the profile and
  re-restore the inventory — both idempotent at the service layer in Phase 1
  (in-memory `IPlayerProfileService` returns `AlreadyExists`), but a
  cross-server race once Postgres landed would risk double-grants.

This wave closes the audit list end-to-end so Phase 1 PoC has the full
"vendor-neutral admin surface" that ADR-0086 (Wave 5t) sketched but
deferred.

## Decision

Wire **15 admin commands** across Core / Items / Economy / Stats / Match
plugins (sibling-isolation matrix preserved — each command lives in the
plugin that owns its primary port), introduce one new `MaintenanceMode`
domain + `IMaintenanceModeService` port, add **one** Konsist rule that
verifies admin commands declare a `requirePermission(...)` call before
register, and guard the two join listeners with a
`PlayerSession.state != Disconnected` idempotency check.

### 15 admin commands

| Plugin | Command | Permission | Backing port / use case |
|---|---|---|---|
| Core | `/astera-maintenance <on\|off\|status>` | `astera.admin.maintenance` | new `IMaintenanceModeService` |
| Core | `/astera-teleport <player> <world> [x y z]` | `astera.admin.teleport` | `IWorldGateway.teleport` |
| Core | `/astera-reload <category>` | `astera.admin.reload` | `IReloadableContentsRegistry` |
| Core | `/astera-audit <player> [limit]` | `astera.admin.audit` | `IAuditLogRepository` |
| Core | `/astera-promote <player> <role-id>` | `astera.admin.role.grant` | `IPermissionService.assign` |
| Core | `/astera-demote <player> <role-id>` | `astera.admin.role.revoke` | `IPermissionService.revoke` |
| Core | `/astera-config <get\|set> <key> [value]` | `astera.admin.config` | `ILiveConfigEditor` |
| Core | `/astera-health` | `astera.admin.health` | `IServerHealthCheck` |
| Core | `/astera-spawn-mob <mob-id> [count] [x y z]` | `astera.admin.spawn-mob` | `IMobSpawner` |
| Core | `/astera-backup <now\|list\|restore> [snapshot-id]` | `astera.admin.backup` | `IServerBackupGateway` |
| Items | `/astera-give <player> <item> [count]` | `astera.admin.give` | `GiveItemUseCase` (existing) |
| Economy | `/astera-grant-currency <player> <currency> <amount>` | `astera.admin.currency.grant` | `ICurrencyLedger.credit` |
| Stats | `/astera-grant-xp <player> <amount>` | `astera.admin.xp.grant` | `GrantXpUseCase` |
| Match | `/astera-stage <list\|select> [stage-id]` | `astera.admin.stage` | `IStageRegistry` |
| Match | `/astera-validate [category]` | `astera.admin.validate` | new `ValidateContentReferencesUseCase` |

`/astera-give` already existed under `/astera-items give …`; the Wave E
addition is the flat top-level alias (`/astera-give`) registered alongside
the Items-side root.

`/astera-validate` lives in Match because the only cross-plugin reference
checker we can write today (item → skill bindings) needs both Items
(item registry) and Combat (skill registry) on the classpath. Match
already `compileOnly`s Combat for `:plugin:stats` and `:plugin:match`'s
own use cases; reusing it here is sibling-isolation-clean.

### `MaintenanceMode` domain

A new value class hierarchy in `core.domain.maintenance`:

```kotlin
public sealed interface MaintenanceMode {
    public val since: Instant
    public data class Off(override val since: Instant) : MaintenanceMode
    public data class On(
        override val since: Instant,
        public val reason: String?,
        public val scheduledOffAt: Instant?,
    ) : MaintenanceMode
}
```

No persistence in Phase 1 — the `InMemoryMaintenanceModeService` keeps the
state in a `@Volatile var` and flips on `setOn` / `setOff`. The actual
login-gate wireup (refuse non-admin `PlayerLogin`) is deferred to Wave 6b's
`BukkitEventAdapter` because the listener side needs a Paper handle. Phase
1 ships the service + command so admin tooling can toggle the state and
the gate impl lands later without API churn.

### New Konsist rule (52 → 53)

```
admin commands must declare a permission gate (ADR-0122)
```

Scans every `CommandSpec.Literal` declaration whose name starts with
`astera-` AND whose name matches the admin verb list
(`maintenance|teleport|reload|audit|promote|demote|config|health|
spawn-mob|backup|grant-currency|grant-xp|stage|validate|give`). Each such
declaration must, in the same file, contain a `requirePermission(`
invocation. Player-facing commands (`/astera-travel`, `/astera-menu`,
`/astera-help`, `/astera-report`) and moderation commands (already gated
inside `ModerationFlatCommandSpec`) are excluded by name prefix.

The check is structural (file-text + name match), not semantic (we do not
verify the permission node matches the verb) — Phase 1 PoC, the strict
mapping lives in `content/permission/roles.yaml` and the role/permission
audit is a separate Phase 4 concern.

### Player session double-join idempotency

Both listeners now consult `IPlayerSessionRepository.find(playerId)` and
short-circuit when the existing session's `state != Disconnected`:

```kotlin
val existing = sessionRepo.find(event.playerId)
if (existing != null && existing.state !is SessionState.Disconnected) {
    log.warn("Skipping {} for {} — session still active (state={})",
        listenerName, event.playerId, existing.state::class.simpleName)
    return
}
```

`CreateFirstTimeProfileOnPlayerJoinedListener` already had a "session
exists → returning player" branch; this hardens it from "skip if any
session exists" to "skip if a *live* session exists" (Disconnected sessions
should still trigger the listener because they represent a reconnect after
a clean quit, which is the normal returning-player flow).

`RestoreInventoryOnPlayerJoinedListener` had no guard; one is added with
the same predicate.

## Consequences

### Positive

- Every admin operation auditable via chat — no more "ssh in and hand-edit
  YAML" for routine ops.
- New Konsist rule prevents the worst possible regression (admin command
  with no permission gate); next admin wave (Wave _ops-2, restic backup)
  inherits the check automatically.
- Maintenance mode lays the groundwork for Wave 6b's login-gate without
  forcing the gate's design now (the service is the lever; the listener is
  the consumer).
- Double-join idempotency closes the "rapid reconnect double-grant" risk
  before Postgres profile storage lands (Wave _persist-2+).

### Negative / Trade-offs

- Each plugin's `Astera*Plugin.kt` grows by ~30-60 lines for the new
  command registrations. Acceptable — wiring is explicit by design
  (CLAUDE.md §coding-posture, manual constructor injection Phase 1).
- The new Konsist rule is structural string-match; a sufficiently creative
  worker could bypass it (e.g., assign `requirePermission` to a variable
  outside the file). Phase 1 trade-off: simpler check is more readable +
  less brittle than a full AST analysis. Phase 4 may revisit if abuses
  appear.
- `/astera-validate` only checks item → skill references for now; mob →
  skill, stage → world, mission → quest, etc. land as separate wave items
  (Wave F) once their respective registries are easier to traverse from
  Match.

### Mitigations

- Each new command has a unit test that exercises the executor path with
  a fake port impl (test fixtures already provide most ports).
- ADR-0086 (Wave 5t admin port set) is the canonical reference; the new
  commands all delegate, never reimplement.

## Alternatives Considered

- **Single `/astera admin <verb>` root** — rejected because EssentialsX /
  vanilla muscle memory favours flat top-level `/astera-<verb>`. The
  existing `ModerationFlatCommandSpec` precedent (Wave 5complete §Axis 3)
  set the pattern; following it keeps tab-complete behaviour uniform.
- **Defer `MaintenanceMode` until login-gate landing** — rejected; without
  a state to toggle, the gate has no API to consume, and the gate
  PR would have to introduce both. Splitting keeps each PR review-sized.
- **Skip the Konsist rule, rely on PR review** — rejected; future waves
  will spawn workers without orchestrator review of every new command
  literal. Mechanical enforcement is cheaper.

## References

- ADR-0022 — multi-plugin layout, sibling-isolation matrix.
- ADR-0073 — item-first content (Items plugin rename).
- ADR-0086 — Wave 5t admin port set (maintenance / health / backup /
  config-edit ports the new commands consume).
- ADR-0117 — PvP completeness orthogonal axes (Axis 3 flat moderation,
  precedent for `/astera-<verb>` top-level naming).
- `tools/architecture-test/.../ArchitectureTest.kt` — new rule #53.
