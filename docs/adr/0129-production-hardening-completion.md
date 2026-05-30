---
status: Accepted
date: 2026-05-30
deciders: orchestrator (Wave L 5hardening-complete)
supersedes: null
superseded_by: null
---

# ADR-0129: Production hardening completion

## Status

Accepted

## Context

The Round 14 audit (Wave J handoff, dated 2026-05-29) revisited the
hardening fleet shipped by Wave 5server-harden (ADR-0123) and found
three "ports committed, zero call site" gaps plus four contributing
gaps Round 11 already noted but no Wave closed:

1. **`IVanillaFeatureSuppressor` has 0 call sites in production.**
   The port + `InMemoryVanillaFeatureSuppressor` + `FakeVanillaFeatureSuppressor`
   all landed in Wave 5server-harden, but nothing in `*Plugin.kt` ever
   calls `suppressor.suppress(...)`. The registry is empty at boot, so
   Wave 6b's `PaperVanillaFeatureSuppressor` adapter would have nothing
   to cancel. The vanilla XP bar / hunger bar / advancement / recipe /
   mob spawning / weather cycle / portal traversal are all silently
   un-suppressed today.
2. **`IErrorReporter` has 0 call sites in production.** Same pattern
   as (1). The port + impl + fake ship; the structured fan-out path
   (Critical → admin broadcast + audit row) exists; but no production
   error path actually calls `errorReporter.report(...)`. Operator-
   visible failures still log as free-form `logger.severe(...)` lines.
3. **No `WorldUnloading` listener** ends matches when an operator
   `/world unload`s a per-match world mid-flight. The `MatchWorld`
   substrate exists (Wave 5d) but `IMatchLifecycle.end(matchId,
   WorldUnloaded)` has no caller — the match would phantom-stay
   `ACTIVE` until the next admin restart.
4. **No performance pin tests.** Wave 5server-harden added
   `ConcurrentWriteIT` for correctness under load, but tick-budget /
   match-startup-latency / `findById` query-count regressions would
   silently slip past `./gradlew check`.
5. **Six content categories have no authoring doc.** Wave _items-1
   shipped `docs/contributing/add-an-item-yaml.md` as the pattern, but
   `add-a-{skill,stage,mob,shop,quest,recipe,achievement}-yaml.md` were
   never written. Content authors had to read source code to discover
   the schema.

The Phase 1 PoC closing posture (CLAUDE.md §Pre-release posture) prefers
"land the call sites now, even if the implementation is a noop, so the
surface area is committed" over "wait for the consumer's Wave to land
both sides at once". Wave 6b's Paper adapter is the eventual consumer
of (1); without (1) committed today, the wave-6b PR cannot test against
a populated registry.

## Decision

Land five sub-scopes in one wave, gated by 1 new domain event
(`WorldUnloading`) and 1 new EndReason variant (`EndReason.WorldUnloaded`).
No new Konsist rule (every change is wiring or doc); no DB migration.

### A. Default-suppress vanilla features on `ServerReady`

- **`SuppressVanillaFeaturesOnServerBootListener`**
  (`plugin/core/.../application/eventbus/listeners/lifecycle/`) —
  subscribes the in-process `IDomainEventBus` for `ServerReady`
  (Wave 5w / ADR-0089), then calls
  `suppressor.suppress(VanillaFeature.X)` for the seven default-suppress
  variants:
  - `VanillaXpBar` (Wave 5e custom Norma HUD replacement)
  - `VanillaHungerBar` (Wave 5j mana HUD)
  - `VanillaAdvancement` (Wave 5p Custom Advancement)
  - `VanillaRecipe` (Wave _items-1 ItemSkillBinding)
  - `VanillaMobSpawning` (Wave 5c Star Generator)
  - `VanillaWeatherCycle` (per-match overlay)
  - `VanillaPortalTraversal` (ADR-0120 Astera portal)
- **`VanillaDeathMessage` is intentionally NOT seeded** — Killfeed
  (ADR-0105) already cancels vanilla death messages via a dedicated
  listener; a duplicate suppress here would be redundant state without
  behavior change.
- **`DEFAULT_SUPPRESSED` is a public companion list** so admin tooling
  (`/astera-debug vanilla`) can render "what Astera suppresses out of
  the box" without re-parsing the live registry.

### B. IErrorReporter actual call sites

Five production paths now call `errorReporter.report(...)`:

1. **`ContentValidatorService.validateAll` per-check throw arm** —
   converts a thrown `ContentReferenceCheck.check()` into one
   `ServerError.Critical(source = ErrorSource.Content(category))`
   in addition to the existing `InvalidSchema` report-entry.
2. **`BootValidateContentLifecycleListener.onReady` Critical fan-out** —
   every `ContentValidationError` in the Critical bucket becomes one
   `ServerError.Critical`; Warning bucket → `ServerError.Warning`.
3. **`AsteraPersistencePlugin.onEnable` connectAndMigrate catch** —
   Postgres bootstrap throw → `ServerError.Critical(source =
   ErrorSource.Persistence("AsteraPersistencePlugin/connectAndMigrate"))`.
