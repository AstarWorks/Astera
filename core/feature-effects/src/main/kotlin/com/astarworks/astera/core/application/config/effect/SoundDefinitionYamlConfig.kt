package com.astarworks.astera.core.application.config.effect

import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.effect.sound.SoundDefinition
import com.astarworks.astera.core.domain.i18n.MessageKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/sounds/<category>/<id>.yaml` registry files
 * (Wave 5effects / ADR-0109).
 *
 * Kept in `application/config/` so the domain [SoundDefinition] stays free
 * of `kotlinx.serialization`. The Astera sound catalog is intentionally
 * authored as one-YAML-per-sound (mirroring `content/particles/`) rather
 * than the Minecraft `sounds.json` flat mapping — per-file authoring keeps
 * diff size small when adding a single sound.
 *
 * # Wire format
 *
 * ```yaml
 * id: skill-fireball-cast
 * resource_key: "astera:skill/fireball/cast"
 * default_volume: 1.0
 * default_pitch: 1.0
 * category: skills
 * display_name_key: astera.sound.skill-fireball-cast.name
 * ```
 *
 * `id` must be a valid [SoundId] (`[a-z0-9_/.:-]`, starts lowercase).
 */
@Serializable
public data class SoundDefinitionYamlConfig(
    public val id: String,
    @SerialName("resource_key") public val resourceKey: String,
    @SerialName("default_volume") public val defaultVolume: Double = 1.0,
    @SerialName("default_pitch") public val defaultPitch: Double = 1.0,
    public val category: String = "master",
    @SerialName("display_name_key") public val displayNameKey: String? = null,
) {
    public fun toSpec(): SoundDefinition = SoundDefinition(
        id = SoundId(id),
        resourceKey = resourceKey,
        defaultVolume = defaultVolume,
        defaultPitch = defaultPitch,
        category = category,
        displayNameKey = displayNameKey?.let(::MessageKey),
    )
}
