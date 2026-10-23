package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.PlayerMoved
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchPlayerPositions
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.domain.event.ResourceSpawned
import com.astarworks.astera.match.domain.event.StarsCollected
import com.astarworks.astera.match.domain.generator.GeneratorId
import com.astarworks.astera.match.domain.generator.StarGenerator
import java.util.concurrent.ConcurrentHashMap

/**
 * Where generator stars go: a stockpile at the generator (capped, so camping is bounded) that the nearest alive
 * participant within the generator's pickup radius collects. Evaluated when the generator fires, when a participant
 * moves ([PlayerMoved]) and on a short poll ([poll]) because command teleports do not produce move events.
 * No item entities: the stockpile is a counter, the pickup is a distance check.
 */
public class StarStockpileService(
    private val matches: IMatchRepository,
    private val generators: IGeneratorRepository,
    private val matchWorlds: IMatchWorldRepository,
    private val positions: IMatchPlayerPositions,
    private val store: IMatchStarStore,
    private val eventBus: IDomainEventBus,
) {
    private val stockpiles: MutableMap<Pair<MatchId, GeneratorId>, Int> = ConcurrentHashMap()

    public fun onSpawned(event: ResourceSpawned) {
        val generator = generators.listForMatch(event.matchId).firstOrNull { it.id == event.generatorId } ?: return
        stockpiles.compute(event.matchId to generator.id) { _, cur ->
            ((cur ?: 0) + event.amount).coerceAtMost(generator.stockpileCap)
        }
        tryCollect(generator)
    }

    public fun onMoved(event: PlayerMoved) {
        val match = matches.findMatchOf(event.playerId) ?: return
        collectAt(match.id)
    }

    /** Re-check every live match that holds an uncollected stockpile. */
    public fun poll() {
        if (stockpiles.isEmpty()) return
        stockpiles.keys.map { it.first }.distinct().forEach(::collectAt)
    }

    /** Match ended: the stockpiles vanish with it. */
    public fun clear(matchId: MatchId) {
        stockpiles.keys.removeAll { it.first == matchId }
    }

    /** Uncollected stars at [generatorId] (diagnostics). */
    public fun pending(matchId: MatchId, generatorId: GeneratorId): Int = stockpiles[matchId to generatorId] ?: 0

    private fun collectAt(matchId: MatchId) {
        for (generator in generators.listForMatch(matchId)) {
            if (stockpiles.containsKey(matchId to generator.id)) tryCollect(generator)
        }
    }

    private fun tryCollect(generator: StarGenerator) {
        val key = generator.matchId to generator.id
        if (!stockpiles.containsKey(key)) return
        val match = matches.findById(generator.matchId) ?: return
        val world = matchWorlds.findByMatch(generator.matchId)?.worldId ?: return
        val radiusSq = generator.pickupRadius * generator.pickupRadius
        val taker = match.teams.asSequence().flatMap { it.members.asSequence() }
            .mapNotNull { player ->
                val loc = positions.positionOf(player) ?: return@mapNotNull null
                if (loc.world != world) return@mapNotNull null
                val d = (loc.position - generator.position).lengthSquared()
                if (d <= radiusSq) player to d else null
            }
            .minByOrNull { it.second }?.first ?: return
        val amount = stockpiles.remove(key) ?: return
        val total = store.add(generator.matchId, taker, amount)
        eventBus.publish(StarsCollected(generator.matchId, taker, amount, total))
    }
}
