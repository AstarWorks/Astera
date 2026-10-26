package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.event.LoginBonusClaimed
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.usecase.achievement.UnlockAchievementUseCase
import com.astarworks.astera.stats.domain.event.LeveledUp
import com.astarworks.astera.stats.domain.event.MissionCompleted

/**
 * The progression-loop achievements (ADR-0210): a 7-day login streak, reaching level 5 / 10 and
 * the first completed mission. Re-unlocking is a no-op in `UnlockAchievementUseCase`, so every
 * handler may fire as often as its event does.
 */
public class UnlockProgressionAchievementsListener(
    private val unlock: UnlockAchievementUseCase,
) {
    public suspend fun handle(event: LoginBonusClaimed) {
        if (event.streakDays >= STREAK_FOR_REGULAR) tryUnlock(event.playerId, STREAK_7)
    }

    public suspend fun handle(event: LeveledUp) {
        LEVEL_ACHIEVEMENTS.forEach { (level, id) ->
            if (event.newLevel.value >= level) tryUnlock(event.playerId, id)
        }
    }

    public suspend fun handle(event: MissionCompleted) {
        tryUnlock(event.playerId, MISSION_FIRST)
    }

    private suspend fun tryUnlock(playerId: PlayerId, id: AchievementId) {
        unlock.unlock(playerId, id)
    }

    public companion object {
        public const val STREAK_FOR_REGULAR: Int = 7
        public val STREAK_7: AchievementId = AchievementId("streak-7")
        public val MISSION_FIRST: AchievementId = AchievementId("mission-first")
        public val LEVEL_ACHIEVEMENTS: List<Pair<Int, AchievementId>> = listOf(
            5 to AchievementId("level-5"),
            10 to AchievementId("level-10"),
        )
    }
}
