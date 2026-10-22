package com.astarworks.astera.core.adapter.paper.world

import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleData
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.effect.ParticleTarget
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Vibration
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack

/**
 * Maps a [ParticleSpec]'s typed [ParticleData] to the object `World.spawnParticle` expects for a
 * given Bukkit [Particle.getDataType]. The mapping is keyed by the *data type*, not the particle
 * name, so every particle of a family (all five `BlockData` ones, ...) works without a name list.
 *
 * A `null` result means "this spec cannot supply what the particle needs" (the caller drops the
 * spawn); [Mapped] boxes the value so "no data needed" differs from "unavailable".
 */
internal object PaperParticleData {

    class Mapped(val value: Any?)

    fun map(bukkit: Particle, spec: ParticleSpec, world: World?, blockData: (String) -> BlockData?): Mapped? {
        val data = spec.data
        val legacyRgb = spec.color?.toBukkit() ?: Color.WHITE
        val legacyScale = spec.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val type = bukkit.dataType
        return when (type) {
            Void::class.java -> Mapped(null)
            Particle.DustOptions::class.java -> when (data) {
                null -> Mapped(Particle.DustOptions(legacyRgb, legacyScale))
                is ParticleData.Dust -> Mapped(Particle.DustOptions(data.color.toBukkit(), data.scale.coerceIn(MIN_SCALE, MAX_SCALE)))
                else -> null
            }
            Particle.DustTransition::class.java -> when (data) {
                null -> Mapped(Particle.DustTransition(legacyRgb, Color.WHITE, legacyScale))
                is ParticleData.DustTransition -> Mapped(
                    Particle.DustTransition(data.from.toBukkit(), data.to.toBukkit(), data.scale.coerceIn(MIN_SCALE, MAX_SCALE)),
                )
                else -> null
            }
            Color::class.java -> when (data) {
                null -> Mapped(legacyRgb)
                is ParticleData.Tint -> Mapped(data.color.toBukkit())
                else -> null
            }
            Particle.Spell::class.java -> when (data) {
                null -> Mapped(Particle.Spell(legacyRgb, 1.0f))
                is ParticleData.Spell -> Mapped(Particle.Spell(data.color.toBukkit(), data.power))
                else -> null
            }
            BlockData::class.java -> (data as? ParticleData.Block)?.let { b -> blockData(b.blockKey)?.let(::Mapped) }
            ItemStack::class.java -> (data as? ParticleData.Item)?.let { i ->
                Material.matchMaterial(i.materialKey)?.takeIf { it.isItem }?.let { Mapped(ItemStack(it)) }
            }
            Particle.Trail::class.java -> (data as? ParticleData.Trail)?.let { t ->
                absolute(t.target)?.let { Mapped(Particle.Trail(Location(world, it.x, it.y, it.z), t.color.toBukkit(), t.durationTicks)) }
            }
            Vibration::class.java -> (data as? ParticleData.Vibration)?.let { v ->
                absolute(v.destination)?.let {
                    Mapped(Vibration(Vibration.Destination.BlockDestination(Location(world, it.x, it.y, it.z)), v.arrivalTicks))
                }
            }
            Float::class.javaObjectType -> when (data) {
                is ParticleData.SculkCharge -> Mapped(data.roll)
                is ParticleData.Power -> Mapped(data.value)
                else -> null
            }
            Int::class.javaObjectType -> (data as? ParticleData.Shriek)?.let { Mapped(it.delayTicks) }
            else -> null
        }
    }

    private fun absolute(t: ParticleTarget) = (t as? ParticleTarget.Absolute)?.position

    private fun ParticleColor.toBukkit(): Color = Color.fromRGB(r, g, b)

    private const val MIN_SCALE = 0.01f
    private const val MAX_SCALE = 4f
}
