package com.astarworks.astera.match.application.usecase.mode

import com.astarworks.astera.core.domain.event.BlockBroken
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.domain.event.MatchEnded
import com.astarworks.astera.match.domain.mode.BreakCore
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5c BreakCore listener tests.
 *
 * Verifies that a [BlockBroken] event matching the configured core block id
 * (for an in-progress match) triggers a [MatchEnded] publication with
 * [EndReason.ObjectiveCompleted]. Non-matching events are ignored.
 *
 * Tests use the `lifecycle = null` fallback path so they don't depend on the
 * full `IMatchLifecycle` wiring graph (Phase C).
 */
class BreakCoreListenerTest {

    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var matches: InMemoryMatchRepository
    private val mode: BreakCore = BreakCore(
        coreBlockId = "astera:core_block",
        coresPerTeam = 1,
    )

    @BeforeEach
    fun setUp() {
        eventBus = FakeDomainEventBus()
        matches = InMemoryMatchRepository()
    }

    @Test
    fun `BlockBroken with matching coreBlockId publishes MatchEnded ObjectiveCompleted`() {
        val matchId = MatchId("match-bc")
        matches.save(buildMatch(matchId, MatchPhase.ACTIVE))

        val listener = BreakCoreListener(eventBus, matches, lifecycle = null, mode = mode)
        listener.subscribe()

        eventBus.publish(buildBlockBroken(matchId, blockId = "astera:core_block", atTick = 500L))

        val ended = eventBus.publishedOfType<MatchEnded>()
        assertThat(ended).hasSize(1)
        assertThat(ended.first().matchId).isEqualTo(matchId)
        assertThat(ended.first().reason).isEqualTo(EndReason.ObjectiveCompleted)
        assertThat(ended.first().winningTeam).isNull() // PoC: region-team mapping not yet wired
        assertThat(ended.first().atTick).isEqualTo(500L)
    }

    @Test
    fun `BlockBroken with non-matching blockId is ignored`() {
        val matchId = MatchId("match-bc")
        matches.save(buildMatch(matchId, MatchPhase.ACTIVE))

        BreakCoreListener(eventBus, matches, lifecycle = null, mode = mode).subscribe()

        eventBus.publish(buildBlockBroken(matchId, blockId = "minecraft:stone", atTick = 1L))

        assertThat(eventBus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `BlockBroken referencing an unknown match is ignored`() {
        BreakCoreListener(eventBus, matches, lifecycle = null, mode = mode).subscribe()

        eventBus.publish(buildBlockBroken(MatchId("match-ghost"), blockId = "astera:core_block", atTick = 1L))

        assertThat(eventBus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `BlockBroken on an already-ENDED match is ignored`() {
        val matchId = MatchId("match-done")
        matches.save(buildMatch(matchId, MatchPhase.ENDED))

        BreakCoreListener(eventBus, matches, lifecycle = null, mode = mode).subscribe()

        eventBus.publish(buildBlockBroken(matchId, blockId = "astera:core_block", atTick = 1L))

        assertThat(eventBus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `listener honors mode coreBlockId override`() {
        val matchId = MatchId("match-custom")
        matches.save(buildMatch(matchId, MatchPhase.ACTIVE))

        val customMode = BreakCore(coreBlockId = "astera:special_core")
        BreakCoreListener(eventBus, matches, lifecycle = null, mode = customMode).subscribe()

        // default block id does not match
        eventBus.publish(buildBlockBroken(matchId, blockId = "astera:core_block", atTick = 1L))
        assertThat(eventBus.publishedOfType<MatchEnded>()).isEmpty()

        // matching custom id does
        eventBus.publish(buildBlockBroken(matchId, blockId = "astera:special_core", atTick = 2L))
        assertThat(eventBus.publishedOfType<MatchEnded>()).hasSize(1)
    }

    @Test
    fun `mode accessor returns the configured BreakCore value`() {
        val l = BreakCoreListener(eventBus, matches, lifecycle = null, mode = mode)

        assertThat(l.mode()).isEqualTo(mode)
        assertThat(l.mode().coreBlockId).isEqualTo("astera:core_block")
    }

    private fun buildMatch(id: MatchId, phase: MatchPhase): Match {
        val teamId = TeamId("red")
        return Match(
            id = id,
            phase = phase,
            teams = listOf(
                Team(
                    id = teamId,
                    displayNameKey = MessageKey("astera.match.team.red"),
                    colorKey = MessageKey("astera.match.team.red.color"),
                    members = emptySet(),
                ),
            ),
            score = Score(mapOf(teamId to 0)),
            stageId = StageId("test-arena"),
            startedAtTick = 0L,
        )
    }

    private fun buildBlockBroken(
        matchId: MatchId,
        blockId: String,
        atTick: Long,
    ): BlockBroken = BlockBroken(
        matchId = matchId,
        playerId = PlayerId(UUID.randomUUID()),
        position = Vec3(0.0, 64.0, 0.0),
        blockId = blockId,
        atTick = atTick,
    )
}
