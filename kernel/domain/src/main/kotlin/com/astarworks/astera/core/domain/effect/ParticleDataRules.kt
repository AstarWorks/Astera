package com.astarworks.astera.core.domain.effect

import kotlin.reflect.KClass

/**
 * Which [ParticleData] variant each vanilla particle takes (its Bukkit `Particle.dataType`), so a skill whose particle and data
 * do not match is rejected at load instead of being dropped at cast time. Particles not listed take no data.
 */
public object ParticleDataRules {
    private val FAMILIES: Map<String, KClass<out ParticleData>> = buildMap {
        put("dust", ParticleData.Dust::class)
        put("dust_color_transition", ParticleData.DustTransition::class)
        for (k in listOf("entity_effect", "flash", "tinted_leaves")) put(k, ParticleData.Tint::class)
        for (k in listOf("effect", "instant_effect")) put(k, ParticleData.Spell::class)
        for (k in listOf("block", "block_crumble", "block_marker", "falling_dust", "dust_pillar")) put(k, ParticleData.Block::class)
        put("item", ParticleData.Item::class)
        put("trail", ParticleData.Trail::class)
        put("sculk_charge", ParticleData.SculkCharge::class)
        put("shriek", ParticleData.Shriek::class)
        put("vibration", ParticleData.Vibration::class)
        put("dragon_breath", ParticleData.Power::class)
    }

    /** Paper 26.3 particles whose `dataType` (`Particle.Geyser` / `GeyserBase`) the Paper mapping has no variant for: they would be dropped at cast time. */
    private val UNSUPPORTED: Set<String> = setOf("geyser", "geyser_plume", "geyser_base", "geyser_poof")

    /** Families whose Paper mapping has a vanilla default (white dust / tint) when no data is given. */
    private val DEFAULTED: Set<KClass<out ParticleData>> = setOf(
        ParticleData.Dust::class, ParticleData.DustTransition::class, ParticleData.Tint::class, ParticleData.Spell::class,
    )

    private fun name(c: KClass<out ParticleData>?): String = c?.simpleName ?: "no data"

    /** null = [data] fits [particle]; otherwise the reason it does not. */
    public fun check(particle: String, data: ParticleData?): String? {
        val key = particle.removePrefix("minecraft:")
        if (key in UNSUPPORTED) return "particle '$key' takes Particle.Geyser data, which Astera has no ParticleData variant for"
        val expected = FAMILIES[key]
        if (data == null) {
            return if (expected == null || expected in DEFAULTED) null
            else "particle '$key' needs ${name(expected)} data (give the matching field), none given"
        }
        return if (expected != data::class) "particle '$key' takes ${name(expected)}, but ${name(data::class)} was given" else null
    }
}
