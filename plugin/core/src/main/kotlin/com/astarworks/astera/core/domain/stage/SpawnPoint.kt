package com.astarworks.astera.core.domain.stage

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * A position in a [Stage] where a player can be teleported during a match
 * lifecycle event (entry, respawn, post-match return to lobby).
 *
 * Sealed by *role*: the adapter / use case picks an appropriate spawn for
 * the player's current state. `MatchSpawnUseCase` (plugin/match) uses these
 * via `IWorldGateway.teleport` once Wave 5's Paper impl lands; until then
 * the port wiring runs through `FakeWorldGateway` only.
 *
 * Wave 4.5 (R3.5, ADR-0027): minimum viable variants. Phase 3 may extend
 * with team-aware spawns, deterministic respawn rotation, etc.
 *
 * @property pos World-space coordinates in [Stage]'s world. The yaw / pitch
 *   of the player after teleport defaults to facing positive X — Wave 5+
 *   may extend the record with explicit facing if a stage authors needs it.
 */
public sealed interface SpawnPoint {
    public val pos: Vec3

    /** Spawn for attackers / red team. */
    public data class Attacker(override val pos: Vec3) : SpawnPoint

    /** Spawn for defenders / blue team. */
    public data class Defender(override val pos: Vec3) : SpawnPoint

    /** Pre-match lobby / staging area. */
    public data class Lobby(override val pos: Vec3) : SpawnPoint

    /** Spectator camera / dead-player view. */
    public data class Spectator(override val pos: Vec3) : SpawnPoint
}
