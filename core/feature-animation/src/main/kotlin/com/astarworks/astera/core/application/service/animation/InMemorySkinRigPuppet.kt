package com.astarworks.astera.core.application.service.animation

import com.astarworks.astera.core.application.port.outbound.animation.ISkinRigPuppet
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.RigAnchor
import com.astarworks.astera.core.domain.animation.skinrig.RigFrame
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigError
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Headless [ISkinRigPuppet] (ADR-0315): remembers what is shown and the last pose pushed, renders nothing. Game-sim and
 * the command tree run on it; `PaperSkinRigPuppet` replaces it at priority 100 on a server.
 */
public class InMemorySkinRigPuppet : ISkinRigPuppet {

    /** The last pose pushed to a shown puppet: what a scenario reads back. */
    public class Shown(public val animation: SkinRigAnimation, public val heldItem: Boolean) {
        public var frame: RigFrame = RigFrame.REST
        public var anchor: RigAnchor? = null
        public var poses: Int = 0
    }

    private val shown = ConcurrentHashMap<PlayerId, Shown>()

    override fun show(playerId: PlayerId, animation: SkinRigAnimation, heldItem: Boolean): Result<Unit, SkinRigError> {
        shown[playerId] = Shown(animation, heldItem)
        return Result.success(Unit)
    }

    override fun pose(playerId: PlayerId, frame: RigFrame, anchor: RigAnchor?) {
        val s = shown[playerId] ?: return
        s.frame = frame
        s.anchor = anchor
        s.poses++
    }

    override fun hide(playerId: PlayerId) {
        shown.remove(playerId)
    }

    override fun shown(playerId: PlayerId): CustomAnimationId? = shown[playerId]?.animation?.id

    /** The shown puppet of [playerId] with its last pose, or null. */
    public fun state(playerId: PlayerId): Shown? = shown[playerId]
}
