---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0224: Dev hotswap on JBR, live resource-pack re-push, reload coverage

## Status

Accepted. Extends [[adr/0186-fast-plugin-and-content-redeploy]] (drain + restart stays the
fallback and the default) and [[adr/0182-resource-pack-pipeline-and-hp-status-hud]] (pack push on
join). Dev stack only: the production image and `docker/Dockerfile` are unchanged.

## Context

Founder: "Why restart every time? Can't we hot reload?" ADR-0186 rejected Paper `/reload` and
PlugMan-style unload/load (the cross-plugin object graph, shaded dependencies and Core's classloader
make a second generation of a plugin unsafe), and settled on a cheap restart (about 55 s per
backend). That decision stands. What it did not consider is replacing the *code of the classes in
place* inside the one running plugin generation: no unload, no second classloader, no registrations
to undo, every object, scheduler task and registration stays where it is.

Plain HotSpot can only swap method bodies. JetBrains Runtime (JBR) carries the DCEVM-style
"enhanced class redefinition" (`-XX:+AllowEnhancedClassRedefinition`) which also allows adding and
removing methods and fields, hierarchy changes and modifier changes.

Two smaller frictions came with it: the resource pack hash is read only at boot, and `reload all`
(ADR-0186) did not cover everything that matters for balance.

## Decision

### 1. Hotswap: JBR 25 plus an in-JVM agent, `make dev-hotswap`

- `docker/docker-compose.hotswap.yml` (overlay, `make dev-hotswap-up`) builds
  `docker/hotswap/Dockerfile` on top of the normal dev image: JBR 25 (pinned build, SHA-512 checked) on
  `PATH`/`JAVA_HOME`, the agent jar, `ASTERA_DEV_HOTSWAP=1`, and
  `-XX:+AllowEnhancedClassRedefinition -javaagent:/opt/astera-dev/hotswap-agent.jar=port=5006`.
  The agent listens on `127.0.0.1:5006` inside the container only; nothing is published. The
  production image keeps Temurin and none of these flags.
- `docker/hotswap/AsteraHotswapAgent.java` (plain Java, no dependencies) takes `APPLY` requests that
  name a running jar, its baseline and the new shadow jar, diffs the two jars and
  - redefines every changed class that is loaded (`Instrumentation.redefineClasses`, which is
    JVMTI RedefineClasses and therefore enhanced under JBR); one atomic call,
  - queues the new bytes of changed classes that are not loaded yet; a `ClassFileTransformer` serves
    them on first load (the plugin class loader still holds the old jar),
  - makes classes that are new in the jar loadable by appending a scratch directory to the plugin's
    `URLClassLoader`,
  - answers `RESTART <reason>` without touching anything when a non-class entry changed
    (`plugin.yml`, service files, resources) or when the JVM rejects the redefinition.
- `make dev-hotswap` (`scripts/dev-deploy.sh hotswap`): `shadowJar` for the 7 plugins, compare
  with `/plugins/<jar>` in the container, stage the changed jars, send the request, and on `OK` copy
  the jars to `/plugins` so a later restart boots the same code. On `RESTART` (or when the container
  is not the hotswap image) it runs the ADR-0186 restart path for that backend.
  `dev-plugins` now also compares against `/plugins`, so it does not restart for code `dev-hotswap`
  already applied.

Why not JDWP `redefineClasses` (the first idea): a debugger connection cannot load a class (invoking
`ClassLoader.loadClass` from JDI needs a thread stopped at an event; tested: `IncompatibleThreadStateException`)
and cannot make new classes loadable. A changed class that is not loaded yet would later be loaded
*from the old jar*, silently running old code, and a new class would be `NoClassDefFoundError`. The
agent has `Instrumentation`, which covers both, and keeps the debug port closed. HotswapAgent
(watching `extraClasspath`) targets webapp class loaders; Paper's plugin loaders would need a custom
plugin for it, so a 330-line purpose-built agent was less code to own than the glue.

### 2. Resource pack without restart

