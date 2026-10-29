package com.astarworks.astera.core.adapter.paper.presentation

import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import com.astarworks.astera.core.application.port.outbound.IActionBarChannel
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.post
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentationVisuals
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.ParticlePreset
import com.astarworks.astera.core.domain.world.WorldId
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Server
import org.slf4j.LoggerFactory
import java.time.Duration

/** Paper [IPresentationVisuals]: Adventure titles / action bar and per-viewer `Player.spawnParticle`. */
public class PaperPresentationVisuals(
    private val server: Server,
    private val renderer: IMessageRenderer,
    private val actionBar: IActionBarChannel,
) : IPresentationVisuals {

    private val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
    private val log = LoggerFactory.getLogger(javaClass)

    override fun title(player: PlayerId, title: MessageKey?, subtitle: MessageKey?, args: Map<String, String>, stayTicks: Int) {
        val p = server.getPlayer(player.uuid) ?: return
        p.showTitle(
            Title.title(
                title?.let { render(player, it, args) } ?: Component.empty(),
                subtitle?.let { render(player, it, args) } ?: Component.empty(),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(stayTicks * 50L), Duration.ofMillis(500)),
            ),
        )
    }

    override fun chat(player: PlayerId, key: MessageKey, args: Map<String, String>) {
        server.getPlayer(player.uuid)?.sendMessage(legacy.deserialize(renderer.render(player, key, args)))
    }

    override fun actionBar(player: PlayerId, key: MessageKey, args: Map<String, String>) {
        actionBar.post(player, key, args)
    }

    override fun particles(preset: ParticlePreset, viewers: Collection<PlayerId>, anchor: PlayerId, at: Vec3?, world: WorldId?) {
        val particle = runCatching { Particle.valueOf(preset.particle.uppercase()) }.getOrNull() ?: run {
            log.warn("[cue-dropped] particle: unknown presentation particle '{}'", preset.particle)
            return
        }
        val w = world?.let { server.getWorld(it.value) } ?: server.getPlayer(anchor.uuid)?.world ?: return
        val base = at?.let { Location(w, it.x, it.y, it.z) } ?: server.getPlayer(anchor.uuid)?.location ?: return
        val loc = base.clone().add(0.0, preset.yOffset, 0.0)
        for (id in viewers) {
            val viewer = server.getPlayer(id.uuid) ?: continue
            if (viewer.world != w) continue
            viewer.spawnParticle(particle, loc, preset.count, preset.spreadX, preset.spreadY, preset.spreadZ, preset.speed)
        }
    }

    private fun render(player: PlayerId, key: MessageKey, args: Map<String, String>): Component =
        if (key.value.startsWith("astera.announce.")) legacy.deserialize(renderer.render(player, key, args))
        else AsteraTextDeserializer.deserialize(renderer.render(player, key, args))
}
