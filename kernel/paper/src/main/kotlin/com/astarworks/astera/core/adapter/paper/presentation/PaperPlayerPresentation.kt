package com.astarworks.astera.core.adapter.paper.presentation

import com.astarworks.astera.core.adapter.paper.net.PaperPacketBundler
import com.astarworks.astera.core.application.port.outbound.presentation.IPlayerPresentation
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.PresentationAudience
import com.astarworks.astera.core.domain.presentation.PresentationDisplay
import com.astarworks.astera.core.domain.presentation.PresentationError
import com.astarworks.astera.core.domain.presentation.PresentationKind
import com.astarworks.astera.core.domain.presentation.PresentationLease
import com.astarworks.astera.core.domain.presentation.PresentationMask
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent
import com.destroystokyo.paper.event.server.ServerTickEndEvent
import io.papermc.paper.event.player.PlayerTrackEntityEvent
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.Pose
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffectType
import java.util.UUID

/**
 * Paper [IPlayerPresentation] (ADR-0276): the one owner of every equipment / invisibility / pose change a performance or
 * an emote makes to a player. The packet sequence is the one tuned in the weapon-rig rounds r16-r26, moved here unchanged
 * in order and generalised from "main hand" to a [PresentationMask]:
 *  - reveal: the hide goes out at server tick end (after the entity tracker's flush) in ONE bundle per viewer together with the
 *    staged displays' entity data; the owner re-adds the displays (a re-added entity is drawn from its first client tick, the
 *    same tick the first-person hand swaps) and gets the stand-in `astera:held_hidden` for the hand.
 *  - release owner leg: hand restored and displays scaled to zero in the same client step, a tick before the entities go.
 *  - release others leg: restore + invisibility off + removal of the displays in one bundle, sent before the server removes them.
 * Events that used to break the invariant are handled here: a viewer that starts tracking the owner (late tracking), hotbar
 * scroll / hand swap while the hand is leased, armor changes, quit, world change and death.
 */
