package com.astarworks.astera.core.application.eventbus.listeners.chat

import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerProfileService
import com.astarworks.astera.core.application.port.outbound.ProfileError
import com.astarworks.astera.core.application.service.InMemoryPlayerSessionRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.chat.ChatChannel
import com.astarworks.astera.core.domain.event.ChatMessageSent
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerProfile
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.core.domain.social.PartyId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ChatDisplayListenerTest {

    private val sender = PlayerId(UUID.randomUUID())
    private val recipientA = PlayerId(UUID.randomUUID())
    private val recipientB = PlayerId(UUID.randomUUID())
    private val sessions = InMemoryPlayerSessionRepository().apply {
        upsert(PlayerSession(sender, SessionState.Lobby))
        upsert(PlayerSession(recipientA, SessionState.Lobby))
        upsert(PlayerSession(recipientB, SessionState.Lobby))
    }
    private val playerGw = RecordingPlayerGateway()
    private val profileService = StubProfileService(
        names = mapOf(sender to "Alice", recipientA to "Bob"),
    )
    private val listener = ChatDisplayListener(playerGw, sessions, profileService)

    @Test
    fun `Global channel sends to every online session including sender`() {
        listener.handle(
            ChatMessageSent(
                sender = sender,
                channel = ChatChannel.Global,
                content = "hello world",
            ),
        )
        val sentRecipients = playerGw.sent.map { it.first }.toSet()
        assertThat(sentRecipients).containsExactlyInAnyOrder(sender, recipientA, recipientB)
        val firstMessage = playerGw.sent.first().second
        assertThat(firstMessage).isEqualTo("<Alice> hello world")
    }

    @Test
    fun `Private channel sends to the single recipient`() {
        listener.handle(
            ChatMessageSent(
                sender = sender,
                channel = ChatChannel.Private(recipientA),
                content = "psst",
            ),
        )
        assertThat(playerGw.sent).hasSize(1)
        assertThat(playerGw.sent.first().first).isEqualTo(recipientA)
        assertThat(playerGw.sent.first().second).isEqualTo("<Alice> psst")
    }

    @Test
    fun `Team channel skips delivery and logs WARN (Phase 1 PoC defer)`() {
        listener.handle(
            ChatMessageSent(
                sender = sender,
                channel = ChatChannel.Party(PartyId(UUID.randomUUID().toString())),
                content = "party chat",
            ),
        )
        assertThat(playerGw.sent).isEmpty()
    }

    @Test
    fun `missing profile falls back to UUID short prefix`() {
        listener.handle(
            ChatMessageSent(
                sender = recipientB,
                channel = ChatChannel.Global,
                content = "no name",
            ),
        )
        val firstMessage = playerGw.sent.first().second
        assertThat(firstMessage).startsWith("<")
        assertThat(firstMessage).endsWith(" no name")
    }

    private class RecordingPlayerGateway : IPlayerGateway {
        val sent: MutableList<Pair<PlayerId, String>> = mutableListOf()
        override fun findByName(name: String): PlayerId? = null
        override fun sendMessage(playerId: PlayerId, rendered: String) {
            sent += playerId to rendered
        }
    }

    private class StubProfileService(
        private val names: Map<PlayerId, String>,
    ) : IPlayerProfileService {
        override fun find(playerId: PlayerId): PlayerProfile? =
            names[playerId]?.let { name ->
                PlayerProfile(
                    id = playerId,
                    displayName = name,
                    locale = null,
                    joinedAtEpochMs = 0L,
                )
            }
        override fun createFirstTime(
            playerId: PlayerId,
            displayName: String,
            locale: String?,
        ): Result<PlayerProfile, ProfileError> = throw UnsupportedOperationException("not used")
    }
}
