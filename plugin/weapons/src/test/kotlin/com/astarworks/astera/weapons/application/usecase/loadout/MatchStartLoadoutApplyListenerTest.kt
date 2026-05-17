package com.astarworks.astera.weapons.application.usecase.loadout

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.Loadout
import com.astarworks.astera.core.domain.player.LoadoutSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.weapons.application.service.InMemoryLoadoutRepository
import com.astarworks.astera.weapons.testfixtures.FakeMcPlayer
import com.astarworks.astera.weapons.testfixtures.FakeMcServer
import com.astarworks.astera.weapons.testfixtures.FakeWeaponDelivery
import com.astarworks.astera.weapons.testfixtures.FakeWeaponRegistry
import com.astarworks.astera.weapons.testfixtures.weaponSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class MatchStartLoadoutApplyListenerTest {

    private val matchId = MatchId("match-1")
    private val teamId = TeamId("team-a")
    private val stageId = StageId("stage-1")
    private val playerId = PlayerId(UUID.randomUUID())
    private val player = FakeMcPlayer(id = playerId, name = "Alice")

    private val sword = weaponSpec(id = "example-sword", itemId = ItemId("example-sword"))

    private fun makeMatch(members: Set<PlayerId> = setOf(playerId)): Match = Match(
        id = matchId,
        phase = MatchPhase.ACTIVE,
        teams = listOf(
            Team(
                id = teamId,
                displayNameKey = MessageKey("astera.team.${teamId.value}.name"),
                colorKey = MessageKey("astera.team.${teamId.value}.color"),
                members = members,
            ),
        ),
        score = Score.EMPTY,
        stageId = stageId,
        startedAtTick = 0L,
    )

    private object EmptyItemRegistry : IItemRegistry<IMcItemTemplate> {
        override fun resolveOrNull(id: ItemId): IMcItemTemplate? = null
        override fun list(): Collection<IMcItemTemplate> = emptyList()
    }

    /**
     * Inline IMatchRepository fake. Weapons cannot import plugin/match
     * (Konsist `weapons must not import combat or match or stats or economy
     * or persistence`); use this local impl instead of plugin/match's
     * InMemoryMatchRepository.
     */
    private class InMemoryMatchRepository : IMatchRepository {
        private val store = mutableMapOf<MatchId, Match>()
        override fun findById(id: MatchId): Match? = store[id]
        override fun all(): Collection<Match> = store.values.toList()
        override fun save(entity: Match) { store[entity.id] = entity }
        override fun delete(id: MatchId) { store.remove(id) }
        override fun findActiveMatches(): Collection<Match> = store.values.filter { it.phase != MatchPhase.ENDED }
        override fun findMatchOf(playerId: PlayerId): Match? = store.values.firstOrNull { match -> match.teams.any { playerId in it.members } }
        override fun findInPhase(phase: MatchPhase): Collection<Match> = store.values.filter { it.phase == phase }
    }

    @Test
    fun `delivers MAIN slot weapon to every participant`() {
        val matches: IMatchRepository = InMemoryMatchRepository().apply { save(makeMatch()) }
        val loadouts = InMemoryLoadoutRepository().apply {
            save(playerId, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))
        }
        val weapons = FakeWeaponRegistry().apply { register(sword) }
        val server = FakeMcServer().apply { register(player) }
        val delivery = FakeWeaponDelivery()

        val listener = MatchStartLoadoutApplyListener(
            fetch = LoadoutFetchUseCase(loadouts),
            matchRepository = matches,
            server = server,
            weaponRegistry = weapons,
            delivery = delivery,
            itemRegistry = EmptyItemRegistry,
        )

        listener.handle(MatchStarted(matchId = matchId, stageId = stageId, atTick = 0L))

        assertThat(delivery.gave).hasSize(1)
        assertThat(delivery.gave.single().first.id).isEqualTo(playerId)
        assertThat(delivery.gave.single().second.id).isEqualTo(sword.id)
    }

    @Test
    fun `players without a MAIN slot are skipped`() {
        val matches: IMatchRepository = InMemoryMatchRepository().apply { save(makeMatch()) }
        val loadouts = InMemoryLoadoutRepository() // empty
        val weapons = FakeWeaponRegistry().apply { register(sword) }
        val server = FakeMcServer().apply { register(player) }
        val delivery = FakeWeaponDelivery()

        val listener = MatchStartLoadoutApplyListener(
            fetch = LoadoutFetchUseCase(loadouts),
            matchRepository = matches,
            server = server,
            weaponRegistry = weapons,
            delivery = delivery,
            itemRegistry = EmptyItemRegistry,
        )

        listener.handle(MatchStarted(matchId = matchId, stageId = stageId, atTick = 0L))

        assertThat(delivery.gave).isEmpty()
    }

    @Test
    fun `offline participants are skipped`() {
        val matches: IMatchRepository = InMemoryMatchRepository().apply { save(makeMatch()) }
        val loadouts = InMemoryLoadoutRepository().apply {
            save(playerId, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))
        }
        val weapons = FakeWeaponRegistry().apply { register(sword) }
        val server = FakeMcServer() // player is offline / not registered
        val delivery = FakeWeaponDelivery()

        val listener = MatchStartLoadoutApplyListener(
            fetch = LoadoutFetchUseCase(loadouts),
            matchRepository = matches,
            server = server,
            weaponRegistry = weapons,
            delivery = delivery,
            itemRegistry = EmptyItemRegistry,
        )

        listener.handle(MatchStarted(matchId = matchId, stageId = stageId, atTick = 0L))

        assertThat(delivery.gave).isEmpty()
    }

    @Test
    fun `unknown match is a no-op`() {
        val matches: IMatchRepository = InMemoryMatchRepository() // empty
        val loadouts = InMemoryLoadoutRepository().apply {
            save(playerId, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))
        }
        val weapons = FakeWeaponRegistry().apply { register(sword) }
        val server = FakeMcServer().apply { register(player) }
        val delivery = FakeWeaponDelivery()

        val listener = MatchStartLoadoutApplyListener(
            fetch = LoadoutFetchUseCase(loadouts),
            matchRepository = matches,
            server = server,
            weaponRegistry = weapons,
            delivery = delivery,
            itemRegistry = EmptyItemRegistry,
        )

        listener.handle(MatchStarted(matchId = matchId, stageId = stageId, atTick = 0L))

        assertThat(delivery.gave).isEmpty()
    }

    @Test
    fun `weapon not in registry for MAIN itemId is skipped`() {
        val matches: IMatchRepository = InMemoryMatchRepository().apply { save(makeMatch()) }
        val loadouts = InMemoryLoadoutRepository().apply {
            // MAIN refers to an itemId that has no matching weapon in the
            // registry — adapter should skip rather than crash.
            save(playerId, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("missing-weapon"))))
        }
        val weapons = FakeWeaponRegistry().apply { register(sword) }
        val server = FakeMcServer().apply { register(player) }
        val delivery = FakeWeaponDelivery()

        val listener = MatchStartLoadoutApplyListener(
            fetch = LoadoutFetchUseCase(loadouts),
            matchRepository = matches,
            server = server,
            weaponRegistry = weapons,
            delivery = delivery,
            itemRegistry = EmptyItemRegistry,
        )

        listener.handle(MatchStarted(matchId = matchId, stageId = stageId, atTick = 0L))

        assertThat(delivery.gave).isEmpty()
    }
}
