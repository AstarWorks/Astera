package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.domain.event.StarSupplied
import com.astarworks.astera.match.domain.generator.GeneratorId
import com.astarworks.astera.match.domain.generator.StarGenerator
import com.astarworks.astera.match.domain.star.StarSource
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Mid star waves: a wave generator (`wave:` in `content/generators/`) does not pop on a period. Wave n lands at
 * `n * wave_period_seconds` of match time; `wave_warn_seconds` before that the whole match gets the warning moment
 * (title, sound, action bar). The wave's size is the generator `amount` (stars per player) times the players alive times the
 * supply ramp at landing time ([StarSupplyClock]), and has to fit the wave cap of the [StarBudget]; it is thrown as separate star items one by one over a disc around the generator.
 * A wave generator with `opens_at_phase` > 1 idles until that phase begins ([StarGate]); then the first warning goes out at once
 * and the first wave lands one warn after it, the next ones every period.
 * Driven by [poll] (the same 10-tick task as the drop poll), so timing is at most one poll late.
 */
public class StarWaveService(
    private val matches: IMatchRepository,
    private val generators: IGeneratorRepository,
    private val matchWorlds: IMatchWorldRepository,
    private val drops: StarDropService,
    private val clock: StarSupplyClock,
    private val budget: StarBudget,
    private val gate: StarGate,
    private val scheduler: IScheduler,
    private val presentation: IPresentation,
    private val eventBus: IDomainEventBus,
    private val random: Random = Random.Default,
    /** Carried across a Match rewire so a wave in progress keeps its schedule. */
    private val states: MutableMap<Pair<MatchId, GeneratorId>, State> = ConcurrentHashMap(),
) {
    public class State(var next: Int = 1, var warned: Boolean = false)


    /** Checks every running match's wave generators. */
    public fun poll() {
        val live = matches.findActiveMatches()
        for (match in live) {
            if (match.phase == MatchPhase.ENDED) continue
            for (generator in generators.listForMatch(match.id)) {
                val wave = generator.wave ?: continue
                val state = states.computeIfAbsent(match.id to generator.id) { State() }
                val now = clock.elapsedSeconds(match.id)
                val openAt = gate.openAtSeconds(match.id, generator)
                if (now < openAt) continue
                val periodSeconds = wave.period.inWholeMilliseconds / 1000.0
                val warnSeconds = wave.warn.inWholeMilliseconds / 1000.0
                val landAt = if (openAt <= 0.0) state.next * periodSeconds else openAt + warnSeconds + (state.next - 1) * periodSeconds
                if (now >= landAt) {
                    land(match.id, generator)
                    state.next += 1
                    state.warned = false
                } else if (!state.warned && warnSeconds > 0 && now >= landAt - warnSeconds) {
                    state.warned = true
                    warn(match.id, generator, landAt, warnSeconds)
                }
            }
        }
    }

    /** Forget a finished match. */
    public fun clear(matchId: MatchId) {
        states.keys.removeIf { it.first == matchId }
    }

    private fun size(matchId: MatchId, generator: StarGenerator, ramp: Double): Int =
        Math.round(generator.amount * budget.alive(matchId) * ramp).toInt().coerceAtLeast(1)

    private fun members(matchId: MatchId) = matches.findById(matchId)?.teams?.flatMap { it.members }.orEmpty()

    private fun warn(matchId: MatchId, generator: StarGenerator, landAt: Double, warnSeconds: Double) {
        val stars = size(matchId, generator, clock.economyOf(matchId).rampAt(landAt))
        val args = mapOf("seconds" to warnSeconds.toInt().toString(), "stars" to stars.toString())
        for (player in members(matchId)) {
            presentation.play(PresentationMoments.STAR_WAVE_WARN, player, args)
        }
    }

    private fun land(matchId: MatchId, generator: StarGenerator) {
        val wave = generator.wave ?: return
        val world = matchWorlds.findByMatch(matchId)?.worldId ?: return
        val total = budget.grant(matchId, StarSource.WAVE, null, size(matchId, generator, clock.ramp(matchId)))
        if (total <= 0) return
        val items = minOf(total, MAX_WAVE_ITEMS)
        val base = total / items
        var extra = total % items
        val spreadTicks = (wave.scatterDuration.inWholeMilliseconds / 50L).toInt().coerceAtLeast(1)
        repeat(items) { index ->
            val amount = base + if (extra-- > 0) 1 else 0
            val angle = random.nextDouble(0.0, 2 * PI)
            val radius = wave.scatterRadius * sqrt(random.nextDouble())
            val at = Vec3(generator.position.x + cos(angle) * radius, generator.position.y + DROP_LIFT, generator.position.z + sin(angle) * radius)
            val delay = if (items == 1) 0 else index * spreadTicks / items
            val sparkle = index % SPARKLE_EVERY == 0
            val place = { drops.dropWaveStar(matchId, generator, at, amount, sparkle) }
            if (delay == 0) place() else scheduler.runLaterTicks(regionScope(world, at), delay.toLong()) { place() }
        }
        eventBus.publish(StarSupplied(matchId, generator.id, total, null, scheduler.currentTick()))
        for (player in members(matchId)) {
            presentation.play(PresentationMoments.STAR_WAVE, player, mapOf("stars" to total.toString()))
        }
    }

    private companion object {
        /** Most separate star items one wave throws (the total is split evenly across them). */
        const val MAX_WAVE_ITEMS = 48
        const val SPARKLE_EVERY = 3
        /** Items appear this far above the generator's y so they fall onto the island. */
        const val DROP_LIFT = 1.5
    }
}
