package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.match.application.port.outbound.INormaPresentation
import com.astarworks.astera.match.domain.norma.NormaGoal
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.Firework
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.plugin.java.JavaPlugin
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper [INormaPresentation]: every title, sound cue, action bar and particle comes from the `norma-*` rows of
 * `content/presentation/events.yaml` through [IPresentation] (FX-05); this class only adds the chat line to the match
 * and the detonated firework. A vanilla firework explosion hurts everything within a few blocks, the level-up
 * player included, so damage dealt by these fireworks is cancelled (LOWEST, before any damage reroute sees it).
 */
public class PaperNormaPresentation(
    private val plugin: JavaPlugin,
    private val renderer: IMessageRenderer,
    private val presentation: IPresentation,
) : INormaPresentation, Listener {
    private val server: Server = plugin.server

    /** Our own fireworks in flight; an entry leaves the set when the firework leaves the world (after its blast). */
    private val ours: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    init {
        server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onFireworkDamage(event: EntityDamageByEntityEvent) {
        if (event.damager.uniqueId in ours) event.isCancelled = true
    }

    @EventHandler
    public fun onFireworkGone(event: EntityRemoveFromWorldEvent) {
        if (event.entity is Firework) ours.remove(event.entity.uniqueId)
    }

    override fun levelUp(player: PlayerId, audience: List<PlayerId>, level: Int, maxLevel: Int) {
        val p = server.getPlayer(player.uuid) ?: return
        val top = level >= maxLevel
        val args = mapOf("level" to level.toString(), "max" to maxLevel.toString(), "player" to p.name)
        val at = p.location.clone().add(0.0, 1.0, 0.0)
        presentation.play(
            if (top) PresentationMoments.NORMA_LEVEL_MAX else PresentationMoments.NORMA_LEVEL_UP,
            player, args, Vec3(at.x, at.y, at.z), WorldId(p.world.name), audience,
        )
        firework(at, big = top)
        val chatKey = MessageKey(if (top) "astera.match.norma-up.max.broadcast" else "astera.match.norma-up.broadcast")
        for (id in audience) {
            val viewer = server.getPlayer(id.uuid) ?: continue
            viewer.sendMessage(AsteraTextDeserializer.deserialize(renderer.render(id, chatKey, args)))
        }
    }

    override fun choiceOpened(player: PlayerId) {
        presentation.play(PresentationMoments.NORMA_CHOICE_OPEN, player)
    }

    override fun choicePicked(player: PlayerId, goal: NormaGoal, target: Int) {
        presentation.play(PresentationMoments.NORMA_CHOICE_PICKED, player, mapOf("goal" to target.toString(), "kind" to goal.name.lowercase()))
    }

    override fun choiceDefaulted(player: PlayerId, goal: NormaGoal, target: Int) {
        presentation.play(PresentationMoments.NORMA_CHOICE_DEFAULTED, player, mapOf("goal" to target.toString(), "kind" to goal.name.lowercase()))
    }

    private fun firework(at: Location, big: Boolean) {
        at.world.spawn(at, Firework::class.java) { fw ->
            ours += fw.uniqueId
            val meta = fw.fireworkMeta
            meta.addEffect(
                FireworkEffect.builder()
                    .with(if (big) FireworkEffect.Type.BALL_LARGE else FireworkEffect.Type.STAR)
                    .withColor(Color.ORANGE, Color.YELLOW)
                    .withFade(Color.WHITE)
                    .flicker(true)
                    .trail(big)
                    .build(),
            )
            fw.fireworkMeta = meta
            fw.isSilent = true
        }.detonate()
    }
}
