package com.astarworks.astera.combat.adapter.paper.preview

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import com.astarworks.astera.combat.application.port.outbound.ISkillPreviewStage
import com.astarworks.astera.combat.application.port.outbound.PreviewStage
import com.astarworks.astera.combat.application.port.outbound.PreviewStageSpec
import com.astarworks.astera.combat.application.preview.PreviewGeometry
import com.astarworks.astera.combat.application.preview.ViewPoint
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Husk
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mannequin
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * Paper [ISkillPreviewStage] for `/astera skill preview`: a vanilla [Mannequin] caster (player-like, holds
 * the skill's weapon), a no-AI [Husk] target (killable, healed by the service), and the viewer's spectator
 * camera with gamemode + location restore (also on quit). Dummies are non-persistent and tagged so strays
 * from a crash are swept on the next spawn. Main thread only.
 */
internal class PaperSkillPreviewStage(plugin: JavaPlugin, listeners: OwnedListeners) : ISkillPreviewStage, Listener {

    private class Saved(val mode: GameMode, val location: Location)

    private val server: Server = plugin.server
    private val saved = HashMap<UUID, Saved>()
    private var quitHandler: (PlayerId) -> Unit = {}
    private var swept = false

    init {
        listeners.register(this)
    }

    override fun spawn(spec: PreviewStageSpec): PreviewStage? {
        val world = server.getWorld(spec.world.value) ?: return null
        if (!swept) {
            world.entities.filter { TAG in it.scoreboardTags }.forEach { it.remove() }
            swept = true
        }
        val axis = spec.targetAt - spec.casterAt
        val casterLoc = Location(world, spec.casterAt.x, spec.casterAt.y, spec.casterAt.z, PreviewGeometry.yawOf(axis), 0f)
        val targetLoc = Location(world, spec.targetAt.x, spec.targetAt.y, spec.targetAt.z, PreviewGeometry.yawOf(axis * -1.0), 0f)
        world.getChunkAt(casterLoc).load()
        world.getChunkAt(targetLoc).load()
        val weapon = Material.matchMaterial(spec.weaponMaterial) ?: Material.IRON_SWORD
        val caster = world.spawn(casterLoc, Mannequin::class.java) { m ->
            com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities.mark(m, "skill-preview")
            m.isSilent = true
            m.isInvulnerable = true
            m.isImmovable = true
            m.setGravity(false)
            m.addScoreboardTag(TAG)
            m.equipment.setItemInMainHand(ItemStack(weapon))
        }
        val target = world.spawn(targetLoc, Husk::class.java) { h ->
            com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities.mark(h, "skill-preview")
            h.isSilent = true
            h.setAI(false)
            h.setGravity(false)
            h.isInvulnerable = false
            h.removeWhenFarAway = false
            h.canPickupItems = false
            h.addScoreboardTag(TAG)
        }
        return PreviewStage(PlayerId(caster.uniqueId), PlayerId(target.uniqueId), spec)
    }

    override fun healTarget(stage: PreviewStage) {
        val target = server.getEntity(stage.target.uuid) as? LivingEntity ?: return
        val max = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: return
        target.health = max
    }

    override fun despawn(stage: PreviewStage) {
        healTarget(stage)
        server.getEntity(stage.caster.uuid)?.remove()
        server.getEntity(stage.target.uuid)?.remove()
    }

    override fun placeViewer(viewer: PlayerId, point: ViewPoint) {
        val player = server.getPlayer(viewer.uuid) ?: return
        saved.getOrPut(viewer.uuid) { Saved(player.gameMode, player.location.clone()) }
        player.gameMode = GameMode.SPECTATOR
        val at = point.position
        player.teleportAsync(Location(player.world, at.x, at.y, at.z, point.yaw, point.pitch))
    }

    override fun restoreViewer(viewer: PlayerId) {
        val state = saved.remove(viewer.uuid) ?: return
        val player = server.getPlayer(viewer.uuid) ?: return
        player.gameMode = state.mode
        player.teleportAsync(state.location)
    }

    override fun onViewerQuit(handler: (PlayerId) -> Unit) {
        quitHandler = handler
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val id = PlayerId(event.player.uniqueId)
        if (id.uuid !in saved) return
        quitHandler(id)
        restoreViewer(id)
    }

    private companion object {
        const val TAG = "astera_skill_preview"
    }
}
