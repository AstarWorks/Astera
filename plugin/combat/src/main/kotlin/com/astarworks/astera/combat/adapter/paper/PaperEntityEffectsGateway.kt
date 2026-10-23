package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.plugin.Plugin
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
 * overlay plus a max-amplifier Slowness and a jump-strength modifier of -100 %, both
 * particle-less. The Astera-side `freeze` status (applied by the dispatcher)
 * stays the source of truth; these are the physical / visual half.
 */
public class PaperEntityEffectsGateway(
    private val server: Server,
    private val plugin: Plugin? = null,
) : IEntityEffectsGateway {

    /** Server tick at which the no-jump modifier of each rooted entity expires. */
    private val noJumpUntil: MutableMap<java.util.UUID, Int> = java.util.concurrent.ConcurrentHashMap()
    private val noJumpKey: NamespacedKey = NamespacedKey("astera", "root_no_jump")

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
        disableJump(living, ticks)
    }

    override fun hold(target: PlayerId, ticks: Int) {
        val living = entity(target) as? LivingEntity ?: return
        living.velocity = Vector(0.0, 0.0, 0.0)
        living.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, ticks, ROOT_AMPLIFIER, false, false, false))
        disableJump(living, ticks)
    }

    override fun thaw(target: PlayerId) {
        val living = entity(target) as? LivingEntity ?: return
        if (living.freezeTicks > 0) living.freezeTicks = 0
        living.getPotionEffect(PotionEffectType.SLOWNESS)
            ?.takeIf { it.amplifier == ROOT_AMPLIFIER }
            ?.let { living.removePotionEffect(PotionEffectType.SLOWNESS) }
        enableJump(living)
    }

    /**
     * Cancels jumping for [ticks] with a `jump_strength` attribute modifier of -100 % (jump strength 0).
     * Jump Boost 128 is NOT usable: modern amplifiers are not byte-wrapped, so it gave a +13.32 b/tick launch
     * that Paper rejected as "moved too quickly!" (ADR-0248 section Jump).
     */
    private fun disableJump(living: LivingEntity, ticks: Int) {
        val attribute = living.getAttribute(Attribute.JUMP_STRENGTH) ?: return
        attribute.removeModifier(noJumpKey)
        attribute.addTransientModifier(
            AttributeModifier(noJumpKey, -1.0, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.ANY),
        )
        val until = server.currentTick + ticks.coerceAtLeast(1)
        noJumpUntil.merge(living.uniqueId, until) { a, b -> maxOf(a, b) }
        val owner = plugin
        if (owner == null) return
        server.scheduler.runTaskLater(
            owner,
            Runnable {
                val deadline = noJumpUntil[living.uniqueId] ?: return@Runnable
                if (server.currentTick >= deadline) enableJump(living)
            },
            ticks.coerceAtLeast(1).toLong() + 1L,
        )
    }

    private fun enableJump(living: LivingEntity) {
        noJumpUntil.remove(living.uniqueId)
        living.getAttribute(Attribute.JUMP_STRENGTH)?.removeModifier(noJumpKey)
    }

    override fun setGlowing(target: PlayerId, glowing: Boolean) {
        entity(target)?.isGlowing = glowing
    }

    /** Hides waiting for the end of the server tick (after the entity tracker flushed this tick's display metadata). */
    private val pendingHides: MutableSet<PlayerId> = LinkedHashSet()
    private var tickEndListenerRegistered: Boolean = false

    override fun setHeldItemHidden(target: PlayerId, hidden: Boolean) {
        if (!hidden) {
            // The restore goes out immediately: callers send it before the rig's displays are removed, so the real
            // weapon is visible again before the rig disappears (two coincident weapons for an instant, never none).
            pendingHides.remove(target)
            sendHeldItem(target, hidden = false)
            return
        }
        val owner = plugin
        if (owner == null) {
            sendHeldItem(target, hidden = true)
            return
        }
        // Display metadata (the rig's reveal, view_range 0 -> n) is not sent when the reveal runs: the entity tracker
        // flushes it later in the same server tick, so an equipment packet sent right away reaches a client tens of ms
        // before the rig does (compare view: one frame with no weapon). Send the hide only after that flush, at tick
        // end, so it can only land after the rig is visible: ordered, never a gap.
        pendingHides.add(target)
        if (!tickEndListenerRegistered) {
            tickEndListenerRegistered = true
            server.pluginManager.registerEvents(TickEndFlush(), owner)
        }
    }

    private inner class TickEndFlush : org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler
        fun onTickEnd(event: com.destroystokyo.paper.event.server.ServerTickEndEvent) {
            if (pendingHides.isEmpty()) return
            val due = pendingHides.toList()
            pendingHides.clear()
            due.forEach { sendHeldItem(it, hidden = true) }
        }
    }

    private fun sendHeldItem(target: PlayerId, hidden: Boolean) {
        val owner = server.getPlayer(target.uuid)?.takeIf { it.isValid } ?: return
        val shown = if (hidden) org.bukkit.inventory.ItemStack.of(org.bukkit.Material.AIR) else owner.inventory.itemInMainHand
        // Includes the owner: the client applies the equipment packet to its own selected hotbar slot, so first person
        // and own third person stop drawing the real weapon while a rig draws it. Packet-only; the server inventory
        // is untouched, and the restore (hidden = false) re-sends the real stack. Any later inventory sync also restores it.
        for (viewer in owner.world.players) {
            viewer.sendEquipmentChange(owner, org.bukkit.inventory.EquipmentSlot.HAND, shown)
        }
    }

    private fun entity(id: PlayerId): Entity? = server.getEntity(id.uuid)?.takeIf { it.isValid }

    private companion object {
        /** Slowness 7: walk speed multiplier is negative-clamped to a stand-still. */
        const val ROOT_AMPLIFIER: Int = 6
    }
}
