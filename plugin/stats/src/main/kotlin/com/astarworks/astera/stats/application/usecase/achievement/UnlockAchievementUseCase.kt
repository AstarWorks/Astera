package com.astarworks.astera.stats.application.usecase.achievement

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.IAchievementRepository
import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.stats.domain.achievement.PlayerAchievement
import com.astarworks.astera.stats.domain.event.AchievementUnlocked
import java.time.Clock

/**
 * Unlock a previously catalogued achievement for [playerId] (Wave 5e).
 *
 * Idempotent at the result level: re-invoking with the same `(playerId,
 * achievementId)` yields [AchievementError.AlreadyUnlocked] without
 * re-publishing [AchievementUnlocked]. The three milestone listeners
 * (kill / damage / match-win) tolerate both expected failure modes silently.
 *
 * # Sequence
 * 1. Resolve [achievementId] in the catalog ([AchievementError.NotInCatalog]
 *    if the admin never loaded the entry — typical when YAML hasn't been
 *    populated yet).
 * 2. Reject if already unlocked ([AchievementError.AlreadyUnlocked]).
 * 3. Build a [PlayerAchievement] stamped at the injected [clock]'s instant,
 *    persist via [repo], then publish [AchievementUnlocked].
 *
 * The [clock] parameter exists so tests can assert on `unlockedAt` /
 * `occurredAt` deterministically.
 */
public class UnlockAchievementUseCase(
    private val repo: IAchievementRepository,
    private val eventBus: IDomainEventBus,
    private val clock: Clock = Clock.systemUTC(),
) {

    public suspend fun unlock(
        playerId: PlayerId,
        achievementId: AchievementId,
    ): Result<PlayerAchievement, AchievementError> {
        repo.find(achievementId)
            ?: return Result.failure(AchievementError.NotInCatalog(achievementId))

        if (repo.isUnlocked(playerId, achievementId)) {
            return Result.failure(AchievementError.AlreadyUnlocked(achievementId))
        }

        val pa = PlayerAchievement(
            playerId = playerId,
            achievementId = achievementId,
            unlockedAt = clock.instant(),
        )
        repo.unlock(pa)

        eventBus.publish(
            AchievementUnlocked(
                playerId = pa.playerId,
                achievementId = pa.achievementId,
                unlockedAt = pa.unlockedAt,
            ),
        )

        return Result.success(pa)
    }
}

/**
 * Expected failure modes of [UnlockAchievementUseCase.unlock]. Each variant
 * carries an i18n [messageKey] so command handlers and reward bridges can
 * surface a localized message without leaking domain detail.
 */
public sealed class AchievementError {
    public abstract val messageKey: MessageKey

    public data class NotInCatalog(val id: AchievementId) : AchievementError() {
        override val messageKey: MessageKey =
            MessageKey("astera.stats.achievement.error.not-in-catalog")
    }

    public data class AlreadyUnlocked(val id: AchievementId) : AchievementError() {
        override val messageKey: MessageKey =
            MessageKey("astera.stats.achievement.error.already-unlocked")
    }
}
