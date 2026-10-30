---
status: Accepted
date: 2026-10-31
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0311: A plugin's wiring can be torn down and wired again without a restart (owner + generation tags)

## Status

Accepted (number reserved by the manager). Partially supersedes [[adr/0186-fast-plugin-and-content-redeploy]]
(its "no plugin-level reload" position) and extends [[adr/0224-dev-hotswap-live-pack-repush-and-reload-coverage]]
section 3 (reload coverage). Builds on [[adr/0174-host-neutral-wiring-and-headless-game-simulator]] (`WiringContext`), keeps the late-override
guard of [[adr/0195-persistence-overlay-registered-before-consumers]], and rides the hotswap default of
[[adr/0310-hotswap-default-dev-deploy]]. Phase P0 ships with this ADR; P1-P5 are the roadmap below.

## Context

Founder 2026-10-31: 「そもそも鯖を止めずにできるは当たり前では？ktのjarですらそうあるべきだっていうのに」.

Hotswap (ADR-0224, default since ADR-0310) redefines method bodies but never re-runs boot-once code: everything
`Astera*Plugin.onEnable` / `*Wiring.install` registers (services, event subscriptions, listeners, scheduler tasks,
GUI intents, reloadable categories, commands) keeps the old behaviour. The only way to apply a wiring change is a
JVM restart, because registrations carried no owner: nothing could remove "what Combat registered" and register it
again.

## Decision

### Rewire model

- **Owner**: `WiringOwner(id)`, the plugin.yml name (`Astera-Combat`), the string `CommandMount` already used.
- **Generation**: one run of a plugin's `wire()+bind()`, `(owner, gen)` = `WiringTag`. Substrate objects (service
  registry, event bus, `PaperScheduler`, command dispatcher, GUI adapter session table, `BukkitEventAdapter`,
  metrics, Lettuce, PacketEvents hook) are created once by Core and carry `WiringTag.SUBSTRATE`; they are never
  rewired.
- **Every registration goes through `WiringContext`** (the single funnel), which stamps the tag:
  `services` (a `ServiceRegistry.scoped(tag)` view), `eventBus` (`OwnedEventBus`), `scheduler` (`OwnedScheduler`),
  `intents` (`OwnedGuiIntents`), `reloadables` (`OwnedReloadables`), `commandMount()` and, on the Paper side,
  `ctx.listeners(plugin)` (`OwnedListeners`, per-listener `HandlerList.unregisterAll`, so two generations can
  coexist during a swap). The `Owned*` classes record what they handed out and can cancel exactly that set.
- The scoped `services` view decorates the four substrate resolutions (bus, scheduler, intent router, reload
  registry) with the matching `Owned*` wrapper, so existing `services.getOrThrow<IDomainEventBus>()` call sites are
  tagged without being rewritten. No default arguments for owner / generation anywhere.
- A rewire (P1+) is: staged build of generation N+1 against the live registry, unregister generation N by tag
  (`unregisterTag`, `cancelAll`, `unregisterAll`), commit. Dependents of the target are rewired too because they
  hold constructor-captured references to the target's implementations.
- `RewireOwnershipTest` (Konsist, additive) forbids direct `registerEvents(`, the Bukkit scheduler and a new root
  `ServiceRegistry()` in main code outside `konsist-allowlist/rewire-ownership.txt` (the funnel and per-JVM substrate).

### Phases

| Phase | Content |
|---|---|
| P0 (this ADR) | owner+gen on every registration via `WiringContext`; `OwnedEventBus/Scheduler/GuiIntents/Reloadables/Listeners`; `ServiceRegistry.scoped/registeredBy/tags/unregisterTag`; `RewireOwnershipTest`. Behaviour-neutral: nothing is torn down yet, `WiringContext.close()` is unchanged. |
| P1 | `PluginLifecycle` + `RewireCoordinator` + `astera admin rewire`; `wire()` purity (NPC spawn, AFK/dashboard tasks move to `bind()`); leaf plugins (Chat, Economy, Stats) with in-memory carry. |
| P2 | Carry for Items (cooldowns), Combat, Match; drain policies; GUI refresh. |
| P3 | Persistence `connect()/overlay()` split, `--reconnect`, generation-aware late-override guard. |
| P4 | Content categories not reloadable today: shops, lobby-npcs, worlds, balance-core, glow. |
| P5 | dev-deploy: hotswap APPLY, class-to-jar map, `rewire` over RCON; restart only on `RestartRequired`. |

### Manager decisions on the design questions (2026-10-31)

- Q1 in-flight matches: A, carry (matches continue; timers resume; old skill-runtime projectiles/puppets vanish with a notice).
- Q2 shop limited stock whose limit shrank: B, clamp remaining to the new limit.
- Q3 where rewire is allowed: C, everywhere (it replaces the restart in the jar deploy path on prod too).
- Q4 casts in flight: A, wait, up to the skill's load-time maximum end tick.
- Q5 `starter-grant.yaml` changes: B, top up existing profiles by the positive difference (never claw back).
- Q6 lobby world `id`/`seed` changes: A, accept `RestartRequired`.
- Q7 branch order vs Folia: A, land P0-P2 on main now (Paper); strand hops are no-ops and the Folia branch fills them.
- Q8 deploy path once rewire exists: A, jar push = hotswap + rewire; restart only on `RestartRequired`.
- Q9 match-scoped snapshots (match shop, resource defaults, arena values): A, reloads apply to the next match.

### What still needs a JVM restart (surfaced as `RESTART_REQUIRED`)

1. `plugin.yml` or any non-class jar entry changed; `permission` nodes declared in plugin.yml.
2. Substrate classes whose instance state must change (service registry, buses, `PaperScheduler`, command
   dispatcher, `PaperGuiAdapter`, `BukkitEventAdapter`, metrics server, Lettuce client, PacketEvents hook).
   Method-body changes to them stay hotswappable; constructor / field-init changes do not.
3. Kotlin `object` singletons and companion state in kernel modules (initialisers do not re-run).
4. Lobby world identity: `content/worlds/lobby.yaml` `id` / `seed` / generator.
5. Shaded dependency upgrades, Gradle module graph changes, JVM flags, Paper version.
6. Resource-pack content is not a restart but a pack re-push (ADR-0224); `hud/` is therefore not on this list.

## Consequences

- P0 touches every plugin's wiring but changes no behaviour; the gameplay-diff oracle must be empty.
- Chat and Persistence, which have no `WiringContext`, stamp their own tag (`OwnedListeners`, a scoped registry).
- Direct use of the Bukkit scheduler, `registerEvents` or a root `ServiceRegistry` in main code now fails the build.
- Adapters that registered themselves (`registerEvents(this, plugin)`) now take an `OwnedListeners`; callers pass the
  context's instance.
- The unscoped root registry records `UNTAGGED`; tests that register on it keep working, main code must not.

## References

Design: rewire-v1 (manager scratch, `/tmp/claude-1000/skill-engine/design/rewire-v1.md`).
[[adr/0174-host-neutral-wiring-and-headless-game-simulator]], [[adr/0186-fast-plugin-and-content-redeploy]], [[adr/0195-persistence-overlay-registered-before-consumers]],
[[adr/0224-dev-hotswap-live-pack-repush-and-reload-coverage]], [[adr/0310-hotswap-default-dev-deploy]].
