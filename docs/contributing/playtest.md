# Playtest (Docker)

Goal: boot the server, log in with a Minecraft Java client, get weapons, play a match.

## Guided first join (ADR-0242)

Use a server built by GitHub Actions for this change. Join with a fresh profile; close the
welcome dialog. Every step shows a title/subtitle, a repeated action-bar hint and a private
floating target marker. Try the flow in English and Japanese (`/astera lang ja`).

| Step | Do this | Expected |
|---|---|---|
| Menu | Empty your hand or hold the compass, press F | Main menu opens; guide points east to the training island |
| Range | Close the menu and walk across the east bridge | Entering the training area advances; waiting in the plaza does nothing |
| Rack | Right-click the Knight's Sword frame and select the sword | Successful rack delivery advances; a refused claim does not |
| Primary | Approach the marked melee dummy and left-click with the sword | A landed hit advances; swinging at air does not |
| Secondary | Face safe ground, sneak + right-click with the sword | Accepted dash advances; rejected casts do not |
| Reload | Take a pistol from the rack, select it, fire with RC, press Q | Wait for ammo to refill; the pistol now reloads with Q |
| HUD | Watch for four seconds | Food bar is shared energy; ammo and cooldown icons / hotbar sweep explain readiness |
| Shop | Put the weapon away; F → Shop or click the marked merchant | Opening the shop advances; no purchase is needed |
| Vault | Sneak+F → Vault, or F → Customize → Vault | The real vault window opens; no deposit is needed |
| Queue | Follow the south marker and step onto a queue pad | Tutorial completes, queue entry proceeds, first graduation grants 50 GOLD |

The compass stays in hotbar slot 8; inventory crafting-grid buttons offer the same lobby
navigation. Q on the compass opens queue selection. Weapons bind their own inputs: this
lesson uses sword LC / sneak+RC and pistol RC / Q; check each other weapon's lore.

At any wait, `/astera tutorial status` describes the next action. `/astera tutorial skip`
pauses and removes the marker; `/astera tutorial start` resumes that step. Disconnect and
rejoin both while active and while skipped. Restart the backend mid-step and verify the
same cursor returns. Paper stores standalone progress under
`plugins/Astera-Core/tutorial-progress/`; network servers use Postgres V65 instead.
After completion, `tutorial start` replays the lesson without another graduation reward.
Do not delete a real player's progress to test rewards; use a fresh test profile.

On an ALL backend, enter a match mid-tutorial: hints/markers disappear and actions cannot
advance it; return to the lobby to continue. GAME backends never enroll or present the
lesson. Verify that one player's marker is invisible to another and that quit, skip and
shutdown remove it. Client appearance and beacon visibility still require this playtest.

CI coverage: `TutorialScenarioTest` drives a fresh player through F, movement, rack claim,
landed primary, secondary, Q reload, HUD observation, shop/vault and a real queue pad.
It also covers skip/reconnect, role and match guards; unit/storage tests cover replay
protection, cursor reopening and Postgres round trips. No local build/test is required for
this task; the orchestrator pushes the committed branch for GitHub Actions verification.

## 1. Build and boot

```bash
cp docker/.env.example docker/.env     # set OPS=<your Minecraft name>
make docker-up                          # builds 7 jars + bakes content/, generates the Velocity secret, starts the stack
make docker-logs                        # wait for "Done (...)!" on lobby AND game, all Astera-* enabled
```

`make docker-up` rebuilds the whole image every time (minutes, both backends restart). For the
edit-test loop use the fast targets below instead (start with the decision table).
The stack is Velocity (proxy) + `lobby` + `game` (two Paper servers) + Postgres +
Redis; see `docker/README.md` and ADR-0179.

### Automatic deploy of `origin/main` on the founder's host

`scripts/dev-autodeploy.sh` fetches every 60 seconds and deploys into the detached checkout
`/IdeaProjects/Astera/.claude/worktrees/dev-deploy`. This is the checkout that owns the
`astera-dev` Compose stack; its gitignored `docker/.env` holds the existing forwarding secret.
No GitHub CI, webhook or external service is involved.

- Start/resume: `make dev-autodeploy-up`; pause/stop: `make dev-autodeploy-down`
  (waits for an active deploy to finish); inspect: `make dev-autodeploy-status`.
