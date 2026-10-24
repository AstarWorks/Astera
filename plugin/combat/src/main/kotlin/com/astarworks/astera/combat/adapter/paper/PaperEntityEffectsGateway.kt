package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.HeldItemAudience
import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.RigReveal
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
    private val pendingHides: MutableMap<PlayerId, HeldItemAudience> = LinkedHashMap()
    private val pendingReveals: MutableMap<PlayerId, List<RigReveal>> = LinkedHashMap()
    private var tickEndListenerRegistered: Boolean = false
    private val bundler: PaperPacketBundler? by lazy { PaperPacketBundler.create(server.javaClass.classLoader) }

    override fun setHeldItemHidden(target: PlayerId, hidden: Boolean, audience: HeldItemAudience, reveal: List<RigReveal>) {
        if (!hidden) {
            // The restore goes out immediately; the caller times it per audience against the rig's removal.
            pendingHides.remove(target)
            pendingReveals.remove(target)
            sendHeldItem(target, hidden = false, audience)
            return
        }
        val owner = plugin
        if (owner == null) {
            sendHeldItem(target, hidden = true, audience, reveal)
            return
        }
        // The hide goes out at tick end, after the entity tracker's metadata flush of this tick. A rig reveal rides in the
        // same packet bundle as the hide (see PaperPacketBundler), so the order of the two never matters to a client:
        // two separate packets, even microseconds apart, can straddle a client frame (a frame with two weapons or none).
        val queued = pendingHides[target]
        pendingHides[target] = if (queued == null || queued == audience) audience else HeldItemAudience.ALL
        if (reveal.isNotEmpty()) pendingReveals[target] = (pendingReveals[target] ?: emptyList()) + reveal
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
            val reveals = pendingReveals.toMap()
            pendingHides.clear()
            pendingReveals.clear()
            due.forEach { (target, audience) -> sendHeldItem(target, hidden = true, audience, reveals[target].orEmpty()) }
        }
    }

    private fun sendHeldItem(target: PlayerId, hidden: Boolean, audience: HeldItemAudience, reveal: List<RigReveal> = emptyList()) {
        val owner = server.getPlayer(target.uuid)?.takeIf { it.isValid } ?: return
        val real = owner.inventory.itemInMainHand
        // Includes the owner: the client applies the equipment packet to its own selected hotbar slot, so first person
        // and own third person stop drawing the real weapon while a rig draws it. The owner gets a stack whose item model
        // draws nothing and swaps without the hand animation (pack: astera:held_hidden): no lowering sword in first person
        // and, on restore, no re-equip raise. Packet-only; the server inventory is untouched, and the restore
        // (hidden = false) re-sends the real stack. Any later inventory sync also restores it.
        val displays = if (hidden) reveal.mapNotNull { r ->
            (server.getEntity(r.handle.id) as? org.bukkit.entity.Display)?.also { it.viewRange = r.viewRange }
        } else emptyList()
        val packets = if (displays.isEmpty()) null else bundler
        // Setting the view range marked the displays dirty: take that metadata now and bundle it with the hide for every
        // client that tracks the rig, so the tracker's own later flush has nothing left to send for it.
        val data = packets?.dirtyDataPackets(displays) ?: emptyList()
        val tracking = displays.flatMap { it.trackedBy }.map { it.uniqueId }.toSet()
        for (viewer in owner.world.players) {
            val isOwner = viewer.uniqueId == owner.uniqueId
            if (isOwner && audience == HeldItemAudience.OTHERS) continue
            if (!isOwner && audience == HeldItemAudience.OWNER) continue
            val shown = when {
                !hidden -> real
                isOwner -> emptyHandStack(real)
                else -> org.bukkit.inventory.ItemStack.of(org.bukkit.Material.AIR)
            }
            val owningPlugin = plugin
            if (isOwner && displays.isNotEmpty() && owningPlugin != null) {
                // The owner's first-person hand swaps its item only on the client's own tick (ItemInHandRenderer.tick), while a
                // display whose view range flips is drawn at once: the rig would show 0-3 frames before the hand empties. A
                // display that is (re)added is drawn from its first tick, the same client tick that updates the hand, so the
                // owner gets the rig as a fresh entity next to the hide, not as a view-range change.
                displays.forEach { viewer.hideEntity(owningPlugin, it) }
                displays.forEach { viewer.showEntity(owningPlugin, it) }
                viewer.sendEquipmentChange(owner, org.bukkit.inventory.EquipmentSlot.HAND, shown)
            } else if (packets != null && viewer.uniqueId in tracking) {
                packets.send(viewer, owner, shown, data)
            } else {
                viewer.sendEquipmentChange(owner, org.bukkit.inventory.EquipmentSlot.HAND, shown)
            }
        }
    }

    /**
     * The owner's stand-in for [real]: the same item and name with only the item model swapped for the empty one. Vanilla
     * restarts the attack-strength ticker (the hand rises from the bottom, ~13 frames) and the item-name popup whenever the
     * held item type or name changes, so a different item (a stick) would replay both on hide and on restore.
     */
    private fun emptyHandStack(real: org.bukkit.inventory.ItemStack): org.bukkit.inventory.ItemStack {
        val stack = if (real.type.isAir) org.bukkit.inventory.ItemStack.of(org.bukkit.Material.STICK) else real.clone()
        stack.editMeta { it.itemModel = HELD_HIDDEN_MODEL }
        return stack
    }

    private fun entity(id: PlayerId): Entity? = server.getEntity(id.uuid)?.takeIf { it.isValid }

    private companion object {
        /** Pack item definition that draws nothing and swaps without the hand animation (ResourcePackBuilder). */
        val HELD_HIDDEN_MODEL: NamespacedKey = NamespacedKey("astera", "held_hidden")

        /** Slowness 7: walk speed multiplier is negative-clamped to a stand-still. */
        const val ROOT_AMPLIFIER: Int = 6
    }
}
