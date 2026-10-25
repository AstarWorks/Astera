package com.astarworks.astera.core.application.config.music

import com.astarworks.astera.core.domain.music.MusicLicense
import com.astarworks.astera.core.domain.music.MusicMood
import com.astarworks.astera.core.domain.music.MusicSource
import com.astarworks.astera.core.domain.music.MusicTrack
import com.astarworks.astera.core.domain.music.MusicTrackId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for music track definition files in `content/music/`
 * (Wave 5cinematic, ADR-0110).
 *
 * Kept in `application/config/music/` (kaml-annotated) so the domain
 * [MusicTrack] stays free of `kotlinx.serialization` (Konsist `core domain
 * stays pure` rule).
 *
 * # Wire format
 *
 * ```yaml
 * id: lobby-loop
 * display_name: "Lobby Loop"
 * source:
 *   kind: resource_pack
 *   path: "astera:music/lobby-loop"
 * license:
 *   kind: royalty_free
 *   attribution: "Composer X (CC0)"
 * mood: lobby
 * default_volume: 0.6
 * loop: true
 * duration_ticks: 2400
 * ```
 *
 * `source.kind` ∈ `resource_pack` / `external_url` / `in_game_note`.
 * `license.kind` ∈ `royalty_free` / `licensed` / `user_uploaded`.
 * `mood` ∈ enum entries of [MusicMood] (case-insensitive, kebab-or-underscore).
 *
 * Unknown discriminator strings raise `IllegalArgumentException` at `toSpec()`
 * time so authoring typos surface immediately rather than as silent
 * "track does not play".
 */
@Serializable
public data class MusicTrackYamlConfig(
    public val id: String,
    @SerialName("display_name") public val displayName: String,
    public val source: SourceDto,
    public val license: LicenseDto,
    public val mood: String,
    @SerialName("default_volume") public val defaultVolume: Double = 1.0,
    public val loop: Boolean = true,
    @SerialName("duration_ticks") public val durationTicks: Long? = null,
) {
    public fun toSpec(): MusicTrack = MusicTrack(
        id = MusicTrackId(id),
        displayName = displayName,
        source = source.toDomain(),
        license = license.toDomain(),
        mood = parseMood(mood),
        defaultVolume = defaultVolume,
        loop = loop,
        durationTicks = durationTicks,
    )

    private fun parseMood(raw: String): MusicMood {
        val normalized = raw.trim().lowercase().replace('-', '_')
        return MusicMood.entries.firstOrNull { it.name.lowercase() == normalized }
            ?: throw IllegalArgumentException(
                "Unknown mood '$raw' (expected one of: ${MusicMood.entries.joinToString { it.name.lowercase() }})",
            )
    }
}

@Serializable
public data class SourceDto(
    public val kind: String,
    public val path: String? = null,
    public val url: String? = null,
    public val key: String? = null,
) {
    public fun toDomain(): MusicSource = when (kind.lowercase().replace('-', '_')) {
        "resource_pack" -> MusicSource.ResourcePack(
            path = requireNotNull(path) { "source.kind=resource_pack requires 'path'" },
        )
        "external_url" -> MusicSource.ExternalUrl(
            url = requireNotNull(url) { "source.kind=external_url requires 'url'" },
        )
        "vanilla" -> MusicSource.Vanilla(
            soundKey = requireNotNull(key) { "source.kind=vanilla requires 'key'" },
        )
        "in_game_note" -> MusicSource.InGameNote
        else -> throw IllegalArgumentException(
            "Unknown source.kind '$kind' (expected: resource_pack/external_url/vanilla/in_game_note)",
        )
    }
}

@Serializable
public data class LicenseDto(
    public val kind: String,
    public val attribution: String? = null,
    @SerialName("license_ref") public val licenseRef: String? = null,
    @SerialName("uploader_id") public val uploaderId: String? = null,
) {
    public fun toDomain(): MusicLicense = when (kind.lowercase().replace('-', '_')) {
        "royalty_free" -> MusicLicense.RoyaltyFree(attribution = attribution)
        "licensed" -> MusicLicense.Licensed(
            licenseRef = requireNotNull(licenseRef) { "license.kind=licensed requires 'license_ref'" },
        )
        "user_uploaded" -> MusicLicense.UserUploaded(
            uploaderId = requireNotNull(uploaderId) { "license.kind=user_uploaded requires 'uploader_id'" },
        )
        else -> throw IllegalArgumentException(
            "Unknown license.kind '$kind' (expected: royalty_free/licensed/user_uploaded)",
        )
    }
}
