package com.astarworks.astera.core.application.eventbus.listeners.block

import com.astarworks.astera.core.application.port.outbound.block.BlockInteractionAction
import com.astarworks.astera.core.application.service.block.InMemoryBlockInteractionRouter
import com.astarworks.astera.core.domain.event.PlayerInteractedBlock
import com.astarworks.astera.core.domain.event.SkillCastRequested
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.BlockInteractAction
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeBroadcaster
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeGuiAdapter
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMcServer
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * End-to-end test for [RouteBlockInteractionListener] (Wave 5block-embed,
 * ADR-0120).
 *
 * Exercises the dispatch matrix: every routable variant of
 * [BlockInteractionAction] hits the right sink when the router resolves it.
 */
internal class RouteBlockInteractionListenerTest {

    private val world = WorldId("world")
    private val pos = Vec3(10.0, 64.0, 20.0)
    private val playerId = PlayerId(UUID.randomUUID())

    @Suppress("UNUSED_PARAMETER")
    private fun newFixture(): Fixture {
        val router = InMemoryBlockInteractionRouter()
        val guiAdapter = FakeGuiAdapter()
        val worldGateway = FakeWorldGateway()
        val mcServer = FakeMcServer().also { it.register(FakeMcPlayer(id = playerId)) }
        val eventBus = FakeDomainEventBus()
        @Suppress("UNUSED_VARIABLE") val unused = FakeBroadcaster() // assure broadcaster dependency exists in test-fixtures
        val listener = RouteBlockInteractionListener(
            router = router,
            guiAdapter = guiAdapter,
            worldGateway = worldGateway,
            mcServer = mcServer,
            eventBus = eventBus,
        )
        return Fixture(router, guiAdapter, worldGateway, mcServer, eventBus, listener)
    }

    private data class Fixture(
        val router: InMemoryBlockInteractionRouter,
        val guiAdapter: FakeGuiAdapter,
        val worldGateway: FakeWorldGateway,
        val mcServer: FakeMcServer,
        val eventBus: FakeDomainEventBus,
        val listener: RouteBlockInteractionListener,
    )

    private fun event(action: BlockInteractAction): PlayerInteractedBlock =
        PlayerInteractedBlock(
            playerId = playerId,
            world = world,
            position = pos,
            blockId = "astera:test_block",
            action = action,
            atTick = 1L,
        )

    @Test
    fun `no binding is a no-op`() {
        val f = newFixture()
        val player = f.mcServer.findPlayer(playerId)!!

        f.listener.handle(event(BlockInteractAction.RightClick))

        assertThat(f.guiAdapter.history(player)).isEmpty()
        assertThat(f.worldGateway.teleports).isEmpty()
        assertThat(f.eventBus.published).isEmpty()
    }

    @Test
    fun `TriggerSkill publishes a SkillCastRequested event`() {
        val f = newFixture()
        f.router.bindRightClick(
            world,
            pos,
            BlockInteractionAction.TriggerSkill(SkillId("fireball")),
        )

        f.listener.handle(event(BlockInteractAction.RightClick))

        val cast = f.eventBus.publishedOfType<SkillCastRequested>()
        assertThat(cast).hasSize(1)
        assertThat(cast.single().actorId).isEqualTo(playerId)
        assertThat(cast.single().skillId).isEqualTo(SkillId("fireball"))
        assertThat(cast.single().origin).isEqualTo(pos)
        assertThat(cast.single().sourceItemId)
            .isEqualTo(RouteBlockInteractionListener.BLOCK_INTERACTION_SOURCE_ITEM)
    }

    @Test
    fun `TeleportTo invokes IWorldGateway`() {
        val f = newFixture()
        val target = Vec3(0.0, 80.0, 0.0)
        val targetWorld = WorldId("nether")
        f.router.bindLeftClick(
            world,
            pos,
            BlockInteractionAction.TeleportTo(targetWorld, target),
        )

        f.listener.handle(event(BlockInteractAction.LeftClick))

        assertThat(f.worldGateway.teleports).hasSize(1)
        assertThat(f.worldGateway.teleports.single().playerId).isEqualTo(playerId)
        assertThat(f.worldGateway.teleports.single().at).isEqualTo(target)
        assertThat(f.worldGateway.teleports.single().world).isEqualTo(targetWorld)
    }

    @Test
    fun `OpenGui opens the spec for the interacting player`() {
        val f = newFixture()
        val player = f.mcServer.findPlayer(playerId)!!
        val gui = GuiSpec.build(GuiId("shop-main"), MessageKey("astera.gui.shop"), GuiSize.CHEST_27) {}
        f.router.bindRightClick(
            world,
            pos,
            BlockInteractionAction.OpenGui(gui),
        )

        f.listener.handle(event(BlockInteractAction.RightClick))

        assertThat(f.guiAdapter.history(player)).hasSize(1)
        assertThat(f.guiAdapter.currentSpec(player)).isSameAs(gui)
    }

    @Test
    fun `OpenGui silently no-ops when the player is offline`() {
        val f = newFixture()
        val player = f.mcServer.findPlayer(playerId)!!
        val offlineId = PlayerId(UUID.randomUUID())
        val gui = GuiSpec.build(GuiId("x"), MessageKey("astera.gui.x"), GuiSize.CHEST_27) {}
        f.router.bindRightClick(
            world,
            pos,
            BlockInteractionAction.OpenGui(gui),
        )

        f.listener.handle(
            PlayerInteractedBlock(
                playerId = offlineId,
                world = world,
                position = pos,
                blockId = "x",
                action = BlockInteractAction.RightClick,
                atTick = 1,
            ),
        )

        assertThat(f.guiAdapter.history(player)).isEmpty()
    }

    @Test
    fun `RunCommand is currently a Wave 6 placeholder no-op`() {
        val f = newFixture()
        val player = f.mcServer.findPlayer(playerId)!!
        f.router.bindRightClick(
            world,
            pos,
            BlockInteractionAction.RunCommand(
                com.astarworks.astera.core.application.command.CommandSpec.Literal(
                    name = "astera",
                    executor = { com.astarworks.astera.core.application.command.CommandResult.Success },
                ),
            ),
        )

        f.listener.handle(event(BlockInteractAction.RightClick))

        // Wave 6 sink: nothing observable except a logged INFO.
        assertThat(f.guiAdapter.history(player)).isEmpty()
        assertThat(f.worldGateway.teleports).isEmpty()
        assertThat(f.eventBus.published).isEmpty()
    }

    @Test
    fun `Custom is currently a Wave 6 placeholder no-op`() {
        val f = newFixture()
        val player = f.mcServer.findPlayer(playerId)!!
        f.router.bindRightClick(
            world,
            pos,
            BlockInteractionAction.Custom("stage.beacon.activate"),
        )

        f.listener.handle(event(BlockInteractAction.RightClick))

        assertThat(f.guiAdapter.history(player)).isEmpty()
        assertThat(f.worldGateway.teleports).isEmpty()
        assertThat(f.eventBus.published).isEmpty()
    }

    @Test
    fun `PhysicalContact and OffHandUse never match the router`() {
        val f = newFixture()
        f.router.bindRightClick(
            world,
            pos,
            BlockInteractionAction.TriggerSkill(SkillId("a")),
        )

        f.listener.handle(event(BlockInteractAction.PhysicalContact))
        f.listener.handle(event(BlockInteractAction.OffHandUse))

        assertThat(f.eventBus.published).isEmpty()
    }
}
