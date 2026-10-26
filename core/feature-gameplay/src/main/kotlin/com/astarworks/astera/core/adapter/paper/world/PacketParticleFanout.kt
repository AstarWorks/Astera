package com.astarworks.astera.core.adapter.paper.world

import com.astarworks.astera.core.application.service.observability.ExecutionCosts
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper
import com.github.retrooper.packetevents.protocol.particle.Particle as PacketParticle
import com.github.retrooper.packetevents.protocol.particle.data.*
import com.github.retrooper.packetevents.protocol.particle.type.ParticleType
import com.github.retrooper.packetevents.protocol.color.Color as PacketColor
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.util.Vector3i
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Vibration
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Encode a native server-protocol packet once. Netty owns one retained view per recipient. */
internal class PacketParticleFanout {
    fun send(world: World, at: Vec3, particle: Particle, spec: ParticleSpec, data: Any?, receivers: List<Player>?): Boolean {
        val packetParticle = convert(particle, data) ?: return false
        val distance = if (spec.force) 512.0 else 32.0
        val users = (receivers ?: world.players).mapNotNull { viewer ->
            if (viewer.world != world) return@mapNotNull null
            val p = viewer.location
            val squared = Vec3(p.x, p.y, p.z).distanceSquared(at)
            if (squared > distance * distance) return@mapNotNull null
            PacketEvents.getAPI().playerManager.getUser(viewer)
        }
        if (users.isEmpty()) return true
        val packet = WrapperPlayServerParticle(packetParticle, spec.force,
            Vector3d(at.x, at.y, at.z), Vector3f(spec.offset.x.toFloat(), spec.offset.y.toFloat(), spec.offset.z.toFloat()),
            spec.extra.toFloat(), spec.count)
        // Backend bytes use the server protocol; ViaVersion/proxies translate downstream per connection.
        try {
            packet.prepareForSend(users.first().channel, true, false)
            ExecutionCosts.increment("combat_vfx_packets_encoded")
            val buffer = packet.buffer
            for (user in users) {
                val retained = ByteBufHelper.retainedDuplicate(buffer)
                user.sendPacket(retained)
                ExecutionCosts.increment("combat_vfx_packet_writes")
            }
        } finally {
            packet.buffer?.let { ByteBufHelper.release(it) }
            packet.buffer = null
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun convert(particle: Particle, data: Any?): PacketParticle<*>? {
        val type = SpigotConversionUtil.fromBukkitParticle(particle) as? ParticleType<ParticleData> ?: return null
        val converted: ParticleData? = when (data) {
            null -> null
            is Particle.DustTransition -> ParticleDustColorTransitionData(data.size, color(data.color), color(data.toColor))
            is Particle.DustOptions -> ParticleDustData(data.size, color(data.color))
            is Color -> ParticleColorData(data.asARGB())
            is Particle.Spell -> ParticleSpellData(color(data.color), data.power)
            is BlockData -> ParticleBlockStateData(SpigotConversionUtil.fromBukkitBlockData(data))
            is ItemStack -> ParticleItemStackData(SpigotConversionUtil.fromBukkitItemStack(data))
            is Particle.Trail -> ParticleTrailData(Vector3d(data.target.x, data.target.y, data.target.z), color(data.color), data.duration)
            is Vibration -> {
                val destination = data.destination as? Vibration.Destination.BlockDestination ?: return null
                val p = destination.location
                ParticleVibrationData(Vector3i(0, 0, 0), Vector3i(p.blockX, p.blockY, p.blockZ), data.arrivalTime)
            }
            is Float -> if (particle == Particle.SCULK_CHARGE) ParticleSculkChargeData(data) else ParticlePowerData(data)
            is Int -> ParticleShriekData(data)
            else -> return null
        }
        return if (converted == null) PacketParticle(type) else PacketParticle(type, converted)
    }

    private fun color(c: Color): PacketColor = PacketColor(c.red, c.green, c.blue)
}
