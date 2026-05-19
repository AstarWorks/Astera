package com.astarworks.astera.core.application.usecase.chat

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.chat.ChatChannel
import com.astarworks.astera.core.domain.event.ChatMessageSent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Clock
import java.time.Instant

/**
 * Publish a [ChatMessageSent] domain event after light content validation
 * (Wave 5e).
 *
 * Chat is fire-and-forget through the event bus — there is no chat repository.
 * Wave 5e's `AuditLogListener` (Worker ζ) subscribes and writes a row per
 * event; Wave 6b's Paper adapter subscribes to render + broadcast via
 * `IBroadcaster`.
 *
 * Validation is intentionally minimal here: only "non-blank" is enforced so
 * the domain test surface stays tiny. Permission gating, mute checks, and
 * profanity filters are added later as additional use cases / decorators
 * that wrap this one.
 */
public class SendChatMessageUseCase(
    private val eventBus: IDomainEventBus,
    private val clock: Clock = Clock.systemUTC(),
) {
    public fun send(sender: PlayerId, channel: ChatChannel, content: String): Result<Unit, ChatError> {
        if (content.isBlank()) {
            return Result.failure(ChatError.BlankMessage)
        }
        eventBus.publish(
            ChatMessageSent(
                sender = sender,
                channel = channel,
                content = content,
                occurredAt = Instant.now(clock),
            ),
        )
        return Result.success(Unit)
    }
}

/**
 * Expected failure modes of [SendChatMessageUseCase]. Phase 1 has only
 * [BlankMessage]; mute / cooldown / permission denial join later.
 */
public sealed class ChatError {
    public abstract val messageKey: MessageKey

    public data object BlankMessage : ChatError() {
        override val messageKey: MessageKey =
            MessageKey("astera.chat.error.blank-message")
    }
}
