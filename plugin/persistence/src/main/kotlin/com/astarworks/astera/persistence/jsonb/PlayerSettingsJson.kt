package com.astarworks.astera.persistence.jsonb

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.player.ControlScheme
import com.astarworks.astera.core.domain.player.ParticleVisibility
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wraps [PlayerSettings] for the V8 `player_settings.settings` JSONB column
 * (Wave 5e, RTM issue #15). Domain types
 * ([ControlScheme] / [ParticleVisibility]) stay free of
 * `@Serializable`; this DTO mirrors them with sealed-by-key discriminators
 * (`type: right_click | button`, `type: all | own_team_only | own_only`).
 *
 * `quickCastSlots` is stored as `Map<String, String>` on the JSON side (JSON
 * object keys must be strings) and converted to `Map<Int, String>` in the
 * domain — same pattern as `Score.perTeam` in [MatchScoreJson].
 *
 * The default `JsonbColumnType` has `ignoreUnknownKeys = true`, so adding a
 * new field to the domain record + DTO does not break reads of existing
 * rows that lack the new key.
 *
 * # Wave G 5player-prefs-social (ADR-0124)
 *
 * Three new fields landed: [locale] (raw string for `en` / `ja`), and the
 * three per-channel volumes ([musicVolume] / [soundVolume] / [voiceVolume]).
 * Defaults match the domain — older rows decode with `ignoreUnknownKeys`
 * and the defaults below kick in, so no DB migration is needed.
 */
@Serializable
public data class PlayerSettingsJson(
    val controlScheme: ControlSchemeJson = ControlSchemeJson.RightClick,
    val particleVisibility: ParticleVisibilityJson = ParticleVisibilityJson.All,
    val doubleJumpEnabled: Boolean = true,
    val quickCastSlots: Map<String, String> = emptyMap(),
    // Wave 5engage / ADR-0104 §Privacy: opt-in public profile flag. Default
    // false (privacy-by-default). ignoreUnknownKeys is on, so older rows
    // without this field round-trip as default-disabled.
    val publicProfileEnabled: Boolean = false,
    // Wave G ADR-0124: preferred UI locale. Stored as raw string (`"en"` /
    // `"ja"`); the domain wraps with LocaleCode. Older rows missing this
    // key default to "en" via ignoreUnknownKeys + the default below.
    val locale: String = LocaleCode.EN.code,
    // Wave G ADR-0124: per-channel volume in 0..100 (percent). Defaults to
    // 100 (unattenuated) on missing-key rows.
    val musicVolume: Int = PlayerSettings.DEFAULT_VOLUME,
    val soundVolume: Int = PlayerSettings.DEFAULT_VOLUME,
    val voiceVolume: Int = PlayerSettings.DEFAULT_VOLUME,
    // UI-03 HUD / notification toggles; missing keys on older rows default to shown.
    val hpLineVisible: Boolean = true,
    val damageNumbersVisible: Boolean = true,
    val tutorialHintsEnabled: Boolean = true,
) {

    public fun toDomain(playerId: PlayerId): PlayerSettings = PlayerSettings(
        playerId = playerId,
        controlScheme = controlScheme.toDomain(),
        particleVisibility = particleVisibility.toDomain(),
        doubleJumpEnabled = doubleJumpEnabled,
        quickCastSlots = quickCastSlots.mapKeys { (k, _) -> k.toInt() },
        publicProfileEnabled = publicProfileEnabled,
        locale = LocaleCode.parse(locale).let { parsed ->
            // Defensive fallback: a row persisted with an unrecognised locale
            // (manual edit, future locale not yet supported on this server)
            // falls back to EN at decode time. Domain `init` would otherwise
            // happily accept any LocaleCode; the SUPPORTED_LOCALES filter
            // lives on the use-case side, so we just normalise here so the
            // GUI / chat renders cleanly.
            if (parsed in PlayerSettings.SUPPORTED_LOCALES) parsed else LocaleCode.EN
        },
        musicVolume = musicVolume.coerceIn(PlayerSettings.VOLUME_RANGE),
        soundVolume = soundVolume.coerceIn(PlayerSettings.VOLUME_RANGE),
        voiceVolume = voiceVolume.coerceIn(PlayerSettings.VOLUME_RANGE),
        hpLineVisible = hpLineVisible,
        damageNumbersVisible = damageNumbersVisible,
        tutorialHintsEnabled = tutorialHintsEnabled,
    )

    public companion object {
        public fun fromDomain(settings: PlayerSettings): PlayerSettingsJson = PlayerSettingsJson(
            controlScheme = ControlSchemeJson.fromDomain(settings.controlScheme),
            particleVisibility = ParticleVisibilityJson.fromDomain(settings.particleVisibility),
            doubleJumpEnabled = settings.doubleJumpEnabled,
            quickCastSlots = settings.quickCastSlots.mapKeys { (k, _) -> k.toString() },
            publicProfileEnabled = settings.publicProfileEnabled,
            locale = settings.locale.code,
            musicVolume = settings.musicVolume,
            soundVolume = settings.soundVolume,
            voiceVolume = settings.voiceVolume,
            hpLineVisible = settings.hpLineVisible,
            damageNumbersVisible = settings.damageNumbersVisible,
            tutorialHintsEnabled = settings.tutorialHintsEnabled,
        )
    }
}

/**
 * Sealed-by-key encoding for [ControlScheme]: `{"type": "right_click"}` or
 * `{"type": "button"}`. Adding a new variant means: add a domain `data
 * object`, add a matching DTO branch with `@SerialName`, add `toDomain` /
 * `fromDomain` arms. `ignoreUnknownKeys` does not help here — an unknown
 * `type` discriminator is fatal at decode, which is the right behaviour
 * (force the deploy to ship matching code).
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class ControlSchemeJson {

    public abstract fun toDomain(): ControlScheme

    @Serializable
    @SerialName("right_click")
    public data object RightClick : ControlSchemeJson() {
        override fun toDomain(): ControlScheme = ControlScheme.RightClick
    }

    @Serializable
    @SerialName("button")
    public data object Button : ControlSchemeJson() {
        override fun toDomain(): ControlScheme = ControlScheme.Button
    }

    public companion object {
        public fun fromDomain(scheme: ControlScheme): ControlSchemeJson = when (scheme) {
            is ControlScheme.RightClick -> RightClick
            is ControlScheme.Button -> Button
        }
    }
}

/**
 * Sealed-by-key encoding for [ParticleVisibility].
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class ParticleVisibilityJson {

    public abstract fun toDomain(): ParticleVisibility

    @Serializable
    @SerialName("all")
    public data object All : ParticleVisibilityJson() {
        override fun toDomain(): ParticleVisibility = ParticleVisibility.All
    }

    @Serializable
    @SerialName("own_team_only")
    public data object OwnTeamOnly : ParticleVisibilityJson() {
        override fun toDomain(): ParticleVisibility = ParticleVisibility.OwnTeamOnly
    }

    @Serializable
    @SerialName("own_only")
    public data object OwnOnly : ParticleVisibilityJson() {
        override fun toDomain(): ParticleVisibility = ParticleVisibility.OwnOnly
    }

    public companion object {
        public fun fromDomain(visibility: ParticleVisibility): ParticleVisibilityJson = when (visibility) {
            is ParticleVisibility.All -> All
            is ParticleVisibility.OwnTeamOnly -> OwnTeamOnly
            is ParticleVisibility.OwnOnly -> OwnOnly
        }
    }
}
