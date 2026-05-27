---
status: Accepted
date: 2026-05-27
deciders: orchestrator (Wave 5server-harden)
supersedes: null
superseded_by: null
---

# ADR-0123: Server hardening + safety substrates

## Status

Accepted

## Context

Round 10/11 of the §Round 10+11 active phase ran a server-level audit and
surfaced seven structural gaps that no prior wave had owned:

1. **No boot-time content validation** — cross-content references
   (shop→item, item→skill, encounter→loot table, …) are walked only
   lazily; an unresolved id surfaces as a NullPointerException at first
   use, mid-session.
2. **Crash recovery untested** — `Match.ACTIVE` mid-flight + an
   `InventorySnapshot` + a `PlayerSession.InMatch` survive (in the
   in-memory model) a simulated restart, but no test pins the contract.
3. **No structured error report** — exceptions and operator-visible
   conditions are logged free-form. There is no severity-tagged
   forensic record, no admin-broadcast fan-out, and no audit row.
4. **Plugin reload safety untested** — every feature plugin's
   `*Plugin.onEnable` subscribes via `IDomainEventBus.subscribe`; only
   four of the seven plugins drop the returned `Subscription` into a
   cancel-on-disable holder. A `/reload` or hot-swap leaks listeners
   that fire twice on the next event.
5. **No vanilla feature suppression register** — vanilla mob spawning,
   advancement auto-grant, death messages, the XP bar, the hunger bar,
   weather cycles, vanilla recipes, and vanilla portal traversal all
   collide with Astera content. Each plugin that wants to suppress
   rolls its own listener, with no central inventory.
6. **Listener cleanup audit not performed** — coupled to (4): even
   plugins that hold subscriptions in a list never had their pattern
   compliance verified by an automated rule.
7. **No concurrent-write IT** — three known race targets (Wallet
   double-spend, InventorySnapshot save race, Party concurrent-join)
   had no integration test that exercises the actual race window.

The Phase-1 PoC-closing posture (CLAUDE.md §Pre-release posture) prefers
clean substrates over bridge code, so each gap is closed by introducing
the substrate now rather than deferring to a later wave's bridge layer.

## Decision

Land seven substrates in one wave, gated by a single new Konsist rule:

### A. Boot-time content validation

- **`core.application.port.outbound.validation.IContentValidator`** — outbound
  port with `validateAll(): ContentValidationReport` and
  `register(ContentReferenceCheck)` for plugin-side contribution.
- **`core.application.service.validation.ContentValidatorService`** —
  default implementation. Holds a thread-safe `CopyOnWriteArrayList<ContentReferenceCheck>`,
  routes errors by sealed variant (MissingReference → critical,
  DuplicateId → warning, InvalidSchema → critical), and swallows
  per-check exceptions so one bad check cannot mask others.
- **`core.domain.validation.ContentValidationError`** — sealed:
  `MissingReference(sourceId, referenceKind, referencedId, field)`,
  `DuplicateId(sourceId, registryName)`, `InvalidSchema(sourceId,
  reason)`. Each variant renders a self-contained admin-readable
  `message`.
- **`core.domain.validation.ContentValidationReport`** — pre-bucketed
  by severity with `entriesChecked` / `registriesChecked` counts.
- **`BootValidateContentLifecycleListener`** — implements
  `IServerLifecycleListener` (Wave 5w / ADR-0089), fires on `onReady()`
  (high priority so a Strict abort precedes any player-facing path),
  logs the report, and re-throws `ContentValidationStrictFailure` when
  in Strict mode and the critical bucket is non-empty.
- **Default mode is Lenient** during Wave 5; Strict flip happens once
  `content/` is verified clean (`/astera-debug validate-content` lands
  in a follow-up wave).
