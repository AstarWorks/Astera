package com.astarworks.astera.domain.model.damage

import com.astarworks.astera.domain.model.geometry.Vec3
import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.skill.SkillId
import com.astarworks.astera.domain.model.weapon.WeaponId

/**
 * Origin of a [DamageAttempt].
 *
 * Per `principles.md` §12.4, Astera collapses RTM's 8-class event hierarchy
 * into a single damage event plus this sealed source. Each variant carries
 * only the data its kind needs; consumers `when`-exhaust on it without
 * reflection.
 */
public sealed class DamageSource {

    /** Non-actor damage: drowning, suffocation, generic environmental. */
    public data object Environment : DamageSource()

    public data class Weapon(
        val weaponId: WeaponId,
        val attackerId: PlayerId,
    ) : DamageSource()

    public data class Skill(
        val skillId: SkillId,
        val casterId: PlayerId,
    ) : DamageSource()

    /**
     * Fall damage. Distinct from [Environment] because the damage rule needs
     * fall distance to compute magnitude.
     */
    public data class Fall(val distance: Double) : DamageSource() {
        init {
            require(distance >= 0.0) { "Fall.distance must be >= 0: $distance" }
        }
    }

    public data class Explosion(
        val center: Vec3,
        val radius: Double,
    ) : DamageSource() {
        init {
            require(radius >= 0.0) { "Explosion.radius must be >= 0: $radius" }
        }
    }
}
