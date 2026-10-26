package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.ParticleSkinYamlConfig
import com.astarworks.astera.core.domain.effect.ParticleSkin

/**
 * `Contents<String, ParticleSkin>` for `content/particle-skins/` (ADR-0274), keyed by vanilla particle type.
 * The skins themselves are pack-time assets; this registry validates the files at boot and lets tooling ask
 * which vanilla types are reskinned.
 */
public class ParticleSkinContents : YamlDynamicContents<String, ParticleSkin, ParticleSkinYamlConfig>(
    categoryName = "particle-skins",
    deserializer = ParticleSkinYamlConfig.serializer(),
) {
    override fun toEntry(config: ParticleSkinYamlConfig): Pair<String, ParticleSkin> {
        val skin = config.toDefinition()
        return skin.vanillaType to skin
    }
}
