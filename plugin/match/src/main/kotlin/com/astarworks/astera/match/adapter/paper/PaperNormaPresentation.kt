package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import com.astarworks.astera.core.adapter.paper.world.assertWorldOwnership
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.match.application.port.outbound.INormaPresentation
import com.astarworks.astera.match.domain.norma.NormaGoal
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import kotlin.time.Duration
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.Firework

/**
 * Paper [INormaPresentation]: every title, sound cue, action bar and particle comes from the `norma-*` rows of
 * `content/presentation/events.yaml` through [IPresentation] (FX-05); this class only adds the chat line to the match
 * and the detonated firework.
 */
public class PaperNormaPresentation(
    private val server: Server,
    private val renderer: IMessageRenderer,
    private val presentation: IPresentation,
    private val scheduler: IScheduler,
) : INormaPresentation {

    override fun levelUp(player: PlayerId, audience: List<PlayerId>, level: Int, maxLevel: Int) {
        scheduler.run(ExecutionScope.Entity(player.uuid), Duration.ZERO) action@ {
            val p = server.getPlayer(player.uuid) ?: return@action
            val top = level >= maxLevel
            val args = mapOf("level" to level.toString(), "max" to maxLevel.toString(), "player" to p.name)
            val at = p.location.clone().add(0.0, 1.0, 0.0)
            presentation.play(
                if (top) PresentationMoments.NORMA_LEVEL_MAX else PresentationMoments.NORMA_LEVEL_UP,
                player, args, Vec3(at.x, at.y, at.z), WorldId(p.world.name), audience,
            )
            assertWorldOwnership(scheduler, ExecutionScope.Entity(player.uuid))
            firework(at, big = top)
            val chatKey = MessageKey(if (top) "astera.match.norma-up.max.broadcast" else "astera.match.norma-up.broadcast")
            for (id in audience) {
                val viewer = server.getPlayer(id.uuid) ?: continue
                viewer.sendMessage(AsteraTextDeserializer.deserialize(renderer.render(id, chatKey, args)))
            }
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
