package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IModerationService
import com.astarworks.astera.core.application.port.outbound.ModerationError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.moderation.BanScope
import com.astarworks.astera.core.domain.moderation.ModerationAction
import com.astarworks.astera.core.domain.moderation.ModerationActionId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.table.ModerationActionsTable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Clock
import java.time.Instant

/**
 * Postgres-backed [IModerationService] (Wave 5h, ADR-0074 §4 / V21
 * migration).
 *
 * Registered by `AsteraPersistencePlugin` at priority=100 (Phase C wiring);
 * overlays the in-memory default `InMemoryModerationService` at priority=0
 * registered by `AsteraCorePlugin`.
 *
 * ## Storage shape
 *
 * One row per applied action in [ModerationActionsTable]; `kind` carries
 * the `ModerationAction` sealed-variant discriminator, `payload` JSONB
 * carries the variant-specific extras (`BanScope` for `Ban`,
 * `ratePerSecond + burstSize` for `ChatRateLimit`; the [EMPTY_PAYLOAD]
 * sentinel `'{}'` for `Mute` / `Kick`). `expires_at` / `lifted_at` are
 * columnar (not JSONB) because the active-row scan filters on them.
 *
 * ## Lift semantics
 *
 * [lift] is an in-place UPDATE of `lifted_at`. Both "unknown id" and
 * "already lifted" return Success per [IModerationService.lift]'s
 * contract (idempotent no-op).
 *
 * ## activeFor query
 *
 * Uses the `idx_mod_active (player_id, lifted_at, expires_at)` composite
 * index from V21. Predicate:
 * `player_id = ? AND lifted_at IS NULL AND (expires_at IS NULL OR expires_at > now)`.
 */
