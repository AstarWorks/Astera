package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.core.domain.stage.Stage

/**
 * Vendor-neutral spawn-point selector (Wave 5o, ADR-0081).
 *
 * Given a player, the match they belong to, the stage they spawn into, and a
 * [SpawnStrategy] choice, returns the [SpawnPoint] the
 * [com.astarworks.astera.match.application.usecase.respawn.RespawnUseCase]
 * and `MatchSpawnUseCase` should teleport into.
 *
 * The selector is team-aware: it filters the stage's spawn list to the spawns
 * owned by the player's team in the match (WP9: any number of teams), then
 * applies the [SpawnStrategy].
 *
 * # Konsist
 *
 * Implementations of this interface MUST NOT import the Bukkit family — the
 * spawn-selection logic is pure domain and must run under the test harness
 * with no Paper plumbing. Enforced by
 * `ArchitectureTest.ISpawnSelector implementations must not import Bukkit Paper (ADR-0081)`.
 */
public interface ISpawnSelector {

    /**
     * Pick a spawn point for [player] in [match] on [stage] using [strategy].
     *
     * Returns `null` if no spawn matches the strategy (e.g. the stage has no
     * attacker spawns and the player is attacker). Callers convert the null
     * into a typed error (`RespawnError.NoSpawnPointAvailable`).
     */
    public fun selectFor(
        player: PlayerId,
        match: MatchId,
        stage: Stage,
        strategy: SpawnStrategy,
    ): SpawnPoint?
}
