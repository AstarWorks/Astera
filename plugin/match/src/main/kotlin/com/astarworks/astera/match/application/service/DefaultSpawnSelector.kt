package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.core.domain.stage.Stage
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [ISpawnSelector] (ADR-0081, per-team since WP9).
 *
 * Candidates are the stage's [SpawnRole.TEAM] spawns of the player's team in
 * [matches]; a player with no team in the match gets the [SpawnRole.LOBBY]
 * spawns. [SpawnStrategy.Spread] hands out the least-used spawn of the team so
 * teammates entering one after another do not stack; usage is counted per
 * match until [forget] is called on match end.
 *
 * Konsist (ADR-0081): Bukkit-free; testable without Paper.
 */
public class DefaultSpawnSelector(
    private val matches: IMatchRepository,
    private val enemyPositionsProvider: (MatchId, PlayerId) -> List<Vec3> = { _, _ -> emptyList() },
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) : ISpawnSelector {

    private val usage = ConcurrentHashMap<MatchId, MutableMap<SpawnPoint, Int>>()

    override fun selectFor(
        player: PlayerId,
        match: MatchId,
        stage: Stage,
        strategy: SpawnStrategy,
    ): SpawnPoint? {
        val team = matches.findById(match)?.teams?.firstOrNull { player in it.members }?.id
        val candidates = if (team != null) {
            stage.spawnPoints.filter { it.role == SpawnRole.TEAM && it.team == team }
        } else {
            stage.spawnPoints.filter { it.role == SpawnRole.LOBBY }
        }
        if (candidates.isEmpty()) return null

        return when (strategy) {
            SpawnStrategy.FirstAvailable -> candidates.first()
            SpawnStrategy.Random -> candidates[random.nextInt(candidates.size)]
            SpawnStrategy.Spread -> synchronized(usage) {
                val counts = usage.getOrPut(match) { mutableMapOf() }
                val pick = candidates.minByOrNull { counts[it] ?: 0 }!! // first on ties = declaration order
                counts[pick] = (counts[pick] ?: 0) + 1
                pick
            }
            is SpawnStrategy.CycleRotation -> {
                val nextIndex = (strategy.previousSpawnIndex + 1).mod(candidates.size)
                candidates[nextIndex]
            }
            is SpawnStrategy.FurthestFromEnemies -> {
                val enemies = enemyPositionsProvider(match, player)
                if (enemies.isEmpty()) {
                    // No enemy positions → strategy degenerates to FirstAvailable.
                    return candidates.first()
                }
                // Compute min-distance-to-any-enemy for each candidate; pick the
                // spawn whose minimum is largest (i.e. furthest from any enemy).
                val ranked = candidates.map { spawn ->
                    val minDistSq = enemies.minOf { enemy -> spawn.position.distanceSquared(enemy) }
                    spawn to minDistSq
                }
                val safe = ranked.filter { (_, distSq) ->
                    distSq >= strategy.safeRadius * strategy.safeRadius
                }
                // Annihilation rule: if every spawn is inside the danger zone we
                // still respawn — pick the furthest one rather than denying.
                val pool = if (safe.isNotEmpty()) safe else ranked
                pool.maxByOrNull { it.second }?.first
            }
        }
    }

    /** Drop the usage counters of an ended match. */
    public fun forget(match: MatchId) {
        usage.remove(match)
    }
}
