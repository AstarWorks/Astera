package com.astarworks.astera.core.application.command.chat

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandContext
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.buildChatCommandSpec
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.application.usecase.chat.SendChatMessageUseCase
import com.astarworks.astera.core.domain.chat.ChatChannel
import com.astarworks.astera.core.domain.event.ChatMessageSent
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeMatchTeamLookup
import com.astarworks.astera.testfixtures.FakePlayerGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ChatCommandSpecTest {

    private val sender = PlayerId(UUID.randomUUID())
    private val target = PlayerId(UUID.randomUUID())

    private fun ctx(args: Map<String, Any>): CommandContext =
        CommandContext(sender = sender, args = args, scheduler = FakeScheduler())

    private fun executor(literal: CommandSpec.Literal, name: String): (CommandContext) -> CommandResult {
        val sub = literal.literals.single { it.name == name }
        val exec = sub.executor ?: error("no executor on $name")
        return { ctx -> runBlocking { exec(ctx) } }
    }

    @Test
    fun `say publishes a ChatMessageSent on Global channel`() {
        val bus = FakeDomainEventBus()
        val sendChat = SendChatMessageUseCase(eventBus = bus)
        val spec = buildChatCommandSpec(
            sendChat = sendChat,
            teamLookup = { null },
            partyRepository = InMemoryPartyRepository(),
            playerResolver = FakePlayerGateway(),
        )

        val outcome = executor(spec, "say")(ctx(mapOf("message" to "hello world")))
        assertThat(outcome).isEqualTo(CommandResult.Success)
        val msgs = bus.published.filterIsInstance<ChatMessageSent>()
        assertThat(msgs).hasSize(1)
        assertThat(msgs[0].channel).isEqualTo(ChatChannel.Global)
        assertThat(msgs[0].content).isEqualTo("hello world")
    }

    @Test
    fun `team fails when sender is not in any team`() {
        val bus = FakeDomainEventBus()
        val sendChat = SendChatMessageUseCase(eventBus = bus)
        val lookup = FakeMatchTeamLookup()
        val spec = buildChatCommandSpec(
            sendChat = sendChat,
            teamLookup = { lookup },
            partyRepository = InMemoryPartyRepository(),
            playerResolver = FakePlayerGateway(),
        )

        val outcome = executor(spec, "team")(ctx(mapOf("message" to "team yo")))
        assertThat(outcome).isInstanceOf(CommandResult.Failure::class.java)
        assertThat(bus.published.filterIsInstance<ChatMessageSent>()).isEmpty()
    }

    @Test
    fun `team publishes Team channel when sender is placed`() {
        val bus = FakeDomainEventBus()
        val sendChat = SendChatMessageUseCase(eventBus = bus)
        val lookup = FakeMatchTeamLookup()
        val teamId = TeamId("red")
        lookup.place(sender, teamId)
        val spec = buildChatCommandSpec(
            sendChat = sendChat,
            teamLookup = { lookup },
            partyRepository = InMemoryPartyRepository(),
            playerResolver = FakePlayerGateway(),
        )

        val outcome = executor(spec, "team")(ctx(mapOf("message" to "team yo")))
        assertThat(outcome).isEqualTo(CommandResult.Success)
        val msgs = bus.published.filterIsInstance<ChatMessageSent>()
        assertThat(msgs).hasSize(1)
        assertThat(msgs[0].channel).isEqualTo(ChatChannel.Team(teamId))
    }

    @Test
    fun `party publishes Party channel when sender is in a party`() {
        val bus = FakeDomainEventBus()
        val sendChat = SendChatMessageUseCase(eventBus = bus)
        val partyId = PartyId("party-abc")
        val partyRepo = InMemoryPartyRepository().also {
            it.upsert(
                Party(
                    id = partyId,
                    leader = sender,
                    members = setOf(sender),
                    maxSize = 4,
                ),
            )
        }
        val spec = buildChatCommandSpec(
            sendChat = sendChat,
            teamLookup = { null },
            partyRepository = partyRepo,
            playerResolver = FakePlayerGateway(),
        )

        val outcome = executor(spec, "party")(ctx(mapOf("message" to "party yo")))
        assertThat(outcome).isEqualTo(CommandResult.Success)
        val msgs = bus.published.filterIsInstance<ChatMessageSent>()
        assertThat(msgs).hasSize(1)
        assertThat(msgs[0].channel).isEqualTo(ChatChannel.Party(partyId))
    }

    @Test
    fun `msg publishes Private channel when target is online`() {
        val bus = FakeDomainEventBus()
        val sendChat = SendChatMessageUseCase(eventBus = bus)
        val resolver = FakePlayerGateway().also { it.register("Bob", target) }
        val spec = buildChatCommandSpec(
            sendChat = sendChat,
            teamLookup = { null },
            partyRepository = InMemoryPartyRepository(),
            playerResolver = resolver,
        )

        val outcome = executor(spec, "msg")(
            ctx(mapOf("target" to "Bob", "message" to "hey there")),
        )
        assertThat(outcome).isEqualTo(CommandResult.Success)
        val msgs = bus.published.filterIsInstance<ChatMessageSent>()
        assertThat(msgs).hasSize(1)
        assertThat(msgs[0].channel).isEqualTo(ChatChannel.Private(target))
    }

    @Test
    fun `msg fails when target is offline`() {
        val bus = FakeDomainEventBus()
        val sendChat = SendChatMessageUseCase(eventBus = bus)
        val spec = buildChatCommandSpec(
            sendChat = sendChat,
            teamLookup = { null },
            partyRepository = InMemoryPartyRepository(),
            playerResolver = FakePlayerGateway(),
        )

        val outcome = executor(spec, "msg")(
            ctx(mapOf("target" to "Ghost", "message" to "hello")),
        )
        assertThat(outcome).isInstanceOf(CommandResult.Failure::class.java)
        assertThat(bus.published.filterIsInstance<ChatMessageSent>()).isEmpty()
    }
}
