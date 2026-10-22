# Playtest (Docker)

Goal: boot the server, log in with a Minecraft Java client, get weapons, play a match.

## 1. Build and boot

```bash
cp docker/.env.example docker/.env     # set OPS=<your Minecraft name>
make docker-up                          # builds 7 jars + bakes content/, generates the Velocity secret, starts the stack
make docker-logs                        # wait for "Done (...)!" on lobby AND game, all Astera-* enabled
```

`make docker-up` rebuilds every time; re-run it after changing code or `content/`.
The stack is Velocity (proxy) + `lobby` + `game` (two Paper servers) + Postgres +
Redis; see `docker/README.md` and ADR-0179.

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

## 3. Get weapons

```
/astera-items give <yourName> <item-id>      # any id in the table below
```

Or buy them: right-click the "Lobby Merchant" villager near spawn, or `/astera-shop`
(new players start with 100 GOLD; prices below). `/vault` or an ender chest opens your personal vault.
Skill damage also hits mobs (`/summon zombie` to test). `Q` reloads any gun.

### Admin panel (`/astera-admin`)

Ops (and anyone with `astera.admin.panel`) get a chest-GUI hub:

| Page | What it does |
|---|---|
| Weapons & items | Every loaded item, tabs for guns / swords / staffs / support / other, paginated. Click = give 1, shift-click = give 16. Shift-click a tab = one of each in that category. "Full kit" = one of each gun, sword and staff. |
| Match control | Active matches (mode, stage, phase, players, time): click = next phase, right-click = end, shift-click = abort. Queue lanes: click = force start now (ignores min players and countdowns), right-click = start the lane with just you. |
| Players | Online heads, then per player: teleport to / bring, toggle gamemode, heal (Astera HP), clear statuses, give currency (presets), kick (shift-click). |
| Server | Reload all content, validate content (issue count in chat), maintenance toggle, health summary. |
| Self tools | God mode, fly, heal, clear cooldowns, refill ammo/mana. |

Every action is permission-checked per click and written to the audit log.
The quickest solo test loop: `/astera-admin` -> Weapons & items -> Full kit ->
Match control -> your lane -> right-click ("just me").

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
/astera-queue join norma         # 2 teams (red / blue) on example-arena
/astera-queue join annihilation  # 3 teams (red / blue / yellow) on annihilation-arena
/astera-queue join break-core      # siege-flat: break the enemy emerald core block (yours is protected)
/astera-queue join carry-tnt       # siege-flat: step into the centre-north zone for the TNT, run it into the enemy base (2 deliveries)
/astera-queue join capture-wool    # siege-flat: stand alone in a wool zone for 10 s to capture it; 2 of 3 zones win
/astera-queue join steal-jewelry   # siege-flat: step on the enemy diamond pedestal (inside their base), bank in your base; steal 2 to win
/astera-queue join battle-loyal    # siege-flat: team deathmatch, 5 kills win
/astera-queue status         # your position + how many players are queued
/astera-queue leave          # optional: leave the queue
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
   for `norma`, `content/stages/annihilation-arena.yaml` for `annihilation`).
   Teammates get different spawns of that team (least-used first).
3. A 10 s in-arena COUNTDOWN (`match_countdown_seconds`), then the match is ACTIVE.
4. When the match ends (win condition, or `/astera-match end <matchId>`) everyone
   is teleported back to the lobby and can `/astera-queue join norma` again.
   `/astera-match list` shows the match id while it runs.

If the arena is still in use, the queue waits (you are told once) and the
next match starts as soon as it is free.

Tuning (`content/matchmaking/queue.yaml`, `/astera reload matchmaking` applies it):
`check_interval_seconds`, `queue_countdown_seconds`, `match_countdown_seconds`, and
per lane `stage`, `teams`, `players_per_team`, `min_players`.

Admin shortcuts (skip the queue): `/astera-match start example-arena`, then
`/astera-match entry <matchId> <name>` (teleports and auto-balances teams), plus
`/astera-match phase|end|abort|list`.

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

The playtest world is superflat: the surface block is y=-61, you stand at y=-60.
Everything you should see in a playtest uses that height: the lobby NPCs
(`content/lobby-npcs/*.yaml`, y=-60; logical world `lobby` = the main world),
the lobby spawn (the world spawn, x~0 z~0) and the `example-arena` /
`annihilation-arena` / `siege-flat` stages. The siege lanes share `siege-flat`
(red base at x=-40, blue at x=+40; objective zones in `content/stages/siege-flat.yaml`),
so one siege match runs at a time; their tunables are in `content/game-modes/<mode>.yaml`.
Objective modes only work through their queue lane (the admin `/astera-match start`
has no mode key and runs plain Norma). A stage names its Bukkit world with `world:`
(default `world`, the server's main world). The other stages (`siege-arena`,
`forest-arena`, `boss-arena`) keep y>=64 coordinates and are not playable on the flat world.

## Logs / reset

- Logs: `docker compose -f docker/docker-compose.yml logs -f velocity lobby game`
- Content is baked into the image at `/content` (outside the `/data` volume),
  so every `make docker-up` serves exactly the repo's `content/`.
- Reset world and data: `make docker-nuke` then `make docker-up`
  (`LEVEL_TYPE=FLAT` only applies to a freshly created world).
- Second stack next to a running one: use another project name and host ports, e.g.
  `ASTERA_PORT=25570 POSTGRES_PORT=5441 REDIS_PORT=6391 docker compose -p astera-other -f docker/docker-compose.yml up -d --build`.
- Single-server mode (no proxy) still exists for the simulator and quick local boots:
  `ASTERA_SERVER_ROLE` unset or `all` runs lobby and matches in one JVM.
