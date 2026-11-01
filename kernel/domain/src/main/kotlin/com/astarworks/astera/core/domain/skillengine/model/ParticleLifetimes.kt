package com.astarworks.astera.core.domain.skillengine.model

/** Mean client lifetime of one vanilla particle type, in ticks; [scaleWithSize] = the dust size multiplies it. [source] says where the number comes from. */
public data class ParticleLifetime(val mean: Double, val scaleWithSize: Boolean, val source: String)

/**
 * Mean client lifetimes of the vanilla particle types the content uses (`content/engine/particle-lifetimes.yaml`), the input of the
 * caster-eye clutter check. A type that is not listed has no known lifetime: the check refuses to guess ([meanFor] = null).
 */
public data class ParticleLifetimes(val entries: Map<String, ParticleLifetime>) {
    /** Mean lifetime of [vanilla] (no `minecraft:` prefix); [size] is the burst's data size (dust scale), null = 1. */
    public fun meanFor(vanilla: String, size: Double?): Double? {
        val e = entries[vanilla.removePrefix("minecraft:")] ?: return null
        return if (e.scaleWithSize) e.mean * (size ?: 1.0) else e.mean
    }
}