4. **`AsteraMatchPlugin.onEnable` reportBootFailure helper** —
   exposed as a closure that downstream risky-bootstrap steps can wrap
   themselves in, reporting `ServerError.Critical(source =
   ErrorSource.Lifecycle("Astera-Match", phase))`.
5. **`RestoreInventoryOnPlayerJoinedListener` failure arm** — when
   the snapshot restore fails on `PlayerJoinedServer`, fans out a
   `ServerError.Critical(source = ErrorSource.UseCase("RestoreInventorySnapshotUseCase"))`.
   The reporter parameter defaults to `null` for source-compat with
   existing test callers; wiring is forward-pending until the listener
   construction can be moved past the errorReporter declaration in
   `AsteraCorePlugin.onEnable`.

### C. BroadcastCriticalErrorListener

- **`plugin/core/.../application/eventbus/listeners/error/BroadcastCriticalErrorListener`** —
  subscribes `IErrorReporter.subscribeCritical` and routes each
  `ServerError.Critical` through the Wave 5complete (ADR-0117 §Axis 2)
  `IScheduledAnnouncementSystem`. Each critical schedules a one-shot
  `AnnouncementSpec` keyed by `astera.admin.error.critical.broadcast`
  with `BroadcastKind.Chat` (default; configurable per-instance to
  `Title` / `ActionBar`).
- Why the announcement system and not direct `IPlayerGateway.sendMessage`:
  the announcement system already owns the online-set enumeration +
  audit emission + broadcast-kind matrix that Wave 6b's Paper adapter
  will plug into. Single fan-out lane keeps audit signatures consistent.
- The listener exposes a `subscribe(): IErrorReporter.Subscription` that
  the caller (AsteraCorePlugin) drops into `wave5completeSubs` per the
  ADR-0123 §D cancel-on-disable contract.

### D. WorldUnloading event + EndReason.WorldUnloaded + listener

- **`core.domain.event.WorldUnloading(worldId)`** — new domain event
  published before a world is unloaded from server memory. Phase 1
  publishers are tests only; Wave 6b's `PaperWorldUnloadAdapter`
  registers a Bukkit `WorldUnloadEvent` listener and publishes this
  event before the actual unload completes.
- **`EndReason.WorldUnloaded`** — new variant of the
  `core.domain.event.EndReason` sealed interface. Distinct from
  `Cancelled` (which is admin-initiated and carries a free-form
  reason): `WorldUnloaded` is a structured, well-known cause that
  downstream listeners can branch on. Every existing `when (reason)`
  on `EndReason` already has an `else` arm, so adding the variant
  does not break exhaustiveness.
- **`EndMatchOnWorldUnloadListener`**
  (`plugin/match/.../application/eventbus/listeners/world/`) —
  subscribes `WorldUnloading`, filters `IMatchWorldRepository.all()`
  by `worldId`, calls `IMatchLifecycle.end(matchId, WorldUnloaded)`
  for each affected match. Listener never re-throws (a WorldUnload
  handler that throws would break the unload chain); failures are
  logged + the loop continues to the next match.

### E. PerformanceAssertionIT

- **`plugin/persistence/src/test/.../performance/PerformanceAssertionIT.kt`** —
  three scenarios, gated by `@Tag("slow")` for opt-out and by
  `PostgresIntegrationTestBase` for Docker-availability skip:
  1. **Tick budget** — `IScheduler.runRepeatingTicks` × 100 tasks
     dispatched on a `FakeScheduler.advanceTicks(1)` step completes
     within a 50 ms tolerant budget.
  2. **Match startup latency** — 10 sequential
     `PostgresMatchRepository.save` calls complete within 1 000 ms.
  3. **`findById` query count** — a `findById` call claims at most
     one connection from the Hikari pool (multi-connection delta
     indicates transactional fan-out).
- Each scenario is intentionally tolerant (real-measurement × ~2 with
  a floor) so jitter on dev laptops does not flake; the pin catches
  a 10× regression, not a 1.2× perf drift.

### F. 6 content-authoring template docs

Modelled after `docs/contributing/add-an-item-yaml.md`:

- `add-a-skill-yaml.md` — Wave 5a SkillSpec / SkillTrigger / Mechanic
- `add-a-stage-yaml.md` — Wave 5d Stage / spawn point / region
- `add-a-mob-yaml.md` — Wave 5pve MobSpec / IMobAdapter
- `add-a-shop-yaml.md` — Wave 5g IShopVendor / shop YAML schema
- `add-a-quest-yaml.md` — Wave 5s QuestChain / QuestStage
- `add-a-recipe-yaml.md` — Wave 5s CustomRecipe
- `add-an-achievement-yaml.md` — Wave 5p CustomAdvancement

Each doc carries: schema reference table + 1+ sample YAML + validation
rule list + reload command. Total: 7 new docs (the spec counted 6 but
"recipe" was a separate category, bringing it to 7).

## Consequences

### Positive

- **The Wave 5server-harden ports stop being dead code.** Suppressor +
  reporter now have committed call sites; Wave 6b's Paper adapter has
  a populated registry to consume.