- Events: `/IdeaProjects/Astera/.claude/state/dev-deploy/deploy.log`; detailed build output:
  `build.log` in that directory. `last-sha` records successful deployment; `attempted-sha`
  prevents retrying a failed push, including after restarts. The next push diffs from `last-sha`.
- Docker/compose changes are never auto-deployed: the watcher logs `manual deploy needed ... entire
  range held` and keeps `last-sha`. Deploy by hand from a checkout of that sha
  (`make dev-content && FORCE=1 make dev-plugins`, then
  `docker compose up -d --build --no-deps bot-runner resource-pack`), then tell the watcher with
  `scripts/dev-autodeploy.sh mark-deployed <sha>`. It writes `last-sha` and `attempted-sha`, clears
  `manual-needed` and logs `deployed <sha> [manual]`; later commits are diffed from that sha.
- Runtime: Docker container `astera-dev-autodeploy`, with `unless-stopped`, supervises a
  `docker exec` watcher in this development container (also `unless-stopped`). The host
  Docker daemon cannot bind-mount this container's repo, so the supervisor mounts only the
  Docker socket. Both resume after a host reboot; the devcontainer and its checkout must be
  retained. `up` installs a runtime copy of the script in the state directory; rerun `up`
  after changing watcher code. `docker logs astera-dev-autodeploy` shows events too.
- Content YAML runs `dev-content`; plugin/build changes run `dev-plugins`; pack assets
  (models, textures, sounds, sprites, effects, HUD, fonts, animations, pack assets, shaders)
  and pack-builder changes rebuild the resource-pack service then run `dev-pack`.
  Core/build changes also rebuild the pack because its builder consumes Core glyphs.
  Mixed changes stage new content before restarting plugins (supports breaking YAML schema
  changes), then apply plugins, pack, content reload. First start deploys all three.
- Deploys require 10 GiB free on the checkout filesystem before building; insufficient
  space records a failed attempt, leaving live files alone. Builds finish before live
  files change. Rollback copies restore previous jars/content
  and the previous pack image after an apply failure. Backends may restart and matches
  may be cancelled. Postgres/Redis and all named data volumes remain untouched.
  A rollback failure is logged explicitly and requires manual recovery; DB migrations
  performed by plugin startup cannot be automatically undone.
- Dockerfiles, `docker/**` and Compose changes hold the **whole range**. A
  `manual deploy needed` event and `manual-needed` state file are the local notification;
  no chat message is sent. Bot-runner/tooling changes outside these dispatch rules are
  also not rebuilt automatically. Unknown paths (e.g. docs) record `[none]`.
  For a manual deploy, pause the watcher, switch the deploy checkout with
  `git checkout --detach origin/main`, and apply the reviewed Docker changes there.
  Write the deployed `git rev-parse HEAD` to `last-sha`, remove `manual-needed`, then resume. To explicitly
  retry a failed SHA, pause, remove `attempted-sha`, then resume.

All automatic work holds `/tmp/claude-1000/astera-dev-deploy.lock`. Hold the same lock for
manual operations against this stack. Never run `docker-down`/`docker-nuke` as part of
watcher recovery. Only the dedicated detached checkout is switched; other worktrees
are not changed.

### Fast loop: change code, content or the pack without `make docker-up` (ADR-0186, ADR-0224)

**Which command for which change** (restart = a backend stops and boots, about 55 s; players are
moved to the other backend, not kicked):

| You changed | Run | Restart? | You wait |
|---|---|---|---|
| Kotlin method bodies (damage formula, log text, a branch, a constant) | `make dev-hotswap` | no | build 10-45 s + 2-4 s |
| Kotlin: added a method / field / class / enum entry, changed a class hierarchy | `make dev-hotswap` | no | same |
| Kotlin: anything above, but the stack is not the hotswap one (first time) | `make dev-hotswap-up` once, then `make dev-hotswap` | one-time recreate | image build + boot |
| Kotlin wiring: what `onEnable` / `*Wiring` registers (a new listener, command node, service), `plugin.yml`, a changed initialiser or constructor that must run again | `make dev-plugins` | yes, changed backends only | build + 55 s per backend |
| Anything `make dev-hotswap` answers "not possible" for | it restarts by itself (same as `make dev-plugins`) | yes | build + 55 s per backend |
| `content/` YAML: skills, status, items, armor, stages, game-modes, mobs, quests, recipes, normas, languages, `balance/element-matrix.yaml`, `balance/status-effect-interactions.yaml`, bet-policies, ... (full list below) | `make dev-content` | no | about 3 s |
| Item name / lore / model of items players already hold | `make dev-content` (it runs `/astera items refresh`) | no | about 3 s |
| Textures, models, HUD art, sounds-in-pack (anything the resource pack carries) | `docker compose -f docker/docker-compose.yml up -d --build resource-pack`, then `make dev-pack` | no | pack build + 1 s |
| Content that is only read at boot (list below) | `make dev-content`, then `FORCE=1 make dev-plugins` | yes | 55 s per backend |
| `velocity.toml`, compose env, Dockerfile | `make docker-up` | everything | minutes |

