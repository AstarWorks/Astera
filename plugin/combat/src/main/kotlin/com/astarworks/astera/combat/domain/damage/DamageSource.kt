package com.astarworks.astera.combat.domain.damage

import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.barrier.BarrierId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.combat.domain.projectile.ProjectileId

/**
 * Origin of a [DamageAttempt].
 *
 * Per `principles.md` §12.4, Astera collapses RTM's 8-class event hierarchy
 * into a single damage event plus this sealed source. Each variant carries
 * the IDs and positional context downstream listeners need so that no caller
 * has to perform reverse lookups.
 *
 * ADR-0030 §8 commits Wave 6 BukkitEventAdapter to hijack every vanilla
 * EntityDamageEvent / EntityDamageByEntityEvent / PlayerDeathEvent /
 * EntityDeathEvent and route through here — after Wave 6 every damage and
 * death event in Astera carries a fully-typed DamageSource.
 */
public sealed class DamageSource {

    /** Non-actor damage: drowning, suffocation, generic environmental. */
    public data object Environment : DamageSource()

    /**
     * Direct item hit (melee swing or hitscan from an attacker's equipped
     * item). Renamed from `Weapon` in Wave _items-1 (ADR-0073) — items are
     * now the first-class abstraction; weapons are one flavour of item.
     *
     * [attackerPosition] is optional but required when the resolved damage
     * should produce knockback (the KnockbackModifier needs an origin
     * vector). Wave 6 BukkitEventAdapter fills it from the attacker's
     * `Location` at the time of the event.
     */
    public data class Item(
        val itemId: ItemId,
        val attackerId: PlayerId,
        val attackerPosition: Vec3? = null,
    ) : DamageSource()

    /**
     * Skill-applied damage (melee skill or AOE).
     *
     * [casterPosition] mirrors [Item.attackerPosition] for knockback math.
     */
    public data class Skill(
        val skillId: SkillId,
        val casterId: PlayerId,
        val casterPosition: Vec3? = null,
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

    /**
     * Damage tick produced by a status effect (e.g. BURN per-tick damage).
     *
     * [originPlayerId] is the player who applied the effect, where known —
     * environmental burn from lava has it null.
     */
    public data class StatusEffectTick(
        val effectId: StatusEffectId,
        val originPlayerId: PlayerId? = null,
    ) : DamageSource()

    /**
     * Hit from a flying projectile. [origin] preserves whether the projectile
     * was fired by a weapon or by a PROJECTILE-archetype skill, so analytics
     * and balance tooling can distinguish them without inspecting the owner's
     * inventory or active cast.
     */
    public data class Projectile(
        val projectileId: ProjectileId,
        val ownerId: PlayerId,
        val origin: ProjectileOrigin,
    ) : DamageSource()

    /**
     * Damage from a barrier (custom enclosure block). Reserved for Wave 5d;
     * declared here so Wave 5a's DamageRule pipeline can exhaustively `when`
     * over DamageSource without revision later.
     */
    public data class Barrier(val barrierId: BarrierId) : DamageSource()
}

/** Discriminator for [DamageSource.Projectile.origin]. */
public sealed class ProjectileOrigin {
    public data class FromItem(val itemId: ItemId) : ProjectileOrigin()
    public data class FromSkill(val skillId: SkillId) : ProjectileOrigin()
}

/**
 * Convenience: derive the responsible player from a [DamageSource], when one
 * exists. Used by `ApplyDamageUseCase` to attribute crit / knockback / FF
 * events to an attacker.
 */
public fun DamageSource.attackerIdOrNull(): PlayerId? = when (this) {
    is DamageSource.Item -> attackerId
    is DamageSource.Skill -> casterId
    is DamageSource.Projectile -> ownerId
    is DamageSource.StatusEffectTick -> originPlayerId
    DamageSource.Environment,
    is DamageSource.Fall,
    is DamageSource.Explosion,
    is DamageSource.Barrier,
    -> null
}
