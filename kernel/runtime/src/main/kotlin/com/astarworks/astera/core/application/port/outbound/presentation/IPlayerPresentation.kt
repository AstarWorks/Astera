package com.astarworks.astera.core.application.port.outbound.presentation

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.PresentationAudience
import com.astarworks.astera.core.domain.presentation.PresentationDisplay
import com.astarworks.astera.core.domain.presentation.PresentationError
import com.astarworks.astera.core.domain.presentation.PresentationKind
import com.astarworks.astera.core.domain.presentation.PresentationLease
import com.astarworks.astera.core.domain.presentation.PresentationMask
import java.util.UUID

/**
 * The only code that hides or shows a player's held item, armor and body (ADR-0276). A skill performance, an emote or a
 * plain timed hide claims a [PresentationLease]; the union of all leases of one owner is what viewers see, and a slot is
 * restored only when the last lease covering it is released. Every transition (reveal, release) reaches each viewer as ONE
 * client packet bundle, so no frame can show the real weapon next to a stand-in, or neither.
 *
 * Main thread only.
 */
public interface IPlayerPresentation {

    /**
     * Claims [mask] for [owner]. Nothing is sent until [reveal]. [onRevoked] runs when the lease is taken away without
     * [release] (owner quit, world change, preempted by a performance); the holder must drop its own state.
     */
    public fun claim(
        owner: PlayerId,
        mask: PresentationMask,
        kind: PresentationKind,
        onRevoked: () -> Unit = {},
    ): Result<PresentationLease, PresentationError>

    /**
     * Applies the lease's mask and makes the staged [displays] visible in the same client bundle (at the end of this
     * server tick, after the entity tracker's flush). With no displays it is a plain hide.
     */
    public fun reveal(lease: PresentationLease, displays: List<PresentationDisplay> = emptyList())

    /**
     * Applies [displays]' pending transforms ([PresentationDisplay.beforeSend]) and sends their entity data (a pose-to-pose cut)
     * to every tracking viewer as one bundle at the end of this tick, so hand and weapon jump in the same client step.
     */
    public fun flushBundled(owner: PlayerId, displays: List<PresentationDisplay>)

    /**
     * Gives the slots back to [audience]. The owner leg restores the hand and scales [displays] to zero (same client tick);
     * the others leg restores and removes [displays] from the client in one bundle. The caller removes the entities
     * afterwards (a tick later for the owner leg). The lease ends once both legs were released.
     */
    public fun release(lease: PresentationLease, displays: List<UUID> = emptyList(), audience: PresentationAudience = PresentationAudience.ALL)

    /** The kind of the strongest lease currently holding [owner], or `null`. */
    public fun holder(owner: PlayerId): PresentationKind?

    /** Restores everything (plugin disable). */
    public fun shutdown()
}
