package com.astarworks.astera.core.application.contents.effect

import com.astarworks.astera.core.application.config.effect.SoundDefinitionYamlConfig
import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.core.application.port.outbound.effect.ISoundRegistry
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.effect.sound.SoundDefinition

/**
 * Sound-side `Contents<SoundId, SoundDefinition>` (Wave 5effects / ADR-0109).
 *
 * Mirrors [com.astarworks.astera.core.application.contents.ParticleContents]:
 * subclass of [YamlDynamicContents] that registers as the [ISoundRegistry]
 * port. Inherits hot-reload + bootstrap.
 *
 * Loaded from each `content/sounds/&lt;category&gt;/&lt;id&gt;.yaml`. The
 * flat OGG deliverables under `content/sounds/&lt;category&gt;/&lt;name&gt;.ogg`
 * are runtime assets shipped via the resource pack; the YAML metadata
 * layer here lets the domain reference sounds by id and look up i18n
 * display names.
 *
 * Registered with `IReloadableContentsRegistry` at `onEnable` time in
 * `AsteraCorePlugin.kt`.
 */
public class SoundContents : YamlDynamicContents<SoundId, SoundDefinition, SoundDefinitionYamlConfig>(
    categoryName = "sounds",
    deserializer = SoundDefinitionYamlConfig.serializer(),
),
    ISoundRegistry {

    /** One YAML per cue under `content/sounds/<category>/`; reload must see them as the bootstrap does. */
    override val readsSubdirectories: Boolean = true

    override fun toEntry(config: SoundDefinitionYamlConfig): Pair<SoundId, SoundDefinition> {
        val def = config.toSpec()
        return def.id to def
    }

    /**
     * Convenience overload: register by definition alone. Mirrors
     * `ParticleContents.register(def)`.
     */
    public fun register(def: SoundDefinition) {
        register(def.id, def)
    }
}
