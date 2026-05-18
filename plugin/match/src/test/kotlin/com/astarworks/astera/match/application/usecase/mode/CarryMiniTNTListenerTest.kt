package com.astarworks.astera.match.application.usecase.mode

import com.astarworks.astera.core.domain.event.EndReason
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
import com.astarworks.astera.match.domain.event.EntityEnteredRegion
import com.astarworks.astera.match.domain.event.EntityPickedUpItem
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.match.domain.mode.CarryMiniTNT
import com.astarworks.astera.match.domain.mode.GameModeId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5c — verifies that [CarryMiniTNTListener] only treats a player
 * as a deliverable carrier after [CarryMiniTNT.miniCountToComplete]
 * pickups, and that a single delivery to the enemy region ends the
 * match.
 */
class CarryMiniTNTListenerTest {

    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var matches: InMemoryMatchRepository
    private lateinit var mode: CarryMiniTNT
    private lateinit var listener: CarryMiniTNTListener

    private val matchId = MatchId("match-carryminitnt")
    private val redTeam = TeamId("red")
    private val blueTeam = TeamId("blue")
    private val redBaseRegion = "red_base"
    private val blueBaseRegion = "blue_base"
    private val redPlayer = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val bluePlayer = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"))

    @BeforeEach
    fun setUp() {
        eventBus = FakeDomainEventBus()
        matches = InMemoryMatchRepository()
        matches.save(twoTeamMatch())
        mode = CarryMiniTNT(
            id = GameModeId("carry-mini-tnt"),
            displayNameKey = MessageKey("astera.match.mode.carry-mini-tnt.name"),
            descriptionKey = MessageKey("astera.match.mode.carry-mini-tnt.description"),
            deliveryRegionIds = mapOf(
                redTeam to redBaseRegion,
                blueTeam to blueBaseRegion,
            ),
        )
        listener = CarryMiniTNTListener(eventBus, mode, matches)
        listener.subscribe()
    }

    @Test
    fun `10 mini-TNT pickups then delivery to enemy base wins`() {
        repeat(mode.miniCountToComplete) { i ->
            eventBus.publish(
                EntityPickedUpItem(matchId, redPlayer, mode.miniTntItemId, quantity = 1, atTick = 10L + i),
            )
        }
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, blueBaseRegion, atTick = 100L))

        val endings = eventBus.publishedOfType<MatchEnded>()
        assertThat(endings).hasSize(1)
        assertThat(endings.first().matchId).isEqualTo(matchId)
        assertThat(endings.first().winningTeam).isEqualTo(redTeam)
        assertThat(endings.first().reason).isEqualTo(EndReason.ObjectiveCompleted)
        assertThat(endings.first().atTick).isEqualTo(100L)
    }

    @Test
    fun `fewer than 10 pickups does not enable delivery`() {
        repeat(mode.miniCountToComplete - 1) { i ->
            eventBus.publish(
                EntityPickedUpItem(matchId, redPlayer, mode.miniTntItemId, quantity = 1, atTick = 10L + i),
            )
        }
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, blueBaseRegion, atTick = 100L))

        assertThat(eventBus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `pickups of unrelated items do not count toward threshold`() {
        repeat(mode.miniCountToComplete) { i ->
            eventBus.publish(
                EntityPickedUpItem(matchId, redPlayer, "astera:apple", quantity = 1, atTick = 10L + i),
            )
        }
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, blueBaseRegion, atTick = 100L))

        assertThat(eventBus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `quantity greater than 1 counts toward threshold`() {
        // Two pickups of 5 = 10 total → reaches threshold.
        eventBus.publish(EntityPickedUpItem(matchId, redPlayer, mode.miniTntItemId, quantity = 5, atTick = 10L))
        eventBus.publish(EntityPickedUpItem(matchId, redPlayer, mode.miniTntItemId, quantity = 5, atTick = 20L))
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, blueBaseRegion, atTick = 100L))

        val endings = eventBus.publishedOfType<MatchEnded>()
        assertThat(endings).hasSize(1)
        assertThat(endings.first().winningTeam).isEqualTo(redTeam)
    }

    @Test
    fun `delivery to own base does not score`() {
        repeat(mode.miniCountToComplete) { i ->
            eventBus.publish(
                EntityPickedUpItem(matchId, redPlayer, mode.miniTntItemId, quantity = 1, atTick = 10L + i),
            )
        }
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, redBaseRegion, atTick = 100L))

        assertThat(eventBus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `blue player can win by delivering to red base`() {
        repeat(mode.miniCountToComplete) { i ->
            eventBus.publish(
                EntityPickedUpItem(matchId, bluePlayer, mode.miniTntItemId, quantity = 1, atTick = 10L + i),
            )
        }
        eventBus.publish(EntityEnteredRegion(matchId, bluePlayer, redBaseRegion, atTick = 100L))

        val endings = eventBus.publishedOfType<MatchEnded>()
        assertThat(endings).hasSize(1)
        assertThat(endings.first().winningTeam).isEqualTo(blueTeam)
    }

    private fun twoTeamMatch(): Match = Match(
        id = matchId,
        phase = MatchPhase.ACTIVE,
        teams = listOf(
            Team(
                id = redTeam,
                displayNameKey = MessageKey("astera.match.team.red"),
                colorKey = MessageKey("astera.match.team.red.color"),
                members = setOf(redPlayer),
            ),
            Team(
                id = blueTeam,
                displayNameKey = MessageKey("astera.match.team.blue"),
                colorKey = MessageKey("astera.match.team.blue.color"),
                members = setOf(bluePlayer),
            ),
        ),
        score = Score(mapOf(redTeam to 0, blueTeam to 0)),
        stageId = StageId("test-arena"),
        startedAtTick = 0L,
    )
}
