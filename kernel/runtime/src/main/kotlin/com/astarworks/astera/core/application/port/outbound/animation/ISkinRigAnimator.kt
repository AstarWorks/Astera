package com.astarworks.astera.core.application.port.outbound.animation

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigError
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Plays keyframed skin-rig animations (`content/animations/`, ADR-0250) on a player: the player's
 * body is swapped for a Display-entity rig wearing their own skin while the animation runs.
 *
 * Owned by `plugin:core` because both combat skills (the `play_animation` mechanic, via
 * [IPlayerAnimationGateway.playCustomAnimation]) and lobby emotes (`/astera emote`) drive it.
 */
public interface ISkinRigAnimator {

    /** Starts [animation] on [playerId], replacing a rig animation already running. */
    public fun play(playerId: PlayerId, animation: CustomAnimationId): Result<Unit, SkinRigError>

    /** Blends the running animation out (the only way to end a looping one). */
    public fun stop(playerId: PlayerId): Result<Unit, SkinRigError>

    /** The animation currently running on [playerId] (blending out included), or `null`. */
    public fun active(playerId: PlayerId): CustomAnimationId?
}
