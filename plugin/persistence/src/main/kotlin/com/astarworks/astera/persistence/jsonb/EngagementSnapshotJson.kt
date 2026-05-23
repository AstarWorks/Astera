package com.astarworks.astera.persistence.jsonb

import com.astarworks.astera.core.domain.engagement.EngagementScore
import com.astarworks.astera.core.domain.engagement.EngagementSnapshot
import com.astarworks.astera.core.domain.player.PlayerId
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * Wraps [EngagementSnapshot] for the V30 `player_engagement_snapshots.snapshot`
 * JSONB column (Wave 5engage, ADR-0104).
 *
 * The domain types ([EngagementSnapshot], [EngagementScore]) stay free of
 * `@Serializable` per the Konsist rule banning kotlinx.serialization in
 * `core.domain.*`. This DTO mirrors them with plain primitives plus
 * `Duration` / `Instant` rendered as their ISO-8601 string form so the JSONB
 * blob is human-readable in `psql` (no opaque longs).
 *
 * The default `JsonbColumnType` has `ignoreUnknownKeys = true`, so adding a
 * new field to the domain record + DTO does not break reads of existing rows
 * that lack the new key — same forward-compatibility property as
 * `PlayerSettingsJson`.
 *
 * `playerId` is the row PK (lives in the columnar column, not in the JSONB
 * blob) — the domain object is reconstructed by combining the row's
 * `player_id` with this DTO via [toDomain].
 */
@Serializable
public data class EngagementSnapshotJson(
    val totalPlaytimeIso: String,
    val loginStreakDays: Int,
    val skillsExercisedCount: Int,
    val refinementAttempts: Long,
    val cosmeticEquipDiversity: Double,
    val firstSeenAtIso: String,
    val lastSeenAtIso: String,
    val score: Double,
    val computedAtIso: String,
) {

    public fun toDomain(playerId: PlayerId): EngagementSnapshot = EngagementSnapshot(
        playerId = playerId,
        totalPlaytime = Duration.parse(totalPlaytimeIso),
        loginStreakDays = loginStreakDays,
        skillsExercisedCount = skillsExercisedCount,
        refinementAttempts = refinementAttempts,
        cosmeticEquipDiversity = cosmeticEquipDiversity,
        firstSeenAt = Instant.parse(firstSeenAtIso),
        lastSeenAt = Instant.parse(lastSeenAtIso),
        score = EngagementScore(score),
        computedAt = Instant.parse(computedAtIso),
    )

    public companion object {
        public fun fromDomain(snapshot: EngagementSnapshot): EngagementSnapshotJson =
            EngagementSnapshotJson(
                totalPlaytimeIso = snapshot.totalPlaytime.toString(),
                loginStreakDays = snapshot.loginStreakDays,
                skillsExercisedCount = snapshot.skillsExercisedCount,
                refinementAttempts = snapshot.refinementAttempts,
                cosmeticEquipDiversity = snapshot.cosmeticEquipDiversity,
                firstSeenAtIso = snapshot.firstSeenAt.toString(),
                lastSeenAtIso = snapshot.lastSeenAt.toString(),
                score = snapshot.score.value,
                computedAtIso = snapshot.computedAt.toString(),
            )
    }
}
