package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.domain.combat.CombatContext
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchPlayerPositions
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.port.outbound.IStarDropWorld
import com.astarworks.astera.core.domain.player.PlayerId
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
 * A drop is a record plus an item entity ([IStarDropWorld]); it is collected only by the host's real item pickup
 * ([onTouched]: vanilla pickup range, pickup delay, animation). There is no proximity crediting. The item never
 * enters an inventory; the carried count lives only in [IMatchStarStore].
 */
public class StarDropService(
    private val matches: IMatchRepository,
    private val generators: IGeneratorRepository,
    private val matchWorlds: IMatchWorldRepository,
    private val positions: IMatchPlayerPositions,
    private val store: IMatchStarStore,
    private val eventBus: IDomainEventBus,
    private val world: IStarDropWorld?,
    private val scheduler: com.astarworks.astera.core.application.port.outbound.IScheduler,
    private val presentation: IPresentation,
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
            newest.copy(amount = newest.amount + amount).also { drops[it.id] = it; it.entity?.let { h -> this.world?.relabel(h, it.amount) } }
        } else {
            StarDrop(nextId.getAndIncrement(), event.matchId, world, generator.position, amount, generator.pickupRadius, generator.id)
                .let { withEntity(it, null) }.also { drops[it.id] = it }
        }
        // FX-04: a new star lights up at the generator for everyone close enough to see it.
        for (near in nearby(event.matchId, world, generator.position, POP_RADIUS)) {
            presentation.play(PresentationMoments.STAR_POP, near, mapOf("amount" to amount.toString()), generator.position, world)
        }
    }

    /** A kill inside a match: the victim's stars burst out as drops around the death position. */
    public fun onKilled(event: PlayerKilled) {
        if (event.context !is CombatContext.Match) { log("star burst skipped: context ${event.context}"); return }
        val match = matches.findMatchOf(event.victim) ?: return
        if (match.phase == MatchPhase.ENDED) return
        val carried = store.get(match.id, event.victim)
        val burst = floor(carried * deathDropRatio(match.id)).toInt()
        log("star burst: victim=${event.victim} carried=$carried burst=$burst")
        scatter(match.id, event.victim, burst, event.killer)
    }

    /**
     * A participant leaves the running match (disconnect, lobby command, forfeit): every star they carry bursts at
     * the position they stand on, whatever `death_drop_ratio` says (MODE-14). Call it before they are moved away.
     */
    public fun onLeft(matchId: MatchId, player: PlayerId) {
        val carried = store.get(matchId, player)
        log("star burst on leave: player=$player carried=$carried")
        scatter(matchId, player, carried)
    }

    /** Takes [burst] stars off [victim]'s counter and scatters them as drops around where they stand. */
    private fun scatter(matchId: MatchId, victim: PlayerId, burst: Int, killer: PlayerId? = null) {
        if (burst <= 0) return
        val at = positions.positionOf(victim, includeDead = true)
        if (at == null) { log("star burst: no position for $victim"); return }
        if (store.spend(matchId, victim, burst) is com.astarworks.astera.core.domain.Result.Failure) return
        // FX-04: radial pop at the death spot; the victim feels the loss, the killer sees the haul, bystanders hear it.
        val credited = killer?.takeIf { it != victim }
        val bystanders = nearby(matchId, at.world, at.position, BURST_HEAR_RADIUS).filter { it != victim && it != credited }
        val args = mapOf("amount" to burst.toString())
        presentation.play(PresentationMoments.STAR_BURST, victim, args, at.position, at.world, bystanders)
        credited?.let { presentation.play(PresentationMoments.STAR_BURST_KILLER, it, args, at.position, at.world) }
        val stacks = minOf(burst, MAX_BURST_DROPS)
        val base = burst / stacks
        var extra = burst % stacks
        repeat(stacks) {
            val amount = base + if (extra-- > 0) 1 else 0
            val angle = random.nextDouble(0.0, 2 * Math.PI)
            val dist = random.nextDouble(0.5, BURST_SCATTER)
            val now = System.currentTimeMillis()
            val pos = Vec3(at.position.x, at.position.y + 0.5, at.position.z)
            val fling = Vec3(cos(angle) * dist * BURST_SPEED, BURST_LIFT, sin(angle) * dist * BURST_SPEED)
            val drop = StarDrop(nextId.getAndIncrement(), matchId, at.world, pos, amount, BURST_PICKUP_RADIUS, null, victim, now + VICTIM_DENY_MILLIS, null, now + BURST_READY_MILLIS)
                .let { withEntity(it, fling) }
            drops[drop.id] = drop
        }
    }

    /** Keep every drop's record on its thing (it may roll) and respawn lost things. */
    public fun poll() {
        if (drops.isEmpty()) return
        val w = world
        if (w != null) {
            for (drop in drops.values.toList()) {
                val scope = drop.entity?.let(w::executionScope) ?: regionScope(drop.world, drop.position)
                fun record(here: Vec3?) {
                    scheduler.run(ExecutionScope.Global, kotlin.time.Duration.ZERO) {
                        val current = drops[drop.id] ?: return@run
                        if (current.entity != drop.entity) return@run
                        drops[drop.id] = if (here != null) current.copy(position = here)
                        else {
                            current.entity?.let(w::remove)
                            withEntity(current.copy(entity = null), null)
                        }
                    }
                }
                scheduler.run(scope, kotlin.time.Duration.ZERO) {
                    record(drop.entity?.let(w::locate))
                }.onRetired { record(null) }
            }
        }
    }

    /** A player touched the thing [handle] (its vanilla pickup is already cancelled). */
    public fun onTouched(handle: String, player: PlayerId) {
        scheduler.run(ExecutionScope.Global, kotlin.time.Duration.ZERO) {
            val drop = drops.values.firstOrNull { it.entity == handle } ?: return@run
            val match = matches.findById(drop.matchId) ?: return@run
            if (match.teams.none { player in it.members }) return@run
            val loc = positions.positionOf(player) ?: return@run
            if (loc.world != drop.world) return@run
            if (isDenied(drop, player) || System.currentTimeMillis() < drop.readyAtMillis) return@run
            take(drop, player)
        }
    }

    /** Match ended: its drops vanish, things included. */
    public fun clear(matchId: MatchId) {
        drops.values.filter { it.matchId == matchId }.forEach { d ->
            drops.remove(d.id)
            d.entity?.let { world?.remove(it) }
        }
    }

    /** Stars lying on the map for [matchId] (diagnostics / tests). */
    public fun lying(matchId: MatchId): Int = drops.values.filter { it.matchId == matchId }.sumOf { it.amount }

    /** Live match players within [radius] blocks of [at] in [world] (presentation audience only). */
    private fun nearby(matchId: MatchId, world: WorldId, at: Vec3, radius: Double): List<PlayerId> {
        val members = matches.findById(matchId)?.teams?.flatMap { it.members }.orEmpty()
        return members.filter { m ->
            val loc = positions.positionOf(m) ?: return@filter false
            loc.world == world && distanceSq(loc.position, at) <= radius * radius
        }
    }

    private fun distanceSq(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun withEntity(drop: StarDrop, velocity: Vec3?): StarDrop {
        val delayTicks = ((drop.readyAtMillis - System.currentTimeMillis()) / MILLIS_PER_TICK).toInt().coerceAtLeast(0)
        val handle = world?.spawn(drop.world, drop.position, drop.amount, velocity, delayTicks) ?: return drop
        return drop.copy(entity = handle)
    }

    private fun isDenied(drop: StarDrop, player: PlayerId): Boolean =
        player == drop.deniedTo && System.currentTimeMillis() < drop.deniedUntilMillis

    private fun take(drop: StarDrop, taker: PlayerId) {
        val taken = drops.remove(drop.id) ?: return
        taken.entity?.let { world?.take(it, taker) }
        val total = store.add(drop.matchId, taker, taken.amount)
        presentation.play(
            PresentationMoments.STAR_PICKUP, taker,
            mapOf("amount" to taken.amount.toString(), "total" to total.toString()), taken.position, taken.world,
        )
        eventBus.publish(StarsCollected(drop.matchId, taker, taken.amount, total))
    }

    private companion object {
        const val MILLIS_PER_TICK = 50L
        const val POP_RADIUS = 40.0
        const val BURST_HEAR_RADIUS = 32.0
        const val MAX_BURST_DROPS = 6
        const val BURST_SCATTER = 2.5
        const val BURST_PICKUP_RADIUS = 1.5
        const val VICTIM_DENY_MILLIS = 8_000L
        /** Outward fling of a burst drop: blocks/tick = scatter distance * this. */
        const val BURST_SPEED = 0.1
        const val BURST_LIFT = 0.25
        /** A burst drop cannot be taken for this long, so the scatter is seen. */
        const val BURST_READY_MILLIS = 600L
    }
}
