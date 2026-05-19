package com.astarworks.astera.match.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.audit.AuditEventId
import com.astarworks.astera.core.domain.event.ChatMessageSent
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.match.domain.event.MatchEnded
import java.time.Clock

/**
 * Translates core-side / match-side domain events into [AuditEvent] variants
 * and appends them to [IAuditLogRepository] (Wave 5e).
 *
 * Hosted in `plugin:match` rather than `plugin:core` because it must import
 * `match.domain.event.MatchEnded` (ADR-0022: Core cannot reach into feature
 * plugins). The other two subscribed events ([ChatMessageSent], [MatchStarted])
 * live in `core.domain.event` and are visible everywhere.
 *
 * Each `handle*` overload is registered separately by the plugin wiring
 * (Phase C). Tests call them directly.
 *
 * TODO Wave 5f: wire CurrencyTransferred + PermissionAssigned audit
 * subscriptions when those producers land. Today the economy + permission
 * surfaces do not publish a typed domain event suitable for translation.
 */
public class AuditLogListener(
    private val repo: IAuditLogRepository,
    @Suppress("unused") // reserved for Wave 5f when emitter-side translations need a stable timestamp
    private val clock: Clock = Clock.systemUTC(),
) {

    public fun handleChatMessageSent(event: ChatMessageSent) {
        repo.write(
            AuditEvent.ChatMessageRecorded(
                id = AuditEventId.random(),
                sender = event.sender,
                channel = event.channel,
                content = event.content,
                occurredAt = event.occurredAt,
            ),
        )
    }

    public fun handleMatchStarted(event: MatchStarted) {
        repo.write(
            AuditEvent.MatchStarted(
                id = AuditEventId.random(),
                matchId = event.matchId,
                occurredAt = event.occurredAt,
            ),
        )
    }

    public fun handleMatchEnded(event: MatchEnded) {
        repo.write(
            AuditEvent.MatchEnded(
                id = AuditEventId.random(),
                matchId = event.matchId,
                winnerTeamId = event.winningTeam?.value,
                occurredAt = event.occurredAt,
            ),
        )
    }
}
