package com.astarworks.astera.items.application.service.training

import com.astarworks.astera.core.application.port.outbound.lobby.LobbyTrainingRange
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.lobby.LobbyTrainingLayout
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.items.application.port.outbound.DeliveryResult
import com.astarworks.astera.items.application.port.outbound.ITrainingInventory
import com.astarworks.astera.items.application.port.outbound.ITrainingRackGateway
import com.astarworks.astera.items.application.port.outbound.ItemOrigin
import com.astarworks.astera.items.application.port.outbound.RackFrame
import com.astarworks.astera.items.testfixtures.FakeItemDelivery
import com.astarworks.astera.items.testfixtures.InMemoryItemRegistry
import com.astarworks.astera.items.testfixtures.itemSpec
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMcServer
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import com.astarworks.astera.testfixtures.FakePlayerPoseLookup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** F3-11: the lobby weapon rack hands out any weapon of content/items, lobby-only, to players in the range. */
class TrainingRackServiceTest {

    private val lobby = WorldId("lobby")
    private val plan = LobbyTrainingLayout.plan(0, 0, 64)
    private val range = LobbyTrainingRange(lobby, plan)

    private val pistol = itemSpec("pistol").copy(resourceType = ResourceType.Ammo)
    private val sword = itemSpec("knight-sword").copy(resourceType = ResourceType.Stamina)
    private val staff = itemSpec("fire-staff").copy(resourceType = ResourceType.Mana)
    private val helmet = itemSpec("iron-helm", equipSlot = com.astarworks.astera.core.domain.item.EquipSlot.HELMET)
    private val tile = itemSpec("gui-tile-queue")

    private val items = InMemoryItemRegistry().apply { listOf(pistol, sword, staff, helmet, tile).forEach(::register) }
    private val delivery = FakeItemDelivery()
    private val playerId = PlayerId(UUID.randomUUID())
    private val server = FakeMcServer().apply { register(FakeMcPlayer(id = playerId, name = "Alice")) }
    private val poses = FakePlayerPoseLookup().apply { place(playerId, plan.spawn, world = lobby) }
    private val players = FakePlayerGateway()
    private val messages = FakeMessageRenderer()

    private class Inventory : ITrainingInventory {
        val held = mutableSetOf<Pair<PlayerId, ItemId>>()
        override fun holds(playerId: PlayerId, itemId: ItemId) = (playerId to itemId) in held
        override fun strip(playerId: PlayerId): Int {
            val mine = held.filter { it.first == playerId }
            held.removeAll(mine.toSet())
            return mine.size
        }

        override fun holders(): Set<PlayerId> = held.map { it.first }.toSet()
    }

    private class Rack : ITrainingRackGateway {
        var frames: List<RackFrame> = emptyList()
        var ready = true
        override fun ensure(world: WorldId, frames: List<RackFrame>): Int? {
            if (!ready) return null
            this.frames = frames
            return frames.size
        }

        override fun removeAll() {
            frames = emptyList()
        }
    }

    private val inventory = Inventory()
    private val rack = Rack()

    private fun service(range: LobbyTrainingRange? = this.range) = TrainingRackService(
        range = { range },
        items = items,
        delivery = delivery,
        server = server,
        poses = { poses },
        inventory = inventory,
        gateway = rack,
        players = players,
        messages = messages,
        matches = { null },
    )

    @Test
    fun `the rack shows one frame per weapon of the content, in slot order, and hides gear and gui items`() {
        val svc = service()
        assertThat(svc.weapons().map { it.id.value }).containsExactly("pistol", "knight-sword", "fire-staff")
        assertThat(svc.ensureRendered()).isEqualTo(3)
        assertThat(rack.frames.map { it.itemId.value }).containsExactly("pistol", "knight-sword", "fire-staff")
        assertThat(rack.frames.map { it.slot.index }).containsExactly(0, 1, 2)
    }

    @Test
    fun `a rack that is not ready yet reports nothing and a backend without a range has no frames`() {
        rack.ready = false
        assertThat(service().ensureRendered()).isNull()
        assertThat(service(range = null).ensureRendered()).isNull()
        assertThat(service(range = null).frames()).isEmpty()
    }

    @Test
    fun `any weapon can be claimed by a non-op standing in the range, as a lobby-only stack`() {
        val result = service().claim(playerId, ItemId("pistol"))
        assertThat(result.isSuccess).isTrue()
        assertThat(delivery.gave).hasSize(1)
        assertThat(delivery.gave.single().spec).isEqualTo(pistol)
        assertThat(delivery.gave.single().origin).isEqualTo(ItemOrigin.TRAINING)
        assertThat(messages.calls.map { it.key.value }).contains("astera.lobby.training.rack.claimed")
    }

    @Test
    fun `claiming outside the range, an item that is not on the rack, or a weapon already held hands over nothing`() {
        poses.place(playerId, Vec3(0.5, 65.0, 6.5), world = lobby)
        assertThat(service().claim(playerId, ItemId("pistol")).errorOrNull()).isEqualTo(RackError.NotInRange)

        poses.place(playerId, plan.spawn, world = lobby)
        assertThat(service().claim(playerId, ItemId("iron-helm")).errorOrNull()).isEqualTo(RackError.NotOnRack(ItemId("iron-helm")))
        assertThat(service().claim(playerId, ItemId("nope")).errorOrNull()).isEqualTo(RackError.NotOnRack(ItemId("nope")))

        inventory.held += playerId to ItemId("pistol")
        assertThat(service().claim(playerId, ItemId("pistol")).errorOrNull()).isEqualTo(RackError.AlreadyHeld(ItemId("pistol")))
        assertThat(delivery.gave).isEmpty()
    }

