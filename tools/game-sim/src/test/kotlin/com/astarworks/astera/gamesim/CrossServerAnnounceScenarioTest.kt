package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.event.LootPickedUp
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.Rarity

import com.astarworks.astera.core.application.command.CommandContext
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.announceCommands
import com.astarworks.astera.core.application.port.outbound.presentation.IAnnouncer
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentationTable
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentationVisuals
import com.astarworks.astera.core.application.service.presentation.LoggingPresentationVisuals
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.presentation.AnnounceError
import com.astarworks.astera.core.domain.presentation.AnnounceTarget
import com.astarworks.astera.core.domain.proxy.ServerName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Real Core wiring, shipped moments/cues and two backend messengers (including the sender's echo). */
class CrossServerAnnounceScenarioTest {
    @Test
    fun `all and server targets play table presentation once and invalid moments never publish`() = kotlinx.coroutines.runBlocking<Unit> {
        val aName = ServerName("lobby")
        val bName = ServerName("game")
        val network = SimProxyNetwork()
        val a = GameSim.boot("announce-a", serverName = aName, proxy = network.node(aName))
        val b = GameSim.boot("announce-b", serverName = bName, proxy = network.node(bName))
        network.register(aName, a)
        network.register(bName, b)
        try {
            val alice = a.spawnPlayer("Alice", clientLocale = "ja")
            val bob = b.spawnPlayer("Bob", clientLocale = "en")
            val carol = b.spawnPlayer("Carol", clientLocale = "ja")
            val av = a.services.getOrThrow<IPresentationVisuals>() as LoggingPresentationVisuals
            val bv = b.services.getOrThrow<IPresentationVisuals>() as LoggingPresentationVisuals
            val announcer = a.services.getOrThrow<IAnnouncer>()
            network.step(2)
            av.calls.clear(); bv.calls.clear()
            a.playerSounds.calls.clear(); b.playerSounds.calls.clear()
            val args = mapOf("name" to "Alice", "item" to "X")
            assertThat(announcer.announce("rare-drop", AnnounceTarget.All, args)).isEqualTo(Result.Success(Unit))
            network.step(3)
            assertThat(av.calls.filterIsInstance<LoggingPresentationVisuals.Call.Title>().map { it.player }).containsExactly(alice.id)
            assertThat(bv.calls.filterIsInstance<LoggingPresentationVisuals.Call.Title>().map { it.player }).containsExactlyInAnyOrder(bob.id, carol.id)
            assertThat(av.calls.filterIsInstance<LoggingPresentationVisuals.Call.Chat>().single().args).isEqualTo(args)
            assertThat(bv.calls.filterIsInstance<LoggingPresentationVisuals.Call.Chat>().map { it.player }).containsExactlyInAnyOrder(bob.id, carol.id)
            assertThat(bv.calls.filterIsInstance<LoggingPresentationVisuals.Call.ActionBar>()).hasSize(2)
            assertThat(bv.calls.filterIsInstance<LoggingPresentationVisuals.Call.Particles>().map { it.anchor }).containsExactlyInAnyOrder(bob.id, carol.id)
            assertThat(a.playerSounds.calls.any { it.playerId == alice.id && it.sound.key == "astera:norma/level-max" }).isTrue()
            assertThat(b.playerSounds.calls.filter { it.sound.key == "astera:norma/level-max" }.map { it.playerId }).containsExactlyInAnyOrder(bob.id, carol.id)

            av.calls.clear(); bv.calls.clear()
            val aSoundCount = a.playerSounds.calls.size
            assertThat(announcer.announce("rare-drop", AnnounceTarget.Server(bName), args)).isEqualTo(Result.Success(Unit))
            network.step(3)
            assertThat(av.calls).isEmpty()
            assertThat(a.playerSounds.calls).hasSize(aSoundCount)
            assertThat(bv.calls.filterIsInstance<LoggingPresentationVisuals.Call.Chat>()).hasSize(2)

            val published = network.sent.size
            assertThat(announcer.announce("missing", AnnounceTarget.All)).isEqualTo(Result.Failure(AnnounceError.UnknownMoment("missing")))
            assertThat(network.sent).hasSize(published)
            assertThat(announcer.announce("server-boost", AnnounceTarget.Server(aName), mapOf("name" to "Alice", "multiplier" to "2", "minutes" to "10")))
                .isEqualTo(Result.Success(Unit))
            network.step(2)
            assertThat(network.sent).hasSize(published)
            assertThat(av.calls.filterIsInstance<LoggingPresentationVisuals.Call.Chat>()).hasSize(1)
            assertThat(announcer.announce("rare-drop", AnnounceTarget.Server(ServerName("absent")), args))
                .isInstanceOf(Result.Failure::class.java)

            // Existing successful pickup event -> Core wiring -> cross-server announcement.
            // Promote a shipped spec only in this sim; production content is untouched.
            val items = a.services.getOrThrow<IItemRegistry>()
                as ItemContents
            val legendary = items.all().first().copy(
                id = ItemId("announce-legendary"),
                rarity = Rarity.LEGENDARY,
            )
            items.register(legendary.id, legendary)
            av.calls.clear(); bv.calls.clear()
            a.eventBus.publish(LootPickedUp(
                alice.id, legendary.id, 1, alice.world, alice.position, a.tick,
            ))
            network.step(2)
            assertThat(bv.calls.filterIsInstance<LoggingPresentationVisuals.Call.Chat>().map { it.args })
                .containsExactly(mapOf("name" to "Alice", "item" to legendary.id.value), mapOf("name" to "Alice", "item" to legendary.id.value))

            val command = announceCommands(announcer, a.services.getOrThrow<IPresentationTable>()) { setOf(aName, bName) }
            assertThat(command.permission?.id).isEqualTo("astera.announce")
            assertThat(command.arguments[0].completer!!(CommandSpec.PartialInput("rare"))).containsExactly("rare-drop")
            assertThat(command.arguments[1].completer!!(CommandSpec.PartialInput(""))).containsExactly("all", "game", "lobby")
            val context = CommandContext(null, mapOf("moment" to "rare-drop", "target" to "all", "args" to "name=Alice item=X"), a.scheduler)
            assertThat(command.executor!!(context)).isEqualTo(CommandResult.Success)
            assertThat(command.executor!!(context.copy(args = context.args + ("args" to "name=A name=B")))).isInstanceOf(CommandResult.Failure::class.java)
        } finally { a.shutdown(); b.shutdown() }
    }
}
