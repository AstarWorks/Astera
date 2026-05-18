package com.astarworks.astera.match.application.usecase.barrier

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.barrier.BarrierId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.match.application.port.outbound.IBarrierGateway
import com.astarworks.astera.match.domain.barrier.BarrierStatus

/**
 * Destroy a tracked [com.astarworks.astera.match.domain.barrier.Barrier]
 * (Wave 5d).
 *
 * Transition order:
 * 1. Flip status to [BarrierStatus.Destroyed]. Subscribers that observe
 *    `updateStatus` (future Wave 6 visual layer, HUD widgets) see the
 *    terminal state before the entry disappears.
 * 2. `despawn` from the gateway. After this point [IBarrierGateway.all]
 *    will not include the barrier.
 *
 * Returns [Result.Failure] with [DestroyBarrierError.NotFound] if the id
 * is unknown — destroying a barrier that never existed (or already
 * despawned) is a caller bug, but a recoverable one; we surface it as a
 * typed error rather than throwing.
 *
 * No domain event is published here in Wave 5d. The companion event type
 * (`BarrierDestroyed`) lands with the Wave 6 visual / block-tracking
 * integration so HUD / sound / score listeners can subscribe in a single
 * coherent pass.
 */
public class DestroyBarrierUseCase(
    private val gateway: IBarrierGateway,
) {

    public fun execute(id: BarrierId): Result<Unit, DestroyBarrierError> {
        if (gateway.all().none { it.id == id }) {
            return Result.failure(DestroyBarrierError.NotFound(id))
        }
        gateway.updateStatus(id, BarrierStatus.Destroyed)
        gateway.despawn(id)
        return Result.success(Unit)
    }
}

/** Sealed error hierarchy for [DestroyBarrierUseCase]. */
public sealed class DestroyBarrierError {
    public abstract val messageKey: MessageKey

    /** No barrier with [id] was tracked by the gateway. */
    public data class NotFound(val id: BarrierId) : DestroyBarrierError() {
        override val messageKey: MessageKey = MessageKey("astera.match.barrier.error.not-found")
    }
}
