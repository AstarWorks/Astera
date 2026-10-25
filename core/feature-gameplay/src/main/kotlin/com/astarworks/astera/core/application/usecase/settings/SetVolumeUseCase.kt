package com.astarworks.astera.core.application.usecase.settings

import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings
import com.astarworks.astera.core.domain.player.VolumeChannel

/**
 * Set the player's per-channel volume (Wave G 5player-prefs-social,
 * ADR-0124).
 *
 * Range is `0..100` inclusive. Out-of-range values surface as
 * [SettingsError.InvalidVolume] rather than throwing — the same posture as
 * [SetQuickCastSlotUseCase] for slot bounds.
 *
 * The `(channel, value)` → field mapping is centralised in
 * [PlayerSettings.withVolume] so this use case only owns the validation
 * + persistence concern.
 */
public class SetVolumeUseCase(private val settings: IPlayerSettingsRepository) {

    public fun set(
        playerId: PlayerId,
        channel: VolumeChannel,
        value: Int,
    ): Result<Unit, SettingsError> {
        if (value !in PlayerSettings.VOLUME_RANGE) {
            return Result.failure(SettingsError.InvalidVolume(value))
        }
        val current = settings.findOrDefault(playerId)
        settings.upsert(current.withVolume(channel, value))
        return Result.success(Unit)
    }
}
