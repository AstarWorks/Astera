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
import com.astarworks.astera.match.domain.event.MatchEnded
import com.astarworks.astera.match.domain.mode.CarryTNT
import com.astarworks.astera.match.domain.mode.GameModeId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5c — verifies that [CarryTNTListener] consumes
 * [EntityPickedUpItem] + [EntityEnteredRegion] events and publishes
 * [MatchEnded] once a team reaches [CarryTNT.deliveriesToWin].
 */
class CarryTNTListenerTest {

    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var matches: InMemoryMatchRepository
    private lateinit var mode: CarryTNT
    private lateinit var listener: CarryTNTListener

    private val matchId = MatchId("match-carrytnt")
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
        mode = CarryTNT(
            id = GameModeId("carry-tnt"),
            displayNameKey = MessageKey("astera.match.mode.carry-tnt.name"),
            descriptionKey = MessageKey("astera.match.mode.carry-tnt.description"),
            deliveryRegionIds = mapOf(
                redTeam to redBaseRegion,
                blueTeam to blueBaseRegion,
            ),
        )
        listener = CarryTNTListener(eventBus, mode, matches)
        listener.subscribe()
    }

    @Test
    fun `red player carrying TNT delivers 3 times to blue base and wins`() {
        eventBus.publish(EntityPickedUpItem(matchId, redPlayer, mode.tntItemId, quantity = 1, atTick = 10L))
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, blueBaseRegion, atTick = 20L))
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, blueBaseRegion, atTick = 30L))
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, blueBaseRegion, atTick = 40L))

        val endings = eventBus.publishedOfType<MatchEnded>()
        assertThat(endings).hasSize(1)
        assertThat(endings.first().winningTeam).isEqualTo(redTeam)
        assertThat(endings.first().matchId).isEqualTo(matchId)
        assertThat(endings.first().reason).isEqualTo(EndReason.ObjectiveCompleted)
        assertThat(endings.first().atTick).isEqualTo(40L)
    }

    @Test
    fun `entering enemy region without carrying TNT does not score`() {
        // No pickup event published — red player has not picked up TNT.
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, blueBaseRegion, atTick = 10L))
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, blueBaseRegion, atTick = 20L))
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, blueBaseRegion, atTick = 30L))

        assertThat(eventBus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `carrier entering own delivery region does not score`() {
        // Red picks up TNT but only enters its own base region — should not count.
        eventBus.publish(EntityPickedUpItem(matchId, redPlayer, mode.tntItemId, quantity = 1, atTick = 5L))
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, redBaseRegion, atTick = 10L))
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, redBaseRegion, atTick = 20L))
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, redBaseRegion, atTick = 30L))

        assertThat(eventBus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `picking up unrelated item does not make the player a carrier`() {
        eventBus.publish(EntityPickedUpItem(matchId, redPlayer, "astera:apple", quantity = 1, atTick = 5L))
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, blueBaseRegion, atTick = 10L))
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, blueBaseRegion, atTick = 20L))
        eventBus.publish(EntityEnteredRegion(matchId, redPlayer, blueBaseRegion, atTick = 30L))

        assertThat(eventBus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `only one MatchEnded published even after additional deliveries`() {
        eventBus.publish(EntityPickedUpItem(matchId, redPlayer, mode.tntItemId, quantity = 1, atTick = 10L))
        repeat(5) { i ->
            eventBus.publish(EntityEnteredRegion(matchId, redPlayer, blueBaseRegion, atTick = 20L + i))
        }

        val endings = eventBus.publishedOfType<MatchEnded>()
        assertThat(endings).hasSize(1)
        assertThat(endings.first().winningTeam).isEqualTo(redTeam)
    }

    @Test
    fun `blue player can also win by delivering to red base`() {
        eventBus.publish(EntityPickedUpItem(matchId, bluePlayer, mode.tntItemId, quantity = 1, atTick = 10L))
        eventBus.publish(EntityEnteredRegion(matchId, bluePlayer, redBaseRegion, atTick = 20L))
        eventBus.publish(EntityEnteredRegion(matchId, bluePlayer, redBaseRegion, atTick = 30L))
        eventBus.publish(EntityEnteredRegion(matchId, bluePlayer, redBaseRegion, atTick = 40L))

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
