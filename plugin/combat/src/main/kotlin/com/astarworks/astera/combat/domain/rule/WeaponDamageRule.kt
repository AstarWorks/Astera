package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.weapons.domain.weapon.DamageProfile

/**
 * Pure damage calculation. Phase 1 returns base damage as-is; Phase 2 will add
 * headshot multipliers, status-effect modifiers, and defense reduction.
 *
 * Keep this object stateless so it stays trivially testable from JUnit without
 * any Paper / Bukkit harness.
 */
object WeaponDamageRule {
    fun calculate(profile: DamageProfile): Double = profile.base
}