`ResourcePackService` (Core) holds the active `ResourcePackSpec` in an `AtomicReference`. The join
listener and the HUD diagnostics read it per join. `/astera admin pack reload [force]`
(permission `astera.admin.pack`) re-runs `ResourcePackEnv.load` (URL from the environment, sha1 fetched
from the pack host), swaps the spec and, when it changed (or `force`), calls `pushResourcePack` for
every online player. Paper derives the pack id from the URL, so the client replaces the pack in place.
An unreadable pack keeps the previous spec. `make dev-pack` runs it on both backends:
`docker compose up -d --build resource-pack` plus `make dev-pack` applies new textures live.
`ASTERA_RESOURCE_PACK_SHA1` (a pinned hash in the environment) beats the fetched one, so leave it unset.

### 3. Reload coverage

- New category `balance-combat` (`content/balance/element-matrix.yaml`,
  `content/balance/status-effect-interactions.yaml`): `CombatBalanceContents` hands the services
  stable delegating views and swaps both files all-or-nothing.
- `bet-policies` is registered with the reload registry (it was already a `ReloadableContents`).
- `languages` was already reloadable (ADR-0186 text said otherwise).
- `/astera items refresh` (and `make dev-content`, which runs it) re-applies name, lore and
  `item_model` of the current item specs to the Astera stacks players hold, in place, and re-scans the
  generated item models. Everything per-instance (refinement, enchants, damage, PDC) is untouched.
  Attributes and skill bindings are read from the registries at use time and need no refresh.

## Evidence (separate compose project `astera-hot`, own ports, live stack untouched)

Lobby backend on the hotswap image, Paper 26.3, JBR 25.0.4.1, plugin classes loaded by Paper's
`PluginClassLoader` from the shaded jars:

| Change | Result | Timing |
|---|---|---|
| log text in a combat class (adds a logger field) | next `reload balance-combat` logs the new text | Gradle 9 s + swap 1.5 s |
| "formula" (`LoadReport(2, 0)` to `(3, 0)`), reload log shows `loaded=3` | live | same |
| new method, field, class, enum + enum use, superclass and interface added, `open` to `abstract` | all applied; singleton state (call counter) kept; new field on the existing singleton read `null` | 9 to 12 s total |
| changed class that is not loaded yet (queued) | first load runs the new bytes (`B-v6` while the old jar holds `B-v5`) | same request |
| Core jar (35 MB) and Combat in one request | applied | Gradle 38 s + swap 4 s |
| `plugin.yml` changed | agent answers `RESTART ... non-class entries changed (plugin.yml)`, script restarts | 66 s |
| `dev-plugins` restart for the same kind of change | for comparison | 70 to 125 s |
| resource pack rebuilt (`up -d --build resource-pack`) then `make dev-pack` | a protocol bot (MCProtocolLib, joined directly) received `ClientboundResourcePackPush` sha1 `88c30f69` on join, then sha1 `8b08c42c` with the same pack id after the command, and answered ACCEPTED/DOWNLOADED/LOADED twice | pack build + 0.4 s |

## Consequences

### Positive
- A Kotlin edit that changes method bodies, adds methods/fields/classes, changes a hierarchy or an enum
  reaches the running server in about 10 to 45 s (the Gradle build) plus 1.5 to 4 s to apply, with
  players staying where they are, instead of 70 to 125 s and a drain.
- Pack and balance edits apply in seconds.

### Negative / Trade-offs
- Only the dev stack can do it; production still restarts.
- Redefinition does not re-run initialisers: a field added to an object that already exists
  (including a Kotlin `object` or a service singleton) is null/0 until something sets it, and a
  changed `static`/`companion` initialiser or constructor does not run again for existing instances.
  Wiring code (`onEnable`, `*Wiring`) runs once at boot, so a change to what gets registered, a new
  listener, a new command node or a changed `plugin.yml` needs a restart (`dev-plugins`). The script
  restarts by itself only when the agent *refuses*; it cannot know that a change "needed" a re-run of
  boot code. `FORCE=1 make dev-plugins` is the escape hatch.
- Lambdas and instances captured before the swap keep their old code until they are recreated.
- JBR is a different JVM than production's Temurin; use it for the edit loop, not for performance or
  GC conclusions. The pinned build is linux/amd64.
- Not reloadable and not cheap to make so: `hud/` (pack art, covered by the pack push), `shops/`
  (reload would re-seed limited stock), `lobby-npcs/` and `worlds/` (spawn entities / build the lobby
  world), `flex/glow-profile.yaml`, and `balance/` files other than the two above (`starter-grant`,
  `block-durability`, `resource-defaults`, `backup-policy`).
