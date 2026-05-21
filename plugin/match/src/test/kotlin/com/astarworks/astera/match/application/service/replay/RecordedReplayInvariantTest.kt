package com.astarworks.astera.match.application.service.replay

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.replay.DamageSourceKind
import com.astarworks.astera.core.domain.replay.KeyFrame
import com.astarworks.astera.core.domain.replay.RecordedReplay
import com.astarworks.astera.core.domain.replay.ReplayEvent
import com.astarworks.astera.core.domain.replay.ReplayFormat
import com.astarworks.astera.core.domain.replay.ReplayId
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.util.UUID

class RecordedReplayInvariantTest {

    private val matchId = MatchId("match-1")
    private val replayId = ReplayId.random()

    @Test
    fun `endTick must be greater than or equal to startTick`() {
        assertThatIllegalArgumentException().isThrownBy {
            RecordedReplay(
                id = replayId, matchId = matchId,
                startTick = 100L, endTick = 50L,
                format = ReplayFormat.EventLogKeyFrame(),
                events = emptyList(), keyFrames = emptyList(),
                sizeBytes = 0,
            )
        }
    }

    @Test
    fun `event atTick outside range is rejected`() {
        assertThatIllegalArgumentException().isThrownBy {
            RecordedReplay(
                id = replayId, matchId = matchId,
                startTick = 0L, endTick = 100L,
                format = ReplayFormat.EventLogKeyFrame(),
                events = listOf(
                    ReplayEvent.DamageDealt(
                        attackerId = null,
                        victimId = PlayerId(UUID.randomUUID()),
                        amount = 1.0,
                        sourceKind = DamageSourceKind.ENVIRONMENT,
                        atTick = 200L,
                    ),
                ),
                keyFrames = emptyList(),
                sizeBytes = 0,
            )
        }
    }

    @Test
    fun `keyFrame tick outside range is rejected`() {
        assertThatIllegalArgumentException().isThrownBy {
            RecordedReplay(
                id = replayId, matchId = matchId,
                startTick = 0L, endTick = 100L,
                format = ReplayFormat.EventLogKeyFrame(),
                events = emptyList(),
                keyFrames = listOf(
                    KeyFrame(tick = 500L, playerPositions = emptyMap(), playerHealth = emptyMap(), playerActiveItems = emptyMap()),
                ),
                sizeBytes = 0,
            )
        }
    }

    @Test
    fun `negative sizeBytes is rejected`() {
        assertThatIllegalArgumentException().isThrownBy {
            RecordedReplay(
                id = replayId, matchId = matchId,
                startTick = 0L, endTick = 1L,
                format = ReplayFormat.EventLogKeyFrame(),
                events = emptyList(), keyFrames = emptyList(),
                sizeBytes = -1,
            )
        }
    }
}