`make dev-hotswap-up` (once per fresh stack) starts the same stack with the two backends on
JetBrains Runtime 25 and an in-container hotswap agent (loopback port, nothing published). It is dev
only: the production image is untouched. `make docker-up` goes back to the stock image.

`make dev-hotswap` builds the 7 shadow jars, compares them with what each backend boots with, and
sends the changed classes to the running JVM. What that does and does not do:

- Works live: method bodies, new / removed methods and fields, new classes, new enum entries,
  hierarchy and modifier changes. State of existing objects is kept (a service's counters, caches,
  registered listeners).
- Applied by the agent, but the new code only runs where it is *called again*: a field added to an
  object that already exists is `null`/`0` until something sets it (initialisers do not re-run), an
  already created lambda keeps its old body, boot-time wiring does not run again. If your change
  must re-run `onEnable`, use `make dev-plugins`.
- Refused (the script then restarts that backend): a changed `plugin.yml` or other resource in a
  jar, or a class the JVM refuses to redefine. Nothing is applied in that case.
- Verify with the server log (`make docker-logs`): the script prints the changed classes, and your
  log line shows the next time that path runs.

`make dev-plugins` details:

- Only jars whose SHA-256 differs from `/plugins/<jar>` in the container are copied, and only a backend with a
  changed jar restarts. `BACKENDS=lobby make dev-plugins` limits it to one backend, `FORCE=1` redeploys
  unchanged jars (use it to restart for non-reloadable content, see below).
- Order is lobby, then game. Before a backend stops, `/astera admin drain <other>` runs over RCON:
  its players are moved to the other backend through Velocity, running matches on `game` are cancelled
  (no winner) and their players go to the lobby. After the backend is back, parked players are moved
  home (`drain <backend> keep` on the other side, which skips players in a running match).
- What a player sees: lobby restart = a switch to the game server (the game server shows its normal
  "wrong server" notice and tries to send them back, which fails while the lobby boots; they are moved
  once it is up). Game restart mid-match = match cancelled, back in the lobby. Nobody gets a
  disconnect screen. If the other backend is down, the players cannot be parked and Velocity's
  `try = ["lobby", "game"]` decides.
- Jars go into `/data/plugins` (what Paper loads) and `/plugins` (what itzg syncs from on every start,
  `SYNC_SKIP_NEWER_IN_DESTINATION=false`), so a plain container restart keeps them. A later
  `make docker-up` recreates the containers from a fresh image and wins again.
- Needs the stack up (`make docker-up` once) and RCON (`docker/.env` `RCON_PASSWORD`, default fine).
- None of these targets touch Velocity, Postgres or Redis.

`make dev-pack` (`/astera admin pack reload [force]` on each backend) re-reads the pack hash from the
pack host (`ASTERA_RESOURCE_PACK_SHA1_URL`, the `astera.zip.sha1` the pack container serves) and, when
it changed, re-sends the pack to every online player; players who join later get the new one
automatically. The client keeps the same pack id and swaps the pack in place (the pack apply screen
flashes once). If `ASTERA_RESOURCE_PACK_SHA1` is set in the environment it wins over the fetched hash:
leave it unset for development. `PACK_FORCE=1 make dev-pack` re-sends even when the hash is unchanged.

