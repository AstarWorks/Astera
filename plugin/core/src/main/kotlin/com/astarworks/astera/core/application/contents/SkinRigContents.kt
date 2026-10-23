package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.SkinRigAnimationYamlConfig
import com.astarworks.astera.core.application.port.outbound.ISkinRigRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation

/**
 * Skin-rig animation `Contents` (ADR-0250): YAML at `content/animations/`, registered as the
 * [ISkinRigRegistry] port. An animation that fails validation is logged and counted as a failed
 * load by the substrate.
 */
public class SkinRigContents : YamlDynamicContents<CustomAnimationId, SkinRigAnimation, SkinRigAnimationYamlConfig>(
    categoryName = "animations",
    deserializer = SkinRigAnimationYamlConfig.serializer(),
),
    ISkinRigRegistry {

    override fun toEntry(config: SkinRigAnimationYamlConfig): Pair<CustomAnimationId, SkinRigAnimation> =
        when (val result = config.toDefinition()) {
            is Result.Success -> result.value.id to result.value
            is Result.Failure -> throw IllegalArgumentException(result.error.message)
        }
}
