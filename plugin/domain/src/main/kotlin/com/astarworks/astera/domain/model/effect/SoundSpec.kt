package com.astarworks.astera.domain.model.effect

/**
 * Vendor-neutral sound playback descriptor. `key` follows the same
 * `namespace:name` convention as [com.astarworks.astera.domain.model.world.BlockType]
 * (e.g. `minecraft:entity.lightning_bolt.thunder`).
 */
public data class SoundSpec(
    val key: String,
    val volume: Double = 1.0,
    val pitch: Double = 1.0,
) {
    init {
        require(key.isNotBlank()) { "sound key must not be blank" }
        require(volume in 0.0..PRACTICAL_MAX_VOLUME) {
            "volume must be in [0.0, $PRACTICAL_MAX_VOLUME], got $volume"
        }
        require(pitch in MIN_PITCH..MAX_PITCH) {
            "pitch must be in [$MIN_PITCH, $MAX_PITCH], got $pitch"
        }
    }

    public companion object {
        /** Bukkit caps volume at 1.0 for clients, but server-side mixing can go higher. */
        public const val PRACTICAL_MAX_VOLUME: Double = 4.0
        public const val MIN_PITCH: Double = 0.5
        public const val MAX_PITCH: Double = 2.0
    }
}
