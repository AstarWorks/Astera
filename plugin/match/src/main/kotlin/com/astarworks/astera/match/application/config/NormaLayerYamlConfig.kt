package com.astarworks.astera.match.application.config

import kotlinx.serialization.Serializable

/**
 * YAML DTO for `content/norma-layer/layer.yaml` (MODE-15): the defaults of the Norma layer, a standard layer of every
 * game mode. A mode overrides any key in its own `default_config` (`content/game-modes/<id>.yaml`).
 *
 * ```yaml
 * defaults:
 *   norma_layer: "true"
 *   norma_win: "true"
 *   time_limit_seconds: "600"
 * ```
 */
@Serializable
public data class NormaLayerYamlConfig(
    public val defaults: Map<String, String> = emptyMap(),
)
