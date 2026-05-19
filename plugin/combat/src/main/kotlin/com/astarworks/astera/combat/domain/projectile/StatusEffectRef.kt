package com.astarworks.astera.combat.domain.projectile

/**
 * Opaque string reference to a status effect, owned by `:plugin:weapons`.
 *
 * The full `StatusEffectId` value type and the matching registry live in
 * `:plugin:combat` (ADR-0022 Phase 4). To keep Weapons free of a Combat
 * dependency, `OnHitEffect.InflictStatus` carries this opaque string ref;
 * the Combat plugin's projectile-hit subscriber is responsible for resolving
 * it to its own `StatusEffectId` and applying the effect.
 *
 * Naming rule: lower-kebab-case (`burn`, `gravity-up`). Enforced at
 * construction so YAML typos surface immediately.
 */
@JvmInline
public value class StatusEffectRef(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "StatusEffectRef must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
