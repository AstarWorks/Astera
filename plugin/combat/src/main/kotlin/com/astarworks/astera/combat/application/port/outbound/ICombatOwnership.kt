package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/** Inline on the target owner; only foreign targets may be deferred one tick. */
public interface ICombatOwnership {
    public fun deferIfForeign(target: PlayerId, action: () -> Unit): Boolean
    /** Logs and counts an ownership violation; callers must skip the target on false. */
    public fun checkOwned(target: PlayerId): Boolean
}
