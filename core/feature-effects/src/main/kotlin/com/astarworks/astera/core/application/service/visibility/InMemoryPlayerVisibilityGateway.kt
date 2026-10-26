package com.astarworks.astera.core.application.service.visibility

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.visibility.IPlayerVisibilityGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.EntityHiddenFromViewer
import com.astarworks.astera.core.domain.event.EntityShownToViewer
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.visibility.VisibilityError
import java.util.UUID

/**
 * In-memory default [IPlayerVisibilityGateway] (Wave 5y, ADR-0090).
 *
 * Holds the per-viewer hide / show index in two `HashMap`s; the Wave 6b
 * Paper adapter wraps this with packet-side spawn / destroy drops via
 * PacketEvents. The in-memory variant is good enough for:
 *
 * - JUnit fixtures (domain tests that need a real port behind a use case)
 * - the FakeMcServer + scenario DSL (ADR-0059) where there is no packet
 *   pipeline at all
 * - a `mcserver-headless` PoC where visibility is logical, not visual
 *
 * Validation order matches the SUT-level expectations: [knownViewers] is
 * checked first, then [knownEntities], then the [PlayerId] self-hide guard,
 * then the index write.
 *
 * Per ADR-0090 the gateway is **not** thread-safe. All mutations run on the
 * Paper main thread (or its equivalent test executor).
 */
public class InMemoryPlayerVisibilityGateway(
    private val eventBus: IDomainEventBus? = null,
) : IPlayerVisibilityGateway {

    private val knownViewers: MutableSet<PlayerId> = HashSet()

    private val knownEntities: MutableSet<UUID> = HashSet()

    /** viewer -> {handles hidden from that viewer} */
    private val hiddenByViewer: MutableMap<PlayerId, MutableMap<UUID, EntityHandle>> = HashMap()

    /**
     * Mark [viewer] as a viable target for hide / show calls. Implementations
     * normally call this on `PlayerJoinedServer`; in-memory mode requires
     * tests to register explicitly.
     */
    public fun trackViewer(viewer: PlayerId) {
        knownViewers += viewer
    }

    /** Stop tracking a viewer (e.g. on logout) and drop any hide index for it. */
    public fun untrackViewer(viewer: PlayerId) {
        knownViewers -= viewer
        hiddenByViewer.remove(viewer)
    }

    /**
     * Mark [target] as a viable hide target. Players themselves are tracked
     * via [trackViewer] (auto-promoted on the [hidePlayer] path).
     */
    public fun trackEntity(target: EntityHandle) {
        knownEntities += target.id
    }

    public fun untrackEntity(target: EntityHandle) {
        knownEntities -= target.id
        hiddenByViewer.values.forEach { it.remove(target.id) }
    }

    override fun hideEntity(viewer: PlayerId, target: EntityHandle): Result<Unit, VisibilityError> {
        if (viewer !in knownViewers) return Result.failure(VisibilityError.UnknownViewer)
        if (target.id !in knownEntities) return Result.failure(VisibilityError.UnknownTarget)
        if (target.backendId == PLAYER_BACKEND && target.id == viewer.uuid) {
            return Result.failure(VisibilityError.SameSelfHide)
        }
        val bucket = hiddenByViewer.getOrPut(viewer) { HashMap() }
        val newly = bucket.put(target.id, target) == null
        if (newly) {
            eventBus?.publish(EntityHiddenFromViewer(viewer = viewer, target = target))
        }
        return Result.success(Unit)
    }

    override fun showEntity(viewer: PlayerId, target: EntityHandle): Result<Unit, VisibilityError> {
        if (viewer !in knownViewers) return Result.failure(VisibilityError.UnknownViewer)
        if (target.id !in knownEntities) return Result.failure(VisibilityError.UnknownTarget)
        val bucket = hiddenByViewer[viewer]
        val removed = bucket?.remove(target.id)
        if (bucket != null && bucket.isEmpty()) hiddenByViewer.remove(viewer)
        if (removed != null) {
            eventBus?.publish(EntityShownToViewer(viewer = viewer, target = target))
        }
        return Result.success(Unit)
    }

    override fun hidePlayer(viewer: PlayerId, target: PlayerId): Result<Unit, VisibilityError> {
        if (viewer == target) return Result.failure(VisibilityError.SameSelfHide)
        if (viewer !in knownViewers) return Result.failure(VisibilityError.UnknownViewer)
        if (target !in knownViewers) return Result.failure(VisibilityError.UnknownTarget)
        val handle = playerHandle(target)
        knownEntities += target.uuid
        val bucket = hiddenByViewer.getOrPut(viewer) { HashMap() }
        val newly = bucket.put(target.uuid, handle) == null
        if (newly) {
            eventBus?.publish(EntityHiddenFromViewer(viewer = viewer, target = handle))
        }
        return Result.success(Unit)
    }

    override fun showPlayer(viewer: PlayerId, target: PlayerId): Result<Unit, VisibilityError> {
        if (viewer == target) return Result.failure(VisibilityError.SameSelfHide)
        if (viewer !in knownViewers) return Result.failure(VisibilityError.UnknownViewer)
        if (target !in knownViewers) return Result.failure(VisibilityError.UnknownTarget)
        val bucket = hiddenByViewer[viewer]
        val removed = bucket?.remove(target.uuid)
        if (bucket != null && bucket.isEmpty()) hiddenByViewer.remove(viewer)
        if (removed != null) {
            eventBus?.publish(EntityShownToViewer(viewer = viewer, target = playerHandle(target)))
        }
        return Result.success(Unit)
    }

    override fun hiddenFromViewer(viewer: PlayerId): Collection<EntityHandle> =
        hiddenByViewer[viewer]?.values?.toList() ?: emptyList()

    override fun visibleToViewers(target: EntityHandle): Collection<PlayerId> {
        val hidingViewers = hiddenByViewer.filterValues { it.containsKey(target.id) }.keys
        return knownViewers - hidingViewers
    }

    private companion object {
        const val PLAYER_BACKEND: String = "player"

        fun playerHandle(target: PlayerId): EntityHandle =
            EntityHandle(id = target.uuid, backendId = PLAYER_BACKEND)
    }
}