- **Example check shipped**: `economy.application.service.validation.ShopReferencesItemCheck`
  walks every registered `ShopSpec.offers[*].itemId` and reports
  unresolved ids against `IItemRegistry`. Pattern carries forward to
  every plugin owning content (Items, Combat skills, Match stages, …)
  in subsequent waves.

### B. Crash recovery scenario test

`plugin/match/src/test/kotlin/com/astarworks/astera/match/scenario/CrashRecoveryScenarioTest.kt`
asserts three invariants over a simulated "JVM kill + restart" cycle
against the in-memory repository layer:

1. `Match.ACTIVE` state restores: id, phase, score, team rosters, tick.
2. Inventory snapshot per player restores via `IPlayerInventorySnapshotRepository`.
3. `PlayerSession` per player restores: `InMatch` + `currentMatch`.

Plus two narrower scenarios: in-flight `COUNTDOWN→ACTIVE` transition
doesn't phantom-commit, and `Disconnected` sessions retain their last
known `currentMatch` across the restart. The Postgres-backed equivalent
lands in Wave 6 once `Match` and `PlayerSession` gain Postgres impls.

### C. Structured error report

- **`core.application.port.outbound.error.IErrorReporter`** — outbound
  port with `report(ServerError)` (MUST NOT throw) +
  `subscribeCritical(handler): Subscription`.
- **`core.domain.error.ServerError`** — sealed Critical / Warning /
  Info; each carries `occurredAt`, `summary`, sealed `ErrorSource`
  (Lifecycle / UseCase / Scheduler / Content / Persistence / Other),
  per-severity context.
- **`InMemoryErrorReporter`** — default impl: per-severity SLF4J line
  (ERROR / WARN / DEBUG) + audit-log append + Critical-only subscriber
  fan-out. Subscriber exceptions are caught (the report MUST NOT
  throw); audit-log exceptions are also caught + WARN-logged.
- **`AuditEvent.ServerErrorReported`** — new variant on the existing
  `AuditEvent` sealed interface. Persistence's `PostgresAuditLogRepository`
  gains a matching `KIND_SERVER_ERROR_REPORTED` encode/decode arm. No
  DB migration: `audit_log.kind` is varchar(64) and the JSONB column is
  schema-free.
- **`FakeErrorReporter`** in `:plugin:test-fixtures` for downstream
  tests.

### D. Plugin reload safety (listener cleanup contract)

The contract is now ADR-text + CI-enforced:

> Every `eventBus.subscribe(...)` invocation inside a
> `platform/Astera*Plugin.kt` MUST be assigned to a subscription holder
> list (`subscriptions += eventBus.subscribe(...)` or a per-wave bucket
> such as `wave5completeSubs += eventBus.subscribe(...)`). `onDisable`
> MUST call `subscriptions.forEach { it.cancel() }; subscriptions.clear()`.

The new Konsist rule **"every plugin onEnable subscribe must be
assigned into a subscription holder (ADR-0123)"** scans every
`platform/Astera*Plugin.kt` file line-by-line and fails when a bare
`eventBus.subscribe(...)` (no `+=` assignment) is detected.

Pre-wave audit found:

- `AsteraCorePlugin.kt`: 16 subscribes / **0** holder-pattern (16 leaks)
- `AsteraMatchPlugin.kt`: 16 subscribes / 15 holder (1 leak)
- Combat / Stats / Economy / Items / Persistence: clean.

Wave 5server-harden Phase C fixed all 17 leaks; the Konsist rule now
locks the pattern in.

### E. Vanilla feature suppression register

- **`core.application.port.outbound.vanilla.IVanillaFeatureSuppressor`**
  — outbound port with `suppress / restore / isSuppressed / suppressed`.
- **`core.domain.vanilla.VanillaFeature`** — sealed: `VanillaAdvancement`,
  `VanillaDeathMessage`, `VanillaXpBar`, `VanillaHungerBar`,
  `VanillaMobSpawning`, `VanillaRecipe`, `VanillaWeatherCycle`,
  `VanillaPortalTraversal`. Each carries a stable `id` for log lines /
  config keys. `VanillaFeature.all` lists every variant (introspection
  for admin tooling).
