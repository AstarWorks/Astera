package com.astarworks.astera.items.application.usecase

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.cooldown.ItemCooldownSweep
import com.astarworks.astera.core.domain.event.SkillCooldownStarted
import com.astarworks.astera.items.application.port.outbound.IItemCooldownSweepGateway

/**
 * Turns [SkillCooldownStarted] into the native hotbar sweep (ADR-0195): the
 * cooldown of the item's primary binding, or a reload, runs as the grey sweep
 * over the slot for exactly its length (the binding's `cooldown_override_ticks`
 * is already applied in the event).
 */
public class SweepItemOnSkillCooldownUseCase(
    private val items: IItemRegistry,
    private val gateway: IItemCooldownSweepGateway,
) {

    public fun handle(event: SkillCooldownStarted) {
        val itemId = event.sourceItemId ?: return
        val spec = items.find(itemId) ?: return
        val primary = ItemCooldownSweep.primaryTrigger(spec.skillBindings)
        val ticks = ItemCooldownSweep.sweepTicks(primary, event.trigger, event.durationTicks, event.reloadTicks)
        if (ticks > 0) gateway.sweep(event.playerId, itemId, ticks)
    }
}
