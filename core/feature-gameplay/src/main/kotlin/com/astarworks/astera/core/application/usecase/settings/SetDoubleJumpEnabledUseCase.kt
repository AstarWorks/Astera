package com.astarworks.astera.core.application.usecase.settings

import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Toggles double-jump opt-out (Wave 5e, RTM issue #15).
 *
 * The default is `true` (RTM README §戦闘システム "デフォルトで二段階ジャ
 * ンプ"). Combat's `JumpCountTracker` only enforces the 2-jump max when this
 * flag is `true`; `false` disables the second jump entirely (independent of
 * the runtime `StatusKind.JUMP_DISABLED` debuff which can flip the bit
 * temporarily mid-match).
 */
public class SetDoubleJumpEnabledUseCase(private val settings: IPlayerSettingsRepository) {

    public fun set(playerId: PlayerId, enabled: Boolean) {
        val current = settings.findOrDefault(playerId)
        settings.upsert(current.copy(doubleJumpEnabled = enabled))
    }
}
