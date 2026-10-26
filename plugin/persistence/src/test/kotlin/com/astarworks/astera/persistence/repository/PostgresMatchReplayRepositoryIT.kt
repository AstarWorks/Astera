package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.replay.DamageSourceKind
import com.astarworks.astera.core.domain.replay.KeyFrame
import com.astarworks.astera.core.domain.replay.RecordedReplay
import com.astarworks.astera.core.domain.replay.ReplayEvent
import com.astarworks.astera.core.domain.replay.ReplayFormat
import com.astarworks.astera.core.domain.replay.ReplayId
import com.astarworks.astera.core.domain.replay.Vec3Snapshot
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Round-trip IT for [PostgresMatchReplayRepository] (Wave 5v / ADR-0088)
 * against real Postgres via Testcontainers. Skipped (via Assumptions)
 * when Docker is unavailable.
 *
 * Covers:
 *  - save + findByMatchId round-trip preserves every ReplayEvent variant
 *  - save + findById round-trip via the UUID index
 *  - upsert: re-recording the same match replaces the row (the old
 *    replay_id is no longer findable; the new one is)
 *  - delete removes both row and index entry
 */
public class PostgresMatchReplayRepositoryIT : PostgresIntegrationTestBase() {

    @Test
    public fun `save + findByMatchId round-trips every ReplayEvent variant`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresMatchReplayRepository(db)
        val matchId = MatchId("it-match-${java.util.UUID.randomUUID().toString().take(8)}")
        val p1 = PlayerId(UUID.randomUUID())
        val p2 = PlayerId(UUID.randomUUID())

        val replay = RecordedReplay(
            id = ReplayId.random(),
            matchId = matchId,
            startTick = 0L,
            endTick = 1000L,
            format = ReplayFormat.EventLogKeyFrame(keyFramePer = 100, interpolateMovement = true),
            events = listOf(
                ReplayEvent.PlayerMoved(p1, 0.5f, 0.0f, -0.25f, atTick = 5L),
                ReplayEvent.DamageDealt(p1, p2, 7.5, DamageSourceKind.ITEM, atTick = 10L),
                ReplayEvent.PlayerKilled(p2, p1, atTick = 11L),
                ReplayEvent.PlayerRespawned(p2, atTick = 20L),
                ReplayEvent.ItemUsed(p1, ItemId("example-potion"), atTick = 30L),
                ReplayEvent.SkillCast(p1, SkillId("fireball"), atTick = 40L),
                ReplayEvent.ChatSent(p1, MessageKey("astera.test.replay.chat"), atTick = 50L),
                ReplayEvent.MatchPhaseChanged(
                    matchId, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE, atTick = 60L,
                ),
                ReplayEvent.PillarCaptured(matchId, "north", TeamId("red"), atTick = 70L),
            ),
            keyFrames = listOf(
                KeyFrame(
                    tick = 100L,
                    playerPositions = mapOf(p1 to Vec3Snapshot(1f, 64f, 2f)),
                    playerHealth = mapOf(p1 to 20.0),
                    playerActiveItems = mapOf(p1 to ItemId("example-sword")),
                ),
            ),
            sizeBytes = 1024,
        )

        val saveResult = repo.save(replay)
        check(saveResult is Result.Success)

        val found = repo.findByMatchId(matchId)
        assertThat(found).isEqualTo(replay)
        }
    }

    @Test
    public fun `findById uses the replay_id index`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresMatchReplayRepository(db)
        val matchId = MatchId("it-match-id-${java.util.UUID.randomUUID().toString().take(8)}")
        val replay = trivialReplay(matchId)
        check(repo.save(replay) is Result.Success)

        val found = repo.findById(replay.id)
        assertThat(found).isEqualTo(replay)
        }
    }

    @Test
    public fun `re-recording the same match upserts and reassigns replay_id`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresMatchReplayRepository(db)
        val matchId = MatchId("it-match-up-${java.util.UUID.randomUUID().toString().take(8)}")
        val first = trivialReplay(matchId)
        val second = trivialReplay(matchId)
        check(repo.save(first) is Result.Success)
        check(repo.save(second) is Result.Success)

        assertThat(repo.findByMatchId(matchId)?.id).isEqualTo(second.id)
        assertThat(repo.findById(first.id)).isNull()
        assertThat(repo.findById(second.id)).isEqualTo(second)
        }
    }

    @Test
    public fun `delete removes the row`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresMatchReplayRepository(db)
        val matchId = MatchId("it-match-del-${java.util.UUID.randomUUID().toString().take(8)}")
        val replay = trivialReplay(matchId)
        check(repo.save(replay) is Result.Success)

        repo.delete(matchId)
        assertThat(repo.findByMatchId(matchId)).isNull()
        assertThat(repo.findById(replay.id)).isNull()
        }
    }

    private fun trivialReplay(matchId: MatchId): RecordedReplay = RecordedReplay(
        id = ReplayId.random(),
        matchId = matchId,
        startTick = 0L,
        endTick = 50L,
        format = ReplayFormat.EventLogKeyFrame(),
        events = listOf(
            ReplayEvent.PlayerKilled(
                victimId = PlayerId(UUID.randomUUID()),
                killerId = null,
                atTick = 5L,
            ),
        ),
        keyFrames = emptyList(),
        sizeBytes = 32,
    )
}
