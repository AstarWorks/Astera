---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0221: Bots inside the LIVE stack through Velocity modern forwarding (`make live-bots`)

## Status

Accepted. Extends [[adr/0186-e2e-harness-with-protocol-bots]] (QA-1, isolated offline backend) to the running
`astera-dev` stack.

## Context

`make e2e` proves behaviour on a throw-away standalone backend. It cannot see what only the real topology
produces: the lobby / game split, the queue hand-off over Redis, Postgres-backed overlays, content and plugin
jars as actually deployed, the Velocity `Connect` hop. The founder plays on that stack, and the orchestrator has
no Minecraft account to join it with (Velocity is `online-mode=true`).

## Decision

### Bots speak Velocity modern forwarding themselves

`lobby` and `game` run `ONLINE_MODE=FALSE` with Paper `proxies.velocity.enabled=true` and a shared secret.
Paper therefore accepts any client that answers its `velocity:player_info` login plugin request with
`HMAC-SHA256(secret, body) ++ body` (`body` = varint version 1, client address, UUID, name, no properties).
`VelocityForwarding` does exactly that, so a bot connects **directly to a backend** inside the compose network as a
forwarded player. No Mojang account, **no change to Velocity, no port published**. Names are `bot_*`, UUIDs are
the offline-style UUIDs of the name, so they never collide with a real account.

The secret is read from the running lobby backend (`/data/config/paper-global.yml`) by `scripts/live-bots.sh` on every
run and handed to the runner container as the environment variable `VELOCITY_FORWARDING_SECRET`. It is never
written to a file, committed, or printed.

### Cross-server hops are emulated

Velocity implements `BungeeCord Connect`; a backend that sends it to a direct-connected client reaches nobody.
`Bot` registers `bungeecord:main` (as Velocity does for real clients, otherwise Paper drops the message), and when
`Connect <server>` arrives it disconnects and logs in to that backend with forwarding again (`hops` records the
sequence). Limitations: a bot lands on the target's join spawn like a fresh login (the proxy does the same), the
hop costs a reconnect (~5 s because of Paper's per-IP connection throttle), and a `Connect` that the server fails to
send is *not* papered over silently: the check fails, and only then does the harness hop the bots by hand
(`forceHop`, labelled `(forced)` in the report) so the rest of the loop can still be exercised.

### `make live-bots SCENARIO=smoke|weapons|match|all`

`scripts/live-bots.sh` builds the existing runner image (`docker/Dockerfile.e2e-runner`, tag `astera/e2e-runner:live`),
copies the **live** `/content` out of the lobby container (the weapons under test are what is deployed, not what the
checkout holds), starts a one-shot runner on `astera-dev_default`, and copies `build/live-report.md` out (same
format as `make e2e`). The two data volumes are mounted read-only for log assertions and stale-op sweeping. The
stack is never restarted or redeployed. Gradle task `:tools:e2e:live`; the `live` package is excluded from
`:tools:e2e:e2e`, and neither is part of `check`.

- `smoke`: two bots join the lobby: kit slots, sidebar, HP line, `/astera menu`, no raw i18n keys, no server errors.
- `weapons`: every item with a `resource_type`, every binding (`right_click`, `left_click`, `sneak_right`, `drop`) cast
  at a second bot: particle / entity / sound packets arrive, target HP drops (server `Health`, up to 3 casts),
  energy (food bar, ADR-0194) or ammo (status line) reacts, no boss bar. It first tries a real `norma` match
  (damage applies only there); if no match can start it falls back to the bare game backend, where bots are
  re-hopped whenever the server sends them away.
- `match`: queue `norma` -> transfer -> ACTIVE -> A kills B -> death screen -> respawn -> `/astera match end` (RCON
  console) -> back on the lobby with kit, sidebar and the reward message.

### Safety rules (a real player may be online)

Bots never op anybody but themselves (`op` refuses non-`bot_*` names), are deopped again on both backends after every
test, kicked and disconnected in `@AfterEach`; stale `bot_*` ops from an aborted run are swept at start. `clear` and
`items give` only ever name bot players; no `@a` / `@e` selectors, no chat, no broadcasts. The match scenarios refuse to
start while any match is running on the game backend. Unavoidable and visible to others: vanilla join / leave
messages and advancement toasts of the bot names, so runs on a lobby with a real player are kept short.

## Consequences

- The orchestrator can verify the deployed stack end to end without Minecraft accounts.
- The bots are only as good as the shared secret staying inside the compose network; anyone who can reach a
  backend port from that network could do the same (they already can: backends trust the proxy). The backends'
  ports stay unpublished.
- First findings (see the QA-2 report): the queue -> match transfer is dead on arrival, no stage can be found on the
  game backend, melee `left_click` does not drain energy.
