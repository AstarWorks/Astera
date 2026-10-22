package com.astarworks.astera.core.adapter.paper.event

/**
 * Marks "a skill mechanic is applying damage right now" so Bukkit listeners can
 * tell it apart from the player's own melee swing.
 *
 * Skill damage to mobs goes through `LivingEntity.damage(amount, caster)`, which
 * raises `EntityDamageByEntityEvent(ENTITY_ATTACK)` with the caster as damager —
 * indistinguishable from a real left click. Without this marker the items input
 * listener would treat every skill hit as a left_click and cast the held item's
 * other bound skills. Reentrancy-safe (counter); main thread only.
 */
public object SkillDamageScope {

    private var depth: Int = 0

    /** True while a skill-originated damage call is on the stack. */
    public val active: Boolean get() = depth > 0

    public fun <T> around(block: () -> T): T {
        depth++
        try {
            return block()
        } finally {
            depth--
        }
    }
}
