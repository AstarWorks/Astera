package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.chat.ChatChannel
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Emitted when a player sends a chat message (Wave 5e).
 *
 * The message has already passed permission / moderation gates by the time
 * this event fires. AuditLogListener (Wave 5e) writes a row per occurrence;
 * Wave 6 Paper-side IBroadcaster fan-outs the rendered MiniMessage to
 * connected viewers.
 */
public data class ChatMessageSent(
    val sender: PlayerId,
    val channel: ChatChannel,
    val content: String,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(sender.uuid)
}
