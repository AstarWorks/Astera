package com.astarworks.astera.items.application.usecase

import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.cooldown.CooldownKey
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
 * ## Cooldown gating
 *
 * Items plugin only sees Core (per ADR-0022 sibling-isolation matrix); it has
 * no visibility onto `ISkillRegistry.SkillSpec.cooldownTicks`. Therefore:
 *
 *  - If a binding sets [com.astarworks.astera.core.domain.item.ItemSkillBinding.cooldownOverrideTicks],
 *    Items applies the cooldown gate here (so designers can bypass the
 *    underlying skill's default).
 *  - Otherwise the event flies unconditionally and Combat's `CastSkillUseCase`
 *    enforces the skill's own cooldown using the same shared [ICooldownTracker].
 *
 * The Items-side cooldown key is [CooldownKey.Custom] with namespace `item` +
 * id `"<item-id>:<skill-id>"`; this keeps it distinct from `CooldownKey.Skill`
 * which Combat owns. Combat's gate ignores Item-namespaced entries.
 */
public class HandleItemTriggerUseCase(
    private val items: IItemRegistry,
    private val cooldowns: ICooldownTracker,
    private val eventBus: IDomainEventBus,
) {

    public fun execute(
        playerId: PlayerId,
        itemId: ItemId,
        trigger: SkillTrigger,
        origin: Vec3,
    ) {
        val spec = items.find(itemId) ?: return
        val matchingBindings = spec.skillBindings.filter { it.trigger == trigger }
        if (matchingBindings.isEmpty()) return

        for (binding in matchingBindings) {
            val overrideTicks = binding.cooldownOverrideTicks
            if (overrideTicks != null) {
                val cooldownKey: CooldownKey = CooldownKey.Custom(
                    namespace = "item",
                    id = "${itemId.value}:${binding.skillId.value}",
                )
                if (cooldowns.isCoolingDown(playerId, cooldownKey)) {
                    // Override gate: still cooling down, skip this binding.
                    continue
                }
                cooldowns.start(playerId, cooldownKey, overrideTicks.toLong())
            }

            eventBus.publish(
                SkillCastRequested(
                    actorId = playerId,
                    skillId = binding.skillId,
                    origin = origin,
                    sourceItemId = itemId,
                    cooldownOverrideTicks = overrideTicks,
                ),
            )
        }
    }
}
