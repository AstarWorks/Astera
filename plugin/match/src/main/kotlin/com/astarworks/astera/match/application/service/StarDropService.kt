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
import com.astarworks.astera.match.domain.event.StarSupplied
import com.astarworks.astera.match.domain.generator.Affiliation
import com.astarworks.astera.match.domain.generator.StarGenerator
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
    private val clock: StarSupplyClock,
    private val deathDropRatio: (MatchId) -> Double,
    private val random: Random = Random.Default,
    private val log: (String) -> Unit = {},
    /** Carried across a Match rewire: the stars lying in the arena and the id counter. */
    private val drops: MutableMap<Long, StarDrop> = ConcurrentHashMap(),
    private val nextId: AtomicLong = AtomicLong(1),
) {

    public fun onSpawned(event: ResourceSpawned) {
        if (event.dropItem != null) return // item generators are GeneratorItemDropService's
        val generator = generators.listForMatch(event.matchId).firstOrNull { it.id == event.generatorId } ?: return
        val world = matchWorlds.findByMatch(event.matchId)?.worldId ?: return
        val late = matches.findById(event.matchId)?.phase == MatchPhase.SUDDEN_DEATH
        // Supply formula first (StarSupply: the pop grows with match progress), then the sudden-death factor on top.
        val scaled = clock.amount(event.matchId, event.amount)
        val amount = if (late) (scaled * generator.lateGameMultiplier).toInt().coerceAtLeast(scaled) else scaled
        val team = (generator.affiliation as? Affiliation.Team)?.teamId
        val own = drops.values.filter { it.matchId == event.matchId && it.generatorId == generator.id }
        val drop = if (own.size >= generator.stockpileCap) {
            val newest = own.maxBy { it.id }
            newest.copy(amount = newest.amount + amount).also { drops[it.id] = it; it.entity?.let { h -> this.world?.relabel(h, it.amount) } }
        } else {
            StarDrop(nextId.getAndIncrement(), event.matchId, world, generator.position, amount, generator.pickupRadius, generator.id, team = team)
                .let { withEntity(it, null) }.also { drops[it.id] = it }
        }
        eventBus.publish(StarSupplied(event.matchId, generator.id, amount, team, scheduler.currentTick()))
        // FX-04: a new star lights up at the generator for everyone close enough to see it.
        for (near in nearby(event.matchId, world, generator.position, POP_RADIUS)) {
            presentation.play(PresentationMoments.STAR_POP, near, mapOf("amount" to amount.toString()), generator.position, world)
        }
    }

    /**
     * One star item of a Mid wave lands at [at] (anyone can take it; [generator] only names the source). A short pickup
     * delay lets the fall be seen. The wave's total is announced by the caller as one [StarSupplied].
     */
    public fun dropWaveStar(matchId: MatchId, generator: StarGenerator, at: Vec3, amount: Int, sparkle: Boolean) {
        val world = matchWorlds.findByMatch(matchId)?.worldId ?: return
        if (matches.findById(matchId)?.let { it.phase != MatchPhase.ENDED } != true) return
        val drop = StarDrop(nextId.getAndIncrement(), matchId, world, at, amount, generator.pickupRadius, generator.id, readyAtTick = scheduler.currentTick() + WAVE_READY_TICKS)
            .let { withEntity(it, null) }
        drops[drop.id] = drop
        if (sparkle) {
            for (near in nearby(matchId, world, at, POP_RADIUS)) {
                presentation.play(PresentationMoments.STAR_POP, near, mapOf("amount" to amount.toString()), at, world)
            }
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
        // Fountain layers: launch thump + gold dust, then firework sparks (same audience; the killer and the victim see them too).
        val audience = (bystanders + listOfNotNull(credited)).distinct()
        presentation.play(PresentationMoments.STAR_BURST_GOLD, victim, args, at.position, at.world, audience)
        presentation.play(PresentationMoments.STAR_BURST_SPARK, victim, args, at.position, at.world, audience)
        credited?.let { presentation.play(PresentationMoments.STAR_BURST_KILLER, it, args, at.position, at.world) }
        val stacks = minOf(burst, MAX_BURST_DROPS)
        val base = burst / stacks
        var extra = burst % stacks
        // Fountain: every stack leaves in its own random direction around the full circle with its own elevation
        // (a little below the horizon up to nearly straight up) and speed, so the burst sprays up, out and low at
        // different distances instead of one ring. The azimuths are stratified (evenly spaced, jittered) so two stacks
        // rarely share a direction. Downward flings are clamped so a drop is not shoved into the floor or off an edge.
        val ringStart = random.nextDouble(0.0, 2 * Math.PI)
        repeat(stacks) { index ->
            val amount = base + if (extra-- > 0) 1 else 0
            val azimuth = ringStart + (index + random.nextDouble(-0.45, 0.45)) * 2 * Math.PI / stacks
            val elevation = Math.toRadians(random.nextDouble(BURST_ELEVATION_MIN_DEG, BURST_ELEVATION_MAX_DEG))
            val speed = random.nextDouble(BURST_SPEED_MIN, BURST_SPEED_MAX)
            val now = scheduler.currentTick()
            val pos = Vec3(at.position.x, at.position.y + BURST_SPAWN_LIFT, at.position.z)
            val fling = Vec3(cos(elevation) * cos(azimuth) * speed, (sin(elevation) * speed).coerceAtLeast(BURST_MIN_VERTICAL), cos(elevation) * sin(azimuth) * speed)
            val drop = StarDrop(nextId.getAndIncrement(), matchId, at.world, pos, amount, BURST_PICKUP_RADIUS, null, victim, now + VICTIM_DENY_TICKS, null, now + BURST_READY_TICKS)
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
                // A match that ended (or vanished) without its end event reaching us leaves lookalike stars nobody can take.
                val live = matches.findById(drop.matchId)?.let { it.phase != MatchPhase.ENDED } == true
                if (!live) {
                    drops.remove(drop.id)
                    drop.entity?.let(w::remove)
                    continue
                }
                val scope = drop.entity?.let(w::executionScope) ?: regionScope(drop.world, drop.position)
                scheduler.run(scope, kotlin.time.Duration.ZERO) {
                val handle = drop.entity
                val here = handle?.let { w.locate(it) }
                if (here != null) {
                    drops.computeIfPresent(drop.id) { _, cur -> cur.copy(position = here) }
                } else {
                    // Never spawned (chunk not loaded yet) or destroyed: put it back where it lay.
                    drops.computeIfPresent(drop.id) { _, cur -> withEntity(cur.copy(entity = null), null) }
                }
                }.onRetired {
                    scheduler.run(regionScope(drop.world, drop.position), kotlin.time.Duration.ZERO) {
                        drops.computeIfPresent(drop.id) { _, cur -> withEntity(cur.copy(entity = null), null) }
                    }
                }
            }
        }
    }

    /** A player touched the thing [handle] (its vanilla pickup is already cancelled). */
    public fun onTouched(handle: String, player: PlayerId) {
        val drop = drops.values.firstOrNull { it.entity == handle }
        if (drop == null) {
            // A star item nobody tracks (a duplicate left by a respawn, a leftover of an earlier match): it can never be
            // taken, so it must not keep lying around looking like a star.
            world?.remove(handle)
            return
        }
        val match = matches.findById(drop.matchId) ?: return
        if (match.teams.none { player in it.members }) return
        val loc = positions.positionOf(player) ?: return // dead / spectating
        if (loc.world != drop.world) return
        if (drop.team != null && match.teams.none { it.id == drop.team && player in it.members }) return // a team base generator's stars
        if (isDenied(drop, player) || scheduler.currentTick() < drop.readyAtTick) return
        // The pickup event arrives on the player's thread; the star ledger is owned by Global.
        scheduler.run(ExecutionScope.Global, kotlin.time.Duration.ZERO) { take(drop, player) }
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
        val delayTicks = (drop.readyAtTick - scheduler.currentTick()).toInt().coerceAtLeast(0)
        val handle = world?.spawn(drop.world, drop.position, drop.amount, velocity, delayTicks) ?: return drop
        return drop.copy(entity = handle)
    }

    private fun isDenied(drop: StarDrop, player: PlayerId): Boolean =
        player == drop.deniedTo && scheduler.currentTick() < drop.deniedUntilTick

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
        const val POP_RADIUS = 40.0
        const val BURST_HEAR_RADIUS = 32.0
        /** Most separate star items one burst throws (a 100-star economy drops tens of stars at once). */
        const val MAX_BURST_DROPS = 16
        const val BURST_PICKUP_RADIUS = 1.5
        const val VICTIM_DENY_TICKS = 160L
        /** Launch speed of a burst drop (blocks/tick, randomized per drop). */
        const val BURST_SPEED_MIN = 0.12
        const val BURST_SPEED_MAX = 0.62
        /** Launch elevation range in degrees, random per drop: slightly below the horizon up to nearly vertical. */
        const val BURST_ELEVATION_MIN_DEG = -15.0
        const val BURST_ELEVATION_MAX_DEG = 80.0
        /** Never launch faster downward than this (blocks/tick): keeps drops out of the floor and off the edge. */
        const val BURST_MIN_VERTICAL = -0.05
        /** Drops spawn this far above the victim's feet. */
        const val BURST_SPAWN_LIFT = 0.8
        /** A burst drop cannot be taken for this long, so the fountain is seen. */
        const val BURST_READY_TICKS = 18L
        /** A wave star cannot be taken for this long after it appears, so it is seen falling in. */
        const val WAVE_READY_TICKS = 10L
    }
}
