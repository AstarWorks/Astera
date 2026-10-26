package com.astarworks.astera.core.domain.presentation

import com.astarworks.astera.core.domain.player.PlayerId
import java.util.UUID

/**
 * What a presentation lease takes away from the real player so a stand-in (skill performance, emote) can draw it instead
 * (ADR-0276). A slot is given back only when the last lease covering it is released.
 *
 * @property hand main-hand item (the owner's own hand shows an empty-model stand-in, everyone else sees air).
 * @property offhand armor blanked for other viewers only (the owner never draws them in first person).
 * @property body the real body is invisible (entity data, inside the same bundle as the equipment).
 * @property pinPose the player's pose is pinned STANDING so passenger attachment height cannot move.
 */
public data class PresentationMask(
    public val hand: Boolean = false,
    public val offhand: Boolean = false,
    public val armor: Boolean = false,
    public val body: Boolean = false,
    public val pinPose: Boolean = false,
) {
    public val isEmpty: Boolean get() = !hand && !offhand && !armor && !body && !pinPose

    public operator fun plus(o: PresentationMask): PresentationMask = PresentationMask(
        hand || o.hand, offhand || o.offhand, armor || o.armor, body || o.body, pinPose || o.pinPose,
    )

    public companion object {
        public val NONE: PresentationMask = PresentationMask()
    }
}

/** Who holds a lease. A [PERFORMANCE] revokes an [EMOTE]; nothing preempts a performance. */
public enum class PresentationKind { PERFORMANCE, EMOTE, TIMED }

/** Which viewers a release is sent to. The owner is timed apart (first person updates only on the client's own tick). */
public enum class PresentationAudience { ALL, OWNER, OTHERS }

/** One claimed presentation of [owner]; opaque to callers. */
public class PresentationLease(
    public val id: Long,
    public val owner: PlayerId,
    public val mask: PresentationMask,
    public val kind: PresentationKind,
)

/**
 * A display in a presentation bundle. [viewRange] is the range a staged display takes when revealed (`null` = leave it);
 * [beforeSend] runs when the bundle is built at server tick end, after the entity tracker's flush, and applies the display's
 * pending transform so it travels inside the bundle and not as a separate tracker packet. [hiddenFromOwner] keeps the display
 * out of the owner's own client for its whole life (the skin rig's head bone sits on the owner's first-person camera).
 */
public class PresentationDisplay(
    public val id: UUID,
    public val viewRange: Float?,
    public val hiddenFromOwner: Boolean = false,
    public val beforeSend: () -> Unit = {},
)

public sealed class PresentationError {
    /** A lease that may not be preempted holds the player. */
    public data class Busy(public val holder: PresentationKind) : PresentationError()

    public data object OwnerOffline : PresentationError()
}
