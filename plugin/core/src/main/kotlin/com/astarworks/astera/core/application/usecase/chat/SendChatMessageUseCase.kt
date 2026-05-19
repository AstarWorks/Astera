package com.astarworks.astera.core.application.usecase.chat

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IModerationService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.chat.ChatChannel
import com.astarworks.astera.core.domain.event.ChatMessageSent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.moderation.ModerationAction
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Publish a [ChatMessageSent] domain event after light content validation
 * (Wave 5e) + moderation gating (Wave 5h, ADR-0074 §4).
 *
 * Chat is fire-and-forget through the event bus — there is no chat
 * repository. Wave 5e's `AuditLogListener` (Worker ζ) subscribes and
 * writes a row per event; Wave 6b's Paper adapter subscribes to render
 * + broadcast via `IBroadcaster`.
 *
 * ## Moderation gates (Wave 5h)
 *
 * When [moderationService] is non-null:
 *
 * - An active [ModerationAction.Mute] for the sender → [ChatError.Muted]
 *   (silent in the sense that we don't publish; the adapter renders the
 *   `astera.chat.error.muted` message to the sender only).
 * - An active [ModerationAction.ChatRateLimit] for the sender → consumes
 *   one token from a per-player in-memory token bucket. Bucket capacity =
 *   `burstSize`, refill rate = `ratePerSecond`. When the bucket is empty,
 *   the use case returns [ChatError.RateLimited] and does NOT publish.
 *
 * The bucket is intentionally in-memory and per-process — Wave 5h treats
 * rate limiting as best-effort. Cross-server consistency would require
 * a shared token store (Redis / Postgres advisory locks) and is out of
 * scope for Phase 1 PoC.
 *
 * Validation is intentionally minimal otherwise: only "non-blank" is
 * enforced. Profanity filters and permission gating on top of chat are
 * layered as additional use cases / decorators around this one.
 */
public class SendChatMessageUseCase(
    private val eventBus: IDomainEventBus,
    private val clock: Clock = Clock.systemUTC(),
    private val moderationService: IModerationService? = null,
) {

    /**
     * Per-player token bucket state. Keyed by [PlayerId]. Lazily
     * initialised on the first rate-limited send for a given player.
     *
     * `tokens` is a Double so partial-second refills accumulate without
     * integer rounding. `capacity` and `refillPerSecond` are reread from
     * the latest active [ModerationAction.ChatRateLimit] every call so a
     * mid-session ratelimit change (lift + re-apply with different
     * parameters) takes effect immediately.
     */
    private val buckets: ConcurrentHashMap<PlayerId, TokenBucket> = ConcurrentHashMap()

    public fun send(sender: PlayerId, channel: ChatChannel, content: String): Result<Unit, ChatError> {
        if (content.isBlank()) {
            return Result.failure(ChatError.BlankMessage)
        }

        val mod = moderationService
        if (mod != null) {
            val active = mod.activeFor(sender)

            val mute = active.firstNotNullOfOrNull { it as? ModerationAction.Mute }
            if (mute != null) {
                return Result.failure(ChatError.Muted(mute.reasonKey))
            }

            val rateLimit = active.firstNotNullOfOrNull { it as? ModerationAction.ChatRateLimit }
            if (rateLimit != null && !consumeToken(sender, rateLimit)) {
                return Result.failure(ChatError.RateLimited(rateLimit.reasonKey))
            }
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

    /**
     * Try to consume one token from [sender]'s bucket, refilling first
     * based on elapsed wall-clock time since the last update. Returns
     * `true` on consume, `false` when the bucket is empty.
     *
     * The compute lambda is `@Synchronized`-equivalent (ConcurrentHashMap
     * locks the per-key bin), so concurrent sends from the same player
     * don't double-spend.
     */
    private fun consumeToken(sender: PlayerId, rateLimit: ModerationAction.ChatRateLimit): Boolean {
        val now = Instant.now(clock)
        var consumed = false
        buckets.compute(sender) { _, existing ->
            val capacity = rateLimit.burstSize.toDouble()
            val current = existing ?: TokenBucket(tokens = capacity, lastRefillAt = now)
            val elapsedSeconds = (now.toEpochMilli() - current.lastRefillAt.toEpochMilli()) / MILLIS_PER_SECOND
            val refilled = min(
                capacity,
                current.tokens + elapsedSeconds * rateLimit.ratePerSecond,
            )
            if (refilled >= 1.0) {
                consumed = true
                TokenBucket(tokens = refilled - 1.0, lastRefillAt = now)
            } else {
                TokenBucket(tokens = refilled, lastRefillAt = now)
            }
        }
        return consumed
    }

    private data class TokenBucket(val tokens: Double, val lastRefillAt: Instant)

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}

/**
 * Expected failure modes of [SendChatMessageUseCase]. Phase 1 added
 * [Muted] and [RateLimited] in Wave 5h (ADR-0074 §4); permission denial
 * lives at the command layer.
 */
public sealed class ChatError {
    public abstract val messageKey: MessageKey

    public data object BlankMessage : ChatError() {
        override val messageKey: MessageKey =
            MessageKey("astera.chat.error.blank-message")
    }

    public data class Muted(val reasonKey: MessageKey) : ChatError() {
        override val messageKey: MessageKey =
            MessageKey("astera.chat.error.muted")
    }

    public data class RateLimited(val reasonKey: MessageKey) : ChatError() {
        override val messageKey: MessageKey =
            MessageKey("astera.chat.error.rate-limited")
    }
}
