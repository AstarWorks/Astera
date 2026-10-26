package com.astarworks.astera.core.domain.effect

/** How a [ParticleSkin] texture meets the colour the server (or the vanilla type) applies. */
public enum class ParticleSkinTint {
    /** Greyscale texture: the particle's colour (`data: {kind: tint}` for `entity_effect`) multiplies it. */
    GREY,

    /** Coloured as drawn; only the vanilla type's own fixed multiplier (if any) applies. */
    BAKED,
}

/**
 * A retextured vanilla particle type (ADR-0274): the resource pack replaces the sprite list of
 * [vanillaType] with [frames] drawn frames named `astera_<channel>_<i>`, so every particle of that type
 * the client spawns (Astera's or vanilla's) shows them. [source] names the `:tools:item-art` painter.
 */
public data class ParticleSkin(
    public val vanillaType: String,
    public val channel: String,
    public val source: String,
    public val frames: Int,
    public val tint: ParticleSkinTint,
    public val size: Int = DEFAULT_SIZE,
) {
    init {
        require(vanillaType.matches(SNAKE)) { "particle skin type must be a vanilla particle id (lower_snake): $vanillaType" }
        require(channel.matches(KEBAB)) { "particle skin channel must be lower-kebab: $channel" }
        require(source.isNotBlank()) { "particle skin source must not be blank" }
        require(frames in 1..MAX_FRAMES) { "particle skin '$channel' frames must be 1..$MAX_FRAMES: $frames" }
        require(size in 16..256) { "particle skin '$channel' size must be 16..256: $size" }
    }

    public companion object {
        public const val DEFAULT_SIZE: Int = 64
        public const val MAX_FRAMES: Int = 64
        private val SNAKE = Regex("^[a-z][a-z0-9_]*$")
        private val KEBAB = Regex("^[a-z][a-z0-9-]*$")
    }
}