**`reload all` / `make dev-content` covers** (live, all-or-nothing per category): advancements,
anomaly, armor, balance-combat (`balance/element-matrix.yaml` and
`balance/status-effect-interactions.yaml`), bet-policies, blocks, changelog, combat-depth/combos,
dialogs, game-modes, gathering, generators, gui, items, languages, lobby-kit, login-bonus,
matchmaking, mobs, music, news, normas, particles, permissions, pve/*, quests, recipes, skills,
skills-progression, sounds, stages, status, tutorials. Skills, statuses and item specs are read from
their registries when used, so the next cast / hit / click uses the new values. Items already in an
inventory keep the name, lore and model they were given with until `/astera items refresh` (which
`make dev-content` runs for you); their attributes and skill bindings are live already.

**Not reloadable (loaded at boot)**, push with `make dev-content` and then `FORCE=1 make dev-plugins`:
`shops/` (a reload would re-seed limited stock), `lobby-npcs/`, `worlds/`, `flex/glow-profile.yaml`,
and the rest of `balance/` (`starter-grant`, `block-durability`, `resource-defaults`,
`backup-policy`). `hud/` is resource-pack art, not server content: rebuild and push the pack.

Why not `/reload` or PlugMan-style unload/load: see ADR-0186. Why the agent instead of a JDWP
debugger push: see ADR-0224.

## 2. Connect

Minecraft Java Edition 26.3 -> Multiplayer -> Direct Connect -> `localhost:25565`
(host:25565). **Velocity runs `online-mode=true`: you need a real, paid Minecraft Java
account and you join under that account's name** (there is no offline / any-username
login any more, and the backends are not reachable from outside the compose network).
Your name and skin are the real ones; your UUID is the real Mojang UUID.

You must be op to use the admin commands: put your exact Minecraft name in `OPS=` in
`docker/.env` before `make docker-up` (both servers op you; the name is resolved to
your real UUID through Mojang). To op someone later:
`docker compose -f docker/docker-compose.yml exec lobby rcon-cli op <name>` (and the same
with `game` if they need op there too).

You always land on the **lobby** server (`try = ["lobby"]`). Shop, vault, queue and NPCs
are there; matches run on the **game** server (section 4).

### Lobby menu kit (compass + keybinds + crafting-grid buttons)

The hotbar is yours (ADR-0232): on the lobby (and on `ALL` backends while you are not in a match) the only menu
item is the **Menu compass in slot 8**; slots 0-7 are for weapons. A join hint on the action bar lists the keys:

| Input | Result |
|---|---|
| Right-click compass, or **F** with no weapon in hand | main menu (same as `/astera menu`) |
| **Sneak+F** with no weapon in hand | vault / loadout (operators and `astera.admin.panel` holders get the admin hub instead, ADR-0233) |
| **Q** with the compass selected | queue |

Open your **inventory (E)**: the 2x2 crafting grid shows Menu / Profile / Shop / Settings and the result slot
shows Queue; click one and the inventory closes and that screen opens. Layout:
`content/gui/kit/lobby-kit.yaml` (ADR-0185, ADR-0232). Check that none of these items can be moved, dropped,
swapped (number keys), double-click collected, crafted, or kept after death; that F with a weapon in hand does
not open a menu; and that in a match the hotbar has zero menu items and F is vanilla, with the compass back
after returning to the lobby.

## 3. Get weapons

Every command is a subcommand of one root: `/astera`. Type `/astera help` for what you may use, or press Tab after `/astera ` (admin areas such as `admin`, `mod`, `items` only show for ops).


```
/astera items give <yourName> <item-id>      # any id in the table below
```

Or buy them: right-click the "Lobby Merchant" villager near spawn, or `/astera shop`
(new players start with 100 GOLD; prices below). `/astera vault` or an ender chest opens your personal vault.
Skill damage also hits mobs (`/summon zombie` to test). `Shift+Q` reloads any gun.

### Admin panel (`/astera admin panel`)

Ops (and anyone with `astera.admin.panel`) get a chest-GUI hub:

| Page | What it does |
|---|---|
| Weapons & items | Every loaded item, tabs for guns / swords / staffs / support / other, paginated. Click = give 1, shift-click = give 16. Shift-click a tab = one of each in that category. "Full kit" = one of each gun, sword and staff. |
| Match control | Active matches (mode, stage, phase, players, time): click = next phase, right-click = end, shift-click = abort. Queue lanes: click = force start now (ignores min players and countdowns), right-click = start the lane with just you. |
| Players | Online heads, then per player: teleport to / bring, toggle gamemode, heal (Astera HP), clear statuses, give currency (presets), kick (shift-click). |
| Server | Reload all content, validate content (issue count in chat), maintenance toggle, health summary. |
| Self tools | God mode, fly, heal, clear cooldowns, refill ammo/energy. |

Every action is permission-checked per click and written to the audit log.
The quickest solo test loop: `/astera admin panel` -> Weapons & items -> Full kit ->
Match control -> your lane -> right-click ("just me").

### Watching a skill from outside (`/astera skill preview`)

First-person casts are hard to read. `/astera skill preview <skill-id> [view=side|opponent|behind|stay] [speed=1|0.5|0.25] [distance=6]`
(permission `astera.skill.preview`, ops) spawns a player-like Mannequin caster holding the skill's weapon 3 blocks in
front of you and a no-AI husk 4 blocks past it, then makes the dummy cast the skill through the real pipeline
(damage, hit_stop, dash_strike, VFX). Views: `side` = spectator camera `distance` blocks off the caster-target line,
`opponent` = from the target's spot, `behind` = over the caster's shoulder, `stay` = you do not move. Your gamemode and
position come back when the preview ends (or you quit). Dummies are removed 120 ticks after the cast.

- `speed<1` slows the **whole backend's** tick rate (`/tick rate`) until the preview ends; ops only.
- `/astera skill preview again` repeats the last one. `freeze`, `step [n]`, `unfreeze` drive `/tick freeze|step` for frame-by-frame reading.

### Weapon roster (WP7)

| Item | Class | Right-click | Sneak + right-click | Left-click | GOLD | Crit-free TTK on 20 HP |
|---|---|---|---|---|---|---|
| `pistol` | gun | shot (7 phys, 12 rounds) | | | 60 | 2.6 s |
| `rifle` | gun | long-range shot (8 phys, 20 rounds) | backstep (hop + sprint) | | 120 | 3.0 s |
| `shotgun` | gun | cone blast (13 phys, knockback, 6 shells) | backstep | | 110 | 1.4 s |
| `knight-sword` | sword | | dash (lunge) | slash (8 phys arc) | 50 | 2.1 s |
| `greatsword` | sword | | | cleave (13 phys, wide, heavy knockback) | 140 | 1.5 s |
| `dagger` | sword | | dash (lunge) | stab (5 phys + bleed DoT) | 70 | 2.5 - 3.0 s |
| `frost-staff` | magic (water) | frost shard (slow + wet) | frost nova (AoE slow) | | 90 | 4.7 s |
| `fire-staff` | magic (fire) | flame burst (burn DoT) | | | 100 | 4.0 s |
| `thunder-staff` | magic (electric) | lightning bolt (shock stun) | | | 160 | 3.7 s |
| `verdant-staff` | support (earth) | thorn bolt (poison) | regrowth (cleanse + heal-over-time for you and allies within 10 blocks) | | 130 | 3.8 s |

Statuses: `burn`, `freeze`, `wet`, `shock`, `poison`, `bleed`, `regen`, `dash`, `evasive`, `reloading`
(`content/status/`). Interactions (`content/balance/status-effect-interactions.yaml`): burn cancels
freeze, a shock on a `wet` target doubles the wet slow. Element loop: fire > wind > earth > electric > water > fire.

TTK numbers come from `./gradlew :tools:game-sim:test --tests '*WeaponRosterTtk*'`
(spam the primary input at a stationary 20-HP player; the test asserts every weapon lands in 1 to 6 s).
Players have 20-tick invulnerability frames, so a direct hit lands at most once a second whatever the
fire rate; a critical (10 percent, x2) can shorten any of these (shotgun / greatsword can one-shot).
The shop, starter grant, recipes and tutorials all use this roster (the old `example-*` items were removed in WP15).

## 4. Run a match (queue)

Matches form automatically from the queue; the config is
`content/matchmaking/queue.yaml` (shipped with `min_players: 1`, so you can
start alone; friends who join the queue before the countdown ends are
split across the two teams).

```
/astera queue join skirmish         # 2 teams (red / blue) on example-arena
/astera queue join annihilation  # 3 teams (red / blue / yellow) on annihilation-arena
/astera queue join break-core      # siege-isles: break the enemy emerald core block (yours is protected)
/astera queue join carry-tnt       # siege-isles: step into the centre-north zone for the TNT, run it into the enemy base (2 deliveries)
/astera queue join capture-wool    # siege-isles: stand alone in a wool zone for 10 s to capture it; 2 of 3 zones win
/astera queue join steal-jewelry   # siege-isles: step on the enemy diamond pedestal (inside their base), bank in your base; steal 2 to win
/astera queue join battle-loyal    # siege-isles: team deathmatch, 5 kills win
/astera queue status         # your position + how many players are queued
/astera queue leave          # optional: leave the queue
```

What happens next (no further commands):

0. The queue lives on the lobby. When a match forms, the lobby sends the teams to the
   game server over Redis, and once the game server accepts, moves every queued player
   there (they see a short "connecting" transition). If the game server is busy (it
   hosts one match at a time) the queue holds and you are told the arena is in use. When
   the match ends, everybody is sent back to the lobby automatically after ~8 s.
   Players who do not arrive within 45 s are dropped from that match. Anyone who
   connects to the game server without an assignment is sent back to the lobby.

1. Once `min_players` are queued a countdown starts (default 10 s, announced
   in chat at 10/5/3/2/1). It restarts if someone leaves or disconnects, and
   is cancelled if the queue drops below the minimum. A full queue
   (`teams x players_per_team`) starts immediately.
2. "Match found!": you are put on a team (sizes balanced within 1) and
   teleported to a spawn of your own team (`content/stages/example-arena.yaml`
   for `skirmish`, `content/stages/annihilation-arena.yaml` for `annihilation`).
   Teammates get different spawns of that team (least-used first).
3. A 10 s in-arena COUNTDOWN (`match_countdown_seconds`), then the match is ACTIVE.
4. When the match ends (win condition, or `/astera match end <matchId>`) everyone
   is teleported back to the lobby and can `/astera queue join skirmish` again.
   `/astera match list` shows the match id while it runs.

If the arena is still in use, the queue waits (you are told once) and the
next match starts as soon as it is free.

Tuning (`content/matchmaking/queue.yaml`, `/astera reload matchmaking` applies it):
`check_interval_seconds`, `queue_countdown_seconds`, `match_countdown_seconds`, and
per lane `stage`, `teams`, `players_per_team`, `min_players`.

Admin shortcuts (skip the queue): `/astera match start example-arena`, then
`/astera match entry <matchId> <name>` (teleports and auto-balances teams), plus
`/astera match phase|end|abort|list`.

### Bots: play against AI or fill a queue (ADR-0247)

The `bot-runner` compose service starts with the stack (`make docker-up`). It turns a request on Redis into bots
named `Bot_Ash`, `Bot_Birch`, ... that log in, queue for a lane and fight like players, then leave after the match.

```bash
make bots N=2 LANE=skirmish     # two bots queue skirmish; prints spawning -> queued (or the failure reason)
make bots N=1 LANE=skirmish     # one bot waits in the queue; join the lane yourself: /astera queue join skirmish
make bots N=3 LANE=annihilation
```

- A PvP lane needs a player on every team, so `N=1` plus you is a duel; `N=2` alone is bot vs bot (the bots play it out and leave;
  `/astera match list` shows it while it runs).
- Cap: `BOT_MAX` bots at once (default 8, set in `docker/.env`). A request over the cap is rejected as a whole.
  Logins are ~5 s apart (Paper's connection throttle), so 4 bots take ~20 s.
- The same request can be published by hand: `docker compose -f docker/docker-compose.yml exec redis redis-cli PUBLISH
  astera:bots:request '{"requestId":"x1","lane":"norma","count":2,"requestedBy":"me"}'`; replies come on
  `astera:bots:status`. Protocol and AI details: `docs/adr/0247-ai-protocol-bots-and-bot-runner.md`.
- Logs: `docker compose -f docker/docker-compose.yml logs -f bot-runner`. After changing the AI:
  `docker compose -f docker/docker-compose.yml up -d --build bot-runner` (`make dev-plugins` does not rebuild it).
- Bots that are still waiting leave after 10 minutes; `docker compose ... restart bot-runner` drops all of them.
- E2E: `make e2e` runs `BotVsBotE2eTest` (two AI bots, a whole Norma match, no RCON kills).

## Resource pack (custom HP bar, ADR-0182)

Vanilla hearts are a lossy mirror of Astera's HP, so Astera ships a resource pack that
hides them and draws a red bar with the exact `current/max` where the hearts were.
Without the pack you still play: vanilla hearts plus an `HP 200/200` text line.

- **Build / serve:** the `resource-pack` compose service builds the pack from `content/`
  (textures, models, sounds, HUD art) and serves `http://localhost:8099/astera.zip`
  (+ `astera.zip.sha1`). `make docker-up` rebuilds it with everything else. To build it
  alone: `./gradlew :tools:resource-pack:buildResourcePack` -> `build/resource-pack/`.
- **Server env** (paper service): `ASTERA_RESOURCE_PACK_URL` (what clients download;
  unset = no pack, no error), `ASTERA_RESOURCE_PACK_SHA1` or `ASTERA_RESOURCE_PACK_SHA1_URL`
  (default `<url>.sha1`, read once at boot), `ASTERA_RESOURCE_PACK_REQUIRED` (default false).
  Players off this machine need the host's LAN / public address in the URL.
- **On join** the client is prompted; accept it. Server list -> edit -> *Server Resource Packs:
  Enabled* skips the prompt next time.
- **Check:** hearts and armor bar gone, red bar with numbers above the hotbar's left half,
  it follows damage / healing, turns orange at 25 % HP, and stays while the cooldown text
  or a toast shows. Decline the pack and the hearts + `HP` text come back.
- **Tuning** (not verifiable headless): if the bar sits too high or low change
  `StatusHudGlyphs.ASCENT` and run `./gradlew :tools:resource-pack:generateHudArt`; the
  committed art under `content/hud/` is generated, never edited by hand.
- **Pack format:** `pack.mcmeta` uses `min_format` `[97, 0]` / `max_format` `[97, 1]`, the
  26.3 resource pack version read from `version.json` in the Paper 26.3 jar
  (`pack_version.resource_major/minor`). Supersedes the 75 / 1000 guess in ADR-0182.
- **HUD diagnostics:** every join logs `HUD channels for <player>: sidebar=..., bossbar=...,
  actionbar=..., pack=...` (INFO, ~3 s after join and again when the client reports the pack).
  No line, or `sidebar=none`, means that channel never reached the gateway.

## World and coordinates

The main world is superflat (surface block y=-61, you stand at y=-60); the lobby and the matches are not in it:

- The lobby is the generated void `lobby` world (ADR-0180) on backends that host the lobby.
- Matches are played on **sky islands the server builds itself** in the void `arenas` world (ADR-0246), created on
  every backend that hosts matches. Top layer y=64, you stand at y=65. The stages live side by side in that world:
  `example-arena` (norma, around z=0: red island x=-30, blue x=+30, centre island, two outposts),
  `annihilation-arena` (around z=-300: three team islands on a triangle of radius ~33 around a centre island) and
  `siege-isles` (around z=300: red x=-33, blue x=+33, centre island, two outposts). The siege lanes share
  `siege-isles` (objective zones in `content/stages/siege-isles.yaml`), so one siege match runs at a time; their
  tunables are in `content/game-modes/<mode>.yaml`. Objective modes only work through their queue lane (the admin
  `/astera match start` has no mode key and runs plain Norma).
- To look at an arena: `/execute in minecraft:arenas run tp @s 0 70 0` (norma), `... 0 70 300` (siege), `... 0 70 -300`
  (annihilation). Falling below y=30 kills you.
- A stage names its Bukkit world with `world:` (default `world`, the server's main world); a stage with a `layout:`
  is built into that world on boot (versioned marker, see `content/stages/README.md`).

## Logs / reset

- Logs: `docker compose -f docker/docker-compose.yml logs -f velocity lobby game`
- Content is baked into the image at `/content` (outside the `/data` volume),
  so every `make docker-up` serves exactly the repo's `content/`; `make dev-content` replaces
  `/content` in the running containers (lost again when the containers are recreated).
- Reset world and data: `make docker-nuke` then `make docker-up`
  (`LEVEL_TYPE=FLAT` only applies to a freshly created world).
- Second stack next to a running one: use another project name and host ports, e.g.
  `ASTERA_PORT=25570 POSTGRES_PORT=5441 REDIS_PORT=6391 docker compose -p astera-other -f docker/docker-compose.yml up -d --build`.
- Single-server mode (no proxy) still exists for the simulator and quick local boots:
  `ASTERA_SERVER_ROLE` unset or `all` runs lobby and matches in one JVM.
