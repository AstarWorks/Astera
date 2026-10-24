package com.astarworks.astera.combat.adapter.paper

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.slf4j.LoggerFactory

/**
 * Sends one `ClientboundBundlePacket` holding a held-item equipment change and the entity-data packets of rig displays.
 * The client applies a bundle as a single task between two frames, which nothing reachable through the Bukkit API can
 * guarantee for two separate packets (the entity tracker flushes metadata mid-tick, `sendEquipmentChange` goes out
 * whenever it is called, and the client's frame loop can land between them). The vanilla classes are reached by reflection
 * (Paper 26 runs on Mojang names, no remapping): the combat plugin compiles against paper-api only.
 *
 * [create] returns `null` when the server does not expose the expected members; the caller then falls back to separate
 * packets and says so once in the log.
 */
internal class PaperPacketBundler private constructor(
    private val getEntityHandle: java.lang.reflect.Method,
    private val getEntityData: java.lang.reflect.Method,
    private val packDirty: java.lang.reflect.Method,
    private val dataPacketCtor: java.lang.reflect.Constructor<*>,
    private val equipmentPacketCtor: java.lang.reflect.Constructor<*>,
    private val bundleCtor: java.lang.reflect.Constructor<*>,
    private val pairOf: java.lang.reflect.Method,
    private val mainHand: Any,
    private val asNmsCopy: java.lang.reflect.Method,
    private val connectionField: java.lang.reflect.Field,
    private val sendMethod: java.lang.reflect.Method,
) {

    /** Entity-data packets for everything [displays] changed since the tracker last flushed (consumes the dirty set). */
    fun dirtyDataPackets(displays: List<Entity>): List<Any> = displays.mapNotNull { display ->
        val data = getEntityData.invoke(getEntityHandle.invoke(display))
        val dirty = packDirty.invoke(data) as? List<*>
        if (dirty.isNullOrEmpty()) null else dataPacketCtor.newInstance(display.entityId, dirty)
    }

    /** One bundle to [viewer]: [owner]'s main hand shows [shown], followed by [data]. */
    fun send(viewer: Player, owner: Player, shown: ItemStack, data: List<Any>) {
        val nmsStack = asNmsCopy.invoke(null, shown)
        val equipment = equipmentPacketCtor.newInstance(owner.entityId, listOf(pairOf.invoke(null, mainHand, nmsStack)))
        val bundle = bundleCtor.newInstance(listOf(equipment) + data)
        val connection = connectionField.get(getEntityHandle.invoke(viewer))
        sendMethod.invoke(connection, bundle)
    }

    companion object {
        private val log = LoggerFactory.getLogger(PaperPacketBundler::class.java)

        fun create(loader: ClassLoader): PaperPacketBundler? = try {
            fun cls(name: String): Class<*> = Class.forName(name, true, loader)
            val craftEntity = cls("org.bukkit.craftbukkit.entity.CraftEntity")
            val entity = cls("net.minecraft.world.entity.Entity")
            val synched = cls("net.minecraft.network.syncher.SynchedEntityData")
            val packet = cls("net.minecraft.network.protocol.Packet")
            val equipmentSlot = cls("net.minecraft.world.entity.EquipmentSlot")
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
                pairOf = cls("com.mojang.datafixers.util.Pair").getMethod("of", Any::class.java, Any::class.java),
                mainHand = equipmentSlot.getField("MAINHAND").get(null),
                asNmsCopy = cls("org.bukkit.craftbukkit.inventory.CraftItemStack").getMethod("asNMSCopy", ItemStack::class.java),
                connectionField = serverPlayer.getField("connection"),
                sendMethod = cls("net.minecraft.server.network.ServerCommonPacketListenerImpl").getMethod("send", packet),
            )
        } catch (ex: ReflectiveOperationException) {
            log.warn("Packet bundling unavailable; held-item hand-over uses separate packets", ex)
            null
        }
    }
}
