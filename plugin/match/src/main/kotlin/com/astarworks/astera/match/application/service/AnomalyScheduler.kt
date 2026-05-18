package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.port.outbound.IAnomalyScheduleRepository
import com.astarworks.astera.match.application.usecase.anomaly.ResolveAnomalyEffectUseCase
import com.astarworks.astera.match.application.usecase.anomaly.TriggerAnomalyUseCase
import com.astarworks.astera.match.domain.anomaly.AnomalyDescriptor
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Periodic driver that fires anomalies inside active matches (Wave 5c).
 *
 * Every [period] ticks the scheduler iterates active matches and, with a
 * low probability ([FIRE_PROBABILITY]), picks a weighted-random
 * [AnomalyDescriptor] from the per-match list and:
 *
 *   1. Calls [TriggerAnomalyUseCase.trigger] to publish the announcement
 *      ([com.astarworks.astera.match.domain.event.AnomalyTriggered]).
 *   2. Calls [ResolveAnomalyEffectUseCase.apply] to install the
 *      [com.astarworks.astera.core.domain.attribute.AttributeModifier] on the
 *      affected subjects (today: every generator in the match).
 *   3. Schedules a one-shot expiration via [IScheduler.runLater] for
 *      `descriptor.durationTicks` later, which calls
 *      [ResolveAnomalyEffectUseCase.expire] to peel the modifier back off.
 *
 * ## Determinism for tests
 *
 * The [random] parameter is exposed so tests can pass a seed/stub
 * [Random] whose `nextDouble()` always falls under [FIRE_PROBABILITY]; the
 * scheduler then fires on every tick step, making the test surface
 * deterministic. Production uses [Random.Default].
 *
 * ## Why not coroutines
 *
 * `IScheduler.runRepeating` already gives us tick-driven cadence without
 * needing a coroutine context. The whole pipeline stays callback-based and
 * synchronous so `FakeScheduler.advanceTicks(...)` works as a single-call
 * test driver.
 */
public class AnomalyScheduler(
    private val repo: IAnomalyScheduleRepository,
    private val trigger: TriggerAnomalyUseCase,
    private val resolve: ResolveAnomalyEffectUseCase,
    private val scheduler: IScheduler,
    private val matches: IMatchRepository,
    private val random: Random = Random.Default,
) {

    /**
     * Start the periodic tick. Returns the [IScheduler.TaskHandle] so the
     * caller (Phase C `AsteraMatchPlugin.onDisable`) can cancel it.
     *
     * Default [period] is `ticks(100)` (5 seconds at 20 TPS); the production
     * wiring may override.
     */
    public fun start(period: Duration = ticks(DEFAULT_PERIOD_TICKS)): IScheduler.TaskHandle =
        scheduler.runRepeating(period, period) { tickOnce() }

    /**
     * Single tick body — exposed so tests that want to bypass
     * [IScheduler.runRepeating] can advance the simulation directly.
     */
    public fun tickOnce() {
        for (match in matches.findActiveMatches()) {
            if (random.nextDouble() >= FIRE_PROBABILITY) continue
            val descriptors = repo.forMatch(match.id)
            val descriptor = pickWeighted(descriptors) ?: continue
            fire(match.id, descriptor)
        }
    }

    private fun fire(matchId: MatchId, descriptor: AnomalyDescriptor) {
        val anomalyId = trigger.trigger(matchId, descriptor)
        resolve.apply(anomalyId, matchId, descriptor)
        scheduler.runLater(ticks(descriptor.durationTicks)) {
            resolve.expire(anomalyId, matchId)
        }
    }

    private fun pickWeighted(descriptors: List<AnomalyDescriptor>): AnomalyDescriptor? {
        if (descriptors.isEmpty()) return null
        val totalWeight: Int = descriptors.sumOf { it.weight }
        if (totalWeight <= 0) return null
        // nextInt(totalWeight) yields [0, totalWeight); accumulate until the
        // threshold is crossed. Deterministic given [random].
        val pick: Int = random.nextInt(totalWeight)
        var running = 0
        for (d in descriptors) {
            running += d.weight
            if (pick < running) return d
        }
        // Numerically unreachable (running ends at totalWeight, pick < totalWeight)
        // but kept as a safety net so the function stays total.
        return descriptors.last()
    }

    public companion object {
        /**
         * Probability that any single (match, tick) pair fires an anomaly.
         * Low enough that authored descriptor lists last the whole match;
         * raise via a constructor knob if Phase 1 playtests want denser
         * pacing.
         */
        public const val FIRE_PROBABILITY: Double = 0.1

        /** Default repeat period in ticks (5 seconds @ 20 TPS). */
        public const val DEFAULT_PERIOD_TICKS: Long = 100L
    }
}
