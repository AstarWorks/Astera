package com.astarworks.astera.match.application.service.replay

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.replay.DamageSourceKind
import com.astarworks.astera.core.domain.replay.RecordedReplay
import com.astarworks.astera.core.domain.replay.ReplayEvent
import com.astarworks.astera.core.domain.replay.ReplayFormat
import com.astarworks.astera.core.domain.replay.ReplayId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryMatchReplayRepositoryTest {

    private val repo = InMemoryMatchReplayRepository()

    @Test
    fun `save then findByMatchId returns the same replay`() {
        val replay = sampleReplay(MatchId("a"))
        check(repo.save(replay) is Result.Success)

        assertThat(repo.findByMatchId(MatchId("a"))).isEqualTo(replay)
        assertThat(repo.findById(replay.id)).isEqualTo(replay)
    }

    @Test
    fun `findByMatchId returns null when nothing saved`() {
        assertThat(repo.findByMatchId(MatchId("nope"))).isNull()
    }

    @Test
    fun `save overwrites prior row for the same matchId and clears the old id index`() {
        val first = sampleReplay(MatchId("a"))
        val second = sampleReplay(MatchId("a"))
        repo.save(first)
        repo.save(second)

        assertThat(repo.findById(first.id)).isNull()
        assertThat(repo.findById(second.id)).isEqualTo(second)
        assertThat(repo.findByMatchId(MatchId("a"))).isEqualTo(second)
    }

    @Test
    fun `delete removes both indices`() {
        val replay = sampleReplay(MatchId("a"))
        repo.save(replay)
        repo.delete(MatchId("a"))

        assertThat(repo.findByMatchId(MatchId("a"))).isNull()
        assertThat(repo.findById(replay.id)).isNull()
    }

    private fun sampleReplay(matchId: MatchId): RecordedReplay = RecordedReplay(
        id = ReplayId.random(),
        matchId = matchId,
        startTick = 0L,
        endTick = 100L,
        format = ReplayFormat.EventLogKeyFrame(),
        events = listOf(
            ReplayEvent.DamageDealt(
                attackerId = PlayerId(UUID.randomUUID()),
                victimId = PlayerId(UUID.randomUUID()),
                amount = 5.0,
                sourceKind = DamageSourceKind.ITEM,
                atTick = 10L,
            ),
        ),
        keyFrames = emptyList(),
        sizeBytes = 100,
    )
}
