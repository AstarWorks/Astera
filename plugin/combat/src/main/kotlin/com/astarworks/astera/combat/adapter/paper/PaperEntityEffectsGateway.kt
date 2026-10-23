package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector

/**
 * Paper concrete of [IEntityEffectsGateway] (WP4). Players and mobs are both
 * addressed through `server.getEntity(uuid)`. Main thread only.
 *
 * Freeze is a vanilla-native root: `freezeTicks` for the powder-snow frost
 * overlay plus a max-amplifier Slowness and a jump-cancelling Jump Boost, both
 * particle-less. The Astera-side `freeze` status (applied by the dispatcher)
 * stays the source of truth; these are the physical / visual half.
 */
public class PaperEntityEffectsGateway(
    private val server: Server,
) : IEntityEffectsGateway {

    override fun setVelocity(target: PlayerId, velocity: Vec3) {
        entity(target)?.velocity = Vector(velocity.x, velocity.y, velocity.z)
    }

    override fun addVelocity(target: PlayerId, delta: Vec3) {
        val entity = entity(target) ?: return
        entity.velocity = entity.velocity.add(Vector(delta.x, delta.y, delta.z))
    }

    override fun setFireTicks(target: PlayerId, ticks: Int) {
        entity(target)?.fireTicks = ticks.coerceAtLeast(0)
    }

    override fun freeze(target: PlayerId, ticks: Int) {
        val living = entity(target) as? LivingEntity ?: return
        living.freezeTicks = ticks.coerceAtLeast(living.maxFreezeTicks)
        living.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, ticks, ROOT_AMPLIFIER, false, false, false))
        living.addPotionEffect(PotionEffect(PotionEffectType.JUMP_BOOST, ticks, NO_JUMP_AMPLIFIER, false, false, false))
    }

    override fun hold(target: PlayerId, ticks: Int) {
        val living = entity(target) as? LivingEntity ?: return
        living.velocity = Vector(0.0, 0.0, 0.0)
        living.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, ticks, ROOT_AMPLIFIER, false, false, false))
        living.addPotionEffect(PotionEffect(PotionEffectType.JUMP_BOOST, ticks, NO_JUMP_AMPLIFIER, false, false, false))
    }

    override fun thaw(target: PlayerId) {
        val living = entity(target) as? LivingEntity ?: return
        if (living.freezeTicks > 0) living.freezeTicks = 0
        living.getPotionEffect(PotionEffectType.SLOWNESS)
            ?.takeIf { it.amplifier == ROOT_AMPLIFIER }
            ?.let { living.removePotionEffect(PotionEffectType.SLOWNESS) }
        living.getPotionEffect(PotionEffectType.JUMP_BOOST)
            ?.takeIf { it.amplifier == NO_JUMP_AMPLIFIER }
            ?.let { living.removePotionEffect(PotionEffectType.JUMP_BOOST) }
    }

    override fun setGlowing(target: PlayerId, glowing: Boolean) {
        entity(target)?.isGlowing = glowing
    }

    override fun setHeldItemHidden(target: PlayerId, hidden: Boolean) {
        val owner = server.getPlayer(target.uuid)?.takeIf { it.isValid } ?: return
        val shown = if (hidden) org.bukkit.inventory.ItemStack.of(org.bukkit.Material.AIR) else owner.inventory.itemInMainHand
        for (viewer in owner.world.players) {
            if (viewer != owner) viewer.sendEquipmentChange(owner, org.bukkit.inventory.EquipmentSlot.HAND, shown)
        }
    }

    private fun entity(id: PlayerId): Entity? = server.getEntity(id.uuid)?.takeIf { it.isValid }

    private companion object {
        /** Slowness 7: walk speed multiplier is negative-clamped to a stand-still. */
        const val ROOT_AMPLIFIER: Int = 6

        /** Jump Boost 128 wraps to a negative jump strength in vanilla, cancelling jumps. */
        const val NO_JUMP_AMPLIFIER: Int = 128
    }
}
