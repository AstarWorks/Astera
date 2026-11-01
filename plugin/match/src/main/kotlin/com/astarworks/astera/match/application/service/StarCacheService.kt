package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchPlayerPositions
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.domain.generator.GeneratorId
import com.astarworks.astera.match.domain.star.StarSource
import java.util.concurrent.ConcurrentHashMap

/**
 * Exploration caches (ADR-0318): every spot of a cache generator holds one one-shot bundle (`amount` x the supply ramp).
 * The first player to walk within `open_radius` opens it, the bundle goes to them within their cache cap, and the spot
 * refills `respawn_seconds` later. A bundle the budget cannot pay stays closed and is tried again shortly. Driven by [poll].
 */
public class StarCacheService(
    private val matches: IMatchRepository,
    private val generators: IGeneratorRepository,
    private val matchWorlds: IMatchWorldRepository,
    private val positions: IMatchPlayerPositions,
    private val issuer: StarIssuer,
    private val budget: StarBudget,
    private val clock: StarSupplyClock,
    private val scheduler: IScheduler,
    private val presentation: IPresentation,
    /** Scheduler tick at which each cache spot is ready again; absent = ready. */
    private val readyAt: MutableMap<Triple<MatchId, GeneratorId, Int>, Long> = ConcurrentHashMap(),
) {
    public fun poll() {
        val now = scheduler.currentTick()
        for (match in matches.findActiveMatches()) {
            if (match.phase != MatchPhase.ACTIVE && match.phase != MatchPhase.SUDDEN_DEATH) continue
            val world = matchWorlds.findByMatch(match.id)?.worldId ?: continue
            val members = match.teams.flatMap { it.members }
            for (generator in generators.listForMatch(match.id)) {
                val cache = generator.cache ?: continue
                generator.spots.forEachIndexed { index, spot ->
                    val key = Triple(match.id, generator.id, index)
                    if (now < (readyAt[key] ?: 0L)) return@forEachIndexed
                    val opener = members.firstOrNull { m ->
                        val loc = positions.positionOf(m) ?: return@firstOrNull false
                        loc.world == world && horizontal(loc.position, spot) <= cache.openRadius && kotlin.math.abs(loc.position.y - spot.y) <= VERTICAL_REACH
                    } ?: return@forEachIndexed
                    val bundle = Math.round(generator.amount * clock.ramp(match.id)).toInt().coerceAtLeast(1)
                    val granted = issuer.issue(match.id, opener, StarSource.CACHE, bundle)
                    if (granted > 0) {
                        budget.stats.add(match.id, "cache-open", 1)
                        readyAt[key] = now + cache.respawn.inWholeMilliseconds / 50L
                        presentation.play(PresentationMoments.STAR_POP, opener, mapOf("amount" to granted.toString()), spot, world)
                    } else {
                        readyAt[key] = now + RETRY_TICKS
                    }
                }
            }
        }
    }

    /** Forget a finished match. */
    public fun clear(matchId: MatchId) {
        readyAt.keys.removeIf { it.first == matchId }
    }

    private fun horizontal(a: Vec3, b: Vec3): Double = kotlin.math.hypot(a.x - b.x, a.z - b.z)

    private companion object {
        const val VERTICAL_REACH = 3.0
        const val RETRY_TICKS = 100L
    }
}
