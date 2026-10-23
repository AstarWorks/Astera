package com.astarworks.astera.match.application.service

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.combat.CombatContext
import com.astarworks.astera.core.domain.event.PlayerMoved
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchPlayerPositions
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.port.outbound.IStarDropMarker
import com.astarworks.astera.match.domain.event.ResourceSpawned
import com.astarworks.astera.match.domain.event.StarsCollected
import com.astarworks.astera.match.domain.star.StarDrop
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * Stars lying on the map. A generator pop becomes a [StarDrop] at the generator (a generator holds at most its
 * `stockpile_cap` separate drops; further pops add to the newest one). When a player is killed in a match,
 * `floor(carried * death_drop_ratio)` of their stars burst into a few drops scattered around the death position.
 * The first alive participant (nearest wins) within a drop's pickup radius takes it. There are no item entities: a
 * drop is a record plus a particle marker, collected by a distance check on [PlayerMoved] and on a short poll
 * (command teleports raise no move event).
 */
public class StarDropService(
    private val matches: IMatchRepository,
    private val generators: IGeneratorRepository,
    private val matchWorlds: IMatchWorldRepository,
    private val positions: IMatchPlayerPositions,
    private val store: IMatchStarStore,
    private val eventBus: IDomainEventBus,
    private val marker: IStarDropMarker?,
    private val deathDropRatio: (MatchId) -> Double,
    private val random: Random = Random.Default,
    private val log: (String) -> Unit = {},
) {
    private val drops: MutableMap<Long, StarDrop> = ConcurrentHashMap()
    private val nextId = AtomicLong(1)

    public fun onSpawned(event: ResourceSpawned) {
        val generator = generators.listForMatch(event.matchId).firstOrNull { it.id == event.generatorId } ?: return
        val world = matchWorlds.findByMatch(event.matchId)?.worldId ?: return
        val late = matches.findById(event.matchId)?.phase == MatchPhase.SUDDEN_DEATH
        val amount = if (late) (event.amount * generator.lateGameMultiplier).toInt().coerceAtLeast(event.amount) else event.amount
        val own = drops.values.filter { it.matchId == event.matchId && it.generatorId == generator.id }
        val drop = if (own.size >= generator.stockpileCap) {
            val newest = own.maxBy { it.id }
            newest.copy(amount = newest.amount + amount).also { drops[it.id] = it }
        } else {
            StarDrop(nextId.getAndIncrement(), event.matchId, world, generator.position, amount, generator.pickupRadius, generator.id)
                .also { drops[it.id] = it }
        }
        tryCollect(drop)
    }

    /** A kill inside a match: the victim's stars burst out as drops around the death position. */
    public fun onKilled(event: PlayerKilled) {
        if (event.context !is CombatContext.Match) { log("star burst skipped: context ${event.context}"); return }
        val match = matches.findMatchOf(event.victim) ?: return
        if (match.phase == MatchPhase.ENDED) return
        val carried = store.get(match.id, event.victim)
        val burst = floor(carried * deathDropRatio(match.id)).toInt()
        log("star burst: victim=${event.victim} carried=$carried burst=$burst")
        if (burst <= 0) return
        val at = positions.positionOf(event.victim, includeDead = true)
        if (at == null) { log("star burst: no position for ${event.victim}"); return }
        if (store.spend(match.id, event.victim, burst) is com.astarworks.astera.core.domain.Result.Failure) return
        val stacks = minOf(burst, MAX_BURST_DROPS)
        val base = burst / stacks
        var extra = burst % stacks
        repeat(stacks) {
            val amount = base + if (extra-- > 0) 1 else 0
            val angle = random.nextDouble(0.0, 2 * Math.PI)
            val dist = random.nextDouble(0.5, BURST_SCATTER)
            val pos = Vec3(at.position.x + cos(angle) * dist, at.position.y, at.position.z + sin(angle) * dist)
            val drop = StarDrop(nextId.getAndIncrement(), match.id, at.world, pos, amount, BURST_PICKUP_RADIUS, null)
            drops[drop.id] = drop
            tryCollect(drop)
        }
    }

    public fun onMoved(event: PlayerMoved) {
        if (drops.isEmpty()) return
        val match = matches.findMatchOf(event.playerId) ?: return
        collectAt(match.id)
    }

    /** Re-check every drop, then redraw its marker. */
    public fun poll() {
        if (drops.isEmpty()) return
        drops.values.map { it.matchId }.distinct().forEach(::collectAt)
        drops.values.forEach { marker?.mark(it.world, it.position) }
    }

    /** Match ended: its drops vanish. */
    public fun clear(matchId: MatchId) {
        drops.values.removeAll { it.matchId == matchId }
    }

    /** Stars lying on the map for [matchId] (diagnostics / tests). */
    public fun lying(matchId: MatchId): Int = drops.values.filter { it.matchId == matchId }.sumOf { it.amount }

    private fun collectAt(matchId: MatchId) {
        drops.values.filter { it.matchId == matchId }.forEach(::tryCollect)
    }

    private fun tryCollect(drop: StarDrop) {
        if (!drops.containsKey(drop.id)) return
        val match = matches.findById(drop.matchId) ?: return
        val radiusSq = drop.pickupRadius * drop.pickupRadius
        val taker = match.teams.asSequence().flatMap { it.members.asSequence() }
            .mapNotNull { player ->
                val loc = positions.positionOf(player) ?: return@mapNotNull null
                if (loc.world != drop.world) return@mapNotNull null
                val d = (loc.position - drop.position).lengthSquared()
                if (d <= radiusSq) player to d else null
            }
            .minByOrNull { it.second }?.first ?: return
        val taken = drops.remove(drop.id) ?: return
        val total = store.add(drop.matchId, taker, taken.amount)
        eventBus.publish(StarsCollected(drop.matchId, taker, taken.amount, total))
    }

    private companion object {
        const val MAX_BURST_DROPS = 6
        const val BURST_SCATTER = 2.5
        const val BURST_PICKUP_RADIUS = 1.5
    }
}
