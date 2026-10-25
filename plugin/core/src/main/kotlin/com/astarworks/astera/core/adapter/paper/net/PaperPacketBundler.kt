package com.astarworks.astera.core.adapter.paper.net

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.slf4j.LoggerFactory

/**
 * Builds and sends one `ClientboundBundlePacket` out of equipment changes, entity-data packets and entity removals.
 * The client applies a bundle as a single task between two frames, which nothing reachable through the Bukkit API can
 * guarantee for separate packets (the entity tracker flushes metadata mid-tick, `sendEquipmentChange` goes out whenever it
 * is called, and the client's frame loop can land between them). The vanilla classes are reached by reflection (Paper 26
 * runs on Mojang names, no remapping): the plugins compile against paper-api only.
 *
 * [create] returns `null` when the server does not expose the expected members; callers then fall back to separate
 * packets and say so once in the log.
 */
public class PaperPacketBundler private constructor(
    private val getEntityHandle: java.lang.reflect.Method,
    private val getEntityData: java.lang.reflect.Method,
    private val packDirty: java.lang.reflect.Method,
    private val dataPacketCtor: java.lang.reflect.Constructor<*>,
    private val equipmentPacketCtor: java.lang.reflect.Constructor<*>,
    private val bundleCtor: java.lang.reflect.Constructor<*>,
    private val removeCtor: java.lang.reflect.Constructor<*>,
    private val pairOf: java.lang.reflect.Method,
    private val slots: Map<EquipmentSlot, Any>,
    private val asNmsCopy: java.lang.reflect.Method,
    private val connectionField: java.lang.reflect.Field,
    private val sendMethod: java.lang.reflect.Method,
) {

    /** Entity-data packets for everything [entities] changed since the tracker last flushed (consumes the dirty set). */
    public fun dirtyDataPackets(entities: List<Entity>): List<Any> = entities.mapNotNull { entity ->
        val data = getEntityData.invoke(getEntityHandle.invoke(entity))
        val dirty = packDirty.invoke(data) as? List<*>
        if (dirty.isNullOrEmpty()) null else dataPacketCtor.newInstance(entity.entityId, dirty)
    }

    /** Takes the pending entity-data changes of [entity] (consumes the dirty set), as a packet to bundle. */
    public fun dirtyDataPacket(entity: Entity): Any? = dirtyDataPackets(listOf(entity)).firstOrNull()

    /** An equipment packet showing [shown] in the given slots of [owner]. */
    public fun equipmentPacket(owner: Player, shown: Map<EquipmentSlot, ItemStack>): Any {
        val pairs = shown.map { (slot, stack) -> pairOf.invoke(null, slots.getValue(slot), asNmsCopy.invoke(null, stack)) }
        return equipmentPacketCtor.newInstance(owner.entityId, pairs)
    }

    /** A packet that removes the entities [ids] from the client. */
    public fun removalPacket(ids: IntArray): Any = removeCtor.newInstance(ids)

    /** One bundle to [viewer] holding [packets] in order. */
    public fun send(viewer: Player, packets: List<Any>) {
        if (packets.isEmpty()) return
        val bundle = bundleCtor.newInstance(packets)
        val connection = connectionField.get(getEntityHandle.invoke(viewer))
        sendMethod.invoke(connection, bundle)
    }

    public companion object {
        private val log = LoggerFactory.getLogger(PaperPacketBundler::class.java)

        public fun create(loader: ClassLoader): PaperPacketBundler? = try {
            fun cls(name: String): Class<*> = Class.forName(name, true, loader)
            val craftEntity = cls("org.bukkit.craftbukkit.entity.CraftEntity")
            val entity = cls("net.minecraft.world.entity.Entity")
            val synched = cls("net.minecraft.network.syncher.SynchedEntityData")
            val packet = cls("net.minecraft.network.protocol.Packet")
            val nmsSlot = cls("net.minecraft.world.entity.EquipmentSlot")
            val handle = craftEntity.getMethod("getHandle")
            val serverPlayer = cls("net.minecraft.server.level.ServerPlayer")
            PaperPacketBundler(
                getEntityHandle = handle,
                getEntityData = entity.getMethod("getEntityData"),
                packDirty = synched.getMethod("packDirty"),
                dataPacketCtor = cls("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket")
                    .getConstructor(Int::class.javaPrimitiveType, List::class.java),
                equipmentPacketCtor = cls("net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket")
                    .getConstructor(Int::class.javaPrimitiveType, List::class.java),
                bundleCtor = cls("net.minecraft.network.protocol.game.ClientboundBundlePacket")
                    .getConstructor(Iterable::class.java),
                removeCtor = cls("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket")
                    .getConstructor(IntArray::class.java),
                pairOf = cls("com.mojang.datafixers.util.Pair").getMethod("of", Any::class.java, Any::class.java),
                slots = mapOf(
                    EquipmentSlot.HAND to nmsSlot.getField("MAINHAND").get(null),
                    EquipmentSlot.OFF_HAND to nmsSlot.getField("OFFHAND").get(null),
                    EquipmentSlot.HEAD to nmsSlot.getField("HEAD").get(null),
                    EquipmentSlot.CHEST to nmsSlot.getField("CHEST").get(null),
                    EquipmentSlot.LEGS to nmsSlot.getField("LEGS").get(null),
                    EquipmentSlot.FEET to nmsSlot.getField("FEET").get(null),
                ),
                asNmsCopy = cls("org.bukkit.craftbukkit.inventory.CraftItemStack").getMethod("asNMSCopy", ItemStack::class.java),
                connectionField = serverPlayer.getField("connection"),
                sendMethod = cls("net.minecraft.server.network.ServerCommonPacketListenerImpl").getMethod("send", packet),
            )
        } catch (ex: ReflectiveOperationException) {
            log.warn("Packet bundling unavailable; presentation hand-over uses separate packets", ex)
            null
        }
    }
}
