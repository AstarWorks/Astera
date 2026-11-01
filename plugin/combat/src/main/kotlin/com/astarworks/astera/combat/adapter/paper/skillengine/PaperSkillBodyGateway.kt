package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.port.outbound.entity.IManagedEntityRegistry
import com.astarworks.astera.core.application.skillengine.runtime.player.ISkillBodyGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import java.util.UUID

/**
 * The world presence of a summon body (ADR-0313 rev): one non-living, non-persistent `Interaction` entity per body, so vanilla melee and arrows have
 * something to hit while [PaperSkillEntityQuery] (living entities only) never sees it twice. The kernel owns hp and teams: a blow on the entity is
 * handed to [onHit] (entity-less id = the body's id) and the vanilla damage is cancelled. Every spawned entity is tagged and swept at start-up,
 * and [remove] / [removeAll] take it down with its run.
 */
public class PaperSkillBodyGateway(
    private val server: Server,
    /** Routes a blow: (body id, attacker, amount). Wired to `TablePlayer.damageBody`. */
    private val onHit: (UUID, UUID, Double) -> Unit,
) : ISkillBodyGateway, Listener {
    private val entities = HashMap<UUID, Entity>()
    private val bodyOfEntity = HashMap<UUID, UUID>()

    public fun sweepOrphans(): Int {
        var removed = 0
        for (world in server.worlds) for (e in world.entities) {
            if (TAG in e.scoreboardTags) { e.remove(); removed++ }
        }
        return removed
    }

    override fun spawn(body: UUID, world: WorldId, feet: Vec3, width: Double, height: Double) {
        val w = server.getWorld(world.value) ?: error("skill engine: world '${world.value}' of the summon body is not loaded")
        val e = w.spawn(Location(w, feet.x, feet.y, feet.z), Interaction::class.java) {
            it.interactionWidth = width.toFloat()
            it.interactionHeight = height.toFloat()
            it.isResponsive = true
            it.isPersistent = false
            it.addScoreboardTag(TAG)
        }
        entities[body] = e
        bodyOfEntity[e.uniqueId] = body
    }

    override fun remove(body: UUID) {
        val e = entities.remove(body) ?: return
        bodyOfEntity.remove(e.uniqueId)
        e.remove()
    }

    /** Match end, reload and plugin disable: nothing may outlive the engine. */
    public fun removeAll() {
        entities.keys.toList().forEach(::remove)
    }

    @EventHandler(ignoreCancelled = true)
    public fun onDamage(e: EntityDamageByEntityEvent) {
        val body = bodyOfEntity[e.entity.uniqueId] ?: return
        e.isCancelled = true
        val damager = e.damager
        val attacker = (damager as? Projectile)?.shooter.let { (it as? Entity)?.uniqueId } ?: damager.uniqueId
        onHit(body, attacker, e.damage)
    }

    private companion object {
        val TAG: String = IManagedEntityRegistry.OWNER_TAG_PREFIX + "skill-summon-body"
    }
}
