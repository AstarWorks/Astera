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
import com.astarworks.astera.match.domain.mode.GameModeId
import com.astarworks.astera.match.domain.mode.StealJewelry
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class StealJewelryListenerTest {

    private lateinit var bus: FakeDomainEventBus
    private lateinit var matches: InMemoryMatchRepository
    private lateinit var listener: StealJewelryListener

    private val matchId = MatchId("match-steal-1")
    private val redId = TeamId("red")
    private val blueId = TeamId("blue")

    private val redPlayer = PlayerId(UUID.randomUUID())
    private val bluePlayer = PlayerId(UUID.randomUUID())

    private val mode = StealJewelry(
        id = GameModeId("steal-jewelry"),
        displayNameKey = MessageKey("astera.mode.steal-jewelry.name"),
        descriptionKey = MessageKey("astera.mode.steal-jewelry.desc"),
        gemItemId = "astera:gem",
        gemsPerTeam = 3,
        depositRegionIds = mapOf(
            redId to "gem_deposit_red",
            blueId to "gem_deposit_blue",
        ),
    )

    @BeforeEach
    fun setUp() {
        bus = FakeDomainEventBus()
        matches = InMemoryMatchRepository()
        matches.save(buildMatch())
        listener = StealJewelryListener(bus, mode, matches)
        listener.subscribe()
    }

    @Test
    fun `pickup alone does not end the match`() {
        bus.publish(pickup(redPlayer, quantity = 3))

        assertThat(bus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `entering deposit region without carrying does nothing`() {
        bus.publish(enteredRegion(redPlayer, "gem_deposit_red"))

        assertThat(bus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `entering wrong region does not deposit`() {
        bus.publish(pickup(redPlayer, quantity = 3))
        // Red player walks into Blue's deposit region — should not bank.
        bus.publish(enteredRegion(redPlayer, "gem_deposit_blue"))

        assertThat(bus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `pickup of non-gem item is ignored`() {
        bus.publish(
            EntityPickedUpItem(
                matchId = matchId,
                playerId = redPlayer,
                itemId = "astera:tnt_carry",
                quantity = 99,
                atTick = 1L,
            ),
        )
        bus.publish(enteredRegion(redPlayer, "gem_deposit_red"))

        assertThat(bus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `stealing all enemy gems publishes MatchEnded with ObjectiveCompleted`() {
        // 2 teams, gemsPerTeam = 3 → red wins by depositing 3 gems (steal all
        // of blue's). One trip, three gems.
        bus.publish(pickup(redPlayer, quantity = 3))
        bus.publish(enteredRegion(redPlayer, "gem_deposit_red", atTick = 42L))

        val ended = bus.publishedOfType<MatchEnded>()
        assertThat(ended).hasSize(1)
        assertThat(ended.single().matchId).isEqualTo(matchId)
        assertThat(ended.single().winningTeam).isEqualTo(redId)
        assertThat(ended.single().reason).isEqualTo(EndReason.ObjectiveCompleted)
        assertThat(ended.single().atTick).isEqualTo(42L)
    }

    @Test
    fun `multiple smaller deposits accumulate to the win threshold`() {
        bus.publish(pickup(redPlayer, quantity = 1))
        bus.publish(enteredRegion(redPlayer, "gem_deposit_red", atTick = 10L))
        assertThat(bus.publishedOfType<MatchEnded>()).isEmpty()

        bus.publish(pickup(redPlayer, quantity = 2))
        bus.publish(enteredRegion(redPlayer, "gem_deposit_red", atTick = 20L))

        val ended = bus.publishedOfType<MatchEnded>()
        assertThat(ended).hasSize(1)
        assertThat(ended.single().winningTeam).isEqualTo(redId)
    }

    @Test
    fun `subsequent events after MatchEnded do not republish`() {
        bus.publish(pickup(redPlayer, quantity = 3))
        bus.publish(enteredRegion(redPlayer, "gem_deposit_red"))
        assertThat(bus.publishedOfType<MatchEnded>()).hasSize(1)

        // Blue tries to score after the match has already ended.
        bus.publish(pickup(bluePlayer, quantity = 3))
        bus.publish(enteredRegion(bluePlayer, "gem_deposit_blue"))

        assertThat(bus.publishedOfType<MatchEnded>()).hasSize(1)
    }

    @Test
    fun `region entry by a non-participant is ignored`() {
        val stranger = PlayerId(UUID.randomUUID())
        bus.publish(pickup(stranger, quantity = 3))
        bus.publish(enteredRegion(stranger, "gem_deposit_red"))

        assertThat(bus.publishedOfType<MatchEnded>()).isEmpty()
    }

    private fun pickup(player: PlayerId, quantity: Int, atTick: Long = 1L) =
        EntityPickedUpItem(
            matchId = matchId,
            playerId = player,
            itemId = mode.gemItemId,
            quantity = quantity,
            atTick = atTick,
        )

    private fun enteredRegion(player: PlayerId, regionId: String, atTick: Long = 1L) =
        EntityEnteredRegion(
            matchId = matchId,
            playerId = player,
            regionId = regionId,
            atTick = atTick,
        )

    private fun buildMatch(): Match =
        Match(
            id = matchId,
            phase = MatchPhase.ACTIVE,
            teams = listOf(
                Team(
                    id = redId,
                    displayNameKey = MessageKey("astera.match.team.red"),
                    colorKey = MessageKey("astera.match.team.red.color"),
                    members = setOf(redPlayer),
                ),
                Team(
                    id = blueId,
                    displayNameKey = MessageKey("astera.match.team.blue"),
                    colorKey = MessageKey("astera.match.team.blue.color"),
                    members = setOf(bluePlayer),
                ),
            ),
            score = Score(mapOf(redId to 0, blueId to 0)),
            stageId = StageId("test-arena"),
            startedAtTick = 0L,
        )
}
