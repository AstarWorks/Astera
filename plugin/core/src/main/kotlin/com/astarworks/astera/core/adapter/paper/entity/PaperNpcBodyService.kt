package com.astarworks.astera.core.adapter.paper.entity

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.adapter.paper.packetevents.PacketEventsNpcPlayers
import com.astarworks.astera.core.adapter.paper.packetevents.PacketEventsBootstrap
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.entity.INpcBodyMaterializer
import com.astarworks.astera.core.application.port.outbound.entity.INpcInteractionRouter
import com.astarworks.astera.core.application.port.outbound.entity.IManagedEntityRegistry
import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.HologramKind
import com.astarworks.astera.core.domain.entity.NpcInteractionClick
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.TextDisplay
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** Region-owned Display + Interaction bodies, or packet-only players. No living NPC entities. */
internal class PaperNpcBodyService(
    private val plugin: JavaPlugin,
    private val mcServer: IMcServer,
    private val scheduler: IScheduler,
    private val messages: IMessageRenderer,
    private val interactions: INpcInteractionRouter,
    private val intents: IGuiIntentRouter,
    private val registry: IManagedEntityRegistry? = null,
    private val itemIcon: (com.astarworks.astera.core.domain.item.ItemId) -> ItemStack?,
) : INpcBodyMaterializer, Listener {
    private data class Request(val handle: EntityHandle, val world: WorldId, val position: Vec3, val spec: SpawnSpec)
    private val requests = ConcurrentHashMap<EntityHandle, Request>()
    private val bodies = ConcurrentHashMap<EntityHandle, List<Entity>>()
    private val hitboxes = ConcurrentHashMap<UUID, EntityHandle>()
    // PacketEvents is a soft-dependency: the class (a PacketListener) must not even be constructed
    // when the plugin is absent, or loading it throws NoClassDefFoundError and Core fails to enable.
    // Without PacketEvents, PlayerNpc fake players are simply not materialised; Display bodies work.
    private val packets: PacketEventsNpcPlayers? =
        if (PacketEventsBootstrap.isInstalled(plugin)) PacketEventsNpcPlayers(plugin, scheduler, ::dispatch) else null

    @Volatile var awaitedWorlds: Set<String> = emptySet()

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        packets?.start()
    }

    fun flush() {
        requests.values.forEach(::schedule)
    }

    @EventHandler fun onServerLoad(event: ServerLoadEvent) = flush()

    @EventHandler
    fun onEntitiesUnload(event: EntitiesUnloadEvent) {
        val unloaded = event.entities.map { it.uniqueId }.toSet()
        bodies.entries.toList().filter { (_, entities) -> entities.any { it.uniqueId in unloaded } }.forEach { (handle, entities) ->
            val request = requests[handle] ?: return@forEach
            synchronized(request) {
                if (!bodies.remove(handle, entities)) return@forEach
                entities.forEach { entity ->
                    hitboxes.remove(entity.uniqueId)
                    packets?.unbindInteraction(entity.entityId)
                    registry?.release("npc:${handle.id}:${entity.uniqueId}")
                }
            }
        }
    }

    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        requests.values.filter { request ->
            val scope = regionScope(request.world, request.position)
            request.world.value == event.world.name && scope.cx == event.chunk.x && scope.cz == event.chunk.z
        }.forEach(::schedule)
    }

    override fun materialize(handle: EntityHandle, world: WorldId, position: Vec3, spec: SpawnSpec) {
        val request = Request(handle, world, position, spec)
        requests[handle] = request
        schedule(request)
    }

    private fun schedule(request: Request) {
        if (request.world.value in awaitedWorlds || plugin.server.getWorld(request.world.value) == null) return
        if (request.spec is SpawnSpec.PlayerNpc) {
            synchronized(request) {
                if (requests[request.handle] !== request) return
                packets?.put(request.handle, request.world, request.position, request.spec)
            }
            return
        }
        scheduler.run(regionScope(request.world, request.position), Duration.ZERO) {
            synchronized(request) {
                if (request.world.value in awaitedWorlds || requests[request.handle] !== request || bodies.containsKey(request.handle)) return@run
                val world = plugin.server.getWorld(request.world.value) ?: return@run
                val spec = request.spec as SpawnSpec.DisplayNpc
                val at = Location(world, request.position.x, request.position.y, request.position.z, spec.yaw, spec.pitch)
                val display: Display = when (val kind = spec.kind) {
                    is HologramKind.Text -> world.spawn(at.clone().add(0.0, 2.0, 0.0), TextDisplay::class.java) {
                        it.text(AsteraTextDeserializer.deserialize(messages.render(null, kind.messageKey)))
                        it.billboard = Display.Billboard.CENTER
                    }
                    is HologramKind.Item -> world.spawn(at, ItemDisplay::class.java) {
                        it.setItemStack(requireNotNull(itemIcon(kind.itemId)) { "Unknown NPC display item ${kind.itemId}" })
                    }
                    is HologramKind.Block -> world.spawn(at, BlockDisplay::class.java) {
                        it.block = requireNotNull(Material.matchMaterial(kind.blockKey)).createBlockData()
                    }
                }
                val hitbox = world.spawn(at, Interaction::class.java) {
                    it.interactionWidth = 0.9f
                    it.interactionHeight = 2f
                    it.isResponsive = true
                    (spec.kind as? HologramKind.Text)?.let { kind ->
                        it.customName(AsteraTextDeserializer.deserialize(messages.render(null, kind.messageKey)))
                    }
                }
                val entities = listOf(display, hitbox)
                entities.forEach { entity ->
                    val owner = "npc:${request.handle.id}:${entity.uniqueId}"
                    PaperManagedEntities.mark(entity, owner)
                    registry?.claim(owner, entity.uniqueId)
                }
                hitboxes[hitbox.uniqueId] = request.handle
                packets?.bindInteraction(hitbox.entityId, request.handle, request.world, request.position)
                bodies[request.handle] = entities
            }
        }
    }

    override fun remove(handle: EntityHandle) {
        val request = requests[handle] ?: return
        synchronized(request) {
            if (!requests.remove(handle, request)) return
            packets?.remove(handle)
            bodies.remove(handle)?.forEach { entity ->
                hitboxes.remove(entity.uniqueId)
                packets?.unbindInteraction(entity.entityId)
                scheduler.run(ExecutionScope.Entity(entity.uniqueId), Duration.ZERO) { entity.remove() }
                registry?.release("npc:${handle.id}:${entity.uniqueId}")
            }
        }
    }

    override fun move(handle: EntityHandle, position: Vec3) {
        val request = requests[handle] ?: return
        remove(handle)
        materialize(handle, request.world, position, request.spec)
    }

    override fun removeAll() {
        requests.keys.toList().forEach(::remove)
        packets?.stop()
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEntityEvent) {
        val handle = hitboxes[event.rightClicked.uniqueId] ?: return
        event.isCancelled = true
        if (event.hand == EquipmentSlot.HAND) dispatch(event.player.uniqueId, handle, NpcInteractionClick.RIGHT)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDamage(event: EntityDamageEvent) {
        val handle = hitboxes[event.entity.uniqueId] ?: return
        event.isCancelled = true
        val attacker = (event as? EntityDamageByEntityEvent)?.damager as? Player ?: return
        dispatch(attacker.uniqueId, handle, NpcInteractionClick.LEFT)
    }

    private fun dispatch(playerId: UUID, handle: EntityHandle, click: NpcInteractionClick) {
        scheduler.run(ExecutionScope.Entity(playerId), Duration.ZERO) {
            val intent = interactions.resolve(handle, click) ?: return@run
            val player = mcServer.findPlayer(PlayerId(playerId)) ?: return@run
            intents.dispatch(player, intent)
        }
    }
}
