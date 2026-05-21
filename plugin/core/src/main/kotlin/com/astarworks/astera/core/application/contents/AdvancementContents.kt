package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.advancement.AdvancementYamlConfig
import com.astarworks.astera.core.application.port.outbound.IAdvancementRegistry
import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.advancement.CustomAdvancement

/**
 * Advancement-side `Contents<AdvancementId, CustomAdvancement>` (Wave 5p,
 * ADR-0088, ADR-0060 substrate).
 *
 * Bootstrap directory: `content/advancements/`. Inherits the substrate's
 * atomic map + reload semantics — see [TutorialContents] for the canonical
 * shape (Wave 5e).
 *
 * Implements [IAdvancementRegistry] (the marker port the use cases consume)
 * so `services.register<IAdvancementRegistry>(advancementContents, priority = 0)`
 * in `AsteraCorePlugin` exposes this instance to the rest of the codebase.
 */
public class AdvancementContents : YamlDynamicContents<AdvancementId, CustomAdvancement, AdvancementYamlConfig>(
    categoryName = "advancements",
    deserializer = AdvancementYamlConfig.serializer(),
),
    IAdvancementRegistry {

    override fun toEntry(config: AdvancementYamlConfig): Pair<AdvancementId, CustomAdvancement> {
        val advancement = config.toDomain()
        return advancement.id to advancement
    }

    /** Convenience overload: register by advancement alone (it owns its id). */
    public fun register(advancement: CustomAdvancement) {
        register(advancement.id, advancement)
    }
}
