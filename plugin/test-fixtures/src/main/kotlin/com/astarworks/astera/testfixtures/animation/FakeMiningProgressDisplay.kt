package com.astarworks.astera.testfixtures.animation

import com.astarworks.astera.core.application.port.outbound.animation.IMiningProgressDisplay
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.mining.MiningProgressContext
import com.astarworks.astera.core.domain.animation.player.PlayerAnimationError
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IMiningProgressDisplay] for use-case tests (Wave 5animation, ADR-0113).
 *
 * Records every update and clear so tests can assert
 *  - that the mining loop calls `update` per tick with the right
 *    progress snapshot, and
 *  - that the cancellation path correctly clears the HUD.
 *
 * Does **not** wrap an [com.astarworks.astera.core.application.port.outbound.IHudGateway] —
 * tests that want to assert end-to-end HUD calls should use
 * [com.astarworks.astera.core.application.service.animation.InMemoryMiningProgressDisplay] composed
 * with [com.astarworks.astera.testfixtures.FakeHudGateway] instead.
 */
public class FakeMiningProgressDisplay : IMiningProgressDisplay {

    /** Operation history. */
    public sealed interface MiningDisplayOp {
        public data class Updated(val playerId: PlayerId, val context: MiningProgressContext) : MiningDisplayOp
        public data class Cleared(val playerId: PlayerId) : MiningDisplayOp
    }

    private val ops: MutableList<MiningDisplayOp> = mutableListOf()
    private val lastContexts: MutableMap<PlayerId, MiningProgressContext> = mutableMapOf()

    /** Ordered call sequence. */
    public val operations: List<MiningDisplayOp> get() = ops.toList()

    /** Most recent context for [playerId], or null if cleared / never updated. */
    public fun lastContext(playerId: PlayerId): MiningProgressContext? = lastContexts[playerId]

    override fun update(
        playerId: PlayerId,
        context: MiningProgressContext,
    ): Result<Unit, PlayerAnimationError> {
        lastContexts[playerId] = context
        ops += MiningDisplayOp.Updated(playerId, context)
        return Result.success(Unit)
    }

    override fun clear(playerId: PlayerId): Result<Unit, PlayerAnimationError> {
        lastContexts.remove(playerId)
        ops += MiningDisplayOp.Cleared(playerId)
        return Result.success(Unit)
    }

    /** Test cleanup. */
    public fun clear() {
        ops.clear()
        lastContexts.clear()
    }
}
