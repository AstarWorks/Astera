package com.astarworks.astera.core.domain.animation.skinrig

import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.player.PlayerId

/** Failure modes of [com.astarworks.astera.core.application.port.outbound.animation.ISkinRigAnimator]. */
public sealed class SkinRigError {
    public data class UnknownAnimation(public val animation: CustomAnimationId) : SkinRigError()
    public data class UnknownPlayer(public val playerId: PlayerId) : SkinRigError()
    public data class NotPlaying(public val playerId: PlayerId) : SkinRigError()

    /** Platform failure (off the main thread, entity spawn refused, ...). */
    public data class Platform(public val detail: String) : SkinRigError()
}
