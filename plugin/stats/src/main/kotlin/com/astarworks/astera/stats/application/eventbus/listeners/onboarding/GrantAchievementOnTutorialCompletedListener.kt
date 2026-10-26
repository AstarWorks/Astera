package com.astarworks.astera.stats.application.eventbus.listeners.onboarding

import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.event.TutorialCompleted
import com.astarworks.astera.stats.application.usecase.achievement.AchievementError
import com.astarworks.astera.stats.application.usecase.achievement.UnlockAchievementUseCase
import com.astarworks.astera.core.domain.Result
import org.slf4j.LoggerFactory

/**
 * On [TutorialCompleted] unlock the `astera:tutorial-graduate` achievement
 * for the player (Wave K 5integration-wire, ADR-0128).
 *
 * # Background — the silent drop
 *
 * Pre-Wave K, `AdvanceTutorialStepUseCase` published [TutorialCompleted]
 * but no subscriber turned tutorial graduation into an unlockable badge.
 * Onboarding looped without a closing milestone — players couldn't see
 * "I'm done with the tutorial", and the achievement page never reflected
 * it. The Annihilation-mode silent-drop pattern (Wave I) applies here:
 * an event without a subscriber is a wire bug, not a content gap.
 *
 * # Sequence
 *
 *  1. Player walks the last tutorial step.
 *  2. `AdvanceTutorialStepUseCase` publishes [TutorialCompleted].
 *  3. This listener calls [UnlockAchievementUseCase.unlock] for the
 *     tutorial-graduate achievement.
 *  4. On success, `UnlockAchievementUseCase` publishes
 *     [com.astarworks.astera.stats.domain.event.AchievementUnlocked]
 *     — Wave K's `RecomputeProfileCardOnAchievementUnlockedListener`
 *     (also added in this wave) picks that up and refreshes the
 *     player's `PublicProfileCard`. The currency reward is
 *     emitted by `GrantRewardOnTutorialCompletedListener` (see
 *     `:plugin:economy`, same wave).
 *
 * # Failure modes
 *
 *  - [AchievementError.NotInCatalog]: `content/advancements/tutorial-graduate.yaml`
 *    wasn't loaded — operator misconfiguration. Logged at WARN so
 *    operators see the problem on first tutorial graduation.
 *  - [AchievementError.AlreadyUnlocked]: player repeated the tutorial
 *    (or the event was somehow republished). Swallowed silently —
 *    achievements are inherently idempotent.
 *
 * # Why stats, not core
 *
 * Per ADR-0022 sibling isolation, `:plugin:core` cannot import
 * `UnlockAchievementUseCase` (lives in `:plugin:stats`). The listener
 * subscribes to a Core event but invokes a Stats use case, so it
 * lives in `:plugin:stats`.
 */
public class GrantAchievementOnTutorialCompletedListener(
    private val unlockAchievement: UnlockAchievementUseCase,
    private val achievementId: AchievementId = TUTORIAL_GRADUATE_ID,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public suspend fun handle(event: TutorialCompleted) {
        when (val outcome = unlockAchievement.unlock(event.playerId, achievementId)) {
            is Result.Success -> {
                log.info(
                    "Tutorial graduation -> achievement unlocked: player={} tutorial={} achievement={}",
                    event.playerId, event.tutorialId, achievementId,
                )
            }
            is Result.Failure -> when (outcome.error) {
                is AchievementError.NotInCatalog -> {
                    log.warn(
                        "Tutorial graduation could not unlock achievement: " +
                            "{} not in catalog. Add content/advancements/tutorial-graduate.yaml.",
                        achievementId,
                    )
                }
                is AchievementError.AlreadyUnlocked -> {
                    log.debug(
                        "Tutorial graduation -> achievement already unlocked: player={} achievement={}",
                        event.playerId, achievementId,
                    )
                }
            }
        }
    }

    public companion object {
        /** Achievement id reserved by `content/advancements/tutorial-graduate.yaml`. */
        public val TUTORIAL_GRADUATE_ID: AchievementId = AchievementId("tutorial-graduate")
    }
}
