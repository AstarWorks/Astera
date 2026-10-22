---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0180: Procedural sky-island lobby world

## Status

Accepted. Realises the lobby intent of ADR-0119 (clear sky, frozen noon, no PvP, no build)
that only ever existed as a never-loaded `content/worlds/lobby.yaml` draft, and gives the
`ServerRole.hostsLobby` backends (ADR-0174 contract) a world of their own.

## Context

The lobby was the server's main world: a bare superflat plain with NPCs on a flat y=-60 line.
The founder wants a floating sky-island lobby (islands in the void, plaza, market, queue area).
Third-party maps/schematics are out: their licenses are unclear.

## Decision

1. **Build procedurally, in Kotlin, at runtime from a pure plan** (not a committed `.schem`/`.nbt`).
   `core.domain.lobby.LobbyIslandGenerator.generate(seed, centre, surfaceY)` returns a
   `LobbyBuildPlan`: a map of block position -> vendor-neutral block-data string, the signs
   (i18n-keyed lines), the spawn, the NPC stands and the lane pads. No Bukkit, no I/O.
   - *Why not `.schem`/`.nbt`*: a binary artefact must be produced by a tool and kept in sync with
     `content/lobby-npcs/` by hand; a loader needs an NBT/Sponge reader (new dependency or hand
     rolled). The plan is ~67k placements, deterministic from one seed, reviewable as code, unit
     testable (walkability of every NPC stand, connectivity of all islands) and renderable to a
     PNG by the same code (`:tools:lobby-gen`, `docs/assets/lobby-preview.png`).
   - Cost: a one-time paste of ~67k `setBlockData` calls on first boot (about a second), not
     per boot.
2. **Content**: `content/worlds/lobby.yaml` (`LobbyWorldYamlConfig`, loaded by `LobbyWorldContent`):
   world id, `seed`, `surface_y`, plaza `center`, `spawn`, `marker_version`, `rules`.
   `content/lobby-npcs/*.yaml` positions are the generator's recommended stands; a test fails if they drift
   or are not on a walkable block.
3. **Paper adapter** (`adapter/paper/lobby/`, installed from `CorePaperBindings.afterWiring`,
   only when `ctx.serverRole.hostsLobby`):
   - `PaperLobbyWorld` creates a void world named `lobby` (empty `ChunkGenerator`, plains biome
     provider, no structures), pastes the plan (water last, with physics, so the fountain and the
     waterfalls start to flow), writes the signs from i18n keys, applies the rules through the
     existing `IWorldRulesGateway` / `IWorldTimeGateway` / `IWorldWeatherGateway`
     (peaceful, no mob spawning, no fire tick, pvp off, noon frozen, clear frozen).
   - **Idempotent**: `plugins/Astera-Core/lobby-world.marker` stores `(marker_version, seed,
     centre, surfaceY)`. Equal -> nothing is placed. Different -> the old bounds are cleared, then
     the new plan is pasted. Delete the marker to force a repaste.
   - `PaperLobbyRulesListener`: no block break/place/bucket/hanging/farmland trample for non-ops
     (`isOp` or `astera.lobby.build`), all damage cancelled at LOWEST (before the Astera HP
     re-route; `/kill` still works), no hunger, players below `void_rescue_y` are teleported back
     to the spawn, and every join on a lobby-hosting backend starts at the lobby spawn.
4. **Resolution**: `PaperWorldGateway.lobbySpawn` and the logical world `lobby` (NPC bodies,
   `teleport`) resolve to the Bukkit world `lobby` when it is loaded, else to the first world as
   before. `ALL` role: matches keep using the main world (arena stages at y=-60); only the lobby
   moved. `GAME` role: no lobby world, no lobby NPCs (`CoreWiringLobby` is gated on `hostsLobby`).

## Layout (offsets from the fountain, +x east, +z south)

Main island r27 (plaza r10 with fountain, spawn 6 south of it facing it, benches, lamp posts,
signposts, ring path, stream falling off the west rim); market island east (shop, refiner and
loadout stalls); queue island south (queue master, one pad + sign board per lane `norma`,
`annihilation`, `siege`); garden island west (pond, waterfall); pavilion island north
(gazebo); three small floating rocks. 3-wide fenced bridges with lantern posts join the four
satellites.

## Consequences

- Layout changes = edit the generator and bump `marker_version`; regenerate the preview with
  `./gradlew :tools:lobby-gen:run`.
- Real-Paper verification of the pasted world (fountain/waterfall flow, sign text) is part of the
  next docker playtest; the unit tests cover the plan only.
- Following-up: lane pads are decoration for now; walking onto a pad does not queue (the queue
  master still does).
