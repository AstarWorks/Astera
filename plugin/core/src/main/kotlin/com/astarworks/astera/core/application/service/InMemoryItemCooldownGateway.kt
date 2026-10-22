package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IItemCooldownGateway
import com.astarworks.astera.core.application.port.outbound.ItemCooldownError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IItemCooldownGateway] (priority 0): records the sweeps it was
 * asked to draw, so the simulator and tests can assert on them.
 */
public class InMemoryItemCooldownGateway : IItemCooldownGateway {

    /** One requested sweep. */
    public data class Sweep(val playerId: PlayerId, val ticks: Int)

    private val _sweeps: MutableList<Sweep> = mutableListOf()

    public val sweeps: List<Sweep> get() = _sweeps.toList()

    override fun showHeldItemCooldown(playerId: PlayerId, ticks: Int): Result<Unit, ItemCooldownError> {
        if (ticks < 1) return Result.failure(ItemCooldownError.InvalidTicks(ticks))
        _sweeps += Sweep(playerId, ticks)
        return Result.success(Unit)
    }
}
