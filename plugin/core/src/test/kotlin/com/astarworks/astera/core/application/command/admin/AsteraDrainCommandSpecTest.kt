package com.astarworks.astera.core.application.command.admin

import com.astarworks.astera.core.application.command.CommandActor
import com.astarworks.astera.core.application.command.CommandAuthorizer
import com.astarworks.astera.core.application.command.CommandContext
import com.astarworks.astera.core.application.command.CommandInvoker
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.port.outbound.IServerTransferGateway
import com.astarworks.astera.core.application.port.outbound.admin.AdminOnlinePlayer
import com.astarworks.astera.core.application.service.InMemoryPermissionService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.lifecycle.ServerDrainStarted
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.TransferError
import com.astarworks.astera.core.domain.proxy.TransferReason
import com.astarworks.astera.core.domain.proxy.TransferWhen
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AsteraDrainCommandSpecTest {

    private class RecordingTransfers : IServerTransferGateway {
        val moves = mutableListOf<Triple<PlayerId, ServerName, TransferReason>>()
        override fun transfer(playerId: PlayerId, targetServer: ServerName, transferReason: TransferReason): Result<Unit, TransferError> {
            moves += Triple(playerId, targetServer, transferReason)
            return Result.success(Unit)
        }
        override fun queueTransfer(
            playerId: PlayerId,
            targetServer: ServerName,
            when_: TransferWhen,
            transferReason: TransferReason,
        ): Result<Unit, TransferError> = Result.success(Unit)
    }

    private val alice = AdminOnlinePlayer(PlayerId(UUID.randomUUID()), "alice")
    private val bob = AdminOnlinePlayer(PlayerId(UUID.randomUUID()), "bob")
    private val bus = FakeDomainEventBus()
    private val scheduler = FakeScheduler()
    private val transfers = RecordingTransfers()

    private fun run(args: Map<String, Any>, gateway: IServerTransferGateway? = transfers): CommandResult {
        val spec = buildAsteraDrainCommandSpec(
            eventBus = bus,
            scheduler = scheduler,
            matches = { null },
            transfers = { gateway },
            onlinePlayers = { listOf(alice, bob) },
        )
        val invoker = CommandInvoker(CommandAuthorizer { InMemoryPermissionService() }, scheduler)
        return runBlocking { invoker.invoke(spec, CommandActor(null, isOperator = true), args) { _, _ -> } }
    }

    @Test
    fun `abort mode announces the drain then moves everyone after the settle delay`() {
        assertThat(run(mapOf("target" to "game"))).isEqualTo(CommandResult.Success)

        assertThat(bus.published).containsExactly(
            bus.published.filterIsInstance<ServerDrainStarted>().single(),
        )
        assertThat(bus.published.filterIsInstance<ServerDrainStarted>().single().abortMatches).isTrue()
        assertThat(transfers.moves).isEmpty() // waits for the match end to settle

        scheduler.advanceTicks(3L * 20L)

        assertThat(transfers.moves.map { it.first }).containsExactly(alice.id, bob.id)
        assertThat(transfers.moves.map { it.second }.distinct()).containsExactly(ServerName("game"))
        assertThat(transfers.moves.map { it.third }.distinct()).containsExactly(TransferReason.AdminMove)
    }

    @Test
    fun `keep mode moves immediately and does not ask for matches to be aborted`() {
        assertThat(run(mapOf("target" to "lobby", "mode" to "keep"))).isEqualTo(CommandResult.Success)

        assertThat(bus.published.filterIsInstance<ServerDrainStarted>().single().abortMatches).isFalse()
        assertThat(transfers.moves).hasSize(2)
    }

    @Test
    fun `fails cleanly when cross-server transfer is not wired`() {
        assertThat(run(mapOf("target" to "game"), gateway = null)).isInstanceOf(CommandResult.Failure::class.java)
        assertThat(bus.published).isEmpty()
    }
}
