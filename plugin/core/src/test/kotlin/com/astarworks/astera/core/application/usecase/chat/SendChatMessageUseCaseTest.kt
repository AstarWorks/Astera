package com.astarworks.astera.core.application.usecase.chat

import com.astarworks.astera.core.domain.chat.ChatChannel
import com.astarworks.astera.core.domain.event.ChatMessageSent
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SendChatMessageUseCaseTest {

    private lateinit var bus: FakeDomainEventBus
    private lateinit var useCase: SendChatMessageUseCase
    private val sender = PlayerId(UUID.randomUUID())
    private val instant = Instant.parse("2026-05-19T12:00:00Z")
    private val fixedClock = Clock.fixed(instant, ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        bus = FakeDomainEventBus()
        useCase = SendChatMessageUseCase(bus, fixedClock)
    }

    @Test
    fun `send publishes ChatMessageSent with the supplied fields`() {
        val result = useCase.send(sender, ChatChannel.Global, "hello world")
        assertThat(result.isSuccess).isTrue()
        val events = bus.publishedOfType<ChatMessageSent>()
        assertThat(events).hasSize(1)
        val evt = events.first()
        assertThat(evt.sender).isEqualTo(sender)
        assertThat(evt.channel).isEqualTo(ChatChannel.Global)
        assertThat(evt.content).isEqualTo("hello world")
        assertThat(evt.occurredAt).isEqualTo(instant)
        assertThat(evt.version).isEqualTo(1)
    }

    @Test
    fun `send works for a Private channel`() {
        val recipient = PlayerId(UUID.randomUUID())
        val result = useCase.send(sender, ChatChannel.Private(recipient), "psst")
        assertThat(result.isSuccess).isTrue()
        val evt = bus.publishedOfType<ChatMessageSent>().single()
        assertThat(evt.channel).isEqualTo(ChatChannel.Private(recipient))
    }

    @Test
    fun `send rejects blank content with BlankMessage and does not publish`() {
        val result = useCase.send(sender, ChatChannel.Global, "   ")
        assertThat(result.errorOrNull()).isEqualTo(ChatError.BlankMessage)
        assertThat(bus.publishedOfType<ChatMessageSent>()).isEmpty()
    }

    @Test
    fun `send rejects empty content`() {
        val result = useCase.send(sender, ChatChannel.Global, "")
        assertThat(result.errorOrNull()).isEqualTo(ChatError.BlankMessage)
    }

    @Test
    fun `subscribed handler receives the event`() {
        val received = mutableListOf<ChatMessageSent>()
        bus.subscribe(ChatMessageSent::class) { received += it }
        useCase.send(sender, ChatChannel.Global, "ping")
        assertThat(received).hasSize(1)
        assertThat(received.first().content).isEqualTo("ping")
    }
}
