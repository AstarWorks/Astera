package com.astarworks.astera.persistence.jsonb

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
 */
@Serializable
public data class PlayerSettingsJson(
    val controlScheme: ControlSchemeJson = ControlSchemeJson.RightClick,
    val particleVisibility: ParticleVisibilityJson = ParticleVisibilityJson.All,
    val doubleJumpEnabled: Boolean = true,
    val quickCastSlots: Map<String, String> = emptyMap(),
) {

    public fun toDomain(playerId: PlayerId): PlayerSettings = PlayerSettings(
        playerId = playerId,
        controlScheme = controlScheme.toDomain(),
        particleVisibility = particleVisibility.toDomain(),
        doubleJumpEnabled = doubleJumpEnabled,
        quickCastSlots = quickCastSlots.mapKeys { (k, _) -> k.toInt() },
    )

    public companion object {
        public fun fromDomain(settings: PlayerSettings): PlayerSettingsJson = PlayerSettingsJson(
            controlScheme = ControlSchemeJson.fromDomain(settings.controlScheme),
            particleVisibility = ParticleVisibilityJson.fromDomain(settings.particleVisibility),
            doubleJumpEnabled = settings.doubleJumpEnabled,
            quickCastSlots = settings.quickCastSlots.mapKeys { (k, _) -> k.toString() },
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
