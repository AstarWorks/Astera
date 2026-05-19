package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.norma.NormaTarget
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.ILevelService
import com.astarworks.astera.stats.application.usecase.level.GrantXpUseCase
import com.astarworks.astera.stats.domain.event.MissionCompleted
import com.astarworks.astera.stats.domain.level.ConfiguredNormaParameter
import com.astarworks.astera.stats.domain.level.LevelUpMission
import com.astarworks.astera.stats.domain.level.XpCurve
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges Norma (mission completion) → Level (advancement) for the README
 * "レベルアップには一定のスター or キルが必要" rule (Wave 5e, RTM
 * `LevelUpMission.kt`).
 *
 * Subscribes [MissionCompleted]. The listener consults
 * [criterionByLevel] (per-player current-level → required mission /
 * threshold) and accumulates per-player counters for KILL / STAR. When
 * the criterion at the player's current level is satisfied, the listener
 * grants enough XP via [GrantXpUseCase] to cross the next-level
 * threshold — i.e. it nudges [ILevelService] (the SSOT for level state)
 * forward without owning a parallel level store.
 *
 * **Idempotency**: every [MissionCompleted] is consumed at most once
 * (tracked by `(playerId, normaId)`). Re-publishing the same event is a
 * no-op, satisfying the handoff's "shouldn't double-advance" requirement.
 *
 * **Relation to XP-based leveling**: this listener is the *shortcut*
 * path. [GrantXpUseCase] (called from kill / damage rewards) still
 * drives the primary XP curve. When this listener triggers an advance,
 * it does so by *granting* XP, so the two paths converge on
 * [ILevelService] as the single level oracle — no divergent state.
 */
public class AdvanceLevelOnNormaCompletedListener(
    private val levelService: ILevelService,
    private val grantXp: GrantXpUseCase,
    private val criterionByLevel: Map<Int, ConfiguredNormaParameter>,
) {

    /** Per-player accumulated counts since this listener started. */
    private val counters: ConcurrentHashMap<PlayerId, Counters> = ConcurrentHashMap()

    /** Idempotency cache. Composite key avoids re-handling re-published events. */
    private val handled: MutableSet<HandledKey> = ConcurrentHashMap.newKeySet()

    public fun handle(event: MissionCompleted) {
        if (!handled.add(HandledKey(event.playerId, event.normaId.value))) return

        val counter = counters.getOrPut(event.playerId) { Counters() }
        // Classify the completed mission. The reward carries the currency
        // id — "STAR" currency means a star-collection mission. Otherwise
        // fall back to NormaTarget shape inference via reward.xp > 0 ≈
        // not a star mission. For Phase 1 PoC, the explicit currency
        // check is enough.
        val classification = classify(event)
        when (classification) {
            LevelUpMission.STAR -> counter.stars += starUnits(event.reward.currencyAmount)
            LevelUpMission.KILL -> counter.kills += 1
            null -> return
        }

        val level = levelService.currentLevel(event.playerId).value
        val criterion = criterionByLevel[level] ?: return
        if (criterion.mission != classification) return

        val current = when (criterion.mission) {
            LevelUpMission.STAR -> counter.stars
            LevelUpMission.KILL -> counter.kills
        }
        if (current < criterion.threshold) return

        // Threshold met: grant enough XP to reach the *next* level start.
        // This delegates the actual level transition to ILevelService and
        // emits LeveledUp via GrantXpUseCase — no parallel level store.
        val nextThreshold = XpCurve.thresholdToReach(level + 1)
        val currentXp = levelService.totalXp(event.playerId).value
        val deficit = nextThreshold - currentXp
        if (deficit <= 0) return  // already past — XP path beat us to it
        grantXp.execute(event.playerId, deficit, XpSource.MissionReward)

        // Consume the threshold so the next level's criterion starts from 0.
        when (criterion.mission) {
            LevelUpMission.STAR -> counter.stars -= criterion.threshold
            LevelUpMission.KILL -> counter.kills -= criterion.threshold
        }
    }

    private fun classify(event: MissionCompleted): LevelUpMission? {
        val cur = event.reward.currencyId
        return when {
            cur != null && cur.equals("STAR", ignoreCase = true) -> LevelUpMission.STAR
            // Heuristic for Phase 1: missions without a STAR currency
            // reward that fire from kill-based progress are KILL missions.
            // The NormaTarget shape is not on MissionCompleted, so the
            // event-payload-only inference uses the reward as the proxy.
            else -> LevelUpMission.KILL
        }
    }

    /** Stars-per-currency-unit. 1:1 in Phase 1; configurable later. */
    private fun starUnits(currencyAmount: Long): Long = currencyAmount

    private data class HandledKey(val playerId: PlayerId, val normaIdValue: String)

    private class Counters {
        @Volatile var stars: Long = 0L
        @Volatile var kills: Long = 0L
    }

    public companion object {
        /**
         * Phase 1 default level-up criterion map. Phase C wires this from
         * `ConfiguredLevelParameter` once that type carries it; until
         * then the listener uses the constant.
         *
         * Shape: README "1 → 2 needs 50 stars, 2 → 3 needs 10 kills,
         * 3 → 4 needs 100 stars, 4 → 5 needs 25 kills, 5 → 6 needs
         * 200 stars". Aligns with the README "スター or キル" intent.
         */
        public val DEFAULT_CRITERION_BY_LEVEL: Map<Int, ConfiguredNormaParameter> = mapOf(
            1 to ConfiguredNormaParameter(LevelUpMission.STAR, 50),
            2 to ConfiguredNormaParameter(LevelUpMission.KILL, 10),
            3 to ConfiguredNormaParameter(LevelUpMission.STAR, 100),
            4 to ConfiguredNormaParameter(LevelUpMission.KILL, 25),
            5 to ConfiguredNormaParameter(LevelUpMission.STAR, 200),
        )
    }
}
