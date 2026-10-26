package com.astarworks.astera.core.domain.animation.mining

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.ToolType

/**
 * Per-player mining progress snapshot (Wave 5animation, ADR-0113).
 *
 * Produced by the per-tick mining loop and handed to
 * [com.astarworks.astera.core.application.port.outbound.animation.IMiningProgressDisplay]
 * which renders an ActionBar / BossBar HUD (Wave 5j HUD foundation).
 *
 * The block animation gateway ([com.astarworks.astera.core.application.port.outbound.block.IBlockBreakAnimationGateway])
 * handles the world-side cracked-texture rendering; this context drives
 * the *player-facing UI* showing time-to-break + tool effectiveness so
 * the player understands why mining is fast (right tool) or slow (wrong
 * tool / unbreakable).
 *
 * # Invariants
 *
 * - [currentHp] is clamped to `0..maxHp` (init block enforces).
 * - [maxHp] must be `>= 1` (otherwise the block has no break-state).
 * - [etaTicks] is `>= 0` — a fully-broken block (`currentHp = 0`) reports
 *   `etaTicks = 0` so renderers can treat zero as "done".
 *
 * # Why a value record, not an event
 *
 * Mining progress updates fire 1-per-tick per active miner — emitting a
 * domain event each tick would saturate the bus. The display port instead
 * receives the snapshot directly. When mining *starts* / *completes* /
 * *cancels* the lifecycle events ([com.astarworks.astera.core.domain.event.MiningStarted] /
 * [com.astarworks.astera.core.domain.event.MiningCompleted] /
 * [com.astarworks.astera.core.domain.event.MiningCancelled]) fire on the
 * bus.
 */
public data class MiningProgressContext(
    public val playerId: PlayerId,
    public val blockPosition: Vec3,
    public val currentHp: Int,
    public val maxHp: Int,
    public val toolType: ToolType,
    public val etaTicks: Long,
) {
    init {
        require(maxHp >= 1) {
            "MiningProgressContext.maxHp must be >= 1, was $maxHp"
        }
        require(currentHp in 0..maxHp) {
            "MiningProgressContext.currentHp must be in 0..$maxHp, was $currentHp"
        }
        require(etaTicks >= 0L) {
            "MiningProgressContext.etaTicks must be >= 0, was $etaTicks"
        }
    }

    /** Fraction `0.0..1.0` of HP remaining (1.0 = intact, 0.0 = broken). */
    public val hpFraction: Double
        get() = currentHp.toDouble() / maxHp.toDouble()
}
