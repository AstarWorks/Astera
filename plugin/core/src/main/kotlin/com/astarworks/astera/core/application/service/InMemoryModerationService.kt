package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IModerationService
import com.astarworks.astera.core.application.port.outbound.ModerationError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.moderation.ModerationAction
import com.astarworks.astera.core.domain.moderation.ModerationActionId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Clock
import java.time.Instant

/**
 * In-memory [IModerationService] (Wave 5h, ADR-0074 §4) backing the
 * Postgres-less default. Postgres overlay
 * ([com.astarworks.astera.persistence.repository.PostgresModerationService])
 * is registered at priority 100 by `AsteraPersistencePlugin`; this default
 * sits at priority 0 in `AsteraCorePlugin`.
 *
 * Storage: append-only [MutableList]; [lift] swaps a row for a copy whose
 * `liftedAt = clock.now()`. [activeFor] filters by `liftedAt == null &&
 * (expiresAt == null || expiresAt > now)`. [history] returns
 * reverse-chronological with `limit` cap.
 *
 * Single-threaded by construction — tests run on the JUnit thread. The
 * Postgres impl handles row-level concurrency.
 */
public class InMemoryModerationService(
    private val clock: Clock = Clock.systemUTC(),
) : IModerationService {

    private val store: MutableList<ModerationAction> = mutableListOf()

    override fun apply(action: ModerationAction): Result<ModerationAction, ModerationError> {
        store += action
        return Result.success(action)
    }

    override fun lift(actionId: ModerationActionId, liftedBy: PlayerId): Result<Unit, ModerationError> {
        val idx = store.indexOfFirst { it.id == actionId }
        if (idx < 0) {
            // No-op success per port contract.
            return Result.success(Unit)
        }
        val current = store[idx]
        if (current.liftedAt != null) {
            // Already lifted — idempotent success.
            return Result.success(Unit)
        }
        val now = Instant.now(clock)
        store[idx] = withLifted(current, now)
        return Result.success(Unit)
    }

    override fun activeFor(playerId: PlayerId): Collection<ModerationAction> {
        val now = Instant.now(clock)
        return store.filter { action ->
            action.playerId == playerId &&
                action.liftedAt == null &&
                (action.expiresAt == null || action.expiresAt!!.isAfter(now))
        }
    }

    override fun history(playerId: PlayerId, limit: Int): List<ModerationAction> {
        require(limit >= 0) { "limit must be >= 0" }
        if (limit == 0) return emptyList()
        return store
            .asReversed()
            .asSequence()
            .filter { it.playerId == playerId }
            .take(limit)
            .toList()
    }

    /** Copy [action] with `liftedAt = now`, enumerated per variant (no `else`). */
    private fun withLifted(action: ModerationAction, now: Instant): ModerationAction = when (action) {
        is ModerationAction.Mute -> action.copy(liftedAt = now)
        is ModerationAction.Kick -> action.copy(liftedAt = now)
        is ModerationAction.Ban -> action.copy(liftedAt = now)
        is ModerationAction.ChatRateLimit -> action.copy(liftedAt = now)
    }
}
