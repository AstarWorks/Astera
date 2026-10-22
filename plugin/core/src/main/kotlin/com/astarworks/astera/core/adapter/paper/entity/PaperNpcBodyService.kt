package com.astarworks.astera.core.adapter.paper.entity

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.entity.INpcBodyMaterializer
import com.astarworks.astera.core.application.port.outbound.entity.INpcInteractionRouter
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.NpcInteractionClick
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.inventory.EquipmentSlot
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper body for the lobby NPCs declared in `content/lobby-npcs/`: each NPC is
 * a frozen, invulnerable, non-persistent [Villager] tagged (PDC) with its
 * [EntityHandle]. A right / left click on the body looks up the intent bound in
 * [INpcInteractionRouter] and dispatches it through [IGuiIntentRouter], the
 * same path a GUI tile click takes (so `lobby.shop.open` opens the shop).
 *
 * The villager is never saved to disk (`isPersistent = false`) and is removed
 * in [removeAll] on plugin disable, so a restart re-spawns each NPC exactly once.
 */
internal class PaperNpcBodyService(
    private val plugin: JavaPlugin,
    private val mcServer: IMcServer,
    private val messages: IMessageRenderer,
    private val interactions: INpcInteractionRouter,
    private val intents: IGuiIntentRouter,
) : INpcBodyMaterializer, Listener {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tagKey = NamespacedKey(plugin, TAG)
    private val bodies = ConcurrentHashMap<EntityHandle, UUID>()
    private val pending = java.util.concurrent.CopyOnWriteArrayList<PendingBody>()

    /**
     * Worlds that are being created after startup (the lobby world, ADR-0180). A body for one of
     * these stays pending instead of falling back to the main world; clear the set and call
     * [flush] once the world exists (or its creation failed).
     */
    @Volatile
    var awaitedWorlds: Set<String> = emptySet()

    fun flush() {
        if (plugin.server.isPrimaryThread) flushPending() else plugin.server.scheduler.runTask(plugin, Runnable { flushPending() })
    }

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.scheduler.runTask(plugin, Runnable { flushPending() })
    }

    override fun materialize(
        handle: EntityHandle,
        world: WorldId,
        position: Vec3,
        yaw: Float,
        pitch: Float,
        displayName: MessageKey,
    ) {
        val request = PendingBody(handle, world, position, yaw, pitch, displayName)
        val run = Runnable { if (!trySpawn(request)) pending += request }
        if (plugin.server.isPrimaryThread) run.run() else plugin.server.scheduler.runTask(plugin, run)
    }

    override fun removeAll() {
        val remove = Runnable {
            pending.clear()
            bodies.values.forEach { id -> plugin.server.getEntity(id)?.remove() }
            bodies.clear()
        }
        if (plugin.server.isPrimaryThread) remove.run() else plugin.server.scheduler.runTask(plugin, remove)
    }

    /**
     * Core enables before the server has loaded its worlds, so early requests are parked in
     * [pending] and flushed once worlds exist (ServerLoadEvent, or the first tick as a fallback).
     */
    @EventHandler
    fun onServerLoad(event: ServerLoadEvent) {
        flushPending()
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        val batch = pending.toList()
        pending.clear()
        batch.forEach { if (!trySpawn(it)) pending += it }
    }

    /** `false` when no world is loaded yet (caller keeps the request pending). */
    private fun trySpawn(req: PendingBody): Boolean {
        // "lobby" is the logical world name: the sky-island world (ADR-0180) when loaded,
        // else the server's main world (see PaperWorldGateway.lobbySpawn).
        val named = plugin.server.getWorld(req.world.value)
        if (named == null && req.world.value in awaitedWorlds) return false
        val bukkitWorld = named ?: plugin.server.worlds.firstOrNull()
            ?: return false
        val location = Location(bukkitWorld, req.position.x, req.position.y, req.position.z, req.yaw, req.pitch)
        val name = MiniMessage.miniMessage().deserialize(messages.render(null, req.displayName))
        val villager = bukkitWorld.spawn(location, Villager::class.java) { v ->
            v.setAI(false)
            v.isInvulnerable = true
            v.isSilent = true
            PaperManagedEntities.mark(v, "npc:${req.handle.id}")
            v.removeWhenFarAway = false
            v.customName(name)
            v.isCustomNameVisible = true
            v.persistentDataContainer.set(tagKey, PersistentDataType.STRING, encode(req.handle))
        }
        bodies[req.handle] = villager.uniqueId
        return true
    }

    private data class PendingBody(
        val handle: EntityHandle,
        val world: WorldId,
        val position: Vec3,
        val yaw: Float,
        val pitch: Float,
        val displayName: MessageKey,
    )

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEntityEvent) {
        val handle = handleOf(event.rightClicked) ?: return
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) return
        dispatch(event.player, handle, NpcInteractionClick.RIGHT)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDamage(event: EntityDamageEvent) {
        val handle = handleOf(event.entity) ?: return
        event.isCancelled = true
        val attacker = (event as? EntityDamageByEntityEvent)?.damager as? Player ?: return
        dispatch(attacker, handle, NpcInteractionClick.LEFT)
    }

    private fun dispatch(bukkitPlayer: Player, handle: EntityHandle, click: NpcInteractionClick) {
        val intent = interactions.resolve(handle, click) ?: return
        val player = mcServer.findPlayer(PlayerId(bukkitPlayer.uniqueId)) ?: return
        intents.dispatch(player, intent)
    }

    private fun handleOf(entity: Entity): EntityHandle? {
        val raw = entity.persistentDataContainer.get(tagKey, PersistentDataType.STRING) ?: return null
        val (id, backend) = raw.split('|', limit = 2).takeIf { it.size == 2 } ?: return null
        return runCatching { EntityHandle(UUID.fromString(id), backend) }.getOrNull()
    }

    private fun encode(handle: EntityHandle): String = "${handle.id}|${handle.backendId}"

    private companion object {
        const val TAG = "lobby_npc"
    }
}
