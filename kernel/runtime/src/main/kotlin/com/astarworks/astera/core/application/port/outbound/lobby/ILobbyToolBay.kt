package com.astarworks.astera.core.application.port.outbound.lobby

import com.astarworks.astera.core.domain.lobby.LobbyToolBayPlan
import com.astarworks.astera.core.domain.world.WorldId

/**
 * The lobby tool test bay (MAP-A6): the one place in the lobby where blocks may be broken, by hand or by tool
 * skills. Edits inside [plan] `.region` are journaled ([remember]) and restored after the bay has been idle for
 * `idleResetTicks` or at once by [reset]. Present only on backends that host the lobby.
 */
public interface ILobbyToolBay {
    public val world: WorldId
    public val plan: LobbyToolBayPlan

    /** True when the block (x, y, z) of [inWorld] is inside the editable bay region. */
    public fun contains(inWorld: WorldId?, x: Int, y: Int, z: Int): Boolean

    /**
     * Edit policy of the lobby for a block: `true` inside the bay, `false` elsewhere in the lobby slot (the lobby
     * is protected), `null` when the block is not part of the lobby (arenas, other worlds), so other rules decide.
     */
    public fun editPolicy(inWorld: WorldId?, x: Int, y: Int, z: Int): Boolean?

    /** Records the current state of a bay block before an edit; a no-op outside the bay. Call on the block's region thread. */
    public fun remember(inWorld: WorldId?, x: Int, y: Int, z: Int)

    /** Restores every journaled block now; returns how many were queued. */
    public fun reset(): Int
}
