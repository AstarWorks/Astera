package com.astarworks.astera.match.application.contents

import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.match.application.config.NormaLayerYamlConfig

/**
 * Holder of the single `content/norma-layer/layer.yaml` document (MODE-15), stored under [KEY]. [defaults] is the
 * Norma layer's config shared by every game mode; a mode's own `default_config` overrides it key by key
 * ([configFor]). Reloadable; an absent or invalid file leaves the built-in defaults of `NormaConfig` in force.
 */
public class NormaLayerContents : YamlDynamicContents<String, Map<String, String>, NormaLayerYamlConfig>(
    categoryName = "norma-layer",
    deserializer = NormaLayerYamlConfig.serializer(),
) {
    override fun toEntry(config: NormaLayerYamlConfig): Pair<String, Map<String, String>> = KEY to config.defaults

    public fun defaults(): Map<String, String> = find(KEY) ?: emptyMap()

    /** The layer defaults overlaid with a mode's own `default_config` (the mode wins). */
    public fun configFor(modeConfig: Map<String, String>): Map<String, String> = defaults() + modeConfig

    public companion object {
        public const val KEY: String = "layer"
    }
}
