package com.astarworks.astera.persistence.repository

import com.astarworks.astera.persistence.adapter.io.blockingTransaction
import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.audit.AuditEventId
import com.astarworks.astera.core.domain.chat.ChatChannel
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.postgresql.util.PGobject
import java.time.Instant
import java.util.UUID

/**
 * `audit_log` Exposed table (Wave 5e / V9). One row per [AuditEvent].
 * `kind` is the sealed-variant discriminator (snake_case); `data` is the
 * variant-specific payload as JSONB (kotlinx.serialization-based DTOs).
 *
 * The default impl is in-memory ([com.astarworks.astera.core.application.service.InMemoryAuditLogRepository]).
 * Persistence registers this overlay at priority=100 from
 * AsteraPersistencePlugin (Phase C wiring).
 */
public object AuditLogTable : Table("audit_log") {
    public val id = javaUUID("id")
    public val kind = varchar("kind", 64)

    /**
     * Untyped JSONB column — the row's serializer depends on [kind], so we
     * encode/decode against [Json] manually rather than declaring a single
     * `KSerializer<T>` at column-definition time.
     */
    public val data = registerColumn<String>("data", RawJsonbColumnType)
    public val occurredAt = timestamp("occurred_at")
    public val recordedAt = timestamp("recorded_at")
    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/** Postgres-backed [IAuditLogRepository] overlaying the in-memory default. */
public class PostgresAuditLogRepository(private val db: Database) : IAuditLogRepository {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun write(event: AuditEvent): Unit = blockingTransaction(db) {
        val (k, payload) = encode(event)
        AuditLogTable.insert {
            it[id] = event.id.value
            it[kind] = k
            it[data] = payload
            it[occurredAt] = event.occurredAt
            it[recordedAt] = Instant.now()
        }
        Unit
    }

    override fun recent(limit: Int, since: Instant?): List<AuditEvent> {
        require(limit >= 0) { "limit must be >= 0: $limit" }
        if (limit == 0) return emptyList()
        return blockingTransaction(db) {
            val q = if (since != null) {
                AuditLogTable.selectAll().where { AuditLogTable.recordedAt greaterEq since }
            } else {
                AuditLogTable.selectAll()
            }
            q.orderBy(AuditLogTable.recordedAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toDomain() }
        }
    }

    // ─── encode ─────────────────────────────────────────────────────────────

    private fun encode(event: AuditEvent): Pair<String, String> = when (event) {
        is AuditEvent.PermissionAssigned -> KIND_PERMISSION_ASSIGNED to json.encodeToString(
            PermissionAssignedJson.serializer(),
            PermissionAssignedJson(
                grantee = event.grantee.uuid.toString(),
                permissionNode = event.permissionNode,
                grantedBy = event.grantedBy?.uuid?.toString(),
            ),
        )

        is AuditEvent.CurrencyTransferred -> KIND_CURRENCY_TRANSFERRED to json.encodeToString(
            CurrencyTransferredJson.serializer(),
            CurrencyTransferredJson(
                from = event.from.uuid.toString(),
                to = event.to.uuid.toString(),
                currency = event.currency.name,
                amount = event.amount,
                reason = event.reason,
            ),
        )

        is AuditEvent.CurrencyMovement -> KIND_CURRENCY_MOVEMENT to json.encodeToString(
            CurrencyMovementJson.serializer(),
            CurrencyMovementJson(
                player = event.player.uuid.toString(),
                currency = event.currency.name,
                delta = event.delta,
                newBalance = event.newBalance,
                reason = event.reason,
            ),
        )

        is AuditEvent.MatchStarted -> KIND_MATCH_STARTED to json.encodeToString(
            MatchStartedJson.serializer(),
            MatchStartedJson(matchId = event.matchId.value),
        )

        is AuditEvent.MatchEnded -> KIND_MATCH_ENDED to json.encodeToString(
            MatchEndedJson.serializer(),
            MatchEndedJson(matchId = event.matchId.value, winnerTeamId = event.winnerTeamId),
        )

        is AuditEvent.ChatMessageRecorded -> KIND_CHAT_MESSAGE_RECORDED to json.encodeToString(
            ChatMessageRecordedJson.serializer(),
            ChatMessageRecordedJson(
                sender = event.sender.uuid.toString(),
                channel = ChannelJson.fromDomain(event.channel),
                content = event.content,
            ),
        )

        is AuditEvent.ServerErrorReported -> KIND_SERVER_ERROR_REPORTED to json.encodeToString(
            ServerErrorReportedJson.serializer(),
            ServerErrorReportedJson(
                severity = event.severity,
                summary = event.summary,
                sourceIdentifier = event.sourceIdentifier,
            ),
        )

        is AuditEvent.AdminActionPerformed -> KIND_ADMIN_ACTION_PERFORMED to json.encodeToString(
            AdminActionPerformedJson.serializer(),
            AdminActionPerformedJson(
                actor = event.actor.uuid.toString(),
                action = event.action,
                target = event.target,
                detail = event.detail,
            ),
        )
    }

