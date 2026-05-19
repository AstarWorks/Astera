package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IModerationService
import com.astarworks.astera.core.application.port.outbound.ModerationError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.moderation.ModerationAction
import com.astarworks.astera.core.domain.moderation.ModerationActionId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Clock
import java.time.Instant

/**
 * Test-fixture [IModerationService] (Wave 5h, ADR-0074 §4).
 *
 * Mirrors [com.astarworks.astera.core.application.service.InMemoryModerationService]
 * but exposes [appliedActions] + [liftedIds] for assertions in cross-plugin
 * use case tests that don't want to depend on
 * `com.astarworks.astera.core.application.service.*`.
 *
 * Single-threaded, deterministic. Use the fixed-clock constructor in tests
 * that verify expiry timing.
 */
public class FakeModerationService(
    private val clock: Clock = Clock.systemUTC(),
) : IModerationService {

    private val store: MutableList<ModerationAction> = mutableListOf()

    /** All actions ever applied, in apply order (lifted variants reflect their lift). */
    public val appliedActions: List<ModerationAction>
        get() = store.toList()

    /** Action ids that have been lifted at least once. */
    public val liftedIds: Set<ModerationActionId>
        get() = store.filter { it.liftedAt != null }.map { it.id }.toSet()

    override fun apply(action: ModerationAction): Result<ModerationAction, ModerationError> {
        store += action
        return Result.success(action)
    }

    override fun lift(actionId: ModerationActionId, liftedBy: PlayerId): Result<Unit, ModerationError> {
        val idx = store.indexOfFirst { it.id == actionId }
        if (idx < 0) return Result.success(Unit)
        val current = store[idx]
        if (current.liftedAt != null) return Result.success(Unit)
        store[idx] = withLifted(current, Instant.now(clock))
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

    /** Test-only: drop every stored action. */
    public fun clear() {
        store.clear()
    }

    private fun withLifted(action: ModerationAction, now: Instant): ModerationAction = when (action) {
        is ModerationAction.Mute -> action.copy(liftedAt = now)
        is ModerationAction.Kick -> action.copy(liftedAt = now)
        is ModerationAction.Ban -> action.copy(liftedAt = now)
        is ModerationAction.ChatRateLimit -> action.copy(liftedAt = now)
    }
}
