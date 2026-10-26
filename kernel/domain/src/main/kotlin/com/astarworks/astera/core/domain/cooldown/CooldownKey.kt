package com.astarworks.astera.core.domain.cooldown

/**
 * Opaque key for cooldown tracking. Specific feature plugins wrap their
 * domain IDs as String inside concrete variants.
 *
 * Per Konsist (ADR-0022), core cannot import sibling-plugin domain IDs
 * directly — so CooldownKey carries a String payload + a discriminator.
 *
 * [raw] is the canonical composite string for map keying and logging.
 */
public sealed class CooldownKey {
    public abstract val raw: String

    public data class Weapon(val weaponId: String) : CooldownKey() {
        override val raw: String = "weapon:$weaponId"
    }

    public data class Skill(val skillId: String) : CooldownKey() {
        override val raw: String = "skill:$skillId"
    }

    public data class Status(val statusEffectId: String) : CooldownKey() {
        override val raw: String = "status:$statusEffectId"
    }

    public data class Custom(val namespace: String, val id: String) : CooldownKey() {
        override val raw: String = "$namespace:$id"
    }
}
