package com.astarworks.astera.combat.domain.damage

import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.barrier.BarrierId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId
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
 *
 * Wave 5m (ADR-0088) widened the `Environment` arm from a singleton into a
 * nested sealed class hosting the named environmental causes
 * (Fall / Drown / Fire / Lightning / Void / Suffocation / Generic). The
 * top-level `DamageSource.Fall` was hoisted under `Environment` as part of
 * the same change (pre-release breaking rename — no compat shim).
 */
public sealed class DamageSource {

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

    /**
     * Non-actor damage hierarchy (Wave 5m / ADR-0088).
     *
     * Wave 6a `BukkitEventAdapter` will translate Bukkit's
     * `EntityDamageEvent.DamageCause` to one of these variants. Wave 5m
     * commits the surface so combat code can `when`-exhaustively over the
     * full environmental catalogue ahead of the Paper hijack.
     *
     * Use [Generic] for damage causes that do not match a named variant
     * (custom datapacks, magic, etc.); previously this was a top-level
     * `DamageSource.Environment` singleton.
     */
    public sealed class Environment : DamageSource() {

        /**
         * Fall damage. Carries fall [distance] so the damage rule can
         * compute magnitude. Hoisted from top-level `DamageSource.Fall`
         * by Wave 5m (ADR-0088).
         */
        public data class Fall(public val distance: Double) : Environment() {
            init {
                require(distance >= 0.0) { "Environment.Fall.distance must be >= 0: $distance" }
            }
        }

        /** Drowning damage. [remainingAir] is the player's air supply at hit time. */
        public data class Drown(public val remainingAir: Int) : Environment() {
            init {
                require(remainingAir >= 0) {
                    "Environment.Drown.remainingAir must be >= 0: $remainingAir"
                }
            }
        }

        /**
         * Fire / lava damage. [ticksOnFire] = how many ticks the entity has
         * been burning at the time of this tick (0 for the initial hit).
         */
        public data class Fire(public val ticksOnFire: Int) : Environment() {
            init {
                require(ticksOnFire >= 0) {
                    "Environment.Fire.ticksOnFire must be >= 0: $ticksOnFire"
                }
            }
        }

        /** Lightning strike damage (vanilla `EntityDamageEvent.DamageCause.LIGHTNING`). */
        public data object Lightning : Environment()

        /** Falling below the world's minimum build limit. */
        public data object Void : Environment()

        /** Suffocation inside a block (head stuck in solid). */
        public data object Suffocation : Environment()

        /**
         * Any non-actor damage cause that does not match a named variant.
         * Replaces the legacy top-level `DamageSource.Environment`
         * singleton; callers that previously wrote `DamageSource.Environment`
         * should write `DamageSource.Environment.Generic`.
         */
        public data object Generic : Environment()
    }
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
    is DamageSource.Explosion,
    is DamageSource.Barrier,
    is DamageSource.Environment,
    -> null
}