- **`InMemoryVanillaFeatureSuppressor`** — thread-safe `ConcurrentHashMap`-
  backed set; both mutations are idempotent.
- **`FakeVanillaFeatureSuppressor`** with `suppressCalls` / `restoreCalls`
  recorders for tests in `:plugin:test-fixtures`.
- Wave 5server-harden ships the **register only** — domain code can
  branch on `isSuppressed(feature)`. The Bukkit-side actual
  suppression (`@EventHandler` + `setCancelled` / `setGameRule`) is
  Wave 6b's `PaperVanillaFeatureSuppressor` adapter.

### F. Listener cleanup audit + fixes

Audit run during Phase A; fixes applied during Phase B:

- `AsteraCorePlugin.kt`: 9 bare `eventBus.subscribe` calls converted
  to `wave5completeSubs += eventBus.subscribe(...)` (the holder
  already existed from Wave 5complete; the new subscribes just hadn't
  joined it).
- `AsteraMatchPlugin.kt`: 1 bare subscribe (the spawn-on-MatchStarted
  bridge in the world-gateway path) converted to `subscriptions +=`.

### G. Concurrent-write IT (P0)

`plugin/persistence/src/test/kotlin/com/astarworks/astera/persistence/integration/ConcurrentWriteIT.kt`
runs three scenarios against Postgres (Testcontainers-gated):

1. **Wallet double-spend** — two threads race a `debit(100)` on a
   player seeded with exactly 100 GOLD. Outcome: 1 success, 1
   `WalletError.InsufficientFunds`, final balance 0. **Required a
   product fix**: `PostgresCurrencyLedger` now uses per-`PlayerId`
   lock striping (single-server safe; Phase 6 multi-server requires
   `SELECT FOR UPDATE` / advisory lock, tracked as a follow-up ADR).
   Transfer also gained ordered two-lock acquisition to avoid
   deadlock on opposite-direction concurrent transfers.
2. **InventorySnapshot race** — two threads race `save()` for the
   same player. Outcome: both upserts commit; `findLatest` returns
   one of the two written snapshots (last-write-wins, pinned by the
   test).
3. **Party concurrent-join** — 5 threads race `JoinPartyUseCase.join`
   on a `maxSize=4` party with leader pre-seated (3 seats open).
   Outcome: 3 successes, 2 `PartyError.PartyFull`. **Required a
   product fix**: `JoinPartyUseCase` now uses per-`PartyId` lock
   striping. Party is in-memory only (runtime-scoped), so the IT
   uses `InMemoryPartyRepository`.

### H. Konsist rule (1 new = 52 → 53)

Added: `every plugin onEnable subscribe must be assigned into a
subscription holder (ADR-0123)`. The rule scans
`platform/Astera*Plugin.kt` files line-by-line and rejects any
`eventBus.subscribe(...)` call not prefixed with `+= eventBus.subscribe(`.
Comments / KDoc lines are skipped; in-application listeners that store
their subscription internally are out of scope (they have their own
unit-test coverage).

## Consequences

### Positive

- Hidden gaps now have CI-enforced floors:
  - Listener leaks fail the build the moment a new plugin onEnable
    introduces a bare `eventBus.subscribe`.
  - Cross-content reference breakage logs at WARN (Lenient) or aborts
    boot (Strict) instead of NPE-ing at first use.
  - Wallet double-spend impossible on single-server (lock striping);
    the IT pins the contract so any future ledger refactor that drops
    the lock fails CI immediately.
- The `IErrorReporter` port becomes the canonical destination for
  every catch-block "log + carry on" path; subsequent waves can migrate
  free-form `logger.error(...)` calls behind a typed report at a
  predictable rate.
