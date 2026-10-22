package com.astarworks.astera.core.domain.cooldown

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSkillBinding
import com.astarworks.astera.core.domain.skill.SkillTrigger

/**
 * Pure rules of the native hotbar cooldown sweep (ADR-0195).
 *
 * Every Astera item that binds a skill carries a `use_cooldown` component whose
 * group is [groupKey]; the client draws the vanilla grey sweep over the slot
 * for as long as the server keeps that group on cooldown. The sweep shows one
 * clock per item: the *primary* binding's cooldown, or a reload.
 */
public object ItemCooldownSweep {

    /** Vanilla group key of [itemId] (`astera:cooldown/<item-id>`); unique per item. */
    public fun groupKey(itemId: ItemId): String = "astera:cooldown/${itemId.value}"

    /** What a player calls "the weapon's main input", most likely first. */
    private val PRIMARY_ORDER: List<SkillTrigger> = listOf(
        SkillTrigger.RIGHT_CLICK,
        SkillTrigger.LEFT_CLICK,
        SkillTrigger.SHOOT,
        SkillTrigger.HOLD,
        SkillTrigger.SNEAK_RIGHT,
        SkillTrigger.SNEAK_LEFT,
    )

    /** The binding trigger whose cooldown the sweep follows; `null` when the item has no such binding. */
    public fun primaryTrigger(bindings: List<ItemSkillBinding>): SkillTrigger? {
        val bound = bindings.map { it.trigger }.toSet()
        return PRIMARY_ORDER.firstOrNull { it in bound }
    }

    /**
     * Ticks the sweep must run after a skill cooldown started, or `0` for "do
     * not touch the sweep".
     *
     * @param primary [primaryTrigger] of the item
     * @param trigger the input that cast the skill
     * @param cooldownTicks the effective cooldown (binding override already applied)
     * @param reloadTicks non-null for a reload skill: the reload shows as a sweep too
     */
    public fun sweepTicks(
        primary: SkillTrigger?,
        trigger: SkillTrigger?,
        cooldownTicks: Int,
        reloadTicks: Int?,
    ): Int {
        if (reloadTicks != null) return maxOf(cooldownTicks, reloadTicks).coerceAtLeast(0)
        if (primary == null || trigger != primary) return 0
        return cooldownTicks.coerceAtLeast(0)
    }
}
