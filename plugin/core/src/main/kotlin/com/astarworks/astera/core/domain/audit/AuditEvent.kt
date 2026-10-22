package com.astarworks.astera.core.domain.audit

import com.astarworks.astera.core.domain.chat.ChatChannel
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant
import java.util.UUID

@JvmInline
public value class AuditEventId(public val value: UUID) {
    public companion object {
        public fun random(): AuditEventId = AuditEventId(UUID.randomUUID())
    }
}

/**
 * Sealed write-once audit record (Wave 5e).
 *
 * Concrete variants cover cross-plugin events that need a forensic
 * trail. Listener wiring (Wave 5e Phase C) attaches per-plugin emitters
 * that translate domain events into [AuditEvent] variants and push them
 * to `IAuditLogRepository`. New variants land in subsequent waves as
 * new audit surface is added.
 *
 * `occurredAt` is the wall-clock at the originating event; `recordedAt`
 * is when the audit row was persisted (may differ under retry).
 */
public sealed interface AuditEvent {
    public val id: AuditEventId
    public val occurredAt: Instant

    public data class PermissionAssigned(
        override val id: AuditEventId,
        val grantee: PlayerId,
        val permissionNode: String,
        val grantedBy: PlayerId?,
        override val occurredAt: Instant,
    ) : AuditEvent

    public data class CurrencyTransferred(
        override val id: AuditEventId,
        val from: PlayerId,
        val to: PlayerId,
        val currency: Currency,
        val amount: Long,
        val reason: String,
        override val occurredAt: Instant,
    ) : AuditEvent

    public data class MatchStarted(
        override val id: AuditEventId,
        val matchId: MatchId,
        override val occurredAt: Instant,
    ) : AuditEvent

    public data class MatchEnded(
        override val id: AuditEventId,
        val matchId: MatchId,
        val winnerTeamId: String?,
        override val occurredAt: Instant,
    ) : AuditEvent

    public data class ChatMessageRecorded(
        override val id: AuditEventId,
        val sender: PlayerId,
        val channel: ChatChannel,
        val content: String,
        override val occurredAt: Instant,
    ) : AuditEvent

    /**
     * Wave 5server-harden (ADR-0123): forensic record of a [ServerError]
     * published through [com.astarworks.astera.core.application.port.outbound.error.IErrorReporter].
     * `severity` is the string form of [com.astarworks.astera.core.domain.error.ServerError.Severity]
     * so audit consumers can filter without re-importing the sealed class.
     */
    public data class ServerErrorReported(
        override val id: AuditEventId,
        val severity: String,
        val summary: String,
        val sourceIdentifier: String,
        override val occurredAt: Instant,
    ) : AuditEvent

    /**
     * F1-D (ADR-0183): a state-changing action taken from the `/astera-admin`
     * GUI panel (give item, force-start lane, kick, heal, ...). [action] is the
     * stable dotted intent key (`admin.items.give`), [target] the affected
     * player / match / lane when there is one, [detail] a short free-form
     * qualifier (`item=rifle x1`).
     */
    public data class AdminActionPerformed(
        override val id: AuditEventId,
        val actor: PlayerId,
        val action: String,
        val target: String?,
        val detail: String,
        override val occurredAt: Instant,
    ) : AuditEvent
}
