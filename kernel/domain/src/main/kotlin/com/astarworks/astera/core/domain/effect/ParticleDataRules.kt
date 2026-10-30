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

    /** Families whose Paper mapping has a vanilla default (white dust / tint) when no data is given. */
    private val DEFAULTED: Set<KClass<out ParticleData>> = setOf(
        ParticleData.Dust::class, ParticleData.DustTransition::class, ParticleData.Tint::class, ParticleData.Spell::class,
    )

    private fun name(c: KClass<out ParticleData>?): String = c?.simpleName ?: "no data"

    /** null = [data] fits [particle]; otherwise the reason it does not. */
    public fun check(particle: String, data: ParticleData?): String? {
        val key = particle.removePrefix("minecraft:")
        val expected = FAMILIES[key]
        if (data == null) {
            return if (expected == null || expected in DEFAULTED) null
            else "particle '$key' needs ${name(expected)} data (give the matching field), none given"
        }
        if (expected != data::class) return "particle '$key' takes ${name(expected)}, but ${name(data::class)} was given"
        val target = when (data) {
            is ParticleData.Trail -> data.target
            is ParticleData.Vibration -> data.destination
            else -> null
        }
        if (target != null && target !is ParticleTarget.Absolute) return "particle '$key' needs an absolute target; a skill particle row has no toward / offset resolution"
        return null
    }
}
