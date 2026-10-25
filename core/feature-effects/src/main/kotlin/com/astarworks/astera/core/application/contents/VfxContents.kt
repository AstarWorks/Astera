package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.VfxYamlConfig
import com.astarworks.astera.core.application.port.outbound.IVfxRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.vfx.VfxDefinition
import com.astarworks.astera.core.domain.effect.vfx.VfxId

/**
 * VFX-side `Contents<VfxId, VfxDefinition>` (ADR-0220): YAML at `content/vfx/`, registered as
 * the [IVfxRegistry] port. Mirrors [ParticleContents]; a definition that fails validation is
 * logged and counted as a failed load by the substrate.
 */
public class VfxContents : YamlDynamicContents<VfxId, VfxDefinition, VfxYamlConfig>(
    categoryName = "vfx",
    deserializer = VfxYamlConfig.serializer(),
),
    IVfxRegistry {

    override fun toEntry(config: VfxYamlConfig): Pair<VfxId, VfxDefinition> =
        when (val result = config.toDefinition()) {
            is Result.Success -> result.value.id to result.value
            is Result.Failure -> throw IllegalArgumentException(result.error.message)
        }

    /** Registers by definition alone (the definition owns its id); used by tests and admin tooling. */
    public fun register(def: VfxDefinition) {
        register(def.id, def)
    }
}