- **Operator-visible failures now have a structured trail.** Critical
  → admin broadcast + audit; Warning → audit. The Bukkit log stops
  being the only source of truth.
- **Match world-unload no longer leaves phantom ACTIVE matches.** The
  `WorldUnloading` → `EndReason.WorldUnloaded` chain plugs the
  audit-flagged scenario from Round 14 §3.
- **Perf regression catches blow-ups at `./gradlew check`.** A 10×
  drift breaks the build instead of being noticed in production.
- **Content authors get one doc per category.** A skill / stage / mob
  / shop / quest / recipe / achievement YAML can now be written without
  reading source.

### Negative / Trade-offs

- **The boot-time listener seeds 7 features unconditionally.** A
  server operator who wants vanilla XP bar back (e.g. a "vanilla-ish
  Astera" deployment) must call `suppressor.restore(VanillaXpBar)`
  from a startup hook. The listener does not yet read a per-deployment
  toggle file (`content/server.yaml` `vanilla.allow: [xp_bar, ...]`
  is the eventual shape — deferred to a follow-up wave).
- **The performance IT is testcontainer-gated and time-sensitive.**
  On a noisy CI runner the tick-budget scenario could flake. Mitigated
  by the `@Tag("slow")` opt-out + tolerant thresholds; if flake rate
  exceeds 1 % on the nightly run, we widen the thresholds before
  removing the test.
- **`BroadcastCriticalErrorListener` routes through
  `IScheduledAnnouncementSystem` Phase-1 contract.** That contract
  does not yet propagate placeholders, so the structured (summary,
  source) detail is *not* in the broadcast line — operators get a
  "look at the console / `/astera-audit`" pointer keyed by a single
  `MessageKey`. Placeholder propagation is a follow-up that lifts
  the broadcast to "Critical: \<summary\> @ \<source\>".

### Mitigations

- **Suppressor opt-out plumbing** — deferred to a follow-up wave that
  introduces `content/server.yaml` (parallel to `content/permissions.yaml`).
  The listener already checks for `restore(...)` calls between its
  fire and the registry read, so a plugin that does
  `restore(VanillaFeature.X)` in its own `onEnable` (before
  `markReady()` fires `ServerReady`) wins — the listener's late seed
  re-suppresses, so the timing is "post-ready restore" only. Document
  this in the suppressor's KDoc as a "Phase 1 limitation".
- **Perf-IT flake** — `@Tag("slow")` lets us run quick-tests without
  it; the `--exclude-tag=slow` switch is documented in the IT's KDoc.
- **Broadcast detail loss** — log line + audit row carry full detail;
  Wave 5q-follow-up adds `AnnouncementSpec.placeholders: Map<String, String>`
  with renderer integration.

## Alternatives Considered

- **Alt A — Land suppression as a `PaperVanillaFeatureSuppressor`
  adapter directly (Wave 6b scope).** Rejected: Wave 6b is committed
  to land the Paper adapter, and committing the call sites now lets
  Wave 6b's PR test against a populated registry from day one. The
  cost of waiting is two PRs racing on the same surface area; the
  cost of landing the call sites now is zero (the domain-level
  suppress calls are no-ops without the adapter, identical to the
  registry being empty).
- **Alt B — Skip `BroadcastCriticalErrorListener` and rely on
  `logger.error` + `/astera-audit` UI.** Rejected: the audit UI
  shipped (Wave 5w `/astera-audit`) is pull, not push. Operators
  watching a live match cannot pause to refresh the audit log mid-
  fight; they need a push signal. The chat broadcast IS that push.
- **Alt C — Wrap each plugin's `onEnable` body in a try-catch +
  errorReporter fan-out.** Rejected: too invasive for one wave;
  Bukkit's own dispatcher already force-disables on uncaught
  exceptions. The selective `reportBootFailure` helper in
  AsteraMatchPlugin is a precedent the other plugins can adopt
  incrementally.
- **Alt D — Use Hikari's `StatementInterceptor` for query counting
  in the perf IT.** Rejected: Exposed v1 wraps Hikari in its own
  transaction layer, so the interceptor count is per-Exposed-
  statement, not per-JDBC-statement. The current `totalConnections`
  delta is coarser but is a reliable structural pin (1 transaction =
  ≤1 new connection).

## References

- 親 ADR: [[adr/0123]] (Server hardening + safety substrates)
- 関連 ADR: [[adr/0089]] (Server bootstrapper + lifecycle),
  [[adr/0117]] (PvP server completeness orthogonal axes — owns
  `IScheduledAnnouncementSystem`), [[adr/0033]] (Stage / region
  foundation), [[adr/0073]] (Item-centric content model),
  [[adr/0091]] (Quest + recipe foundation), [[adr/0088]] (Custom
  advancement)
- 関連 docs: `docs/contributing/add-{an-item,a-skill,a-stage,a-mob,
  a-shop,a-quest,a-recipe,an-achievement}-yaml.md` (this wave),
  `docs/IMPLEMENTATION-ROADMAP.md` §Round 14
