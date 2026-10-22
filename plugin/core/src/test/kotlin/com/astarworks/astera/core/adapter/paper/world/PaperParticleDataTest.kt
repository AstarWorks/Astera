package com.astarworks.astera.core.adapter.paper.world

import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleData
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.effect.ParticleTarget
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Vibration
import org.bukkit.block.data.BlockData
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * Pins the data mapping to the real `Particle.dataType` values of the Paper API on the classpath, so a
 * Paper bump that changes a particle's data type fails here instead of silently dropping particles.
 */
class PaperParticleDataTest {

    private val c = ParticleColor(10, 20, 30)
    private val blockData: (String) -> BlockData? = { key ->
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BlockData::class.java)) { _, m, _ ->
            if (m.name == "getAsString") key else null
        } as BlockData
    }

    private fun map(p: Particle, data: ParticleData?) =
        PaperParticleData.map(p, ParticleSpec(key = p.name.lowercase(), count = 1, data = data), null, blockData)

    @Test
    fun `particle families have the data types the mapper assumes`() {
        val byType = Particle.entries.filter { it.dataType != Void::class.java }.groupBy({ it.dataType }, { it.name })
        assertThat(byType[Particle.DustOptions::class.java]).containsExactly("DUST")
        assertThat(byType[Particle.DustTransition::class.java]).containsExactly("DUST_COLOR_TRANSITION")
        assertThat(byType[Particle.Trail::class.java]).containsExactly("TRAIL")
        assertThat(byType[Vibration::class.java]).containsExactly("VIBRATION")
        assertThat(byType[BlockData::class.java])
            .contains("BLOCK", "BLOCK_CRUMBLE", "BLOCK_MARKER", "FALLING_DUST", "DUST_PILLAR")
        assertThat(byType[Color::class.java]).contains("ENTITY_EFFECT", "FLASH")
        assertThat(byType[Particle.Spell::class.java]).contains("EFFECT", "INSTANT_EFFECT")
        assertThat(byType[Float::class.javaObjectType]).contains("SCULK_CHARGE")
        assertThat(byType[Int::class.javaObjectType]).containsExactly("SHRIEK")
        assertThat(byType[org.bukkit.inventory.ItemStack::class.java]).containsExactly("ITEM")
    }

    @Test
    fun `every data-carrying particle except the geysers is mappable`() {
        val samples = mapOf<Class<*>, ParticleData>(
            Particle.DustOptions::class.java to ParticleData.Dust(c, 1f),
            Particle.DustTransition::class.java to ParticleData.DustTransition(c, c, 1f),
            Color::class.java to ParticleData.Tint(c),
            Particle.Spell::class.java to ParticleData.Spell(c, 1f),
            BlockData::class.java to ParticleData.Block("stone"),
            Particle.Trail::class.java to ParticleData.Trail(ParticleTarget.Absolute(Vec3(1.0, 2.0, 3.0)), c, 10),
            Vibration::class.java to ParticleData.Vibration(ParticleTarget.Absolute(Vec3(1.0, 2.0, 3.0)), 10),
            Int::class.javaObjectType to ParticleData.Shriek(3),
        )
        val unmapped = mutableListOf<String>()
        for (p in Particle.entries) {
            val type = p.dataType
            if (type == Void::class.java) {
                assertThat(map(p, null)?.value).describedAs(p.name).isNull()
                assertThat(map(p, null)).describedAs(p.name).isNotNull()
                continue
            }
            val sample = samples[type] ?: when (type) {
                Float::class.javaObjectType -> if (p.name == "SCULK_CHARGE") ParticleData.SculkCharge(1f) else ParticleData.Power(1f)
                else -> null
            }
            if (map(p, sample) == null) unmapped += "${p.name}:${type.simpleName}"
        }
        // ITEM needs a Bukkit item factory at runtime (checked in game); geysers are not authorable.
        assertThat(unmapped).allMatch { it.startsWith("ITEM:") || it.startsWith("GEYSER") }
    }

    @Test
    fun `a mismatched data variant is rejected`() {
        assertThat(map(Particle.BLOCK, ParticleData.Dust(c))).isNull()
        assertThat(map(Particle.TRAIL, ParticleData.Trail(ParticleTarget.Toward, c))).describedAs("unresolved target").isNull()
        assertThat(map(Particle.BLOCK, null)).describedAs("block without data").isNull()
    }

    @Test
    fun `mapped values carry the authored fields`() {
        val dust = map(Particle.DUST, ParticleData.Dust(c, 9f))!!.value as Particle.DustOptions
        assertThat(dust.color).isEqualTo(Color.fromRGB(10, 20, 30))
        assertThat(dust.size).isEqualTo(4f) // clamped to the vanilla max

        val transition = map(Particle.DUST_COLOR_TRANSITION, ParticleData.DustTransition(c, ParticleColor(1, 2, 3), 2f))!!.value
            as Particle.DustTransition
        assertThat(transition.toColor).isEqualTo(Color.fromRGB(1, 2, 3))

        val trail = map(Particle.TRAIL, ParticleData.Trail(ParticleTarget.Absolute(Vec3(1.0, 2.0, 3.0)), c, 12))!!.value as Particle.Trail
        assertThat(trail.duration).isEqualTo(12)
        assertThat(trail.target.y).isEqualTo(2.0)

        assertThat(map(Particle.SHRIEK, ParticleData.Shriek(7))!!.value).isEqualTo(7)
        assertThat(map(Particle.SCULK_CHARGE, ParticleData.SculkCharge(0.5f))!!.value).isEqualTo(0.5f)
    }

    @Test
    fun `legacy colour and scale still drive dust`() {
        val legacy = PaperParticleData.map(Particle.DUST, ParticleSpec("dust", 1, color = c, scale = 2f), null, blockData)!!.value
            as Particle.DustOptions
        assertThat(legacy.color).isEqualTo(Color.fromRGB(10, 20, 30))
        assertThat(legacy.size).isEqualTo(2f)
    }
}
