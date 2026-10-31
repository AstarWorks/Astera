package com.astarworks.astera.core.application.port.outbound.animation

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigError
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Plays keyframed skin-rig animations (`content/animations/`, ADR-0250) on a player on their own clock: the clip
 * is sampled once per server tick and pushed to the player's [ISkinRigPuppet] (ADR-0315). Used by lobby emotes
 * (`/astera emote`) and the `play_animation` mechanic ([IPlayerAnimationGateway.playCustomAnimation]). A skill
 * `body` clip does not come through here: the skill engine drives the puppet from its own frame table.
 */
public interface ISkinRigAnimator {

    /** Starts [animation] on [playerId], replacing a rig animation already running. [speed] scales the clip clock (1 = authored). The puppet draws the main-hand stack. */
    public fun play(playerId: PlayerId, animation: CustomAnimationId, speed: Double = 1.0): Result<Unit, SkinRigError>

    /** Blends the running animation out (the only way to end a looping one). */
    public fun stop(playerId: PlayerId): Result<Unit, SkinRigError>

    /** The animation currently running on [playerId] (blending out included), or `null`. */
    public fun active(playerId: PlayerId): CustomAnimationId?
}
