---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0195: Persistence repositories are registered in `onLoad`, before any consumer exists

## Status

Accepted. Fixes the priority-overlay pattern of [[adr/0022-multi-plugin-architecture]] / ADR-0020.

## Context

Core's `onEnable` wired services with in-memory repositories (priority 0) and many consumers captured
`services.get<IRepo>()` once. Persistence enabled afterwards and registered Postgres repositories at
priority 100, so later plugins resolved Postgres while Core's consumers kept the in-memory one:
two live stores for the same data ("overlay split brain"). Seen as WP12 (vault / inventory snapshot,
patched with per-call `Live*Repository` delegates) and F2-1 (join listener wrote sessions to the
in-memory store, `LobbySidebarService` read Postgres: empty lobby sidebar). `plugin.yml` `loadbefore`
only ordered `onEnable` among the feature plugins; Core always ran first.

## Decision

1. **`ServiceRegistry` is created eagerly** by `AsteraCorePlugin` (a `val`, not built in `onEnable`),
   so it exists in every plugin's `onLoad`. Paper runs every plugin's `onLoad` before any `onEnable`.
2. **`AsteraPersistencePlugin.onLoad`** reads the env config, connects, runs Flyway, builds every
   Postgres repository and only then registers them all at priority 100. When Core and the feature
   plugins wire in `onEnable`, `services.get<X>()` already returns the final implementation, so the
   existing `get() ?: InMemory().also { register(priority = 0) }` idiom is correct as is: the
   in-memory default is created only when no overlay exists.
3. **Failure behaviour (never a mix).** Missing env: WARN, nothing registered. DB down, migration
   failure or any repository construction failure: SEVERE "CRITICAL bootstrap failed ... NO Postgres
   overlays registered", the pool is closed, nothing is registered, the whole server runs on the
   in-memory defaults. Once Core has enabled, Persistence's `onEnable` reports the failure through
   `IErrorReporter` (Critical). Registration is two-phase (stage all, then commit) so a failure while
   building the 30th repository cannot leave 29 registered.
4. **Guard.** `ServiceRegistry` remembers the highest priority it has handed out per port. A later
   `register` with a strictly higher priority is a `LateOverride`: recorded in
   `ServiceRegistry.lateOverrides` and sent to `lateOverrideListener` (Core logs SEVERE). The game-sim
   scenario and `ServiceRegistryTest` assert it stays empty.
5. **Removed ad-hoc workarounds**: `LiveVaultRepository` / `LiveSnapshotRepository` (consumers now
   capture once). Event-driven lobby sidebar tracking stays (better on its own).
6. **Other split brains found by the audit and fixed**: `EconomyWiring` built its own
   `InMemoryShopStockRepository` (ignoring the Postgres one); `PaperBlockStateGateway` was built with
   its constructor-default in-memory journal; Core's `sessionRepo` was a local `InMemory` instance.
7. **State that had no overlay at all**: `IPlayerProfileRepository` (V49 `player_profiles`) makes the
   "already onboarded" fact durable so a restart no longer re-runs the starter grant;
   `IPlayerXpRepository` (V50 `player_xp`) backs `ILevelService` so XP/levels survive restarts.
   Migrations are new versions only; existing ones are never edited.

## Consequences

- Adding a repository overlay needs one `stage<Port>(impl)` line in Persistence; no consumer wiring.
- Persistence now connects during `onLoad` (server start is blocked on the DB, as before on enable).
- Hot reload of Persistence alone is unsupported (as before); the guard would flag it.
