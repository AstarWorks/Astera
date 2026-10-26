package com.astarworks.astera.core.application.usecase.settings

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.player.PlayerSettings

/**
 * Sealed error hierarchy for player-settings use cases (Wave 5e, RTM issue
 * #15; Wave G ADR-0124 added [UnsupportedLocale] / [InvalidVolume]).
 *
 * Slot bounds (1..9) match the Bukkit hotbar; the domain
 * [com.astarworks.astera.core.domain.player.PlayerSettings] `init` block
 * enforces the same range, but [SetQuickCastSlotUseCase] reports the failure
 * here rather than throwing.
 *
 * Volume bounds (0..100) work the same way: the domain `init` block requires
 * them, [SetVolumeUseCase] surfaces the same range check as a typed error
 * so callers (chat command, GUI handler) can localise the message.
 */
public sealed interface SettingsError {

    public data class InvalidSlot(val slot: Int) : SettingsError

    /**
     * Wave G — caller asked to switch to a [LocaleCode] not in
     * [PlayerSettings.SUPPORTED_LOCALES]. The error carries the requested
     * locale so the rendered message can include it via placeholder.
     */
    public data class UnsupportedLocale(val locale: LocaleCode) : SettingsError

    /**
     * Wave G — `value` lies outside [PlayerSettings.VOLUME_RANGE] (0..100).
     */
    public data class InvalidVolume(val value: Int) : SettingsError
}
