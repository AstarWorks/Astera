package com.astarworks.astera.core.domain.effect.sound

import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Catalog entry for a sound registered under [SoundId].
 *
 * Wave 5effects (ADR-0109). Mirrors `ParticleDefinition` shape so the
 * `content/sounds/` registry follows the same authoring conventions.
 *
 * # Wire format vs runtime
 *
 * Loaded from `content/sounds/sounds.json` (kaml). The runtime emitter
 * uses [defaultSpec] when a caller asks `ISoundRegistry.find(id) ->
 * SoundDefinition` and then plays without overrides. Inline overrides
 * (per-mechanic volume/pitch) layer on top via the existing
 * `SoundSpec(key, volume, pitch)` constructor.
 *
 * `category` is a free-form tag (`weapons`, `skills`, `match`, ...) so
 * the per-viewer gateway can mute / volume-trim by category at the
 * adapter layer.
 *
 * Stays in `core.domain.effect.sound` (pure Kotlin) — kaml DTO lives in
 * `core.application.config.effect.SoundsJsonConfig`.
 */
public data class SoundDefinition(
    public val id: SoundId,
    public val resourceKey: String,
    public val defaultVolume: Double = 1.0,
    public val defaultPitch: Double = 1.0,
    public val category: String = "master",
    public val displayNameKey: MessageKey? = null,
) {
    init {
        require(resourceKey.isNotBlank()) { "resourceKey must not be blank" }
        require(defaultVolume in 0.0..4.0) {
            "defaultVolume must be in [0.0, 4.0], got $defaultVolume"
        }
        require(defaultPitch in 0.5..2.0) {
            "defaultPitch must be in [0.5, 2.0], got $defaultPitch"
        }
        require(category.isNotBlank()) { "category must not be blank" }
    }
}
