package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.IEntityDamageGateway
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.damage.attackerIdOrNull
import com.astarworks.astera.core.adapter.paper.event.SkillDamageScope
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

/**
 * Paper [IEntityDamageGateway] (WP3): applies Astera skill damage to
 * non-player [LivingEntity] mobs.
 *
 * Mobs have no Astera HP container, so Bukkit health is authoritative and the
 * hit goes through `LivingEntity.damage(amount, attacker)` — that gives the
 * red flash, hurt sound, vanilla `EntityDeathEvent`, and kill
 * credit / loot / xp for the attacking player. (The player-only rule against
 * `damage(` in `PaperDamageGateway` is about the player HP container.)
 *
 * The damage call raises a vanilla `EntityDamageByEntityEvent` whose damager is
 * the caster; [VanillaMeleeSuppressor] cancels vanilla melee from Astera items,
 * so this gateway raises [applying] around the call to let exactly this event
 * through.
 *
 * Main thread only.
 */
public class PaperEntityDamageGateway(
    private val server: Server,
    private val grid: CombatSpatialGrid,
    private val ownership: PaperCombatOwnership,
) : IEntityDamageGateway {

    /** True only while this gateway's own `damage(...)` call is on the stack. */
    private val applyingOnThread = ThreadLocal.withInitial { false }
    public val applying: Boolean get() = applyingOnThread.get()

    override fun isEntityTarget(target: PlayerId): Boolean {
        val entity = grid.entity(target.uuid)
        return entity is LivingEntity && entity !is Player && entity !is ArmorStand
    }

    override fun hitPoints(target: PlayerId): Double? {
        val entity = grid.entity(target.uuid) ?: return null
        return if (org.bukkit.Bukkit.isOwnedByCurrentRegion(entity)) entity.takeIf { it.isValid }?.health
        else grid.snapshot(target.uuid)?.health
    }

    override fun maxHitPoints(target: PlayerId): Double? {
        val entity = grid.entity(target.uuid) ?: return null
        return if (org.bukkit.Bukkit.isOwnedByCurrentRegion(entity)) entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value
        else grid.snapshot(target.uuid)?.maxHealth
    }

    override fun heal(target: PlayerId, amount: Double) {
        if (ownership.deferIfForeign(target) { heal(target, amount) }) return
        if (!ownership.checkOwned(target)) return
        val entity = grid.entity(target.uuid) as? LivingEntity ?: return
        if (!entity.isValid || entity.isDead) return
        val max = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: entity.health
        entity.health = (entity.health + amount).coerceIn(0.0, max)
        grid.update(entity)
    }

    override fun apply(
        target: PlayerId,
        resolved: ResolvedDamage,
        source: DamageSource,
    ): Result<DamageOutcome, DamageApplicationError> {
        val entity = grid.entity(target.uuid) as? LivingEntity
            ?: return Result.failure(DamageApplicationError.TargetOffline(target))
        check(org.bukkit.Bukkit.isOwnedByCurrentRegion(entity)) { "Damage outside target region" }
        if (entity.isDead || !entity.isValid) {
            return Result.failure(DamageApplicationError.TargetAlreadyDead(target))
        }
        if (entity.isInvulnerable) {
            return Result.failure(DamageApplicationError.Immune(target))
        }
        val attacker = source.attackerIdOrNull()?.let { server.getPlayer(it.uuid) }

        // Skill cadence is governed by cooldowns, not by vanilla i-frames.
        entity.noDamageTicks = 0
        val previousApplying = applyingOnThread.get()
        applyingOnThread.set(true)
        try {
            // SkillDamageScope: the caster-as-damager event must never re-enter item input triggers.
            SkillDamageScope.around {
                if (attacker != null) entity.damage(resolved.finalAmount, attacker) else entity.damage(resolved.finalAmount)
            }
        } finally {
            applyingOnThread.set(previousApplying)
        }

        grid.update(entity)
        return if (entity.isDead || entity.health <= 0.0) {
            Result.success(DamageOutcome.Killed)
        } else {
            Result.success(DamageOutcome.HpReduced(remainingHp = entity.health))
        }
    }
}
