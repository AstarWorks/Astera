package com.astarworks.astera.combat.adapter.paper.training

import com.astarworks.astera.combat.application.port.outbound.ITrainingDummyShells
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.lobby.TrainingDummySpot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.Entity
import org.bukkit.entity.Mannequin
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import java.util.UUID

/**
 * Paper [ITrainingDummyShells] (F3-11): a non-persistent [Mannequin] per dummy — no AI, no gravity,
 * immovable, name on top and the HP / DPS line as its description. The body is only a hitbox: Astera damage
 * never reaches it (the dummy gateway intercepts its id) and [PaperTrainingDummyGuard] cancels every vanilla
 * damage event on it. Bodies are tagged so strays from a crash or `/reload` are swept on the first spawn.
 * Main thread only.
 */
internal class PaperTrainingDummyShells(plugin: JavaPlugin) : ITrainingDummyShells {

    private val server: Server = plugin.server
    private val bodies = HashSet<UUID>()
    private var swept = false

    override fun spawn(world: WorldId, spot: TrainingDummySpot, at: Vec3): PlayerId? {
        val bukkitWorld = server.getWorld(world.value) ?: return null
        if (!swept) {
            bukkitWorld.entities.filter { TAG in it.scoreboardTags }.forEach { it.remove() }
            swept = true
        }
        val loc = Location(bukkitWorld, at.x, at.y, at.z, spot.yaw, 0f)
        bukkitWorld.getChunkAt(loc).load()
        val body = bukkitWorld.spawn(loc, Mannequin::class.java) { m ->
            com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities.mark(m, "training-dummy")
            m.isSilent = true
            m.setGravity(false)
            m.isInvulnerable = false // targeting skips invulnerable entities; vanilla damage is cancelled instead
            m.isImmovable = true
            m.isCustomNameVisible = true
            m.addScoreboardTag(TAG)
        }
        bodies += body.uniqueId
        return PlayerId(body.uniqueId)
    }

    override fun isAlive(id: PlayerId): Boolean = entity(id)?.isValid == true

    override fun positionOf(id: PlayerId): Vec3? = entity(id)?.location?.let { Vec3(it.x, it.y, it.z) }

    override fun moveTo(id: PlayerId, at: Vec3) {
        val body = entity(id) ?: return
        body.velocity = Vector(0.0, 0.0, 0.0)
        body.teleport(Location(body.world, at.x, at.y, at.z, body.location.yaw, 0f))
    }

    override fun setLabel(id: PlayerId, lines: List<String>) {
        val body = entity(id) as? Mannequin ?: return
        body.customName(lines.firstOrNull()?.let(AsteraTextDeserializer::deserialize))
        body.description = lines.getOrNull(1)?.let(AsteraTextDeserializer::deserialize) ?: Component.empty()
    }

    override fun remove(id: PlayerId) {
        entity(id)?.remove()
        bodies -= id.uuid
    }

    override fun removeAll() {
        bodies.toList().forEach { server.getEntity(it)?.remove() }
        bodies.clear()
    }

    private fun entity(id: PlayerId): Entity? = server.getEntity(id.uuid)

    companion object {
        const val TAG: String = "astera_training_dummy"
    }
}

/** Training dummies are immortal: every vanilla damage event on a body is cancelled (Astera's HP model is the only one). */
internal class PaperTrainingDummyGuard : Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDamage(event: EntityDamageEvent) {
        if (PaperTrainingDummyShells.TAG in event.entity.scoreboardTags) event.isCancelled = true
    }
}
