package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.SpriteYamlConfig
import com.astarworks.astera.core.application.port.outbound.ISpriteRegistry
import com.astarworks.astera.core.domain.effect.vfx.SpriteGlyphs
import com.astarworks.astera.core.domain.effect.vfx.SpriteId

/** Runtime metadata of one generated sprite: its flipbook length and cell size in pixels. */
public data class SpriteDefinition(
    public val id: SpriteId,
    public val frames: Int,
    public val size: Int = 32,
) {
    init {
        require(frames in 1..SpriteGlyphs.MAX_FRAMES) { "sprite '$id' frames must be 1..${SpriteGlyphs.MAX_FRAMES}: $frames" }
        require(size >= 1) { "sprite '$id' size must be >= 1: $size" }
    }
}

/**
 * Sprite-side `Contents<SpriteId, SpriteDefinition>` (ADR-0237): YAML at `content/sprites/`, registered as the
 * [ISpriteRegistry] port. Bootstrapped before [VfxContents] so flipbooks resolve their frame counts at play time.
 */
public class SpriteContents : YamlDynamicContents<SpriteId, SpriteDefinition, SpriteYamlConfig>(
    categoryName = "sprites",
    deserializer = SpriteYamlConfig.serializer(),
),
    ISpriteRegistry {

    override fun toEntry(config: SpriteYamlConfig): Pair<SpriteId, SpriteDefinition> {
        val def = config.toDefinition()
        return def.id to def
    }

    /** Registers by definition alone (the definition owns its id); used by tests. */
    public fun register(def: SpriteDefinition) {
        register(def.id, def)
    }
}
