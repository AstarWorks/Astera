package com.astarworks.astera.persistence.feedback

import com.astarworks.astera.core.application.port.outbound.feedback.IPlayerFeedbackQueue
import com.astarworks.astera.core.application.port.outbound.feedback.PlayerFeedbackError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.feedback.FeedbackCategory
import com.astarworks.astera.core.domain.feedback.FeedbackId
import com.astarworks.astera.core.domain.feedback.FeedbackStatus
import com.astarworks.astera.core.domain.feedback.FeedbackTriageDecision
import com.astarworks.astera.core.domain.feedback.PlayerFeedback
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.table.PlayerFeedbackTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Instant

/**
 * Wave 5t (ADR-0086) — Postgres-backed [IPlayerFeedbackQueue] (V24).
 *
 * Registered at priority 100 by `AsteraPersistencePlugin`; overlays the
 * in-memory default. Storage trail joins with Wave 5e audit log for unified
 * per-player history view (Phase 4 Web Admin UI).
 */
public class PostgresPlayerFeedbackQueue(
    private val db: Database,
    private val maxCommentLength: Int = DEFAULT_MAX_COMMENT_LENGTH,
    private val clock: () -> Instant = Instant::now,
) : IPlayerFeedbackQueue {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun submit(
        submittedBy: PlayerId,
        target: PlayerId?,
        category: FeedbackCategory,
        comment: String,
    ): Result<PlayerFeedback, PlayerFeedbackError> {
        if (comment.length > maxCommentLength) {
            return Result.failure(PlayerFeedbackError.CommentTooLong(maxCommentLength))
        }
        val record = PlayerFeedback(
            id = FeedbackId.random(),
            submittedBy = submittedBy,
            target = target,
            category = category,
            comment = comment,
            submittedAt = clock(),
            status = FeedbackStatus.Open,
        )
        return try {
            ioTransaction(db) {
                PlayerFeedbackTable.insert {
                    it[PlayerFeedbackTable.id] = record.id.value
                    it[PlayerFeedbackTable.submittedBy] = record.submittedBy.uuid
                    it[PlayerFeedbackTable.targetPlayerId] = record.target?.uuid
                    it[PlayerFeedbackTable.categoryKey] = record.category.key
                    it[PlayerFeedbackTable.categoryFreeform] =
                        (record.category as? FeedbackCategory.Other)?.freeForm
                    it[PlayerFeedbackTable.comment] = record.comment
                    it[PlayerFeedbackTable.submittedAt] = record.submittedAt
                    it[PlayerFeedbackTable.status] = record.status.name.lowercase()
                }
            }
            Result.success(record)
        } catch (ex: SQLException) {
            log.warn("player_feedback insert failed", ex)
            Result.failure(PlayerFeedbackError.UnknownFeedback(record.id))
        }
    }

    override suspend fun list(
        statusFilter: FeedbackStatus?,
        categoryKey: String?,
        limit: Int,
    ): List<PlayerFeedback> = ioTransaction(db) {
        // Phase 1 PoC: load + filter in-process. The expected admin queue
        // size (< 10k rows) keeps this comfortably fast; Phase 4 may push
        // the filter into SQL once the Web Admin UI lands.
        PlayerFeedbackTable
            .selectAll()
            .orderBy(PlayerFeedbackTable.submittedAt to SortOrder.DESC)
            .map { it.toFeedback() }
            .filter { statusFilter == null || it.status == statusFilter }
            .filter { categoryKey == null || it.category.key == categoryKey }
            .take(limit)
    }

    override suspend fun triage(
        id: FeedbackId,
        decision: FeedbackTriageDecision,
        decidedBy: PlayerId,
    ): Result<PlayerFeedback, PlayerFeedbackError> {
        val newStatus = when (decision) {
            FeedbackTriageDecision.MarkInProgress -> FeedbackStatus.InProgress
            is FeedbackTriageDecision.Resolve -> FeedbackStatus.Resolved
            is FeedbackTriageDecision.Dismiss -> FeedbackStatus.Dismissed
        }

        return try {
            ioTransaction(db) {
                val current = PlayerFeedbackTable
                    .selectAll()
                    .where { PlayerFeedbackTable.id eq id.value }
                    .firstOrNull()
                    ?.toFeedback()
                    ?: return@ioTransaction Result.failure(PlayerFeedbackError.UnknownFeedback(id))

                if (!isValidTransition(current.status, newStatus)) {
                    return@ioTransaction Result.failure(
                        PlayerFeedbackError.InvalidTransition(current.status, newStatus),
                    )
                }

                PlayerFeedbackTable.update({ PlayerFeedbackTable.id eq id.value }) {
                    it[status] = newStatus.name.lowercase()
                }
                @Suppress("UNUSED_VARIABLE")
                val whoRef = decidedBy
                Result.success(current.copy(status = newStatus))
            }
        } catch (ex: SQLException) {
            log.warn("player_feedback triage failed for id={}", id.value, ex)
            Result.failure(PlayerFeedbackError.UnknownFeedback(id))
        }
    }

    override suspend fun get(id: FeedbackId): PlayerFeedback? = ioTransaction(db) {
        PlayerFeedbackTable
            .selectAll()
            .where { PlayerFeedbackTable.id eq id.value }
            .firstOrNull()
            ?.toFeedback()
    }

    private fun ResultRow.toFeedback(): PlayerFeedback {
        val categoryKey = this[PlayerFeedbackTable.categoryKey]
        val freeform = this[PlayerFeedbackTable.categoryFreeform]
        val category: FeedbackCategory = when (categoryKey) {
            FeedbackCategory.Cheating.key -> FeedbackCategory.Cheating
            FeedbackCategory.Bug.key -> FeedbackCategory.Bug
            FeedbackCategory.Harassment.key -> FeedbackCategory.Harassment
            FeedbackCategory.Suggestion.key -> FeedbackCategory.Suggestion
            else -> FeedbackCategory.Other(freeform ?: "")
        }
        val statusText = this[PlayerFeedbackTable.status]
        val status = FeedbackStatus.values().first { it.name.lowercase() == statusText }
        return PlayerFeedback(
            id = FeedbackId(this[PlayerFeedbackTable.id]),
            submittedBy = PlayerId(this[PlayerFeedbackTable.submittedBy]),
            target = this[PlayerFeedbackTable.targetPlayerId]?.let { PlayerId(it) },
            category = category,
            comment = this[PlayerFeedbackTable.comment],
            submittedAt = this[PlayerFeedbackTable.submittedAt],
            status = status,
        )
    }

    private fun isValidTransition(from: FeedbackStatus, to: FeedbackStatus): Boolean = when (from) {
        FeedbackStatus.Open -> to != FeedbackStatus.Open
        FeedbackStatus.InProgress -> to == FeedbackStatus.Resolved || to == FeedbackStatus.Dismissed
        FeedbackStatus.Resolved, FeedbackStatus.Dismissed -> false
    }

    public companion object {
        public const val DEFAULT_MAX_COMMENT_LENGTH: Int = 1024
    }
}
