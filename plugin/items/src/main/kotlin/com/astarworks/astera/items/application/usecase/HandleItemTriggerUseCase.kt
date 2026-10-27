package com.astarworks.astera.items.application.usecase

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchLevelGate
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.domain.event.SkillCastRequested
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillTesterItems
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
 * ## Norma level gate
 *
 * A binding with `minNormaLevel > 1` is skipped (with feedback from [IMatchLevelGate]) while the caster is below
 * that in-match level; the other bindings of the trigger still fire.
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
    /** Resolved lazily: Match may register after Items enables. `null` result = no Norma gate. */
    private val levelGate: () -> IMatchLevelGate? = { null },
) {

    /** The hotbar selection changed; [itemId] is the Astera item now in hand, if any. */
    public fun onHeldItemChanged(playerId: PlayerId, itemId: ItemId?) {
        resourcePool?.setActiveWeapon(playerId, itemId)
    }

    /**
     * `skill-tester-<skill>` items (F2-5, ADR-0196) are not in the registry: the id itself
     * names the skill, bound to right click.
     */
    private fun castSkillTester(playerId: PlayerId, itemId: ItemId, trigger: SkillTrigger, origin: Vec3): Boolean {
        val skill = SkillTesterItems.skillOf(itemId) ?: return false
        if (trigger != SkillTrigger.RIGHT_CLICK) return false
        eventBus.publish(
            SkillCastRequested(
                actorId = playerId,
                skillId = skill,
                origin = origin,
                sourceItemId = itemId,
                cooldownOverrideTicks = null,
            ),
        )
        return true
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
        val spec = items.find(itemId) ?: return castSkillTester(playerId, itemId, trigger, origin)
        val matchingBindings = spec.skillBindings.filter { it.trigger == trigger }
        if (matchingBindings.isEmpty()) return false

        for (binding in matchingBindings) {
            // Norma level gate (ADR-0288): a locked binding is refused with feedback; the input is still swallowed.
            if (binding.minNormaLevel > 1 && levelGate()?.denyIfLocked(playerId, binding.minNormaLevel) == true) continue
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
