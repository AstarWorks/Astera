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

    /** Starts [animation] on [playerId], replacing a rig animation already running. [speed] scales the clip clock (1 = authored); [holds] stand the clock still where a hit-stopped rig owns the clip. [heldItem] false = the puppet draws no main-hand stack: a skill performance draws its own single weapon (its rig), so a second one on the puppet shows the weapon twice. The rig is visible to every viewer including [playerId] (founder 10/31: nothing is hidden from the caster). */
    public fun play(playerId: PlayerId, animation: CustomAnimationId, speed: Double = 1.0, holds: List<com.astarworks.astera.core.domain.animation.skinrig.SkinRigHold> = emptyList(), heldItem: Boolean = true): Result<Unit, SkinRigError>

    /** Blends the running animation out (the only way to end a looping one). */
    public fun stop(playerId: PlayerId): Result<Unit, SkinRigError>

    /** The animation currently running on [playerId] (blending out included), or `null`. */
    public fun active(playerId: PlayerId): CustomAnimationId?
}
