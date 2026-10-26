package com.astarworks.astera.stats.application.port.outbound

import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Persisted current-milestone record for the nameplate
 * (Wave 5flex / ADR-0102 surface #5).
 *
 * The hot nameplate path reads from the in-memory
 * [com.astarworks.astera.stats.application.service.nameplate.NameplateBadgeRegistry];
 * this port persists *only* the most-recent displayed milestone so the
 * value survives a server restart.
 *
 * # Read on join
 *
 * The Wave 5flex `RefreshNameplateOnJoinListener` (Phase C wiring)
 * reads from this port on player-join and re-injects the milestone
 * into the badge cache.
 *
 * # Write on unlock
 *
 * `RefreshNameplateOnAchievementUnlockedListener` upserts here when
 * the unlocked achievement qualifies for display (per the
 * `milestoneKeyOf` resolver).
 */
public interface INameplateMilestoneRepository {

    public data class Record(
        public val playerId: PlayerId,
        public val achievementId: AchievementId?,
        public val milestoneKey: MessageKey?,
        public val setAt: Instant,
    )

    public suspend fun find(playerId: PlayerId): Record?

    public suspend fun save(record: Record)

    public suspend fun delete(playerId: PlayerId)
}
