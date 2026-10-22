package com.astarworks.astera.items.application.usecase

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.domain.event.SkillCastRequested
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillTrigger

/**
 * Item-side trigger dispatcher (Wave _items-1, ADR-0073).
 *
 * Listens to vendor-neutral player interactions, looks up the held item's
 * [ItemId], filters its [ItemSkillBinding] list by the incoming [SkillTrigger],
 * and publishes one [SkillCastRequested] event per matching binding for
 * `:plugin:combat`'s `CastSkillUseCase` to consume.
 *
 * ## Cooldown
 *
 * Items plugin only sees Core (per ADR-0022 sibling-isolation matrix); it has
 * no visibility onto `ISkillRegistry.SkillSpec.cooldownTicks`. The binding's
 * [com.astarworks.astera.core.domain.item.ItemSkillBinding.cooldownOverrideTicks]
 * therefore travels on [SkillCastRequested] and Combat's `CastSkillUseCase`
 * applies it as the one and only cooldown gate (the item override wins over
 * the skill's own `cooldown_ticks`).
 *
 * ## Active weapon
 *
 * [onHeldItemChanged] keeps the resource pool's active weapon (which decides
 * the HUD readout and the per-weapon capacity) in sync with the hotbar.
 */
public class HandleItemTriggerUseCase(
    private val items: IItemRegistry,
    private val eventBus: IDomainEventBus,
    private val resourcePool: IResourcePoolService? = null,
) {

    /** The hotbar selection changed; [itemId] is the Astera item now in hand, if any. */
    public fun onHeldItemChanged(playerId: PlayerId, itemId: ItemId?) {
        resourcePool?.setActiveWeapon(playerId, itemId)
    }

    /**
     * @return `true` when the item binds [trigger] (even if every binding was
     *   swallowed by a cooldown) — the caller uses this to decide whether to
     *   cancel the vanilla side effect of the input.
     */
    public fun execute(
        playerId: PlayerId,
        itemId: ItemId,
        trigger: SkillTrigger,
        origin: Vec3,
    ): Boolean {
        val spec = items.find(itemId) ?: return false
        val matchingBindings = spec.skillBindings.filter { it.trigger == trigger }
        if (matchingBindings.isEmpty()) return false

        for (binding in matchingBindings) {
            eventBus.publish(
                SkillCastRequested(
                    actorId = playerId,
                    skillId = binding.skillId,
                    origin = origin,
                    sourceItemId = itemId,
                    cooldownOverrideTicks = binding.cooldownOverrideTicks,
                    phaseOverrides = binding.phaseOverrides,
                    trigger = binding.trigger,
                ),
            )
        }
        return true
    }
}
