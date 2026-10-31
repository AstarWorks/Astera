package com.astarworks.astera.core.application.port.outbound.animation

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.RigAnchor
import com.astarworks.astera.core.domain.animation.skinrig.RigFrame
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigError
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * The driven skin-rig puppet (ADR-0315): the Display-entity body that stands in for a player (ADR-0250), shown and
 * posed from outside, one [RigFrame] per tick. It has no clock of its own: whoever shows it (the skill engine from
 * its frame table, [ISkinRigAnimator] for an emote) pushes every pose, so the body the viewers see is exactly the
 * body the pusher computed with - same clip tick, same yaw, same anchor.
 */
public interface ISkinRigPuppet {

    /**
     * Replaces [playerId]'s body with the rig at [animation]'s rest pose (its `lock_movement` applies while shown). A rig
     * already shown is torn down first. [heldItem] false = no main-hand stack on the puppet: a skill performance draws
     * its own single weapon (its rig), so a second one on the puppet shows the weapon twice. Visible to every viewer,
     * [playerId] included (founder 10/31: nothing is hidden from the caster).
     */
    public fun show(playerId: PlayerId, animation: SkinRigAnimation, heldItem: Boolean): Result<Unit, SkinRigError>

    /**
     * Shows [frame] on [playerId]'s puppet this tick, standing at [anchor] (null = where the player is, facing the
     * player's own look). Moves and re-poses every display synchronously in the same call: no lag between the body and
     * what the pusher drew from the same frame. A player without a shown puppet is a no-op.
     */
    public fun pose(playerId: PlayerId, frame: RigFrame, anchor: RigAnchor?)

    /** Removes [playerId]'s puppet and gives the real body back. No puppet shown is a no-op. */
    public fun hide(playerId: PlayerId)

    /** The animation whose puppet is shown on [playerId], or `null`. */
    public fun shown(playerId: PlayerId): CustomAnimationId?
}
