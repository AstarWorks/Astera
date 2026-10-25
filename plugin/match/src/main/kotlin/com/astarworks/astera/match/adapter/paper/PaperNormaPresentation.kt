package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.effect.ISoundCuePlayer
import com.astarworks.astera.core.domain.effect.SoundCues
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.INormaPresentation
import com.astarworks.astera.match.domain.norma.NormaGoal
import net.kyori.adventure.title.Title
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Server
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import java.time.Duration

/** Paper [INormaPresentation]: Adventure titles / action bar, sound cues (ADR-0256), particles and a detonated firework. */
public class PaperNormaPresentation(
    private val server: Server,
    private val renderer: IMessageRenderer,
    private val cues: ISoundCuePlayer,
) : INormaPresentation {

    override fun levelUp(player: PlayerId, audience: List<PlayerId>, level: Int, maxLevel: Int) {
        val p = server.getPlayer(player.uuid) ?: return
        val top = level >= maxLevel
        val args = mapOf("level" to level.toString(), "max" to maxLevel.toString(), "player" to p.name)
        val prefix = if (top) "astera.match.norma-up.max" else "astera.match.norma-up"
        title(p, MessageKey("$prefix.title"), MessageKey("$prefix.subtitle"), args, if (top) 80 else 50)
        val at = p.location.clone().add(0.0, 1.0, 0.0)
        val w = p.world
        if (top) {
            cues.play(player, SoundCues.NORMA_LEVEL_MAX, Vec3(at.x, at.y, at.z))
            cues.playAll(audience, SoundCues.NORMA_BURST_MAX, Vec3(at.x, at.y, at.z))
            w.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 120, 0.8, 1.0, 0.8, 0.5)
            w.spawnParticle(Particle.END_ROD, at, 60, 0.6, 1.2, 0.6, 0.15)
            firework(at, big = true)
        } else {
            cues.play(player, SoundCues.NORMA_LEVEL_UP, Vec3(at.x, at.y, at.z))
            cues.playAll(audience, SoundCues.NORMA_BURST, Vec3(at.x, at.y, at.z))
            w.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 40, 0.5, 0.8, 0.5, 0.3)
            firework(at, big = false)
        }
        val chatKey = MessageKey(if (top) "astera.match.norma-up.max.broadcast" else "astera.match.norma-up.broadcast")
        for (id in audience) {
            val viewer = server.getPlayer(id.uuid) ?: continue
            viewer.sendMessage(AsteraTextDeserializer.deserialize(renderer.render(id, chatKey, args)))
        }
    }

    override fun choiceOpened(player: PlayerId) {
        val p = server.getPlayer(player.uuid) ?: return
        title(p, MessageKey("astera.match.norma-choice.open.title"), MessageKey("astera.match.norma-choice.open.subtitle"), emptyMap(), 40)
        cues.play(player, SoundCues.NORMA_CHOICE_OPEN)
    }

    override fun choicePicked(player: PlayerId, goal: NormaGoal, target: Int) {
        val p = server.getPlayer(player.uuid) ?: return
        actionBar(p, MessageKey("astera.match.norma-choice.picked.${goal.name.lowercase()}"), mapOf("goal" to target.toString()))
        cues.play(player, SoundCues.NORMA_CHOICE_PICKED)
    }

    override fun choiceDefaulted(player: PlayerId, goal: NormaGoal, target: Int) {
        val p = server.getPlayer(player.uuid) ?: return
        actionBar(p, MessageKey("astera.match.norma-choice.defaulted.${goal.name.lowercase()}"), mapOf("goal" to target.toString()))
        cues.play(player, SoundCues.NORMA_CHOICE_DEFAULTED)
    }

    private fun title(p: Player, title: MessageKey, subtitle: MessageKey, args: Map<String, String>, stayTicks: Int) {
        val id = PlayerId(p.uniqueId)
        p.showTitle(
            Title.title(
                AsteraTextDeserializer.deserialize(renderer.render(id, title, args)),
                AsteraTextDeserializer.deserialize(renderer.render(id, subtitle, args)),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(stayTicks * 50L), Duration.ofMillis(500)),
            ),
        )
    }

    private fun actionBar(p: Player, key: MessageKey, args: Map<String, String>) {
        p.sendActionBar(AsteraTextDeserializer.deserialize(renderer.render(PlayerId(p.uniqueId), key, args)))
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
