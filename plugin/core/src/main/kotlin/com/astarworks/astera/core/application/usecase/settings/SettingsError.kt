package com.astarworks.astera.core.application.usecase.settings

/**
 * Sealed error hierarchy for player-settings use cases (Wave 5e, RTM issue #15).
 *
 * Slot bounds (1..9) match the Bukkit hotbar; the domain
 * [com.astarworks.astera.core.domain.player.PlayerSettings] `init` block
 * enforces the same range, but [SetQuickCastSlotUseCase] reports the failure
 * here rather than throwing.
 */
public sealed interface SettingsError {
    public data class InvalidSlot(val slot: Int) : SettingsError
}