public class PostgresModerationService(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : IModerationService {

    private val log = LoggerFactory.getLogger(javaClass)

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun apply(action: ModerationAction): Result<ModerationAction, ModerationError> {
        val (kindStr, payloadStr) = encode(action)
        return try {
            transaction(db) {
                ModerationActionsTable.insert {
                    it[id] = action.id.value
                    it[playerId] = action.playerId.uuid
                    it[kind] = kindStr
                    it[reasonKey] = action.reasonKey.value
                    it[issuedBy] = action.issuedBy.uuid
                    it[issuedAt] = action.issuedAt
                    it[expiresAt] = action.expiresAt
                    it[liftedAt] = action.liftedAt
                    it[payload] = payloadStr
                }
            }
            Result.success(action)
        } catch (ex: SQLException) {
            log.warn("PostgresModerationService.apply failed: id={} kind={}", action.id, kindStr, ex)
            Result.failure(ModerationError.Storage(ex.message ?: "unknown SQL error"))
        }
    }

    override fun lift(actionId: ModerationActionId, liftedBy: PlayerId): Result<Unit, ModerationError> {
        return try {
            transaction(db) {
                val existing = ModerationActionsTable
                    .selectAll()
                    .where { ModerationActionsTable.id eq actionId.value }
                    .firstOrNull()
                    ?: return@transaction Result.success(Unit)

                if (existing[ModerationActionsTable.liftedAt] != null) {
                    return@transaction Result.success(Unit)
                }

                val now = Instant.now(clock)
                ModerationActionsTable.update(
                    { ModerationActionsTable.id eq actionId.value },
                ) {
                    it[liftedAt] = now
                }
                Result.success(Unit)
            }
        } catch (ex: SQLException) {
            log.warn("PostgresModerationService.lift failed: id={}", actionId, ex)
            Result.failure(ModerationError.Storage(ex.message ?: "unknown SQL error"))
        }
    }

    override fun activeFor(playerId: PlayerId): Collection<ModerationAction> {
        val now = Instant.now(clock)
        return transaction(db) {
            ModerationActionsTable
                .selectAll()
                .where {
                    (ModerationActionsTable.playerId eq playerId.uuid) and
                        ModerationActionsTable.liftedAt.isNull() and
                        (
                            ModerationActionsTable.expiresAt.isNull() or
                                (ModerationActionsTable.expiresAt greater now)
                            )
                }
                .map { it.toDomain() }
        }
    }

    override fun history(playerId: PlayerId, limit: Int): List<ModerationAction> {
        require(limit >= 0) { "limit must be >= 0: $limit" }
        if (limit == 0) return emptyList()
        return transaction(db) {
            ModerationActionsTable
                .selectAll()
                .where { ModerationActionsTable.playerId eq playerId.uuid }
                .orderBy(ModerationActionsTable.issuedAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toDomain() }
        }
    }

    // ─── encode ─────────────────────────────────────────────────────────────

    private fun encode(action: ModerationAction): Pair<String, String> = when (action) {
        is ModerationAction.Mute -> KIND_MUTE to EMPTY_PAYLOAD
        is ModerationAction.Kick -> KIND_KICK to EMPTY_PAYLOAD
        is ModerationAction.Ban -> KIND_BAN to json.encodeToString(
            BanPayloadJson.serializer(),
            BanPayloadJson(scope = BanScopeJson.fromDomain(action.scope)),
        )

        is ModerationAction.ChatRateLimit -> KIND_CHAT_RATE_LIMIT to json.encodeToString(
            ChatRateLimitPayloadJson.serializer(),
            ChatRateLimitPayloadJson(
                ratePerSecond = action.ratePerSecond,
                burstSize = action.burstSize,
            ),
        )
    }

    // ─── decode ─────────────────────────────────────────────────────────────

    private fun ResultRow.toDomain(): ModerationAction {
        val id = ModerationActionId(this[ModerationActionsTable.id])
        val playerId = PlayerId(this[ModerationActionsTable.playerId])
        val issuedBy = PlayerId(this[ModerationActionsTable.issuedBy])
        val reasonKey = MessageKey(this[ModerationActionsTable.reasonKey])
        val issuedAt = this[ModerationActionsTable.issuedAt]
        val expiresAt = this[ModerationActionsTable.expiresAt]
        val liftedAt = this[ModerationActionsTable.liftedAt]
        val payload = this[ModerationActionsTable.payload]
        return when (val k = this[ModerationActionsTable.kind]) {
            KIND_MUTE -> ModerationAction.Mute(
                id = id,
                playerId = playerId,
                issuedBy = issuedBy,
                reasonKey = reasonKey,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                liftedAt = liftedAt,
            )

            KIND_KICK -> ModerationAction.Kick(
                id = id,
                playerId = playerId,
                issuedBy = issuedBy,
                reasonKey = reasonKey,
                issuedAt = issuedAt,
                liftedAt = liftedAt,
            )

            KIND_BAN -> {
                val dto = json.decodeFromString(
                    BanPayloadJson.serializer(),
                    payload,
                )
                ModerationAction.Ban(
                    id = id,
                    playerId = playerId,
                    issuedBy = issuedBy,
                    reasonKey = reasonKey,
                    issuedAt = issuedAt,
                    expiresAt = expiresAt,
                    liftedAt = liftedAt,
                    scope = dto.scope.toDomain(),
                )
            }

            KIND_CHAT_RATE_LIMIT -> {
                val dto = json.decodeFromString(
                    ChatRateLimitPayloadJson.serializer(),
                    payload,
                )
                ModerationAction.ChatRateLimit(
                    id = id,
                    playerId = playerId,
                    issuedBy = issuedBy,
                    reasonKey = reasonKey,
                    issuedAt = issuedAt,
                    expiresAt = expiresAt,
                    liftedAt = liftedAt,
                    ratePerSecond = dto.ratePerSecond,
                    burstSize = dto.burstSize,
                )
            }

            else -> error("PostgresModerationService: unknown kind='$k' id=$id (forward-compat fence)")
        }
    }

    public companion object {
        public const val KIND_MUTE: String = "mute"
        public const val KIND_KICK: String = "kick"
        public const val KIND_BAN: String = "ban"
        public const val KIND_CHAT_RATE_LIMIT: String = "chat_rate_limit"

        /** Sentinel payload for variants without extra fields (Mute / Kick). */
        internal const val EMPTY_PAYLOAD: String = "{}"
    }
}

// ─── JSONB DTOs (impl-internal — domain stays kotlinx-serialization-free) ───

@Serializable
private data class BanPayloadJson(val scope: BanScopeJson)

@Serializable
private data class BanScopeJson(
    val kind: String,
    val serverId: String? = null,
) {
    fun toDomain(): BanScope = when (kind) {
        "global" -> BanScope.Global
        "per_server" -> BanScope.PerServer(
            serverId ?: error("BanScopeJson PerServer missing serverId"),
        )

        else -> error("BanScopeJson: unknown scope kind='$kind'")
    }

    companion object {
        fun fromDomain(scope: BanScope): BanScopeJson = when (scope) {
            is BanScope.Global -> BanScopeJson(kind = "global")
            is BanScope.PerServer -> BanScopeJson(
                kind = "per_server",
                serverId = scope.serverId,
            )
        }
    }
}

@Serializable
private data class ChatRateLimitPayloadJson(
    val ratePerSecond: Double,
    val burstSize: Int,
)