    // ─── decode ─────────────────────────────────────────────────────────────

    private fun ResultRow.toDomain(): AuditEvent {
        val id = AuditEventId(this[AuditLogTable.id])
        val occurredAt = this[AuditLogTable.occurredAt]
        val raw = this[AuditLogTable.data]
        return when (val k = this[AuditLogTable.kind]) {
            KIND_PERMISSION_ASSIGNED -> {
                val dto = json.decodeFromString(PermissionAssignedJson.serializer(), raw)
                AuditEvent.PermissionAssigned(
                    id = id,
                    grantee = PlayerId(UUID.fromString(dto.grantee)),
                    permissionNode = dto.permissionNode,
                    grantedBy = dto.grantedBy?.let { PlayerId(UUID.fromString(it)) },
                    occurredAt = occurredAt,
                )
            }
            KIND_CURRENCY_TRANSFERRED -> {
                val dto = json.decodeFromString(CurrencyTransferredJson.serializer(), raw)
                AuditEvent.CurrencyTransferred(
                    id = id,
                    from = PlayerId(UUID.fromString(dto.from)),
                    to = PlayerId(UUID.fromString(dto.to)),
                    currency = Currency.valueOf(dto.currency),
                    amount = dto.amount,
                    reason = dto.reason,
                    occurredAt = occurredAt,
                )
            }
            KIND_CURRENCY_MOVEMENT -> {
                val dto = json.decodeFromString(CurrencyMovementJson.serializer(), raw)
                AuditEvent.CurrencyMovement(
                    id = id,
                    player = PlayerId(UUID.fromString(dto.player)),
                    currency = Currency.valueOf(dto.currency),
                    delta = dto.delta,
                    newBalance = dto.newBalance,
                    reason = dto.reason,
                    occurredAt = occurredAt,
                )
            }
            KIND_MATCH_STARTED -> {
                val dto = json.decodeFromString(MatchStartedJson.serializer(), raw)
                AuditEvent.MatchStarted(id = id, matchId = MatchId(dto.matchId), occurredAt = occurredAt)
            }
            KIND_MATCH_ENDED -> {
                val dto = json.decodeFromString(MatchEndedJson.serializer(), raw)
                AuditEvent.MatchEnded(
                    id = id,
                    matchId = MatchId(dto.matchId),
                    winnerTeamId = dto.winnerTeamId,
                    occurredAt = occurredAt,
                )
            }
            KIND_CHAT_MESSAGE_RECORDED -> {
                val dto = json.decodeFromString(ChatMessageRecordedJson.serializer(), raw)
                AuditEvent.ChatMessageRecorded(
                    id = id,
                    sender = PlayerId(UUID.fromString(dto.sender)),
                    channel = dto.channel.toDomain(),
                    content = dto.content,
                    occurredAt = occurredAt,
                )
            }
            KIND_SERVER_ERROR_REPORTED -> {
                val dto = json.decodeFromString(ServerErrorReportedJson.serializer(), raw)
                AuditEvent.ServerErrorReported(
                    id = id,
                    severity = dto.severity,
                    summary = dto.summary,
                    sourceIdentifier = dto.sourceIdentifier,
                    occurredAt = occurredAt,
                )
            }
            KIND_ADMIN_ACTION_PERFORMED -> {
                val dto = json.decodeFromString(AdminActionPerformedJson.serializer(), raw)
                AuditEvent.AdminActionPerformed(
                    id = id,
                    actor = PlayerId(UUID.fromString(dto.actor)),
                    action = dto.action,
                    target = dto.target,
                    detail = dto.detail,
                    occurredAt = occurredAt,
                )
            }
            else -> error("PostgresAuditLogRepository: unknown audit kind='$k' id=$id (forward-compat fence)")
        }
    }

