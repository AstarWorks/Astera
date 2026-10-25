package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.moderation.ModerationAction
import com.astarworks.astera.core.domain.moderation.ModerationActionId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: server-wide moderation actions on players
 * (Wave 5h, ADR-0079 §4).
 *
 * Wave 5h ships `InMemoryModerationService` (priority 0) +
 * `PostgresModerationService` (priority 100, V21 migration) +
 * `FakeModerationService` (test-fixtures).
 *
 * **Wave 5h scope = domain decision only**. Paper-side enforcement
 * (server-kick on Kick / Ban, chat-cancel on Mute / ChatRateLimit) lands in
 * Wave 6b. A Wave 5h `apply(Mute)` writes to the DB but does NOT cause
 * chat to actually stop until the Paper listener exists.
 *
 * Idempotency: `apply` is not idempotent — calling twice creates two rows.
 * Callers should `activeFor(playerId)` first if dedup is desired.
 */
public interface IModerationService {

    /** Record a new moderation action. Returns the action with its assigned id. */
    public fun apply(action: ModerationAction): Result<ModerationAction, ModerationError>

    /**
     * Lift a previously-applied action (sets `liftedAt = now()`). No-op
     * (Success) if the action is already lifted or does not exist.
     */
    public fun lift(actionId: ModerationActionId, liftedBy: PlayerId): Result<Unit, ModerationError>

    /**
     * Currently-active actions on [playerId] (not lifted, not expired).
     *
     * Empty collection = no restrictions. Callers (e.g.
     * `SendChatMessageUseCase`) filter by variant
     * (`activeFor(player).filterIsInstance<ModerationAction.Mute>()`).
     */
    public fun activeFor(playerId: PlayerId): Collection<ModerationAction>

    /**
     * Full action history for [playerId], most recent first, up to [limit]
     * rows. Used by moderator dashboard / audit query.
     */
    public fun history(playerId: PlayerId, limit: Int = 50): List<ModerationAction>
}

public sealed class ModerationError {
    public data class UnknownAction(public val id: ModerationActionId) : ModerationError()
    public data class Storage(public val detail: String) : ModerationError()
}
