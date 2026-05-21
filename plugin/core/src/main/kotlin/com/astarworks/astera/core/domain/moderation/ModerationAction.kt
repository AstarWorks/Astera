package com.astarworks.astera.core.domain.moderation

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Server-wide moderation action against a player (Wave 5h, ADR-0079 §4).
 *
 * Closed hierarchy of four variants:
 *
 * - [Mute] — silences chat output until [expiresAt] or [liftedAt]. `null`
 *   [expiresAt] means permanent.
 * - [Kick] — instantaneous; only the record (audit) persists. [expiresAt]
 *   is forced to `null` because kick has no notion of duration.
 * - [Ban] — connection-level denial (Wave 6b adapter wires the
 *   `PlayerLoginEvent` listener). Carries a [BanScope] so per-server bans
 *   are distinguishable from global bans without overloading
 *   [PlayerId].
 * - [ChatRateLimit] — silent suppression once the in-memory token bucket
 *   tied to ([ratePerSecond], [burstSize]) is exhausted. Bucket lives on
 *   the chat use case side; this record carries only the configuration.
 *
 * Per ADR-0079 §4 / Konsist `ModerationAction sealed must remain
 * exhaustive`, every `when` over this hierarchy must enumerate all four
 * variants and MUST NOT carry an `else` branch.
 *
 * Wave 5h scope = domain decision only. Paper-side enforcement
 * (kick-from-server / chat-cancel) lands in Wave 6b. Wave 5h `apply`
 * writes to the DB; the chat / connection effects materialize when Wave
 * 6b adapters subscribe to the active-actions stream.
 */
public sealed interface ModerationAction {
    public val id: ModerationActionId
    public val playerId: PlayerId
    public val issuedBy: PlayerId
    public val reasonKey: MessageKey
    public val issuedAt: Instant
    public val expiresAt: Instant?
    public val liftedAt: Instant?

    public data class Mute(
        public override val id: ModerationActionId,
        public override val playerId: PlayerId,
        public override val issuedBy: PlayerId,
        public override val reasonKey: MessageKey,
        public override val issuedAt: Instant,
        public override val expiresAt: Instant? = null,
        public override val liftedAt: Instant? = null,
    ) : ModerationAction

    public data class Kick(
        public override val id: ModerationActionId,
        public override val playerId: PlayerId,
        public override val issuedBy: PlayerId,
        public override val reasonKey: MessageKey,
        public override val issuedAt: Instant,
        public override val liftedAt: Instant? = null,
    ) : ModerationAction {
        // Kick is instantaneous — no expiry concept.
        public override val expiresAt: Instant? = null
    }

    public data class Ban(
        public override val id: ModerationActionId,
        public override val playerId: PlayerId,
        public override val issuedBy: PlayerId,
        public override val reasonKey: MessageKey,
        public override val issuedAt: Instant,
        public override val expiresAt: Instant? = null,
        public override val liftedAt: Instant? = null,
        public val scope: BanScope,
    ) : ModerationAction

    public data class ChatRateLimit(
        public override val id: ModerationActionId,
        public override val playerId: PlayerId,
        public override val issuedBy: PlayerId,
        public override val reasonKey: MessageKey,
        public override val issuedAt: Instant,
        public override val expiresAt: Instant? = null,
        public override val liftedAt: Instant? = null,
        public val ratePerSecond: Double,
        public val burstSize: Int,
    ) : ModerationAction {
        init {
            require(ratePerSecond > 0.0) { "ratePerSecond must be > 0" }
            require(burstSize > 0) { "burstSize must be > 0" }
        }
    }
}
