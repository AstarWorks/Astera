package com.astarworks.astera.core.application.usecase.settings

import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings

/**
 * Switches the player's preferred [LocaleCode] (Wave G 5player-prefs-social,
 * ADR-0124).
 *
 * Only locales in [PlayerSettings.SUPPORTED_LOCALES] are accepted — the
 * server can only render chat / GUI / tooltips for locales whose YAML lives
 * under `content/languages/`. Asking for anything else returns
 * [SettingsError.UnsupportedLocale]; the chat command + GUI handler render
 * the typed error into a localized red message and leave the row untouched.
 *
 * Implementation pattern mirrors [SetControlSchemeUseCase] /
 * [SetParticleVisibilityUseCase]: read-or-default, `copy` the field, upsert.
 */
public class SetLocaleUseCase(private val settings: IPlayerSettingsRepository) {

    public fun set(playerId: PlayerId, locale: LocaleCode): Result<Unit, SettingsError> {
        if (locale !in PlayerSettings.SUPPORTED_LOCALES) {
            return Result.failure(SettingsError.UnsupportedLocale(locale))
        }
        val current = settings.findOrDefault(playerId)
        settings.upsert(current.copy(locale = locale))
        return Result.success(Unit)
    }
}
