---
status: Accepted
date: 2026-10-31
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0310: Hotswap is the default for dev autodeploy; boot-code changes restart loudly

## Status

Accepted (number reserved by the manager). Extends [[adr/0224-dev-hotswap-live-pack-repush-and-reload-coverage]]
(which built the JBR + agent path but left it opt-in) and changes the default of
[[adr/0186-fast-plugin-and-content-redeploy]] (drain + restart stays the fallback). Dev stack only.

## Context

Founder 2026-10-31: 「そもそも鯖を止めずにできるは当たり前では？ktのjarですらそうあるべきだっていうのに」.

Autodeploy (`scripts/dev-autodeploy.sh`) ran `make dev-deploy` / `make dev-plugins` for every jar push. Both
drain, stop, copy and start each backend: measured about 40 s per backend (lobby then game), matches on the
backend cancelled. The hotswap path (`make dev-hotswap`, ADR-0224: 9-12 s end to end, no stop) existed but was
never used: the live backends ran `astera/minecraft-server:dev`, the autodeploy compose set excluded
`docker-compose.hotswap.yml`, `docker/hotswap/*` was ignored, and `dev-deploy.sh deploy` (jars + content) had no
hotswap branch.

Hotswap has silent-stale cases: redefinition never re-runs boot-once code. Wiring in `Astera*Plugin` /
`*Wiring*` (listeners, commands, services, tasks), constructors, instance and static initialisers, and added
fields on live singletons all keep the old behaviour while the agent answers `OK`.

## Decision

1. **Default.** The dev stack runs the JBR + agent image. Autodeploy's compose set is base + `docker-compose.hotswap.yml`
   (+ remote overlay), `docker/hotswap/*` and the overlay are part of the stack step (the `:dev-hotswap` image is built on the
   deploy host on top of `:dev` and shipped with `docker save | load`, never built on the VM), a plugins-only push
   calls `make dev-hotswap`, and a jars + content push calls `dev-deploy.sh deploy`, which now hot swaps the
   changed jars and reloads content in place. `FORCE=1` (stack step) and `ASTERA_HOTSWAP=0` keep the old restart.
2. **Loud restart detection, before the swap.** `scripts/hotswap-classify.sh`:
   - `paths <from> <to>` (autodeploy, before the build): a restart when the range touches `plugin.yml` or any
     plugin resource/migration, `Astera*Plugin.kt`, `*Wiring*` / `platform/wiring/*`, or build files that change
     the shaded jar.
   - `jars <old> <new>` (`dev-deploy.sh`, after the build, authoritative): the running `/plugins/<jar>` is
     copied out and compared class by class with `javap -p -c`. A restart when a non-class entry changed, an
     `Astera*Plugin` / `*Wiring*` class changed, a class's field set changed, or a constructor or `<clinit>`
     body changed (constant-pool indexes normalised away). Body-only changes, new methods and new classes swap.
   - the agent still answers `RESTART` for what the JVM rejects; `dev-deploy.sh` then restarts that backend.
   - `dry-run [N]` prints the source-level verdict for the last N commits (adds a source heuristic for
     constructor/initialiser lines).
3. **Logged, never silent.** `deploy.log` gets `hotswapped <sha>` when every changed backend was swapped, and
   `restart <sha> reason=<files/classes/agent text>` whenever any backend restarted (also in `build.log` with the
   per-backend `RESTART needed` line). `ASTERA_DEPLOY_OUTCOME` carries the per-backend result from
   `dev-deploy.sh` to autodeploy. The existing `deployed <sha> [steps]` line is unchanged.
4. **Periodic clean restart.** The watcher runs `make dev-plugins` with `FORCE=1` once per night
   (`ASTERA_NIGHTLY_HOUR`, default 04, JST host time) and logs `restart <sha> reason=nightly clean restart`, so
   boot-order bugs hidden by swaps and any drift surface within a day.

## Consequences

### Positive
- A jar-only push takes about 10-15 s per backend with no drain and no cancelled match (was about 40 s each plus a drain).
- Pushes that need boot code still restart, with the reason in `deploy.log`.

### Negative / Trade-offs
- **JBR is not production's Temurin.** Dev now runs JBR full time; no performance or GC conclusion from dev,
  and a JBR-only behaviour difference would not show up in prod. The nightly restart does not change that.
- The jar check is conservative: a constructor-body edit in any class restarts, including anonymous
  classes. A restart is the safe direction.
- Not detected: a changed *call site* that only matters at boot (for example a new `register(...)` call added
  inside an ordinary helper that `Wiring` invokes). Wiring files are caught; helpers they call are not. The
  nightly restart bounds the staleness; `astera admin reload` categories and a manual `FORCE=1 make dev-deploy`
  remain.
- Content in categories ADR-0224 lists as not reloadable (hud, shops, lobby-npcs, worlds, parts of balance, and arenas
  built at boot) used to be picked up by the jar+content restart; with a hotswap it is only reloaded in place.
  Push those with `ASTERA_HOTSWAP=0` or wait for the nightly restart until the content gaps are closed.
- `docker/hotswap/*` changes now trigger a stack step (image rebuild + recreate of lobby/game).

### Mitigations
- `deploy.log` is greppable (`grep -E 'restart|hotswapped'`).
- Rewire command (re-run wiring on the same JVM) and the content reload gaps are separate designs and
  would shrink the restart list.

## Alternatives Considered

- **Agent-side wiring set** (the agent answers `RESTART` for a configured class list): rejected, the exact
  class-level diff lives in the deploy script where javap is available and the verdict is logged there.
- **Only path-level detection**: misses constructor/initialiser/field changes in ordinary classes.
- **Keep restart as default, hotswap on request**: what existed; nobody ran it.

## References

- [[adr/0224-dev-hotswap-live-pack-repush-and-reload-coverage]], [[adr/0186-fast-plugin-and-content-redeploy]]
- `scripts/hotswap-classify.sh`, `scripts/dev-deploy.sh`, `scripts/dev-autodeploy.sh`
