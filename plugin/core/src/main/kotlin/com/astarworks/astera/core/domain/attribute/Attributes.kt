package com.astarworks.astera.core.domain.attribute

/**
 * Stable registry of well-known [AttributeKey]s (ADR-0058 §2).
 *
 * Feature plugins may add their own keys (e.g. Wave 5c's
 * `Attributes.GENERATOR_ENERGY = AttributeKey("generator.energy")`); the
 * namespace prefix prevents collision. The constants here are the ones
 * Core, Combat, and the upcoming Match / HUD code reach for by name.
 *
 * Persistence (Wave 5e V14) backs these JSONB-encoded; the string key is
 * the natural column.
 */
public object Attributes {
    // ---- Stats ---------------------------------------------------------
    public val MAX_HP: AttributeKey = AttributeKey("stat.max_hp")
    public val CURRENT_HP: AttributeKey = AttributeKey("stat.current_hp")
    public val MOVEMENT_SPEED: AttributeKey = AttributeKey("stat.movement_speed")
    public val ATTACK_DAMAGE: AttributeKey = AttributeKey("stat.attack_damage")

    // ---- Per-attribute resists ----------------------------------------
    // Wave 5e migrates ArmorReduction.perAttribute (Wave 5a-fix transient
    // form) into resist.* keys on the wearer's container.
    public val RESIST_PHYSICAL: AttributeKey = AttributeKey("resist.physical")
    public val RESIST_FIRE: AttributeKey = AttributeKey("resist.fire")
    public val RESIST_ELECTRIC: AttributeKey = AttributeKey("resist.electric")
    public val RESIST_WIND: AttributeKey = AttributeKey("resist.wind")
    public val RESIST_WATER: AttributeKey = AttributeKey("resist.water")
    public val RESIST_EARTH: AttributeKey = AttributeKey("resist.earth")

    // ---- Resources -----------------------------------------------------
    public val MANA_CURRENT: AttributeKey = AttributeKey("resource.mana_current")
    public val MANA_MAX: AttributeKey = AttributeKey("resource.mana_max")
    public val MANA_REGEN_PER_SEC: AttributeKey = AttributeKey("resource.mana_regen_per_sec")

    // ---- Modifiers -----------------------------------------------------
    public val COOLDOWN_REDUCTION: AttributeKey = AttributeKey("modifier.cooldown_reduction")
}
