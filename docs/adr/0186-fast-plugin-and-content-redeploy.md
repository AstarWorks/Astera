---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0186: Fast plugin/content redeploy with graceful backend drain (no hot swap)

## Status

Accepted. Builds on the Docker stack of [[adr/0179-velocity-proxy-and-lobby-game-split]] (Velocity +
`lobby` + `game`, jars baked into the image) and the `/astera` tree of
[[adr/0184-single-astera-command-tree]].

## Context

Founder: "Can't we patch just the plugins without restarting the server?" Every change meant
`make docker-up`: a full image rebuild (Gradle in the builder stage, minutes) and a restart of both
backends with everybody disconnected.

## Decision

### 1. True hot swap (Paper `/reload`, PlugMan-style unload/load of one plugin) is not the normal path

Judged on this codebase, not in general:

- **Cross-plugin object graph.** Core owns `ServiceRegistry` and `IDomainEventBus`; the feature
  plugins register ports/impls into the registry and subscribe lambdas on the bus (the `DomainEvent`
  types are Core classes). Unloading `combat` leaves its registered services and bus subscriptions in
  Core, pointing into a dead `PluginClassLoader`; the reloaded plugin registers a second set
  (priority-based registry, so the stale one may even win) and every event is delivered twice or to a
  zombie. A full unregister protocol for the many registrations per plugin would be needed.
- **Reloading Core in place is impossible.** Core's types are in every other plugin's class space
  (`depend`/`softdepend`); replacing Core's classloader invalidates every reference held by siblings,
  so a Core change always means reloading all 7 plus Persistence's connection pool, the Redis
  messenger and the Paper command tree.
- **Classloader leaks.** Bukkit schedulers, listeners, plugin messaging channels, Brigadier command
  nodes, packet interceptors (PacketEvents), HUD/hologram entities and per-player state outlive the
  plugin object unless each is torn down; any leaked reference pins the old loader (metaspace growth)
  and keeps running old code.
- **Shaded dependencies.** Each jar shades its own Kotlin stdlib / kaml / Exposed; two generations of
  the same class in one JVM cause `ClassCastException` on values crossing plugin borders.
- **Paper discourages it.** `/reload` is deprecated and warns; the plugin loader is not designed for
  unload. A state-corrupting failure mode in a playtest server that should be representative is worse
  than a one-minute restart.

Conclusion: a hot-swapped state is not the state production will run. We keep restarts and make them
cheap and invisible to players instead.

### 2. Fast loop

- `make dev-plugins` (`scripts/dev-deploy.sh plugins`): incremental `shadowJar` of the 7 plugins on
  the host, SHA-256 compare against `/data/plugins` in each container, then per backend with a
  changed jar (lobby first): drain players, `docker compose stop`, `docker compose cp` the changed
  jars while the container is stopped, `start`, wait for RCON. Velocity, Postgres, Redis and the
  resource pack are untouched. `BACKENDS=` and `FORCE=1` narrow/force.
- The daemon is shared with the host, so no bind mounts: `docker compose cp` is the transport.
- **itzg overwrite semantics (read in `/image/scripts/start-setupMounts`).** On every start itzg
  syncs `/plugins` -> `/data/plugins` with `mc-image-helper sync --skip-newer-in-destination`
  (default, `SYNC_SKIP_NEWER_IN_DESTINATION=true`). Jars are therefore copied into both directories
  (`/plugins` lives in the container's writable layer and survives `stop`/`start`), and the compose
  file sets `SYNC_SKIP_NEWER_IN_DESTINATION=false` so the image's `/plugins` is always authoritative
  after a recreate (`make docker-up`) and an mtime accident can never keep a stale jar.
- `make dev-content` (`scripts/dev-deploy.sh content`): copies `content/` to `/content.new` in each
  backend, swaps it for `/content` (deleted files disappear), then `/astera admin reload all`. The
  `reload` command gains the pseudo-category `all` (the same loop the admin panel's server page
  uses). Languages, balance, hud, shops, lobby-npcs, worlds, flex and bet-policies are loaded at boot
  and are not in the reload registry; they need `FORCE=1 make dev-plugins` after `dev-content`.

### 3. Graceful drain

`/astera admin drain <target> [abort|keep]` (permission `astera.admin.drain`; console/RCON passes):

- publishes `ServerDrainStarted(target, abortMatches)` (Core domain event); the match plugin cancels
  every active match (`EndReason.Cancelled("server-drain")`) when `abortMatches`;
- after a 3 s settle delay (abort) or immediately (keep) moves every online player through
  `IServerTransferGateway` (`TransferReason.AdminMove`, BungeeCord `Connect` via Velocity), skipping
  players in a running match in `keep` mode.

`dev-deploy.sh` drains the backend that is about to stop onto the other one, and after the restart
runs `drain <restarted> keep` on the other side to bring the parked players home. Game restart
mid-match: match cancelled, `MatchAssignmentReceiver` and the drain both send players to the lobby.
Velocity `try` becomes `["lobby", "game"]` so a lobby that is down (crash, drain impossible) falls
back to game instead of a disconnect screen.

Known rough edge: players parked on `game` while the lobby boots trigger the game server's
stray-player bounce (`MatchAssignmentReceiver.bounceIfStray`): they see the wrong-server notice and
a failed transfer to the lobby, then stay on `game` until the drain-back. Acceptable for a dev loop;
a proper "lobby unavailable" state is not worth a Redis flag now.

## Consequences

- Kotlin change in one plugin: 25-45 s build and about 55 s per restarted backend (Paper's own
  `Done` is ~25 s; itzg init runs several `mc-image-helper` JVMs and Paper bootstraps another JVM).
- `velocity.toml` and the compose env only take effect on the next `make docker-up` (Velocity is
  deliberately not restarted by the fast loop).
- Jars built on the host must match the container toolchain (JDK 25 / Kotlin target), same as the
  Docker builder.
