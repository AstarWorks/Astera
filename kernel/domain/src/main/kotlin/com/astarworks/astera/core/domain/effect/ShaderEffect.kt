package com.astarworks.astera.core.domain.effect

/** Per-display shader protocol (ADR-0305). Tint is supplied by an isolated generated item-model variant. */
public object ShaderEffect {
    public const val BLOCK_LIGHT: Int = 13
    public const val SKY_LIGHT: Int = 11

    public fun model(id: String, source: String): String {
        require(Regex("[a-z][a-z0-9-]*").matches(id)) { "invalid shader effect id '$id'" }
        require(source.startsWith("astera:")) { "shader effects require an Astera item model: $source" }
        return "astera:shader-effects/$id/${source.removePrefix("astera:")}"
    }
}
