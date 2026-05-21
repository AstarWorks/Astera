package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.visibility.IPlayerVisibilityGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.visibility.PacketBackendError
import com.astarworks.astera.core.domain.visibility.VisibilityError

/**
 * Cross-plugin Fake for [IPlayerVisibilityGateway] (Wave 5y, ADR-0090).
 *
 * Differs from `InMemoryPlayerVisibilityGateway` in three ways:
 *  1. No domain events are published — tests that care about events should
 *     wire the in-memory variant directly with a [FakeDomainEventBus].
 *  2. No viewer / target registration is required — every call is accepted
 *     by default. Use [rejectViewer] / [rejectTarget] / [failBackend] to
 *     simulate failure modes per [VisibilityError].
 *  3. Recorded call lists let tests assert "the use case called `hideEntity`
 *     in this order" without instantiating Paper.
 */
public class FakePlayerVisibilityGateway : IPlayerVisibilityGateway {

    public sealed interface Call {
        public val viewer: PlayerId

        public data class HideEntity(override val viewer: PlayerId, val target: EntityHandle) : Call
        public data class ShowEntity(override val viewer: PlayerId, val target: EntityHandle) : Call
        public data class HidePlayer(override val viewer: PlayerId, val target: PlayerId) : Call
        public data class ShowPlayer(override val viewer: PlayerId, val target: PlayerId) : Call
    }

    public val calls: MutableList<Call> = mutableListOf()

    private val rejectedViewers: MutableSet<PlayerId> = HashSet()
    private val rejectedTargets: MutableSet<Any> = HashSet()

    private var backendFailure: PacketBackendError? = null

    private val hiddenIndex: MutableMap<PlayerId, MutableMap<Any, EntityHandle>> = HashMap()

    /** Subsequent calls referencing [viewer] return [VisibilityError.UnknownViewer]. */
    public fun rejectViewer(viewer: PlayerId): Unit { rejectedViewers += viewer }

    /** Subsequent calls referencing [target] return [VisibilityError.UnknownTarget]. */
    public fun rejectTarget(target: EntityHandle): Unit { rejectedTargets += target.id }
    public fun rejectTarget(target: PlayerId): Unit { rejectedTargets += target }

    /**
     * Make every subsequent call return [VisibilityError.BackendError] wrapping
     * [cause]. Pass `null` to restore success-mode.
     */
    public fun failBackend(cause: PacketBackendError?): Unit { backendFailure = cause }

    override fun hideEntity(viewer: PlayerId, target: EntityHandle): Result<Unit, VisibilityError> {
        calls += Call.HideEntity(viewer, target)
        return resolve(viewer, target.id) {
            hiddenIndex.getOrPut(viewer) { HashMap() }[target.id] = target
        }
    }

    override fun showEntity(viewer: PlayerId, target: EntityHandle): Result<Unit, VisibilityError> {
        calls += Call.ShowEntity(viewer, target)
        return resolve(viewer, target.id) {
            hiddenIndex[viewer]?.remove(target.id)
        }
    }

    override fun hidePlayer(viewer: PlayerId, target: PlayerId): Result<Unit, VisibilityError> {
        calls += Call.HidePlayer(viewer, target)
        if (viewer == target) return Result.failure(VisibilityError.SameSelfHide)
        return resolve(viewer, target) {
            hiddenIndex.getOrPut(viewer) { HashMap() }[target] =
                EntityHandle(id = target.uuid, backendId = "player")
        }
    }

    override fun showPlayer(viewer: PlayerId, target: PlayerId): Result<Unit, VisibilityError> {
        calls += Call.ShowPlayer(viewer, target)
        if (viewer == target) return Result.failure(VisibilityError.SameSelfHide)
        return resolve(viewer, target) {
            hiddenIndex[viewer]?.remove(target)
        }
    }

    override fun hiddenFromViewer(viewer: PlayerId): Collection<EntityHandle> =
        hiddenIndex[viewer]?.values?.toList() ?: emptyList()

    override fun visibleToViewers(target: EntityHandle): Collection<PlayerId> {
        val hidingViewers = hiddenIndex.filterValues { it.containsKey(target.id) }.keys
        return calls.map { it.viewer }.distinct() - hidingViewers
    }

    public fun clear() {
        calls.clear()
        rejectedViewers.clear()
        rejectedTargets.clear()
        backendFailure = null
        hiddenIndex.clear()
    }

    private inline fun resolve(
        viewer: PlayerId,
        targetKey: Any,
        applyIndex: () -> Unit,
    ): Result<Unit, VisibilityError> {
        backendFailure?.let { return Result.failure(VisibilityError.BackendError(it)) }
        if (viewer in rejectedViewers) return Result.failure(VisibilityError.UnknownViewer)
        if (targetKey in rejectedTargets) return Result.failure(VisibilityError.UnknownTarget)
        applyIndex()
        return Result.success(Unit)
    }
}
