package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.ParticleYamlConfig
import com.astarworks.astera.core.application.port.outbound.IParticleRegistry
import com.astarworks.astera.core.domain.effect.ParticleDefinition
import com.astarworks.astera.core.domain.effect.ParticleId

/**
 * Particle-side `Contents<ParticleId, ParticleDefinition>` (Wave 5f, RTM #22).
 *
 * Mirrors the [com.astarworks.astera.weapons.application.service.WeaponContents]
 * pattern — subclass of [YamlDynamicContents] that registers as the
 * [IParticleRegistry] port. Inherits:
 *
 *   - `find(id) / all()` from [Contents] (the `IParticleRegistry` port surface)
 *   - `bootstrap(dir)` for `onEnable` initial load (best-effort)
 *   - `reload(dir)` for `/astera reload particles` (all-or-nothing, atomic swap)
 *   - `register(id, def) / clear()` for tests + the `AsteraTestHarness`
 *
 * No state of its own — the substrate's `AtomicReference<Map<ParticleId,
 * ParticleDefinition>>` is the single source of truth and is read by every
 * consumer through `find()` (so concurrent readers always see a self-consistent
 * snapshot, never a torn map).
 *
 * Registration with `IReloadableContentsRegistry` happens at `onEnable` time
 * in `AsteraCorePlugin.kt` (Phase C orchestrator work).
 */
public class ParticleContents : YamlDynamicContents<ParticleId, ParticleDefinition, ParticleYamlConfig>(
    categoryName = "particles",
    deserializer = ParticleYamlConfig.serializer(),
),
    IParticleRegistry {

    /**
     * Convert each YAML DTO to its `(ParticleId, ParticleDefinition)` entry.
     * Delegates to [ParticleYamlConfig.toSpec] so any future schema evolution
     * stays single-sourced in the DTO.
     */
    override fun toEntry(config: ParticleYamlConfig): Pair<ParticleId, ParticleDefinition> {
        val def = config.toSpec()
        return def.id to def
    }

    /**
     * Convenience overload: register by definition alone (the definition owns
     * its id). Used by `AsteraTestHarness.withParticles(...)` and admin
     * commands that build a [ParticleDefinition] in memory.
     */
    public fun register(def: ParticleDefinition) {
        register(def.id, def)
    }
}
