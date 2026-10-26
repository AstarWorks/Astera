package com.astarworks.astera.core.adapter.paper.packetevents

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.NpcInteractionClick
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.InteractionHand
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.plugin.Plugin
import java.util.EnumSet
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Fake players never enter a server world. Each viewer owns its visibility and packet lifecycle. */
public class PacketEventsNpcPlayers(
    private val plugin: Plugin,
    private val scheduler: IScheduler,
    private val click: (UUID, EntityHandle, NpcInteractionClick) -> Unit,
) : PacketListener, Listener {
    private data class Body(val handle: EntityHandle, val world: WorldId, val at: Vec3, val spec: SpawnSpec.PlayerNpc, val entityId: Int)
    private val bodies = ConcurrentHashMap<EntityHandle, Body>()
    private val byEntityId = ConcurrentHashMap<Int, Body>()
    private data class Hitbox(val handle: EntityHandle, val world: WorldId, val at: Vec3)
    private val hitboxes = ConcurrentHashMap<Int, Hitbox>()
    private data class Click(val handle: EntityHandle, val kind: NpcInteractionClick, val tick: Long)
    private val lastClick = ConcurrentHashMap<UUID, Click>()

    public fun bindInteraction(entityId: Int, handle: EntityHandle, world: WorldId, at: Vec3) {
        hitboxes[entityId] = Hitbox(handle, world, at)
    }

    public fun unbindInteraction(entityId: Int) { hitboxes.remove(entityId) }
    private val visible = ConcurrentHashMap<UUID, MutableMap<EntityHandle, Body>>()
    private var task: IScheduler.TaskHandle? = null
    private var listener: com.github.retrooper.packetevents.event.PacketListenerCommon? = null

    public fun start() {
        listener = PacketEvents.getAPI().eventManager.registerListener(this, com.github.retrooper.packetevents.event.PacketListenerPriority.NORMAL)
        plugin.server.pluginManager.registerEvents(this, plugin)
        task = scheduler.repeat(ExecutionScope.Global, 50.milliseconds, 250.milliseconds) {
            plugin.server.onlinePlayers.forEach { viewer ->
                scheduler.run(ExecutionScope.Entity(viewer.uniqueId), Duration.ZERO) { refresh(viewer) }
            }
        }
    }

    public fun put(handle: EntityHandle, world: WorldId, at: Vec3, spec: SpawnSpec.PlayerNpc) {
        if (bodies.containsKey(handle)) return
        val body = Body(handle, world, at, spec, io.github.retrooper.packetevents.util.SpigotReflectionUtil.generateEntityId())
        bodies[handle] = body
        byEntityId[body.entityId] = body
    }

    public fun remove(handle: EntityHandle) {
        val body = bodies.remove(handle) ?: return
        byEntityId.remove(body.entityId)
    }

    private fun refresh(viewer: Player) {
        val seen = visible.computeIfAbsent(viewer.uniqueId) { ConcurrentHashMap() }
        val location = viewer.location
        val wanted = bodies.values.filter { body ->
            body.world.value == location.world.name && distanceSquared(location.x, location.y, location.z, body.at) <= 64.0 * 64.0
        }.associateBy { it.handle }
        seen.values.toList().filter { wanted[it.handle] !== it }.forEach { body ->
            hide(viewer, body)
            seen.remove(body.handle)
        }
        wanted.values.filter { seen[it.handle] !== it }.forEach { body ->
            show(viewer, body)
            seen[body.handle] = body
        }
    }

    private fun show(viewer: Player, body: Body) {
        val user = PacketEvents.getAPI().playerManager.getUser(viewer) ?: return
        val spec = body.spec
        val profile = UserProfile(body.handle.id, spec.profileName, listOf(TextureProperty("textures", spec.skin.textureValue, spec.skin.signature)))
        val info = WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profile, false, 0, GameMode.SURVIVAL, null, null, 0)
        user.sendPacket(WrapperPlayServerPlayerInfoUpdate(EnumSet.of(
            WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
        ), listOf(info)))
        user.sendPacket(WrapperPlayServerSpawnEntity(body.entityId, Optional.of(body.handle.id), EntityTypes.PLAYER,
            Vector3d(body.at.x, body.at.y, body.at.z), spec.pitch, spec.yaw, spec.yaw, 0, Optional.empty()))
    }

    private fun hide(viewer: Player, body: Body) {
        val user = PacketEvents.getAPI().playerManager.getUser(viewer) ?: return
        user.sendPacket(WrapperPlayServerDestroyEntities(body.entityId))
        user.sendPacket(WrapperPlayServerPlayerInfoRemove(body.handle.id))
    }

    override fun onPacketReceive(event: PacketReceiveEvent) {
        val left = event.packetType == PacketType.Play.Client.ATTACK
        if (!left && event.packetType != PacketType.Play.Client.INTERACT_ENTITY) return
        val interaction = if (left) null else WrapperPlayClientInteractEntity(event)
        val entityId = if (left) WrapperPlayClientAttack(event).entityId else requireNotNull(interaction).entityId
        val body = byEntityId[entityId]
        val hitbox = hitboxes[entityId]
        if (body == null && hitbox == null) return
        val handle = body?.handle ?: requireNotNull(hitbox).handle
        val world = body?.world ?: requireNotNull(hitbox).world
        val position = body?.at ?: requireNotNull(hitbox).at
        event.isCancelled = true
        if (!left && interaction?.hand != InteractionHand.MAIN_HAND) return
        val id = event.user.uuid
        scheduler.run(ExecutionScope.Entity(id), Duration.ZERO) {
            if (body != null && (visible[id]?.get(handle) !== body || bodies[handle] !== body)) return@run
            if (hitbox != null && hitboxes[entityId] !== hitbox) return@run
            val viewer = plugin.server.getPlayer(id) ?: return@run
            val at = viewer.location
            if (at.world.name != world.value || distanceSquared(at.x, at.y, at.z, position) > 36.0) return@run
            // INTERACT_AT and INTERACT can describe the same right click in one tick.
            val current = Click(handle, if (left) NpcInteractionClick.LEFT else NpcInteractionClick.RIGHT, scheduler.currentTick())
            if (lastClick.put(id, current) == current) return@run
            click(id, handle, current.kind)
        }
    }

    @EventHandler public fun onQuit(event: PlayerQuitEvent) {
        visible.remove(event.player.uniqueId)
        lastClick.remove(event.player.uniqueId)
    }
    @EventHandler public fun onWorldChange(event: PlayerChangedWorldEvent) { reset(event.player) }
    @EventHandler public fun onRespawn(event: PlayerRespawnEvent) { reset(event.player) }

    private fun reset(player: Player) {
        visible.remove(player.uniqueId)?.values?.forEach { hide(player, it) }
    }

    public fun stop() {
        task?.cancel()
        listener?.let { PacketEvents.getAPI().eventManager.unregisterListener(it) }
        listener = null
        org.bukkit.event.HandlerList.unregisterAll(this)
        plugin.server.onlinePlayers.forEach { viewer ->
            scheduler.run(ExecutionScope.Entity(viewer.uniqueId), Duration.ZERO) {
                visible.remove(viewer.uniqueId)?.values?.forEach { hide(viewer, it) }
            }
        }
        bodies.clear()
        byEntityId.clear()
        hitboxes.clear()
        lastClick.clear()
    }

    private fun distanceSquared(x: Double, y: Double, z: Double, at: Vec3): Double =
        (x - at.x) * (x - at.x) + (y - at.y) * (y - at.y) + (z - at.z) * (z - at.z)

}