public class PaperPlayerPresentation(
    private val server: Server,
    private val plugin: Plugin,
) : IPlayerPresentation, Listener {

    private class Held(val handle: PresentationLease, val onRevoked: () -> Unit) {
        var active = false
        var ownerLeg = true
        var othersLeg = true
        var revoked = false
    }

    private class Owner {
        val leases = ArrayList<Held>()
        var appliedOwner = PresentationMask.NONE
        var appliedOthers = PresentationMask.NONE
        var wasInvisible = false
        var bodyApplied = false
        var poseApplied = false
        var prevPose: Pose = Pose.STANDING
        var prevFixed = false
    }

    private val owners = LinkedHashMap<PlayerId, Owner>()
    private val pendingReveals = LinkedHashMap<PlayerId, MutableList<PresentationDisplay>>()
    private val pendingFlush = LinkedHashMap<PlayerId, MutableList<PresentationDisplay>>()
    private val pendingTrack = LinkedHashMap<PlayerId, MutableSet<UUID>>()
    private val pendingArmor = LinkedHashSet<PlayerId>()
    private var nextId = 0L
    private val bundler: PaperPacketBundler? by lazy { PaperPacketBundler.create(server.javaClass.classLoader) }

    /** Registers the listeners; call once from the plugin's `onEnable`. */
    public fun start() {
        server.pluginManager.registerEvents(this, plugin)
    }

    // ---- lease bookkeeping ------------------------------------------------------

    override fun claim(owner: PlayerId, mask: PresentationMask, kind: PresentationKind, onRevoked: () -> Unit): Result<PresentationLease, PresentationError> {
        if (player(owner) == null) return Result.failure(PresentationError.OwnerOffline)
        val o = owners.getOrPut(owner) { Owner() }
        if (kind == PresentationKind.EMOTE && o.leases.any { it.handle.kind == PresentationKind.PERFORMANCE }) {
            return Result.failure(PresentationError.Busy(PresentationKind.PERFORMANCE))
        }
        if (kind == PresentationKind.PERFORMANCE) o.leases.filter { it.handle.kind == PresentationKind.EMOTE }.forEach { revokeLease(owner, it) }
        val held = Held(PresentationLease(++nextId, owner, mask, kind), onRevoked)
        o.leases += held
        return Result.success(held.handle)
    }

    override fun holder(owner: PlayerId): PresentationKind? =
        owners[owner]?.leases.orEmpty().map { it.handle.kind }.minByOrNull { it.ordinal }

    // A claimed lease covers its slots from the claim on: a chained performance claims before the old one is released, so the
    // union never dips and nothing is restored in between (ADR-0276).
    private fun union(o: Owner, ownerLeg: Boolean): PresentationMask =
        o.leases.filter { if (ownerLeg) it.ownerLeg else it.othersLeg }.fold(PresentationMask.NONE) { a, l -> a + l.handle.mask }

    private fun unionAll(o: Owner): PresentationMask =
        o.leases.fold(PresentationMask.NONE) { a, l -> a + l.handle.mask }

    private fun find(lease: PresentationLease): Pair<Owner, Held>? {
        val o = owners[lease.owner] ?: return null
        val l = o.leases.firstOrNull { it.handle.id == lease.id && !it.revoked } ?: return null
        return o to l
    }

    private fun player(id: PlayerId): Player? = server.getPlayer(id.uuid)?.takeIf { it.isValid }

    // ---- reveal -----------------------------------------------------------------

    override fun reveal(lease: PresentationLease, displays: List<PresentationDisplay>) {
        val (_, l) = find(lease) ?: return
        l.active = true
        pendingReveals.getOrPut(lease.owner) { ArrayList() } += displays
    }

    override fun flushBundled(owner: PlayerId, displays: List<PresentationDisplay>) {
        if (displays.isEmpty()) return
        pendingFlush.getOrPut(owner) { ArrayList() } += displays
    }

    @EventHandler
    public fun onTickEnd(event: ServerTickEndEvent) {
        if (pendingReveals.isNotEmpty()) {
            val due = pendingReveals.toMap()
            pendingReveals.clear()
            due.forEach { (id, displays) -> applyReveal(id, displays) }
        }
        if (pendingFlush.isNotEmpty()) {
            val due = pendingFlush.toMap()
            pendingFlush.clear()
            due.forEach { (id, list) -> flushDisplays(id, list) }
        }
        if (pendingTrack.isNotEmpty() || pendingArmor.isNotEmpty()) {
            val track = pendingTrack.toMap()
            val armor = pendingArmor.toList()
            pendingTrack.clear()
            pendingArmor.clear()
            track.forEach { (id, viewers) -> resendMask(id, viewers) }
            armor.forEach { id -> resendMask(id, null) }
        }
    }

    private fun applyReveal(id: PlayerId, displays: List<PresentationDisplay>) {
        val o = owners[id] ?: return
        val owner = player(id) ?: return
        val mask = unionAll(o)
        val entities = displays.mapNotNull { d ->
            d.beforeSend()
            (server.getEntity(d.id) as? Display)?.also { e -> d.viewRange?.let { e.viewRange = it } }
        }
        if (mask.body && !o.bodyApplied) {
            o.wasInvisible = owner.isInvisible
            owner.isInvisible = true
            o.bodyApplied = true
        }
        if (mask.pinPose && !o.poseApplied) {
            o.prevPose = owner.pose
            o.prevFixed = owner.hasFixedPose()
            owner.setPose(Pose.STANDING, true)
            o.poseApplied = true
        }
        val packets = bundler
        // Setting the view range / invisibility marked entity data dirty: take it now and bundle it with the hide for every
        // client, so the tracker's own later flush has nothing left to send for it.
        val playerData = if (packets != null && mask.body) packets.dirtyDataPacket(owner) else null
        val displayData = packets?.dirtyDataPackets(entities).orEmpty()
        val tracking = entities.flatMap { it.trackedBy }.map { it.uniqueId }.toSet()
        for (viewer in owner.trackedBy + owner) {
            val isOwner = viewer.uniqueId == owner.uniqueId
            val shown = hiddenStacks(owner, mask, isOwner)
            if (isOwner && entities.isNotEmpty()) {
                entities.forEach { viewer.hideEntity(plugin, it) }
                entities.forEach { viewer.showEntity(plugin, it) }
            }
            if (packets != null) {
                val list = ArrayList<Any>()
                if (shown.isNotEmpty()) list += packets.equipmentPacket(owner, shown)
                if (!isOwner && viewer.uniqueId in tracking) list.addAll(displayData)
                playerData?.let { list += it }
                packets.send(viewer, list)
            } else if (shown.isNotEmpty()) {
                viewer.sendEquipmentChange(owner, shown)
            }
        }
        o.appliedOwner = mask
        o.appliedOthers = mask
    }

    private fun flushDisplays(id: PlayerId, list: List<PresentationDisplay>) {
        list.forEach { it.beforeSend() }
        val packets = bundler ?: return
        val owner = player(id) ?: return
        val entities = list.mapNotNull { server.getEntity(it.id) as? Display }
        val data = packets.dirtyDataPackets(entities)
        if (data.isEmpty()) return
        val tracking = entities.flatMap { it.trackedBy }.toSet()
        // Ordinary tracker traffic already carries the owner's copy; bundling here is for the viewers that track the displays.
        for (viewer in tracking) packets.send(viewer, data)
    }

    /** Re-sends the mask to [viewers] (or everyone tracking [id]): late tracking, armor change. */
    private fun resendMask(id: PlayerId, viewers: Set<UUID>?) {
        val o = owners[id] ?: return
        val owner = player(id) ?: return
        val mask = union(o, ownerLeg = false)
        if (mask.isEmpty) return
        for (viewer in owner.trackedBy) {
            if (viewers != null && viewer.uniqueId !in viewers) continue
            val shown = hiddenStacks(owner, mask, false)
            if (shown.isEmpty()) continue
            val packets = bundler
            if (packets != null) packets.send(viewer, listOf(packets.equipmentPacket(owner, shown))) else viewer.sendEquipmentChange(owner, shown)
        }
    }

    // ---- release ----------------------------------------------------------------

    override fun release(lease: PresentationLease, displays: List<UUID>, audience: PresentationAudience) {
        val (o, l) = find(lease) ?: return
        val ownerId = lease.owner
        if (audience != PresentationAudience.OTHERS) l.ownerLeg = false
        if (audience != PresentationAudience.OWNER) l.othersLeg = false
        pendingReveals[ownerId]?.removeAll { it.id in displays }
        val owner = player(ownerId)
        if (owner != null) restoreFor(o, owner, displays, audience)
        if (!l.ownerLeg && !l.othersLeg) {
            o.leases.remove(l)
            if (o.leases.none { it.handle.mask.pinPose } && o.poseApplied) unpin(o, owner)
            if (o.leases.isEmpty()) owners.remove(ownerId)
        }
    }

    private fun restoreFor(o: Owner, owner: Player, displays: List<UUID>, audience: PresentationAudience) {
        val entities = displays.mapNotNull { server.getEntity(it) as? Display }
        val packets = bundler
        if (audience != PresentationAudience.OTHERS) {
            val now = union(o, ownerLeg = true)
            val freed = diff(o.appliedOwner, now)
            // The owner's displays are scaled to zero (applied on the client tick that also updates the hand); the entity data
            // goes only to the owner, other viewers keep the full-size rig until the bundle that removes it.
            val concealed = if (packets != null) entities.mapNotNull { display ->
                ConcealedDisplays.add(display.uniqueId)
                display.interpolationDelay = 0
                display.interpolationDuration = 0
                val t = display.transformation
                display.transformation = org.bukkit.util.Transformation(t.translation, t.leftRotation, org.joml.Vector3f(0f, 0f, 0f), t.rightRotation)
                packets.dirtyDataPacket(display)
            } else emptyList()
            val shown = realStacks(owner, freed, isOwner = true)
            if (packets != null) {
                val list = ArrayList<Any>()
                if (shown.isNotEmpty()) list += packets.equipmentPacket(owner, shown)
                list.addAll(concealed)
                packets.send(owner, list)
            } else if (shown.isNotEmpty()) {
                owner.sendEquipmentChange(owner, shown)
            }
            o.appliedOwner = now
        }
        if (audience != PresentationAudience.OWNER) {
            val now = union(o, ownerLeg = false)
            val freed = diff(o.appliedOthers, now)
            var playerData: Any? = null
            if (freed.body && o.bodyApplied) {
                owner.isInvisible = o.wasInvisible || owner.hasPotionEffect(PotionEffectType.INVISIBILITY)
                o.bodyApplied = false
                playerData = packets?.dirtyDataPacket(owner)
            }
            val shown = realStacks(owner, freed, isOwner = false)
            val ids = entities.map { it.entityId }.toIntArray()
            for (viewer in owner.trackedBy) {
                if (packets != null) {
                    val list = ArrayList<Any>()
                    if (shown.isNotEmpty()) list += packets.equipmentPacket(owner, shown)
                    playerData?.let { list += it }
                    if (ids.isNotEmpty()) list += packets.removalPacket(ids)
                    packets.send(viewer, list)
                } else if (shown.isNotEmpty()) {
                    viewer.sendEquipmentChange(owner, shown)
                }
            }
            if (playerData == null && freed.body && owner.isInvisible && !o.wasInvisible) {
                owner.isInvisible = o.wasInvisible
                o.bodyApplied = false
            }
            o.appliedOthers = now
        }
    }

    private fun unpin(o: Owner, owner: Player?) {
        o.poseApplied = false
        if (owner != null && owner.isValid) owner.setPose(if (o.prevFixed) o.prevPose else Pose.STANDING, o.prevFixed)
    }

    /** Slots masked in [before] that [after] no longer masks. */
    private fun diff(before: PresentationMask, after: PresentationMask): PresentationMask = PresentationMask(
        hand = before.hand && !after.hand,
        offhand = before.offhand && !after.offhand,
        armor = before.armor && !after.armor,
        body = before.body && !after.body,
    )

    // ---- stacks -----------------------------------------------------------------

    private fun hiddenStacks(owner: Player, mask: PresentationMask, isOwner: Boolean): Map<EquipmentSlot, ItemStack> {
        val out = LinkedHashMap<EquipmentSlot, ItemStack>()
        // The owner's hand shows a stack whose item model draws nothing and swaps without the hand animation, so the first-person
        // hand neither lowers nor re-raises. Packet-only; the server inventory is untouched.
        if (mask.hand) out[EquipmentSlot.HAND] = if (isOwner) emptyHandStack(owner.inventory.itemInMainHand) else air()
        if (!isOwner) {
            if (mask.offhand) out[EquipmentSlot.OFF_HAND] = air()
            if (mask.armor) ARMOR.forEach { out[it] = air() }
        }
        return out
    }

    private fun realStacks(owner: Player, freed: PresentationMask, isOwner: Boolean): Map<EquipmentSlot, ItemStack> {
        val out = LinkedHashMap<EquipmentSlot, ItemStack>()
        if (freed.hand) out[EquipmentSlot.HAND] = owner.inventory.itemInMainHand
        if (!isOwner) {
            if (freed.offhand) out[EquipmentSlot.OFF_HAND] = owner.inventory.itemInOffHand
            if (freed.armor) ARMOR.forEach { out[it] = owner.inventory.getItem(it) ?: air() }
        }
        return out
    }

    private fun air(): ItemStack = ItemStack.of(Material.AIR)

    /**
     * The owner's stand-in for [real]: the same item and name with only the item model swapped for the empty one. Vanilla
     * restarts the attack-strength ticker and the item-name popup whenever the held item type or name changes, so a different
     * item (a stick) would replay both on hide and on restore.
     */
    private fun emptyHandStack(real: ItemStack): ItemStack {
        val stack = if (real.type.isAir) ItemStack.of(Material.STICK) else real.clone()
        stack.editMeta { it.itemModel = HELD_HIDDEN_MODEL }
        return stack
    }

    // ---- last resorts -----------------------------------------------------------

    private fun revokeLease(id: PlayerId, l: Held) {
        l.revoked = true
        owners[id]?.leases?.remove(l)
        l.onRevoked()
    }

    /** Gives everything back at once: the owner left, changed world or died. [packets] is `false` when the viewers are gone. */
    private fun forceRelease(id: PlayerId, packets: Boolean) {
        val o = owners[id] ?: return
        pendingReveals.remove(id)
        pendingFlush.remove(id)
        val held = o.leases.toList()
        held.forEach { it.revoked = true }
        held.forEach { it.onRevoked() }
        val owner = player(id)
        if (owner != null) {
            if (o.bodyApplied) owner.isInvisible = o.wasInvisible || owner.hasPotionEffect(PotionEffectType.INVISIBILITY)
            if (o.poseApplied) unpin(o, owner)
            val applied = o.appliedOthers + o.appliedOwner
            if (packets && !applied.isEmpty) {
                val data = if (o.bodyApplied) bundler?.dirtyDataPacket(owner) else null
                for (viewer in owner.trackedBy + owner) {
                    val shown = realStacks(owner, applied, isOwner = viewer.uniqueId == owner.uniqueId)
                    val b = bundler
                    if (b != null) b.send(viewer, listOfNotNull(if (shown.isEmpty()) null else b.equipmentPacket(owner, shown), data))
                    else if (shown.isNotEmpty()) viewer.sendEquipmentChange(owner, shown)
                }
            }
        }
        owners.remove(id)
    }

    override fun shutdown() {
        owners.keys.toList().forEach { forceRelease(it, packets = true) }
    }

    // ---- events -----------------------------------------------------------------

    @EventHandler
    public fun onQuit(event: PlayerQuitEvent) {
        forceRelease(PlayerId(event.player.uniqueId), packets = false)
    }

    @EventHandler
    public fun onWorldChange(event: PlayerChangedWorldEvent) {
        forceRelease(PlayerId(event.player.uniqueId), packets = true)
    }

    @EventHandler
    public fun onDeath(event: PlayerDeathEvent) {
        forceRelease(PlayerId(event.entity.uniqueId), packets = true)
    }

    /** The client is authoritative for the selected slot: refuse a scroll while the hand is leased (Paper re-syncs the slot). */
    @EventHandler(ignoreCancelled = true)
    public fun onHeld(event: PlayerItemHeldEvent) {
        if (handLeased(event.player)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    public fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        if (handLeased(event.player)) event.isCancelled = true
    }

    @EventHandler
    public fun onArmorChange(event: PlayerArmorChangeEvent) {
        val id = PlayerId(event.player.uniqueId)
        if (owners[id]?.let { union(it, ownerLeg = false).armor } == true) pendingArmor += id
    }

    /** A viewer starting to track the owner is paired with the real equipment: mask it in the same tick. */
    @EventHandler
    public fun onTrack(event: PlayerTrackEntityEvent) {
        val target = event.entity as? Player ?: return
        val id = PlayerId(target.uniqueId)
        val o = owners[id] ?: return
        if (union(o, ownerLeg = false).isEmpty) return
        pendingTrack.getOrPut(id) { LinkedHashSet() } += event.player.uniqueId
    }

    private fun handLeased(player: Player): Boolean =
        owners[PlayerId(player.uniqueId)]?.let { union(it, ownerLeg = true).hand } == true

    private companion object {
        /** Pack item definition that draws nothing and swaps without the hand animation (ResourcePackBuilder). */
        val HELD_HIDDEN_MODEL: NamespacedKey = NamespacedKey("astera", "held_hidden")
        val ARMOR = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
    }
}