- The vanilla-suppression register decouples *intent* (Astera owns
  this feature) from *mechanism* (which Paper event to cancel) — Wave
  6b can flip the actual suppression on/off without touching domain
  code.
- Phase 1 PoC stays clean: no `@Deprecated` shims, no fallback decode
  paths; the new pattern is the only one moving forward.

### Negative / Trade-offs

- Lock striping in `PostgresCurrencyLedger` / `JoinPartyUseCase` is
  single-JVM only. The first multi-server deploy will need to revisit
  with a SELECT FOR UPDATE or Redis SETNX. Follow-up ADR tracked.
- The Konsist rule is line-based, not AST-based — a sufficiently
  contorted `val x = eventBus; x.subscribe(...)` would slip past. The
  rule trades that edge case against zero false positives on the
  current corpus; if a sibling plugin starts dynamically constructing
  the bus reference, the rule needs upgrading.
- `BootValidateContentLifecycleListener` default Mode is **Lenient**
  for Wave 5; we accept that an operator misreading a WARN as
  "everything's fine" is possible until Strict is flipped. The flip
  lands once `content/` is shown to validate clean for three
  consecutive nightly runs.

### Mitigations

- `wave5completeSubs` and the new `validationRegistrations` list in
  Economy follow the same pattern as `subscriptions` — they're
  retained as `MutableList<...Registration>` properties and cleared
  in `onDisable`. The Konsist rule does **not** check `Registration`
  hygiene yet (only `Subscription`); a follow-up wave can broaden
  it once the registration helpers stabilise.
- `InMemoryErrorReporter` swallows subscriber + audit-log exceptions
  but writes them at WARN — operators who care can grep for
  `"Critical-error subscriber threw"` / `"Failed to write ServerError to
  audit log"` and route to alerting.

## Alternatives Considered

- **Alt A**: ship the validation port + Strict-mode boot abort from
  day one. Rejected: at this point `content/` carries known-bad
  references from earlier waves (legacy NPCs without dialog entries,
  etc.); flipping Strict would abort every dev / CI boot. Lenient
  with Strict-mode opt-in is a strictly better gradient.
- **Alt B**: put the listener cleanup pattern in a `BaseAsteraPlugin`
  class that every `Astera*Plugin` extends. Rejected: subclass
  inheritance is hostile to `JavaPlugin` extension (Bukkit insists on
  a no-arg constructor), and we already have working forwards (every
  plugin holds `subscriptions: MutableList<Subscription>` itself); a
  Konsist rule pins the discipline without forcing a class hierarchy.
- **Alt C**: model `IErrorReporter` as a sealed event on
  `IDomainEventBus` rather than its own port. Rejected: the publish
  contract for `IDomainEventBus` does **not** include MUST-NOT-throw
  semantics (subscribers can throw and other subscribers still run),
  but `IErrorReporter.report` does; mixing the two surfaces is a
  silly-knuth §5 violation (different invariants on the same vocabulary).
- **Alt D**: build a single Vanilla suppressor in `:plugin:items` that
  cancels every event Astera cares about. Rejected: ownership cuts
  across every feature plugin (Match owns weather, Combat owns death
  messages, Stats owns XP bar). A central register that domain code
  can introspect lets each plugin opt-in without spawning a per-plugin
  Bukkit listener tree.

## References

- 関連 ADR: [[adr/0089-server-lifecycle-port]] (Wave 5w lifecycle hook contract),
  [[adr/0058-player-attribute-container]] (Wallet facade),
  [[adr/0073-items-first-class]] (ItemSpec authority),
  [[adr/0090-packet-system-and-per-viewer-visibility]] (CustomBlockSpec
  predecessor for the IContentValidator contract pattern).
- 関連 rules: [[.claude/rules/wave-execution.md]] §Listener cleanup
  contract; [[.claude/rules/coding-posture.md]] §Pre-release breaking
  changes (Phase 1).
- 外部リンク: なし。
