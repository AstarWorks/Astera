package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.SpawnFaction
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.core.domain.stage.Stage

/**
 * Default in-memory [ISpawnSelector] (Wave 5o, ADR-0081).
 *
 * Resolves the player's faction via [IMatchTeamLookup] using the
 * [factionByTeamId] map — caller-supplied so test fixtures + production both
 * thread their own team naming convention. When the lookup returns no team
 * (or the team is not in [factionByTeamId]), the selector falls back to
 * [SpawnFaction.LOBBY], which preserves the Wave 4.5 behaviour of "no team
 * info → put the player in the staging area".
 *
 * Konsist (ADR-0081): this class lives in `application/service/` and imports
 * only domain + port types — Bukkit-free, suitable for unit testing without
 * any Paper plumbing.
 */
public class DefaultSpawnSelector(
    private val matchTeamLookup: IMatchTeamLookup? = null,
    private val factionByTeamId: Map<TeamId, SpawnFaction> = DEFAULT_TEAM_FACTION_MAP,
    private val enemyPositionsProvider: (MatchId, PlayerId) -> List<Vec3> = { _, _ -> emptyList() },
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) : ISpawnSelector {

    override fun selectFor(
        player: PlayerId,
        match: MatchId,
        stage: Stage,
        strategy: SpawnStrategy,
    ): SpawnPoint? {
        val faction = resolveFaction(player)
        val candidates = stage.spawnPoints.filter { it.faction == faction }
        if (candidates.isEmpty()) return null

        return when (strategy) {
            SpawnStrategy.FirstAvailable -> candidates.first()
            SpawnStrategy.Random -> candidates[random.nextInt(candidates.size)]
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

    private fun resolveFaction(player: PlayerId): SpawnFaction {
        val teamId = matchTeamLookup?.teamOf(player) ?: return SpawnFaction.LOBBY
        return factionByTeamId[teamId] ?: SpawnFaction.LOBBY
    }

    public companion object {
        /**
         * Canonical Annihilation team-name → faction mapping. Stage authors and
         * match orchestrators are expected to use these team ids; production
         * wiring can pass a richer map without touching the selector.
         */
        public val DEFAULT_TEAM_FACTION_MAP: Map<TeamId, SpawnFaction> = mapOf(
            TeamId("red") to SpawnFaction.ATTACKER,
            TeamId("attacker") to SpawnFaction.ATTACKER,
            TeamId("blue") to SpawnFaction.DEFENDER,
            TeamId("defender") to SpawnFaction.DEFENDER,
        )
    }
}
