package com.astarworks.astera.core.application.eventbus.listeners.portal

import com.astarworks.astera.core.domain.event.PlayerInteractedBlock
import com.astarworks.astera.core.domain.event.PlayerMoved
import com.astarworks.astera.core.domain.event.PortalActivated
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.portal.Portal
import com.astarworks.astera.core.domain.portal.PortalActivationRule
import com.astarworks.astera.core.domain.portal.PortalId
import com.astarworks.astera.core.domain.portal.PortalShape
import com.astarworks.astera.core.domain.world.BlockInteractAction
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakePortalGateway
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

internal class PortalActivationListenersTest {

    private val world = WorldId("hub")
    private val targetWorld = WorldId("arena")
    private val playerId = PlayerId(UUID.randomUUID())
    private val source = Vec3(0.0, 64.0, 0.0)
    private val target = Vec3(100.0, 64.0, 100.0)

    private fun walkOnPortal() = Portal(
        id = PortalId("hub-arena"),
        sourceWorld = world,
        sourcePosition = source,
        sourceShape = PortalShape.Block(source),
        targetWorld = targetWorld,
        targetPosition = target,
        activationRule = PortalActivationRule.WalkOn,
    )

    private fun rightClickPortal() = Portal(
        id = PortalId("hub-arena-rc"),
        sourceWorld = world,
        sourcePosition = source,
        sourceShape = PortalShape.Block(source),
        targetWorld = targetWorld,
        targetPosition = target,
        activationRule = PortalActivationRule.RightClick,
    )

    private fun moved(to: Vec3 = source): PlayerMoved = PlayerMoved(
        playerId = playerId,
        from = Vec3(10.0, 64.0, 10.0),
        to = to,
        world = world,
        atTick = 1L,
    )

    private fun rightClick(pos: Vec3 = source): PlayerInteractedBlock = PlayerInteractedBlock(
        playerId = playerId,
        world = world,
        position = pos,
        blockId = "x",
        action = BlockInteractAction.RightClick,
        atTick = 1L,
    )

    @Test
    fun `walk-on listener teleports and publishes PortalActivated`() {
        val portalGw = FakePortalGateway().also { it.register(walkOnPortal()) }
        val worldGw = FakeWorldGateway()
        val eventBus = FakeDomainEventBus()
        val listener = ActivatePortalOnPlayerMovedListener(portalGw, worldGw, eventBus)

        listener.handle(moved())

        assertThat(worldGw.teleports).hasSize(1)
        assertThat(worldGw.teleports.single().at).isEqualTo(target)
        assertThat(worldGw.teleports.single().world).isEqualTo(targetWorld)
        assertThat(eventBus.publishedOfType<PortalActivated>()).hasSize(1)
        assertThat(eventBus.publishedOfType<PortalActivated>().single().portalId).isEqualTo(PortalId("hub-arena"))
    }

    @Test
    fun `walk-on listener ignores right-click-only portals`() {
        val portalGw = FakePortalGateway().also { it.register(rightClickPortal()) }
        val worldGw = FakeWorldGateway()
        val eventBus = FakeDomainEventBus()
        val listener = ActivatePortalOnPlayerMovedListener(portalGw, worldGw, eventBus)

        listener.handle(moved())

        assertThat(worldGw.teleports).isEmpty()
        assertThat(eventBus.publishedOfType<PortalActivated>()).isEmpty()
    }

    @Test
    fun `walk-on listener guards against the loop-back to target`() {
        val portalGw = FakePortalGateway().also {
            it.register(
                walkOnPortal().copy(targetWorld = world, targetPosition = source),
            )
        }
        val worldGw = FakeWorldGateway()
        val eventBus = FakeDomainEventBus()
        val listener = ActivatePortalOnPlayerMovedListener(portalGw, worldGw, eventBus)

        listener.handle(moved())

        assertThat(worldGw.teleports).isEmpty()
        assertThat(eventBus.publishedOfType<PortalActivated>()).isEmpty()
    }