    @Test
    fun `a full inventory is reported`() {
        delivery.nextResult = DeliveryResult.InventoryFull("Alice")
        val result = service().claim(playerId, ItemId("fire-staff"))
        assertThat((result as Result.Failure).error).isEqualTo(RackError.InventoryFull)
        assertThat(messages.calls.map { it.key.value }).contains("astera.lobby.training.rack.full")
    }

    @Test
    fun `an unknown or offline player gets nothing`() {
        val ghost = PlayerId(UUID.randomUUID())
        poses.place(ghost, plan.spawn, world = lobby)
        assertThat(service().claim(ghost, ItemId("pistol")).errorOrNull()).isEqualTo(RackError.PlayerOffline)
    }

    @Test
    fun `stripping removes the lobby-only stacks`() {
        inventory.held += playerId to ItemId("pistol")
        inventory.held += playerId to ItemId("fire-staff")
        assertThat(service().strip(playerId)).isEqualTo(2)
        assertThat(inventory.held).isEmpty()
    }

    @Test
    fun `match participants lose their training weapons when the match starts`() {
        val matchId = MatchId("match-1")
        val other = PlayerId(UUID.randomUUID())
        inventory.held += playerId to ItemId("pistol")
        inventory.held += other to ItemId("pistol")
        val match = com.astarworks.astera.core.domain.match.Match(
            id = matchId,
            phase = com.astarworks.astera.core.domain.match.MatchPhase.COUNTDOWN,
            teams = listOf(
                com.astarworks.astera.core.domain.match.Team(
                    id = com.astarworks.astera.core.domain.match.TeamId("red"),
                    displayNameKey = com.astarworks.astera.core.domain.i18n.MessageKey("astera.team.red"),
                    colorKey = com.astarworks.astera.core.domain.i18n.MessageKey("astera.team.red.color"),
                    members = setOf(playerId),
                ),
            ),
            score = com.astarworks.astera.core.domain.match.Score.EMPTY,
            stageId = StageId("siege-flat"),
            startedAtTick = 0,
        )
        val repo = object : com.astarworks.astera.core.application.port.outbound.IMatchRepository {
            override fun findActiveMatches() = listOf(match)
            override fun findMatchOf(playerId: PlayerId) = match.takeIf { it.hasParticipant(playerId) }
            override fun findInPhase(phase: com.astarworks.astera.core.domain.match.MatchPhase) = emptyList<com.astarworks.astera.core.domain.match.Match>()
            override fun findById(id: MatchId) = match.takeIf { it.id == id }
            override fun all() = listOf(match)
            override fun save(entity: com.astarworks.astera.core.domain.match.Match) = Unit
            override fun delete(id: MatchId) = Unit
        }
        val svc = TrainingRackService({ range }, items, delivery, server, { poses }, inventory, rack, players, messages, { repo })

        svc.onMatchStarted(MatchStarted(matchId = matchId, stageId = StageId("siege-flat"), atTick = 0))

        assertThat(inventory.held).containsExactly(other to ItemId("pistol")) // only the participant was stripped
    }

    @Test
    fun `the sweep strips players that are in a match, even if they joined after the match started`() {
        val matchId = MatchId("match-2")
        val inMatch = playerId
        val bystander = PlayerId(UUID.randomUUID())
        inventory.held += inMatch to ItemId("pistol")
        inventory.held += bystander to ItemId("pistol")
        val match = com.astarworks.astera.core.domain.match.Match(
            id = matchId,
            phase = com.astarworks.astera.core.domain.match.MatchPhase.COUNTDOWN,
            teams = listOf(
                com.astarworks.astera.core.domain.match.Team(
                    id = com.astarworks.astera.core.domain.match.TeamId("red"),
                    displayNameKey = com.astarworks.astera.core.domain.i18n.MessageKey("astera.team.red"),
                    colorKey = com.astarworks.astera.core.domain.i18n.MessageKey("astera.team.red.color"),
                    members = setOf(inMatch),
                ),
            ),
            score = com.astarworks.astera.core.domain.match.Score.EMPTY,
            stageId = StageId("siege-flat"),
            startedAtTick = 0,
        )
        val repo = object : com.astarworks.astera.core.application.port.outbound.IMatchRepository {
            override fun findActiveMatches() = listOf(match)
            override fun findMatchOf(playerId: PlayerId) = match.takeIf { it.hasParticipant(playerId) }
            override fun findInPhase(phase: com.astarworks.astera.core.domain.match.MatchPhase) = emptyList<com.astarworks.astera.core.domain.match.Match>()
            override fun findById(id: MatchId) = match.takeIf { it.id == id }
            override fun all() = listOf(match)
            override fun save(entity: com.astarworks.astera.core.domain.match.Match) = Unit
            override fun delete(id: MatchId) = Unit
        }
        val svc = TrainingRackService({ range }, items, delivery, server, { poses }, inventory, rack, players, messages, { repo })

        assertThat(svc.sweep()).isEqualTo(1)
        assertThat(inventory.held).containsExactly(bystander to ItemId("pistol"))
        svc.onMatchFormed(com.astarworks.astera.core.domain.event.MatchFormed(listOf(bystander), listOf(setOf(bystander)), "norma"))
        assertThat(inventory.held).isEmpty()
    }
}
