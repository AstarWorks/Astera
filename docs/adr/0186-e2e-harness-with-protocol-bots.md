---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0186: In-game E2E harness with real protocol bots (`make e2e`)

## Status

Accepted. Realises the "Paper testkit with fake players" planned in
[[adr/0174-headless-game-simulator]] section 4, but as a black-box client instead of a
server-side plugin.

## Context

"Done" used to mean `./gradlew check` plus a docker boot with 0 ERROR. The founder then logged in
and found the HUD not rendered and commands broken. Unit tests and `:tools:game-sim` never
exercise the Paper adapters: packets, the Brigadier command tree a client receives, scoreboard,
boss bar, action bar, resource pack push, GUIs. The orchestrator (no human, no Minecraft client)
needs to verify **player-visible** behaviour itself.

## Decision

### Bot approach: a real protocol client (option a)

[MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib) **26.3** (`org.geysermc.mcprotocollib:protocol`,
release tag == game version; resolved from `repo.opencollab.dev`, scoped to the groups it needs in
`settings.gradle.kts`) joins an offline-mode backend and records every clientbound packet.

Evidence for the choice:

- It publishes a `26.3` release (and `26.2`, `26.1-1`) on the day-0 protocol of our Paper 26.3;
  its sources contain the packets we need (`ClientboundCommands`, `ClientboundCommandSuggestions`,
  `ClientboundBossEvent`, `ClientboundSetObjective/Score/PlayerTeam/DisplayObjective`,
  `ClientboundResourcePackPush`, `ClientboundOpenScreen`, `ClientboundContainerSetContent/Slot`,
  action bar as `SystemChat(overlay)` / `SetActionBarText`).
- It is a JVM library: the harness is Kotlin/JUnit, versions live in `gradle/libs.versions.toml`,
  no second toolchain. node-minecraft-protocol/mineflayer would add Node plus a `minecraft-data`
  lag risk (the 26.x line is not in its tagged data set at the time of writing).
- Option (b), a dev-only Paper plugin faking players, would capture packets *before* the client
  codec and misses command-tree/permission filtering as the client sees it; it also puts test
  code in a plugin jar. Not needed because (a) works.

### Test backend

`docker/docker-compose.e2e.yml` (own compose project `astera-e2e`, profile `e2e`, so it never touches
the dev stack or its volumes): one Paper server with `ASTERA_SERVER_ROLE=all`, `ONLINE_MODE=FALSE`,
no Velocity, no Postgres/Redis (in-memory repositories), RCON, its own `e2e-data` volume, ports bound
to `127.0.0.1` only, plus its own resource-pack nginx. The image is the same `docker/Dockerfile`
tagged `astera/minecraft-server:e2e`.

### Harness `:tools:e2e` (not shipped, not in `check`)

- The regular `test` task is disabled; the suite is the `e2e` task (`./gradlew :tools:e2e:e2e`).
- `Bot` records resource pack pushes (and answers with a chosen policy: load / decline / fail),
  chat, action bars, boss bars, sidebar (objective, scores, team prefix/suffix), command tree,
  tab-complete replies, windows and inventory slot packets. `Rcon` gives server-side truth
  (`op`, `tp`, `execute if items ...`, `data get`). `ServerLog` reads the backend log.
- Checks are **soft**: `E2eReport.check(suite, name, channel) { ...; evidence }` records PASS/FAIL
  with evidence and carries on, so one broken channel does not hide the others; the test method
  fails at the end. `build/e2e-report.md` lists every check, its channel (the packet / command /
  GUI it is attributable to) and the captured evidence.
- Bots are spaced 4.3 s apart (Paper's `connection-throttle` rejects faster logins from one IP)
  and use a fresh name per bot (inventory, op status persist per offline UUID).

### Running

`make e2e` (`scripts/e2e.sh`): fresh volume, build + start the backend, build the runner image,
run the suite **inside the compose network** (`docker/Dockerfile.e2e-runner`; a Docker daemon shared
with the host publishes ports somewhere the calling shell cannot reach, and bind mounts would be
empty, the same constraint as `docker/Dockerfile`), copy `build/e2e-report.md` out, stop the backend.
Exit code = suite result. `E2E_KEEP=1` leaves the backend running, `E2E_REUSE=1` keeps the world.
On a developer machine with loopback-published ports, `./gradlew :tools:e2e:e2e` runs the suite
directly against `127.0.0.1:25570` (RCON `25571`).

## Consequences

### Positive
- The orchestrator can prove HUD, commands, GUIs, kit and match flow from the client's side.
- Failing checks name their channel, so the owner of the broken adapter is obvious.

### Negative / trade-offs
- A run takes minutes (image build, world generation, 4.3 s login spacing).
- Item identity is checked through RCON (`execute if items ...`), because the client sees numeric
  registry ids only.
- Pixel-level rendering (does the glyph actually draw) is still not covered: the bot proves what is
  *sent*, not how the real client draws it.

## Alternatives considered

- Node `mineflayer`: second toolchain, protocol data lag.
- Fake-player Paper plugin: misses the client codec / command-tree view, ships test code in a plugin.
- Testcontainers from the JVM test: needs the host to reach container ports, which is not true on
  the shared-daemon dev boxes.