    @Test
    fun `walk-on listener no-ops when teleport fails`() {
        val portalGw = FakePortalGateway().also { it.register(walkOnPortal()) }
        // Custom one-off IWorldGateway whose teleport returns false.
        val teleportCalls = mutableListOf<Triple<PlayerId, Vec3, WorldId?>>()
        val worldGw = object : com.astarworks.astera.core.application.port.outbound.IWorldGateway {
            override fun placeBlock(world: WorldId, at: Vec3, type: com.astarworks.astera.core.domain.world.BlockType) {}
            override fun removeBlock(world: WorldId, at: Vec3) {}
            override fun blockAt(world: WorldId, at: Vec3): com.astarworks.astera.core.domain.world.BlockType? = null
            override fun spawnParticle(world: WorldId, at: Vec3, particle: com.astarworks.astera.core.domain.effect.ParticleSpec) {}
            override fun playSound(world: WorldId, at: Vec3, sound: com.astarworks.astera.core.domain.effect.SoundSpec) {}
            override fun teleport(playerId: PlayerId, at: Vec3, world: WorldId?, yaw: Float?, pitch: Float?): Boolean {
                teleportCalls += Triple(playerId, at, world)
                return false
            }
            override fun spectatorSpawn(
                matchId: com.astarworks.astera.core.domain.match.MatchId,
                playerId: PlayerId,
            ): com.astarworks.astera.core.domain.Result<Unit, com.astarworks.astera.core.application.port.outbound.WorldGatewayError> =
                com.astarworks.astera.core.domain.Result.Success(Unit)
            override fun lobbySpawn(playerId: PlayerId):
                com.astarworks.astera.core.domain.Result<Unit, com.astarworks.astera.core.application.port.outbound.WorldGatewayError> =
                com.astarworks.astera.core.domain.Result.Success(Unit)
        }
        val eventBus = FakeDomainEventBus()
        val listener = ActivatePortalOnPlayerMovedListener(portalGw, worldGw, eventBus)

        listener.handle(moved())

        assertThat(teleportCalls).hasSize(1)
        // No PortalActivated when teleport returned false.
        assertThat(eventBus.publishedOfType<PortalActivated>()).isEmpty()
    }

    @Test
    fun `right-click listener teleports and publishes`() {
        val portalGw = FakePortalGateway().also { it.register(rightClickPortal()) }
        val worldGw = FakeWorldGateway()
        val eventBus = FakeDomainEventBus()
        val listener = ActivatePortalOnPlayerInteractedBlockListener(portalGw, worldGw, eventBus)

        listener.handle(rightClick())

        assertThat(worldGw.teleports).hasSize(1)
        assertThat(eventBus.publishedOfType<PortalActivated>()).hasSize(1)
    }

    @Test
    fun `right-click listener ignores walk-only portals`() {
        val portalGw = FakePortalGateway().also { it.register(walkOnPortal()) }
        val worldGw = FakeWorldGateway()
        val eventBus = FakeDomainEventBus()
        val listener = ActivatePortalOnPlayerInteractedBlockListener(portalGw, worldGw, eventBus)

        listener.handle(rightClick())

        assertThat(worldGw.teleports).isEmpty()
        assertThat(eventBus.publishedOfType<PortalActivated>()).isEmpty()
    }

    @Test
    fun `right-click listener ignores non-right-click actions`() {
        val portalGw = FakePortalGateway().also { it.register(rightClickPortal()) }
        val worldGw = FakeWorldGateway()
        val eventBus = FakeDomainEventBus()
        val listener = ActivatePortalOnPlayerInteractedBlockListener(portalGw, worldGw, eventBus)

        listener.handle(rightClick().copy(action = BlockInteractAction.LeftClick))
        listener.handle(rightClick().copy(action = BlockInteractAction.PhysicalContact))

        assertThat(worldGw.teleports).isEmpty()
    }
}
