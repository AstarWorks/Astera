package com.astarworks.astera.core.adapter.paper.cosmetic

import com.astarworks.astera.core.application.port.outbound.cosmetic.ICosmeticEffectPlayer
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Particle
import org.bukkit.Server

/** Spawns the kill-effect particle burst at the victim (vanilla particles without extra data only). */
public class PaperCosmeticEffectPlayer(private val server: Server) : ICosmeticEffectPlayer {
    override fun playAt(victim: PlayerId, particleKey: String, count: Int) {
        val player = server.getPlayer(victim.uuid) ?: return
        val particle = runCatching {
            Particle.valueOf(particleKey.substringAfter(':').uppercase())
        }.getOrNull() ?: return
        if (particle.dataType != Void::class.java) return
        val at = player.location.add(0.0, 1.0, 0.0)
        player.world.spawnParticle(particle, at, count, 0.4, 0.5, 0.4, 0.05)
    }
}