    public companion object {
        public const val KIND_PERMISSION_ASSIGNED: String = "permission_assigned"
        public const val KIND_CURRENCY_TRANSFERRED: String = "currency_transferred"
        public const val KIND_CURRENCY_MOVEMENT: String = "currency_movement"
        public const val KIND_MATCH_STARTED: String = "match_started"
        public const val KIND_MATCH_ENDED: String = "match_ended"
        public const val KIND_CHAT_MESSAGE_RECORDED: String = "chat_message_recorded"
        public const val KIND_SERVER_ERROR_REPORTED: String = "server_error_reported"
        public const val KIND_ADMIN_ACTION_PERFORMED: String = "admin_action_performed"
    }
}

// ─── JSONB DTOs (Postgres-impl-internal — Phase A AuditEvent stays kaml-free) ──

@Serializable
private data class PermissionAssignedJson(
    val grantee: String,
    val permissionNode: String,
    val grantedBy: String?,
)

@Serializable
private data class CurrencyTransferredJson(
    val from: String,
    val to: String,
    val currency: String,
    val amount: Long,
    val reason: String,
)

@Serializable
private data class CurrencyMovementJson(
    val player: String,
    val currency: String,
    val delta: Long,
    val newBalance: Long,
    val reason: String,
)

@Serializable
private data class MatchStartedJson(val matchId: String)

@Serializable
private data class MatchEndedJson(val matchId: String, val winnerTeamId: String?)

@Serializable
private data class ChatMessageRecordedJson(
    val sender: String,
    val channel: ChannelJson,
    val content: String,
)

@Serializable
private data class AdminActionPerformedJson(
    val actor: String,
    val action: String,
    val target: String?,
    val detail: String,
)

@Serializable
private data class ServerErrorReportedJson(
    val severity: String,
    val summary: String,
    val sourceIdentifier: String,
)

/**
 * Variant-tagged DTO for [ChatChannel]. Stored as
 * `{ "kind": "global" }`, `{ "kind": "team", "teamId": "red" }`,
 * `{ "kind": "party", "partyId": "<uuid>" }`,
 * `{ "kind": "private", "recipient": "<uuid>" }`.
 */
@Serializable
private data class ChannelJson(
    val kind: String,
    val teamId: String? = null,
    val partyId: String? = null,
    val recipient: String? = null,
) {
    fun toDomain(): ChatChannel = when (kind) {
        "global" -> ChatChannel.Global
        "team" -> ChatChannel.Team(TeamId(teamId!!))
        "party" -> ChatChannel.Party(PartyId(partyId!!))
        "private" -> ChatChannel.Private(PlayerId(UUID.fromString(recipient!!)))
        else -> error("ChannelJson: unknown channel kind='$kind'")
    }

    companion object {
        fun fromDomain(channel: ChatChannel): ChannelJson = when (channel) {
            is ChatChannel.Global -> ChannelJson(kind = "global")
            is ChatChannel.Team -> ChannelJson(kind = "team", teamId = channel.teamId.value)
            is ChatChannel.Party -> ChannelJson(kind = "party", partyId = channel.partyId.value)
            is ChatChannel.Private -> ChannelJson(
                kind = "private",
                recipient = channel.recipient.uuid.toString(),
            )
        }
    }
}

// ─── Raw JSONB column (string payload, since dispatch happens at row level) ──

/**
 * JSONB column whose value is the raw JSON string — used when the row-level
 * serializer depends on a sibling discriminator column (here: `kind`).
 * `JsonbColumnType<T>` requires a fixed `KSerializer<T>` at definition time,
 * which doesn't fit the audit-log shape.
 */
private object RawJsonbColumnType : ColumnType<String>() {
    override fun sqlType(): String = "JSONB"

    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value ?: "{}"
        is String -> value
        else -> value.toString()
    }

    override fun valueToDB(value: String?): Any? = value?.let { wrap(it) }

    override fun notNullValueToDB(value: String): Any = wrap(value)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        val pg = when (value) {
            null -> null
            is PGobject -> value
            is String -> wrap(value)
            else -> wrap(value.toString())
        }
        super.setParameter(stmt, index, pg)
    }

    private fun wrap(s: String): PGobject = PGobject().apply {
        type = "jsonb"
        this.value = s
    }
}

