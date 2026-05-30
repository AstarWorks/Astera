package com.astarworks.astera.stats.application.eventbus.listeners.quest

import com.astarworks.astera.core.application.port.outbound.IQuestChainRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.QuestStageCompleted
import com.astarworks.astera.stats.application.usecase.achievement.AchievementError
import com.astarworks.astera.stats.application.usecase.achievement.UnlockAchievementUseCase
import org.slf4j.LoggerFactory

/**
 * On [QuestStageCompleted] unlock the stage's `linkedAchievementId` (if any)
 * for the completing player (Wave K 5integration-wire, ADR-0128).
 *
 * # Background — the silent drop
 *
 * Pre-Wave K, quest stage completion published [QuestStageCompleted] +
 * dispatched stage rewards through `AdvancementRewardGranted`, but
 * authors had no way to say "this stage also counts towards <achievement>"
 * without packaging the achievement as a stage *reward*. That's the wrong
 * vocabulary (rewards are granted, achievements are *unlocked* — they're
 * outputs of progress, not gifts), and overloading it would conflate
 * "you earned 100 GOLD" with "you unlocked Explorer".
 *
 * Wave K introduces an explicit `linked_achievement_id` field on
 * `QuestStage` so authors can wire the link in YAML without retrofitting
 * the rewards vocabulary.
 *
 * # Progression vs. unlock
 *
 * Phase 1 PoC has no multi-tick `IAchievementService.incrementProgress`
 * pipeline — achievements are binary (locked / unlocked). This listener
 * therefore treats `linkedAchievementId` as "unlock attempt on stage
 * completion": one stage = one unlock. Future waves may swap to a
 * counter-based progress model; the YAML field name (`linked_achievement_id`,
 * not `unlock_achievement_id`) keeps that path open.
 *
 * # Why stats, not core
 *
 * Per ADR-0022 sibling isolation, `:plugin:core` cannot import
 * `UnlockAchievementUseCase`. The listener subscribes to a Core event
 * but invokes a Stats use case, so it lives in `:plugin:stats` (same
 * rule as Wave K's other onboarding wires).
 *
 * # Failure modes
 *
 *  - Stage has no `linkedAchievementId`: no-op (silent skip).
 *  - Chain not in registry: stage was completed via a synthetic test
 *    publish — log at debug.
 *  - Stage id not in chain: same as above (debug).
 *  - Achievement `NotInCatalog`: operator linked a YAML that doesn't
 *    exist — WARN.
 *  - Achievement `AlreadyUnlocked`: player completed the linked stage
 *    via a different quest path — debug.
 */
public class UpdateAchievementProgressOnQuestStageCompletedListener(
    private val chains: IQuestChainRegistry,
    private val unlockAchievement: UnlockAchievementUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: QuestStageCompleted) {
        val chain = chains.find(event.chainId)
        if (chain == null) {
            log.debug(
                "QuestStageCompleted carried unknown chain id: chain={} (test publish?)",
                event.chainId,
            )
            return
        }
        val stage = chain.stage(event.completedStageId)
        if (stage == null) {
            log.debug(
                "QuestStageCompleted carried stage id not in chain: chain={} stage={}",
                event.chainId, event.completedStageId,
            )
            return
        }
        val achievementId = stage.linkedAchievementId ?: return

        when (val outcome = unlockAchievement.unlock(event.playerId, achievementId)) {
            is Result.Success -> {
                log.info(
                    "Quest stage -> achievement unlocked: player={} chain={} stage={} achievement={}",
                    event.playerId, event.chainId, event.completedStageId, achievementId,
                )
            }
            is Result.Failure -> when (outcome.error) {
                is AchievementError.NotInCatalog -> {
                    log.warn(
                        "Quest stage links to achievement not in catalog: " +
                            "chain={} stage={} achievement={}",
                        event.chainId, event.completedStageId, achievementId,
                    )
                }
                is AchievementError.AlreadyUnlocked -> {
                    log.debug(
                        "Quest stage -> achievement already unlocked: player={} achievement={}",
                        event.playerId, achievementId,
                    )
                }
            }
        }
    }
}
